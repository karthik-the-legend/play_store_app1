package app.formkit.feature.resize

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.di.ApplicationScope
import app.formkit.core.di.IoDispatcher
import app.formkit.core.export.ImageExports
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.TargetProgress
import app.formkit.core.storage.SourceImporter
import app.formkit.core.storage.Workspaces
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import javax.inject.Inject

sealed interface ResizeProblem {
    data class TooLarge(val smallestBytes: Long, val size: PixelSize, val canDownscale: Boolean, val maxBytes: Long) : ResizeProblem
    data class TooSmall(val largestBytes: Long, val size: PixelSize, val minBytes: Long) : ResizeProblem
    data object UnreadableImage : ResizeProblem
    data object OutOfMemory : ResizeProblem
    data object SaveFailed : ResizeProblem
    data object Unexpected : ResizeProblem
}

sealed interface ResizeEvent {
    data class Saved(val displayName: String) : ResizeEvent
    data class Share(val uri: Uri, val mimeType: String) : ResizeEvent
}

data class ResizeUiState(
    val session: ResizeSession,
    val validation: OptionsValidation,
    val isImporting: Boolean = false,
    val isProcessing: Boolean = false,
    /** What the search is trying right now; null before the first encode. */
    val progress: TargetProgress? = null,
    val isSaving: Boolean = false,
    val problem: ResizeProblem? = null,
)

@HiltViewModel
class ResizeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importer: SourceImporter,
    private val decoder: SourceImageDecoder,
    private val processor: ResizeProcessor,
    private val exports: ImageExports,
    private val workspaces: Workspaces,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val session = MutableStateFlow(restoreSession())
    private val activity = MutableStateFlow(Activity())
    private val _events = Channel<ResizeEvent>(Channel.BUFFERED)
    val events: Flow<ResizeEvent> = _events.receiveAsFlow()
    private var resizeJob: Job? = null

    val uiState: StateFlow<ResizeUiState> = combine(session, activity) { session, activity ->
        ResizeUiState(
            session = session,
            validation = session.options.validate(),
            isImporting = activity.isImporting,
            isProcessing = activity.isProcessing,
            progress = activity.progress,
            isSaving = activity.isSaving,
            problem = activity.problem,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ResizeUiState(session.value, session.value.options.validate()),
    )

    init {
        // A session restored after process death can point at files the cache janitor has
        // since removed. Drop what's gone now rather than failing on the next tap.
        viewModelScope.launch {
            val restored = session.value
            val (sourceGone, resultGone) = withContext(ioDispatcher) {
                val sourceGone = restored.source != null && !File(restored.source.path).exists()
                val resultGone = restored.result != null && !File(restored.result.path).exists()
                sourceGone to resultGone
            }
            if (sourceGone || resultGone) {
                updateSession {
                    it.copy(
                        source = if (sourceGone) null else it.source,
                        result = if (sourceGone || resultGone) null else it.result,
                    )
                }
            }
        }
    }

    fun onImagePicked(uri: Uri) {
        val current = activity.value
        if (current.isImporting || current.isProcessing) return
        activity.update { it.copy(isImporting = true, problem = null) }
        viewModelScope.launch {
            val workspace = workspaces.create(WORKSPACE_PREFIX)
            try {
                val source = withContext(ioDispatcher) {
                    val imported = importer.import(uri, workspace)
                    val size = decoder.readSize(imported.file)
                    SourceImage(imported.file.path, imported.displayName, imported.sizeBytes, size.width, size.height)
                }
                val previousWorkspace = session.value.workspace
                updateSession { it.copy(workspace = workspace.path, source = source, result = null) }
                previousWorkspace?.let(::deleteWorkspaceLater)
            } catch (e: CancellationException) {
                deleteWorkspaceLater(workspace.path)
                throw e
            } catch (_: Exception) {
                deleteWorkspaceLater(workspace.path)
                activity.update { it.copy(problem = ResizeProblem.UnreadableImage) }
            } finally {
                activity.update { it.copy(isImporting = false) }
            }
        }
    }

    fun updateOptions(transform: (ResizeOptions) -> ResizeOptions) {
        updateSession { it.copy(options = transform(it.options)) }
    }

    fun resize() {
        val current = session.value
        val source = current.source ?: return
        val workspace = current.workspace ?: return
        val target = (current.options.validate() as? OptionsValidation.Valid)?.target ?: return
        if (resizeJob?.isActive == true) return

        activity.update { it.copy(isProcessing = true, progress = null, problem = null) }
        resizeJob = viewModelScope.launch {
            try {
                val output = withContext(ioDispatcher) {
                    File(workspace).listFiles { file -> file.name.startsWith(RESULT_PREFIX) }?.forEach { it.delete() }
                    // A fresh name each run, so image previews never show a stale cached result.
                    File(workspace, "$RESULT_PREFIX${System.currentTimeMillis()}.${target.format.extension}")
                }
                val outcome = processor.resize(File(source.path), target, output) { progress ->
                    activity.update { it.copy(progress = progress) }
                }
                when (outcome) {
                    is ResizeOutcome.Done -> updateSession {
                        it.copy(
                            result = ResizeResult(
                                path = outcome.file.path,
                                sizeBytes = outcome.sizeBytes,
                                width = outcome.size.width,
                                height = outcome.size.height,
                                quality = outcome.quality,
                                format = target.format,
                                maxBytes = target.maxBytes,
                                cropped = outcome.cropped,
                            ),
                        )
                    }
                    is ResizeOutcome.TooLarge -> showProblem(
                        ResizeProblem.TooLarge(outcome.smallestBytes, outcome.size, outcome.canDownscale, target.maxBytes),
                    )
                    is ResizeOutcome.TooSmall -> showProblem(
                        ResizeProblem.TooSmall(outcome.largestBytes, outcome.size, target.minBytes ?: 0),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                showProblem(ResizeProblem.OutOfMemory)
            } catch (_: IOException) {
                showProblem(ResizeProblem.UnreadableImage)
            } catch (_: Exception) {
                showProblem(ResizeProblem.Unexpected)
            } finally {
                activity.update { it.copy(isProcessing = false, progress = null) }
            }
        }
    }

    fun cancelResize() {
        resizeJob?.cancel()
    }

    fun allowDownscaleAndRetry() {
        dismissProblem()
        updateOptions { it.copy(allowDownscale = true) }
        resize()
    }

    fun dismissProblem() {
        activity.update { it.copy(problem = null) }
    }

    fun backToEditing() {
        updateSession { it.copy(result = null) }
    }

    fun save() {
        val current = session.value
        val source = current.source ?: return
        val result = current.result ?: return
        if (result.savedUri != null || activity.value.isSaving) return

        activity.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val exported = exports.save(
                    file = File(result.path),
                    displayName = exportFileName(source.displayName, result.sizeBytes, result.format),
                    format = result.format,
                    maxBytes = result.maxBytes,
                    size = result.size,
                )
                updateSession { s ->
                    s.copy(result = s.result?.copy(savedUri = exported.uri.toString(), savedName = exported.displayName))
                }
                _events.send(ResizeEvent.Saved(exported.displayName))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(ResizeProblem.SaveFailed)
            } finally {
                activity.update { it.copy(isSaving = false) }
            }
        }
    }

    fun share() {
        val current = session.value
        val source = current.source ?: return
        val result = current.result ?: return
        viewModelScope.launch {
            try {
                val uri = result.savedUri?.let(Uri::parse)
                    ?: exports.shareableCopy(File(result.path), exportFileName(source.displayName, result.sizeBytes, result.format))
                _events.send(ResizeEvent.Share(uri, result.format.mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(ResizeProblem.Unexpected)
            }
        }
    }

    /** Starts over with the same settings. */
    fun doAnother() {
        resizeJob?.cancel()
        val previousWorkspace = session.value.workspace
        updateSession { ResizeSession(options = it.options) }
        previousWorkspace?.let(::deleteWorkspaceLater)
    }

    override fun onCleared() {
        session.value.workspace?.let(::deleteWorkspaceLater)
    }

    private fun showProblem(problem: ResizeProblem) {
        activity.update { it.copy(problem = problem) }
    }

    private fun deleteWorkspaceLater(path: String) {
        appScope.launch { workspaces.delete(path) }
    }

    private fun updateSession(transform: (ResizeSession) -> ResizeSession) {
        val updated = session.updateAndGet(transform)
        savedStateHandle[KEY_SESSION] = sessionJson.encodeToString(ResizeSession.serializer(), updated)
    }

    private fun restoreSession(): ResizeSession =
        savedStateHandle.get<String>(KEY_SESSION)
            ?.let { runCatching { sessionJson.decodeFromString(ResizeSession.serializer(), it) }.getOrNull() }
            ?: ResizeSession()

    private data class Activity(
        val isImporting: Boolean = false,
        val isProcessing: Boolean = false,
        val progress: TargetProgress? = null,
        val isSaving: Boolean = false,
        val problem: ResizeProblem? = null,
    )

    private companion object {
        const val KEY_SESSION = "resize_session"
        const val WORKSPACE_PREFIX = "resize"
        const val RESULT_PREFIX = "result-"
        val sessionJson = Json { ignoreUnknownKeys = true }
    }
}
