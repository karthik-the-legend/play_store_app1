package app.formkit.feature.passport

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.di.ApplicationScope
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.di.IoDispatcher
import app.formkit.core.export.ImageExports
import app.formkit.core.imaging.AndroidImageCodec
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.SizeTargeter
import app.formkit.core.imaging.SourceImageDecoder
import app.formkit.core.imaging.TargetOutcome
import app.formkit.core.imaging.passport.BrushMode
import app.formkit.core.imaging.passport.BrushStroke
import app.formkit.core.imaging.passport.DirtyRect
import app.formkit.core.imaging.passport.ForegroundMask
import app.formkit.core.imaging.passport.HeadFraming
import app.formkit.core.imaging.passport.JpegDensity
import app.formkit.core.imaging.passport.MaskBrush
import app.formkit.core.imaging.passport.MaskEdits
import app.formkit.core.imaging.passport.PassportPreset
import app.formkit.core.imaging.passport.PersonSegmenter
import app.formkit.core.imaging.passport.Placement
import app.formkit.core.imaging.passport.PrintSheets
import app.formkit.core.storage.FileExporter
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject

sealed interface PassportProblem {
    data class TooLarge(val smallestBytes: Long, val size: PixelSize, val maxBytes: Long) : PassportProblem
    data object SheetDoesNotFit : PassportProblem
    data object UnreadableImage : PassportProblem
    data object OutOfMemory : PassportProblem
    data object SaveFailed : PassportProblem
    data object Unexpected : PassportProblem
}

sealed interface PassportEvent {
    data class LaunchCamera(val uri: Uri) : PassportEvent
    data class Saved(val displayName: String, val inDocuments: Boolean) : PassportEvent
    data class Share(val uri: Uri, val mimeType: String) : PassportEvent
}

data class PassportUiState(
    val session: PassportSession,
    val validation: OptionsValidation,
    /** Null while a custom size is incomplete. */
    val frame: PixelSize?,
    /** The photo with the person's mask as alpha. Its pixels change in place; [cutoutVersion] says when. */
    val cutout: Bitmap? = null,
    val cutoutVersion: Int = 0,
    val canUndo: Boolean = false,
    val personFound: Boolean = true,
    val isPreparing: Boolean = false,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val problem: PassportProblem? = null,
) {
    /** The frame to draw: the chosen size, or the Indian size while a custom one is being typed. */
    val previewFrame: PixelSize get() = frame ?: PassportPreset.India.pixelSize()
}

@HiltViewModel
class PassportViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val importer: SourceImporter,
    private val decoder: SourceImageDecoder,
    private val codec: AndroidImageCodec,
    private val segmenter: PersonSegmenter,
    private val renderer: PassportRenderer,
    private val exports: ImageExports,
    private val fileExporter: FileExporter,
    private val workspaces: Workspaces,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val session = MutableStateFlow(restoreSession())
    private val activity = MutableStateFlow(Activity())
    private val _events = Channel<PassportEvent>(Channel.BUFFERED)
    val events: Flow<PassportEvent> = _events.receiveAsFlow()

    // In memory only. After process death they're rebuilt from the source photo and the
    // workspace files (the model's mask and the touch-ups).
    private var photo: Bitmap? = null
    private var baseMask: ForegroundMask? = null
    private var mask: ForegroundMask? = null
    private var cutout: Bitmap? = null
    private var edits = MaskEdits()

    private var strokePoints: MutableList<Float>? = null
    private var strokeRadius = 0f
    private var strokeMode = BrushMode.Erase

    private var prepareJob: Job? = null
    private var workJob: Job? = null

    val uiState: StateFlow<PassportUiState> = combine(session, activity) { session, activity ->
        PassportUiState(
            session = session,
            validation = session.options.validate(),
            frame = session.options.frameSize(),
            cutout = activity.cutout,
            cutoutVersion = activity.cutoutVersion,
            canUndo = activity.canUndo,
            personFound = activity.personFound,
            isPreparing = activity.isPreparing,
            isProcessing = activity.isProcessing,
            isSaving = activity.isSaving,
            problem = activity.problem,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PassportUiState(session.value, session.value.options.validate(), session.value.options.frameSize()),
    )

    init {
        val restored = session.value
        restored.source?.let { preparePhoto(File(it.path), fresh = false) }
        if (restored.photo != null || restored.sheet != null) {
            viewModelScope.launch {
                val photoGone = restored.photo?.let { !withContext(ioDispatcher) { File(it.path).exists() } } ?: false
                val sheetGone = restored.sheet?.let { !withContext(ioDispatcher) { File(it.path).exists() } } ?: false
                if (photoGone || sheetGone) {
                    updateSession {
                        it.copy(
                            photo = if (photoGone) null else it.photo,
                            sheet = if (sheetGone || photoGone) null else it.sheet,
                            step = if (photoGone) PassportStep.Edit else if (it.step == PassportStep.SheetResult) PassportStep.Sheet else it.step,
                        )
                    }
                }
            }
        }
    }

    // Picking a photo

    fun takePhoto() {
        viewModelScope.launch {
            val workspace = ensureWorkspace()
            val capture = File(workspace, "capture-${System.currentTimeMillis()}.jpg")
            updateSession { it.copy(pendingCapture = capture.path) }
            _events.send(PassportEvent.LaunchCamera(fileExporter.shareableUri(capture)))
        }
    }

    fun onPhotoCaptured(saved: Boolean) {
        val path = session.value.pendingCapture ?: return
        updateSession { it.copy(pendingCapture = null) }
        viewModelScope.launch {
            val file = File(path)
            val usable = saved && withContext(ioDispatcher) { file.length() > 0 }
            if (usable) usePhoto(file, CAMERA_PHOTO_NAME) else withContext(ioDispatcher) { file.delete() }
        }
    }

    fun onPhotoPicked(uri: Uri) {
        if (activity.value.isPreparing) return
        activity.update { it.copy(isPreparing = true, problem = null) }
        viewModelScope.launch {
            try {
                val workspace = ensureWorkspace()
                val imported = importer.import(uri, File(workspace, "picked-${System.currentTimeMillis()}"))
                usePhoto(imported.file, imported.displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                activity.update { it.copy(isPreparing = false, problem = PassportProblem.UnreadableImage) }
            }
        }
    }

    // Positioning

    fun setMode(mode: EditorMode) {
        updateSession { it.copy(mode = mode) }
    }

    fun updatePlacement(placement: Placement) {
        updateSession { it.copy(placement = placement) }
    }

    fun autoFit() {
        val currentMask = mask ?: return
        val currentPhoto = photo ?: return
        viewModelScope.launch {
            val head = withContext(defaultDispatcher) { HeadFraming.measureHead(currentMask) }
            val options = session.value.options
            val frame = options.frameSize() ?: PassportPreset.India.pixelSize()
            val placement = head?.let { HeadFraming.placementFor(it, options.guide(frame)) }
                ?: Placement.cover(PixelSize(currentPhoto.width, currentPhoto.height), frame)
            updateSession { it.copy(placement = placement) }
        }
    }

    // Touch-ups. Called on the main thread for every pointer move; each step is a few milliseconds.

    fun beginStroke(x: Float, y: Float, radius: Float) {
        val currentMask = mask ?: return
        strokeMode = session.value.options.brushMode
        strokeRadius = radius
        strokePoints = mutableListOf(x, y)
        refreshCutout(MaskBrush.apply(currentMask, BrushStroke(strokeMode, radius, listOf(x, y))))
    }

    fun extendStroke(x: Float, y: Float) {
        val currentMask = mask ?: return
        val points = strokePoints ?: return
        val lastX = points[points.size - 2]
        val lastY = points[points.size - 1]
        val spacing = strokeRadius * MaskBrush.DAB_SPACING
        val dx = x - lastX
        val dy = y - lastY
        if (dx * dx + dy * dy < spacing * spacing) return
        points += x
        points += y
        refreshCutout(MaskBrush.applySegment(currentMask, lastX, lastY, x, y, strokeRadius, strokeMode))
    }

    fun endStroke() {
        val points = strokePoints ?: return
        strokePoints = null
        edits += BrushStroke(strokeMode, strokeRadius, points.toList())
        activity.update { it.copy(canUndo = true) }
        persistEdits(edits)
    }

    fun undoTouchUp() {
        if (!edits.canUndo) return
        edits = edits.undo()
        rebuildMask()
    }

    fun clearTouchUps() {
        if (!edits.canUndo) return
        edits = MaskEdits()
        rebuildMask()
    }

    fun updateOptions(transform: (PassportOptions) -> PassportOptions) {
        updateSession { it.copy(options = transform(it.options)) }
    }

    // Creating files

    fun createPhoto() {
        val current = session.value
        val target = (current.options.validate() as? OptionsValidation.Valid)?.target ?: return
        val frame = target.exactSize ?: return
        val currentCutout = cutout ?: return
        val placement = current.placement ?: return
        if (workJob?.isActive == true) return

        activity.update { it.copy(isProcessing = true, problem = null) }
        workJob = viewModelScope.launch {
            try {
                val workspace = ensureWorkspace()
                val output = freshFile(workspace, PHOTO_PREFIX, "jpg")
                val outcome = withContext(defaultDispatcher) {
                    val rendered = renderer.render(currentCutout, placement, frame, current.options.backgroundColor)
                    try {
                        SizeTargeter(codec).fit(rendered, target)
                    } finally {
                        rendered.recycle()
                    }
                }
                when (outcome) {
                    is TargetOutcome.Fitted -> {
                        val dpi = current.options.dpi
                        // Editing the JFIF header keeps the size; inserting one adds 18 bytes, so only keep it if it still fits.
                        val tagged = JpegDensity.withDpi(outcome.bytes, dpi).takeIf { it.size <= target.maxBytes } ?: outcome.bytes
                        withContext(ioDispatcher) { output.writeBytes(tagged) }
                        updateSession {
                            it.copy(
                                photo = PassportPhotoResult(
                                    path = output.path,
                                    sizeBytes = tagged.size.toLong(),
                                    width = frame.width,
                                    height = frame.height,
                                    dpi = dpi,
                                    maxBytes = if (current.options.hasSizeLimit) target.maxBytes else null,
                                    quality = outcome.quality,
                                ),
                                sheet = null,
                                step = PassportStep.Result,
                            )
                        }
                    }
                    is TargetOutcome.TooLarge -> showProblem(PassportProblem.TooLarge(outcome.smallestBytes, outcome.size, target.maxBytes))
                    is TargetOutcome.TooSmall -> showProblem(PassportProblem.Unexpected)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                showProblem(PassportProblem.OutOfMemory)
            } catch (_: Exception) {
                showProblem(PassportProblem.Unexpected)
            } finally {
                activity.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun openSheet() {
        if (session.value.photo == null) return
        updateSession { it.copy(step = PassportStep.Sheet) }
    }

    /** [watermark] is the localised note printed in the sheet's margin. */
    fun createSheet(watermark: String) {
        val current = session.value
        val options = current.options
        val frame = options.frameSize() ?: return
        val currentCutout = cutout ?: return
        val placement = current.placement ?: return
        if (workJob?.isActive == true) return
        val dpi = options.dpi
        val count = options.sheetCount?.takeIf { it in PrintSheets.countOptions(frame, dpi) } ?: PrintSheets.defaultCount(frame, dpi)
        val layout = if (count > 0) PrintSheets.layout(frame, count, dpi) else null
        if (layout == null) {
            showProblem(PassportProblem.SheetDoesNotFit)
            return
        }

        activity.update { it.copy(isProcessing = true, problem = null) }
        workJob = viewModelScope.launch {
            try {
                val workspace = ensureWorkspace()
                val format = options.sheetFormat
                val output = freshFile(workspace, SHEET_PREFIX, format.extension)
                val preview = if (format == SheetFormat.Jpeg) output else File(output.path.substringBeforeLast('.') + "-preview.jpg")
                withContext(defaultDispatcher) {
                    val rendered = renderer.render(currentCutout, placement, frame, options.backgroundColor)
                    val sheet = try {
                        renderer.renderSheet(rendered, layout, watermark)
                    } finally {
                        rendered.recycle()
                    }
                    try {
                        preview.writeBytes(JpegDensity.withDpi(codec.encode(sheet, OutputFormat.Jpeg, SHEET_JPEG_QUALITY), dpi))
                        if (format == SheetFormat.Pdf) renderer.writeSheetPdf(sheet, dpi, output)
                    } finally {
                        sheet.recycle()
                    }
                }
                val sizeBytes = withContext(ioDispatcher) { output.length() }
                updateSession {
                    it.copy(
                        sheet = SheetResult(
                            path = output.path,
                            sizeBytes = sizeBytes,
                            width = layout.sheet.width,
                            height = layout.sheet.height,
                            count = count,
                            format = format,
                            previewPath = preview.path,
                        ),
                        step = PassportStep.SheetResult,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                showProblem(PassportProblem.OutOfMemory)
            } catch (_: Exception) {
                showProblem(PassportProblem.Unexpected)
            } finally {
                activity.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun cancelWork() {
        workJob?.cancel()
    }

    fun savePhoto() {
        val result = session.value.photo ?: return
        if (result.savedUri != null || activity.value.isSaving) return
        runSave {
            val exported = exports.save(File(result.path), passportFileName(result.sizeBytes, result.size), OutputFormat.Jpeg, result.maxBytes, result.size)
            updateSession { s -> s.copy(photo = s.photo?.copy(savedUri = exported.uri.toString(), savedName = exported.displayName)) }
            _events.send(PassportEvent.Saved(exported.displayName, inDocuments = false))
        }
    }

    fun saveSheet() {
        val result = session.value.sheet ?: return
        if (result.savedUri != null || activity.value.isSaving) return
        runSave {
            val name = sheetFileName(result.count, result.format)
            val exported = when (result.format) {
                SheetFormat.Jpeg -> exports.save(File(result.path), name, OutputFormat.Jpeg, maxBytes = null, size = result.size)
                SheetFormat.Pdf -> exports.saveDocument(File(result.path), name, result.format.mimeType, result.size)
            }
            updateSession { s -> s.copy(sheet = s.sheet?.copy(savedUri = exported.uri.toString(), savedName = exported.displayName)) }
            _events.send(PassportEvent.Saved(exported.displayName, inDocuments = result.format == SheetFormat.Pdf))
        }
    }

    fun sharePhoto() {
        val result = session.value.photo ?: return
        share(result.savedUri, File(result.path), passportFileName(result.sizeBytes, result.size), OutputFormat.Jpeg.mimeType)
    }

    fun shareSheet() {
        val result = session.value.sheet ?: return
        share(result.savedUri, File(result.path), sheetFileName(result.count, result.format), result.format.mimeType)
    }

    // Moving between steps

    fun backToEditor() {
        updateSession { it.copy(step = PassportStep.Edit) }
    }

    fun backToPhotoResult() {
        updateSession { it.copy(step = PassportStep.Result) }
    }

    fun backToSheetOptions() {
        updateSession { it.copy(step = PassportStep.Sheet) }
    }

    /** Back to picking a photo, keeping the size, colour and limit. */
    fun startOver() {
        prepareJob?.cancel()
        workJob?.cancel()
        clearImages()
        val previousWorkspace = session.value.workspace
        updateSession { PassportSession(options = it.options) }
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
            it.copy(
                source = PassportSource(file.path, displayName),
                placement = null,
                mode = EditorMode.Position,
                step = PassportStep.Edit,
                photo = null,
                sheet = null,
            )
        }
        preparePhoto(file, fresh = true)
    }

    private fun preparePhoto(file: File, fresh: Boolean) {
        prepareJob?.cancel()
        workJob?.cancel()
        clearImages()
        activity.update { it.copy(isPreparing = true, cutout = null, canUndo = false, personFound = true, problem = null) }
        prepareJob = viewModelScope.launch {
            try {
                val workspace = ensureWorkspace()
                val working = withContext(defaultDispatcher) { loadWorkingPhoto(file) }
                val savedMask = if (fresh) null else withContext(ioDispatcher) { readMask(workspace, working) }
                val base = savedMask ?: segmenter.segment(working).also { segmented ->
                    withContext(ioDispatcher) { writeMask(workspace, segmented) }
                }
                val restoredEdits = if (fresh) {
                    withContext(ioDispatcher) { File(workspace, EDITS_FILE).delete() }
                    MaskEdits()
                } else {
                    withContext(ioDispatcher) { readEdits(workspace) }
                }
                val current = withContext(defaultDispatcher) { restoredEdits.applyTo(base) }
                val newCutout = withContext(defaultDispatcher) { renderer.cutout(working, current) }
                val head = withContext(defaultDispatcher) { HeadFraming.measureHead(current) }

                photo = working
                baseMask = base
                mask = current
                edits = restoredEdits
                cutout = newCutout

                if (session.value.placement == null) {
                    val options = session.value.options
                    val frame = options.frameSize() ?: PassportPreset.India.pixelSize()
                    val placement = head?.let { HeadFraming.placementFor(it, options.guide(frame)) }
                        ?: Placement.cover(PixelSize(working.width, working.height), frame)
                    updateSession { it.copy(placement = placement) }
                }
                activity.update {
                    it.copy(cutout = newCutout, cutoutVersion = it.cutoutVersion + 1, canUndo = restoredEdits.canUndo, personFound = head != null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                dropPhoto(PassportProblem.OutOfMemory)
            } catch (_: Exception) {
                dropPhoto(PassportProblem.UnreadableImage)
            } finally {
                // A newer preparation cancelled this one and owns the loading state now.
                if (isActive) activity.update { it.copy(isPreparing = false) }
            }
        }
    }

    private fun loadWorkingPhoto(file: File): Bitmap {
        var bitmap = decoder.decode(file, maxLongEdge = WORKING_LONG_EDGE * 2).bitmap
        val size = PixelSize(bitmap.width, bitmap.height)
        if (size.longEdge > WORKING_LONG_EDGE) {
            val scaled = codec.scale(bitmap, size.scaledBy(WORKING_LONG_EDGE.toDouble() / size.longEdge))
            bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    private fun rebuildMask() {
        val base = baseMask ?: return
        val currentPhoto = photo ?: return
        val currentCutout = cutout ?: return
        val snapshot = edits
        viewModelScope.launch {
            val rebuilt = withContext(defaultDispatcher) { snapshot.applyTo(base) }
            mask = rebuilt
            renderer.updateCutout(currentCutout, currentPhoto, rebuilt, DirtyRect(0, 0, rebuilt.width, rebuilt.height))
            activity.update { it.copy(canUndo = snapshot.canUndo, cutoutVersion = it.cutoutVersion + 1) }
            persistEdits(snapshot)
        }
    }

    private fun refreshCutout(dirty: DirtyRect) {
        val currentCutout = cutout ?: return
        val currentPhoto = photo ?: return
        val currentMask = mask ?: return
        renderer.updateCutout(currentCutout, currentPhoto, currentMask, dirty)
        activity.update { it.copy(cutoutVersion = it.cutoutVersion + 1) }
    }

    private fun persistEdits(snapshot: MaskEdits) {
        val workspace = session.value.workspace ?: return
        appScope.launch(ioDispatcher) {
            runCatching { File(workspace, EDITS_FILE).writeText(json.encodeToString(MaskEdits.serializer(), snapshot)) }
        }
    }

    private fun readEdits(workspace: File): MaskEdits = runCatching {
        json.decodeFromString(MaskEdits.serializer(), File(workspace, EDITS_FILE).readText())
    }.getOrDefault(MaskEdits())

    private fun writeMask(workspace: File, mask: ForegroundMask) {
        DataOutputStream(File(workspace, MASK_FILE).outputStream().buffered()).use {
            it.writeInt(mask.width)
            it.writeInt(mask.height)
            it.write(mask.alpha)
        }
    }

    private fun readMask(workspace: File, photo: Bitmap): ForegroundMask? = runCatching {
        DataInputStream(File(workspace, MASK_FILE).inputStream().buffered()).use {
            val width = it.readInt()
            val height = it.readInt()
            if (width != photo.width || height != photo.height) return null
            val alpha = ByteArray(width * height)
            it.readFully(alpha)
            ForegroundMask(width, height, alpha)
        }
    }.getOrNull()

    private suspend fun freshFile(workspace: File, prefix: String, extension: String): File = withContext(ioDispatcher) {
        workspace.listFiles { file -> file.name.startsWith(prefix) }?.forEach { it.delete() }
        File(workspace, "$prefix${System.currentTimeMillis()}.$extension")
    }

    private fun runSave(block: suspend () -> Unit) {
        activity.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PassportProblem.SaveFailed)
            } finally {
                activity.update { it.copy(isSaving = false) }
            }
        }
    }

    private fun share(savedUri: String?, file: File, displayName: String, mimeType: String) {
        viewModelScope.launch {
            try {
                val uri = savedUri?.let(Uri::parse) ?: exports.shareableCopy(file, displayName)
                _events.send(PassportEvent.Share(uri, mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PassportProblem.Unexpected)
            }
        }
    }

    private fun dropPhoto(problem: PassportProblem) {
        clearImages()
        updateSession { it.copy(source = null, placement = null, photo = null, sheet = null, step = PassportStep.Edit) }
        showProblem(problem)
    }

    /** Drops references only. Compose may still be drawing the old cutout, so it isn't recycled. */
    private fun clearImages() {
        photo = null
        baseMask = null
        mask = null
        cutout = null
        edits = MaskEdits()
        strokePoints = null
    }

    private suspend fun ensureWorkspace(): File {
        val existing = session.value.workspace
        if (existing != null) return withContext(ioDispatcher) { File(existing).apply { mkdirs() } }
        val created = workspaces.create(WORKSPACE_PREFIX)
        updateSession { it.copy(workspace = created.path) }
        return created
    }

    private fun showProblem(problem: PassportProblem) {
        activity.update { it.copy(problem = problem) }
    }

    private fun deleteWorkspaceLater(path: String) {
        appScope.launch { workspaces.delete(path) }
    }

    private fun updateSession(transform: (PassportSession) -> PassportSession) {
        val updated = session.updateAndGet(transform)
        savedStateHandle[KEY_SESSION] = json.encodeToString(PassportSession.serializer(), updated)
    }

    private fun restoreSession(): PassportSession =
        savedStateHandle.get<String>(KEY_SESSION)
            ?.let { runCatching { json.decodeFromString(PassportSession.serializer(), it) }.getOrNull() }
            ?: PassportSession()

    private data class Activity(
        val cutout: Bitmap? = null,
        val cutoutVersion: Int = 0,
        val canUndo: Boolean = false,
        val personFound: Boolean = true,
        val isPreparing: Boolean = false,
        val isProcessing: Boolean = false,
        val isSaving: Boolean = false,
        val problem: PassportProblem? = null,
    )

    private companion object {
        const val KEY_SESSION = "passport_session"
        const val WORKSPACE_PREFIX = "passport"
        const val PHOTO_PREFIX = "photo-"
        const val SHEET_PREFIX = "sheet-"
        const val MASK_FILE = "mask.bin"
        const val EDITS_FILE = "edits.json"
        const val CAMERA_PHOTO_NAME = "camera.jpg"
        const val WORKING_LONG_EDGE = 1600
        const val SHEET_JPEG_QUALITY = 95
        val json = Json { ignoreUnknownKeys = true }
    }
}
