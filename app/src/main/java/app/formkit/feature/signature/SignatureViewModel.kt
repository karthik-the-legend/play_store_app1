package app.formkit.feature.signature

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.di.ApplicationScope
import app.formkit.core.di.IoDispatcher
import app.formkit.core.export.ImageExports
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.PixelSize
import app.formkit.core.storage.FileExporter
import app.formkit.core.storage.SourceImporter
import app.formkit.core.storage.Workspaces
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

sealed interface SignatureProblem {
    data class TooLarge(val smallestBytes: Long, val size: PixelSize, val maxBytes: Long) : SignatureProblem
    data object NoInk : SignatureProblem
    data object UnreadableImage : SignatureProblem
    data object OutOfMemory : SignatureProblem
    data object SaveFailed : SignatureProblem
    data object Unexpected : SignatureProblem
}

sealed interface SignatureEvent {
    data class LaunchCamera(val uri: Uri) : SignatureEvent
    data class Saved(val displayName: String) : SignatureEvent
    data class Share(val uri: Uri, val mimeType: String) : SignatureEvent
}

data class SignatureUiState(
    val session: SignatureSession,
    val validation: OptionsValidation,
    /** The whole cleaned photo on white, for choosing the crop. */
    val preview: Bitmap? = null,
    val inkFound: Boolean = true,
    val isLoadingPhoto: Boolean = false,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val problem: SignatureProblem? = null,
)

@HiltViewModel
class SignatureViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importer: SourceImporter,
    private val processor: SignatureProcessor,
    private val exports: ImageExports,
    private val fileExporter: FileExporter,
    private val workspaces: Workspaces,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val session = MutableStateFlow(restoreSession())
    private val activity = MutableStateFlow(Activity())
    private val _events = Channel<SignatureEvent>(Channel.BUFFERED)
    val events: Flow<SignatureEvent> = _events.receiveAsFlow()

    /** The decoded photo. Kept in memory only; rebuilt from the source file after process death. */
    private var photoWork: PhotoWork? = null
    private var photoJob: Job? = null
    private var createJob: Job? = null

    val uiState: StateFlow<SignatureUiState> = combine(session, activity) { session, activity ->
        SignatureUiState(
            session = session,
            validation = session.options.validate(),
            preview = activity.preview,
            inkFound = activity.inkFound,
            isLoadingPhoto = activity.isLoadingPhoto,
            isProcessing = activity.isProcessing,
            isSaving = activity.isSaving,
            problem = activity.problem,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SignatureUiState(session.value, session.value.options.validate()),
    )

    init {
        val restored = session.value
        val source = restored.source
        if (restored.mode == SignatureMode.Photo && source != null) {
            loadPhoto(File(source.path), autoCrop = restored.crop == null)
        }
        restored.result?.let { result ->
            viewModelScope.launch {
                val exists = withContext(ioDispatcher) { File(result.path).exists() }
                if (!exists) updateSession { it.copy(result = null) }
            }
        }
    }

    fun startDrawing() {
        updateSession { it.copy(mode = SignatureMode.Draw, result = null) }
    }

    /** Prepares the file the camera app writes into, then asks the screen to open the camera. */
    fun takePhoto() {
        viewModelScope.launch {
            val workspace = ensureWorkspace()
            val capture = File(workspace, "capture-${System.currentTimeMillis()}.jpg")
            updateSession { it.copy(pendingCapture = capture.path) }
            _events.send(SignatureEvent.LaunchCamera(fileExporter.shareableUri(capture)))
        }
    }

    fun onPhotoCaptured(saved: Boolean) {
        val path = session.value.pendingCapture ?: return
        updateSession { it.copy(pendingCapture = null) }
        viewModelScope.launch {
            val file = File(path)
            val usable = saved && withContext(ioDispatcher) { file.length() > 0 }
            if (usable) {
                usePhoto(file, CAMERA_PHOTO_NAME)
            } else {
                withContext(ioDispatcher) { file.delete() }
            }
        }
    }

    fun onPhotoPicked(uri: Uri) {
        if (activity.value.isLoadingPhoto) return
        activity.update { it.copy(isLoadingPhoto = true, problem = null) }
        viewModelScope.launch {
            try {
                val workspace = ensureWorkspace()
                val imported = importer.import(uri, File(workspace, "picked-${System.currentTimeMillis()}"))
                usePhoto(imported.file, imported.displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                activity.update { it.copy(isLoadingPhoto = false, problem = SignatureProblem.UnreadableImage) }
            }
        }
    }

    fun setInkStrength(strength: Float) {
        updateOptions { it.copy(inkStrength = strength) }
        val work = photoWork ?: return
        photoJob?.cancel()
        photoJob = viewModelScope.launch {
            // Slider events arrive every frame; only clean for where it settles.
            delay(PREVIEW_DEBOUNCE_MILLIS)
            val cleaned = processor.clean(work, strength)
            activity.update { it.copy(preview = cleaned.preview, inkFound = cleaned.inkBounds != null) }
        }
    }

    fun autoCrop() {
        val work = photoWork ?: return
        viewModelScope.launch {
            val cleaned = processor.clean(work, session.value.options.inkStrength)
            updateSession { it.copy(crop = cleaned.cropSuggestion()) }
        }
    }

    fun useWholePhoto() {
        updateSession { it.copy(crop = CropRect.Full) }
    }

    fun updateCrop(crop: CropRect) {
        updateSession { it.copy(crop = crop) }
    }

    fun addStroke(stroke: DrawnStroke) {
        updateSession { it.copy(drawing = it.drawing + stroke) }
    }

    fun undoStroke() {
        updateSession { it.copy(drawing = it.drawing.undo()) }
    }

    fun clearDrawing() {
        updateSession { it.copy(drawing = Drawing()) }
    }

    fun setStrokeWidth(width: StrokeWidth) {
        updateOptions { it.copy(strokeWidth = width) }
    }

    fun updateOptions(transform: (SignatureOptions) -> SignatureOptions) {
        updateSession { it.copy(options = transform(it.options)) }
    }

    fun createFile() {
        val current = session.value
        val target = (current.options.validate() as? OptionsValidation.Valid)?.target ?: return
        if (createJob?.isActive == true) return
        val work = photoWork
        when (current.mode) {
            SignatureMode.Photo -> if (work == null) return
            SignatureMode.Draw -> if (current.drawing.isEmpty) return
            null -> return
        }

        activity.update { it.copy(isProcessing = true, problem = null) }
        createJob = viewModelScope.launch {
            try {
                val workspace = ensureWorkspace()
                val output = withContext(ioDispatcher) {
                    workspace.listFiles { file -> file.name.startsWith(RESULT_PREFIX) }?.forEach { it.delete() }
                    File(workspace, "$RESULT_PREFIX${System.currentTimeMillis()}.${target.format.extension}")
                }
                val outcome = if (current.mode == SignatureMode.Photo && work != null) {
                    processor.exportPhoto(work, current.options.inkStrength, current.crop ?: CropRect.Full, target, output)
                } else {
                    processor.exportDrawing(current.drawing, target, output)
                }
                when (outcome) {
                    is SignatureOutcome.Done -> updateSession {
                        it.copy(
                            result = SignatureResult(
                                path = outcome.file.path,
                                sizeBytes = outcome.sizeBytes,
                                width = outcome.size.width,
                                height = outcome.size.height,
                                format = target.format,
                                maxBytes = if (current.options.hasSizeLimit) target.maxBytes else null,
                            ),
                        )
                    }
                    is SignatureOutcome.TooLarge ->
                        showProblem(SignatureProblem.TooLarge(outcome.smallestBytes, outcome.size, target.maxBytes))
                    SignatureOutcome.NoInk -> showProblem(SignatureProblem.NoInk)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                showProblem(SignatureProblem.OutOfMemory)
            } catch (_: Exception) {
                showProblem(SignatureProblem.Unexpected)
            } finally {
                activity.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun cancelCreate() {
        createJob?.cancel()
    }

    fun backToEditing() {
        updateSession { it.copy(result = null) }
    }

    fun save() {
        val result = session.value.result ?: return
        if (result.savedUri != null || activity.value.isSaving) return
        activity.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val exported = exports.save(
                    file = File(result.path),
                    displayName = signatureFileName(result.sizeBytes, result.format),
                    format = result.format,
                    maxBytes = result.maxBytes,
                    size = result.size,
                )
                updateSession { s ->
                    s.copy(result = s.result?.copy(savedUri = exported.uri.toString(), savedName = exported.displayName))
                }
                _events.send(SignatureEvent.Saved(exported.displayName))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(SignatureProblem.SaveFailed)
            } finally {
                activity.update { it.copy(isSaving = false) }
            }
        }
    }

    fun share() {
        val result = session.value.result ?: return
        viewModelScope.launch {
            try {
                val uri = result.savedUri?.let(Uri::parse)
                    ?: exports.shareableCopy(File(result.path), signatureFileName(result.sizeBytes, result.format))
                _events.send(SignatureEvent.Share(uri, result.format.mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(SignatureProblem.Unexpected)
            }
        }
    }

    /** Back to choosing photo or drawing, keeping the output settings. */
    fun startOver() {
        photoJob?.cancel()
        createJob?.cancel()
        photoWork = null
        val previousWorkspace = session.value.workspace
        updateSession { SignatureSession(options = it.options) }
        activity.update { Activity() }
        previousWorkspace?.let(::deleteWorkspaceLater)
    }

    fun dismissProblem() {
        activity.update { it.copy(problem = null) }
    }

    override fun onCleared() {
        session.value.workspace?.let(::deleteWorkspaceLater)
    }

    private fun usePhoto(file: File, displayName: String) {
        updateSession {
            it.copy(mode = SignatureMode.Photo, source = SignatureSource(file.path, displayName), crop = null, result = null)
        }
        loadPhoto(file, autoCrop = true)
    }

    private fun loadPhoto(file: File, autoCrop: Boolean) {
        photoJob?.cancel()
        photoWork = null
        activity.update { it.copy(isLoadingPhoto = true, preview = null, inkFound = true, problem = null) }
        photoJob = viewModelScope.launch {
            try {
                val work = processor.loadPhoto(file)
                val cleaned = processor.clean(work, session.value.options.inkStrength)
                photoWork = work
                if (autoCrop) updateSession { it.copy(crop = cleaned.cropSuggestion()) }
                activity.update { it.copy(preview = cleaned.preview, inkFound = cleaned.inkBounds != null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                dropPhoto(SignatureProblem.OutOfMemory)
            } catch (_: Exception) {
                dropPhoto(SignatureProblem.UnreadableImage)
            } finally {
                // A newer load cancelled this one and owns the loading state now.
                if (isActive) activity.update { it.copy(isLoadingPhoto = false) }
            }
        }
    }

    private fun dropPhoto(problem: SignatureProblem) {
        updateSession { it.copy(mode = null, source = null, crop = null) }
        showProblem(problem)
    }

    private fun CleanedSignature.cropSuggestion(): CropRect =
        inkBounds?.let { CropRect.of(it, mask.width, mask.height) } ?: CropRect.Full

    private suspend fun ensureWorkspace(): File {
        val existing = session.value.workspace
        if (existing != null) {
            return withContext(ioDispatcher) { File(existing).apply { mkdirs() } }
        }
        val created = workspaces.create(WORKSPACE_PREFIX)
        updateSession { it.copy(workspace = created.path) }
        return created
    }

    private fun showProblem(problem: SignatureProblem) {
        activity.update { it.copy(problem = problem) }
    }

    private fun deleteWorkspaceLater(path: String) {
        appScope.launch { workspaces.delete(path) }
    }

    private fun updateSession(transform: (SignatureSession) -> SignatureSession) {
        val updated = session.updateAndGet(transform)
        savedStateHandle[KEY_SESSION] = sessionJson.encodeToString(SignatureSession.serializer(), updated)
    }

    private fun restoreSession(): SignatureSession =
        savedStateHandle.get<String>(KEY_SESSION)
            ?.let { runCatching { sessionJson.decodeFromString(SignatureSession.serializer(), it) }.getOrNull() }
            ?: SignatureSession()

    private data class Activity(
        val preview: Bitmap? = null,
        val inkFound: Boolean = true,
        val isLoadingPhoto: Boolean = false,
        val isProcessing: Boolean = false,
        val isSaving: Boolean = false,
        val problem: SignatureProblem? = null,
    )

    private companion object {
        const val KEY_SESSION = "signature_session"
        const val WORKSPACE_PREFIX = "signature"
        const val RESULT_PREFIX = "signature-"
        const val CAMERA_PHOTO_NAME = "camera.jpg"
        const val PREVIEW_DEBOUNCE_MILLIS = 40L
        val sessionJson = Json { ignoreUnknownKeys = true }
    }
}
