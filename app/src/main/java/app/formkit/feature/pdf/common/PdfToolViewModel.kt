package app.formkit.feature.pdf.common

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.di.ApplicationScope
import app.formkit.core.di.DefaultDispatcher
import app.formkit.core.di.IoDispatcher
import app.formkit.core.export.ImageExports
import app.formkit.core.pdf.PdfDocuments
import app.formkit.core.storage.Workspaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

const val PDF_MIME = "application/pdf"

/** What every PDF tool needs, in one injectable bundle. */
class PdfToolDeps @Inject constructor(
    val intake: PdfIntake,
    val documents: PdfDocuments,
    val workspaces: Workspaces,
    val exports: ImageExports,
    @param:IoDispatcher val ioDispatcher: CoroutineDispatcher,
    @param:DefaultDispatcher val defaultDispatcher: CoroutineDispatcher,
    @param:ApplicationScope val appScope: CoroutineScope,
)

sealed interface PdfProblem {
    data object ImportFailed : PdfProblem
    data object Failed : PdfProblem
    data object OutOfMemory : PdfProblem
    data object SaveFailed : PdfProblem
    data class TooLarge(val smallestBytes: Long, val maxBytes: Long) : PdfProblem
    data class AlreadySmall(val sizeBytes: Long, val maxBytes: Long) : PdfProblem
}

enum class ProgressStage { Analysing, Pages, Photos, Writing }

data class WorkProgress(val stage: ProgressStage, val done: Int, val total: Int)

/** The in-memory side of a tool screen: loading, dialogs and progress. Not saved across process death. */
data class ToolActivity(
    val isImporting: Boolean = false,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val progress: WorkProgress? = null,
    val problem: PdfProblem? = null,
    /** The id of the PDF whose password dialog is showing. */
    val passwordFor: String? = null,
    val checkingPassword: Boolean = false,
)

sealed interface PdfEvent {
    /** [folder] names where a set of images went, such as "Pictures/FormKit/Statement". */
    data class Saved(val displayName: String, val count: Int, val inDocuments: Boolean, val folder: String? = null) : PdfEvent
    data class Share(val uris: List<Uri>, val mimeType: String) : PdfEvent
}

@Serializable
data class SavedFile(val uri: String, val displayName: String)

/**
 * The shared shape of the PDF tools. The session ([S]) is kept as JSON in [SavedStateHandle] and
 * the files it points at live in a per-session workspace, so a tool comes back after process
 * death. Long work is one cancellable job at a time.
 */
abstract class PdfToolViewModel<S : Any>(
    private val savedStateHandle: SavedStateHandle,
    private val serializer: KSerializer<S>,
    private val initial: S,
    protected val deps: PdfToolDeps,
) : ViewModel() {

    protected val session = MutableStateFlow(restoreSession())
    protected val activity = MutableStateFlow(ToolActivity())
    val sessionState: StateFlow<S> = session.asStateFlow()
    val activityState: StateFlow<ToolActivity> = activity.asStateFlow()

    private val eventChannel = Channel<PdfEvent>(Channel.BUFFERED)
    val events: Flow<PdfEvent> = eventChannel.receiveAsFlow()

    private var workspacePath: String? = savedStateHandle[KEY_WORKSPACE]
    private var workJob: Job? = null

    /** Names the workspace folder, like "merge". */
    protected abstract val workspacePrefix: String

    protected abstract fun pdfsIn(state: S): List<PickedPdf>

    protected abstract fun replacePdf(state: S, pdf: PickedPdf): S

    // Passwords

    fun askPassword(id: String) {
        activity.update { it.copy(passwordFor = id) }
    }

    fun dismissPassword() {
        activity.update { it.copy(passwordFor = null, checkingPassword = false) }
    }

    fun submitPassword(password: String) {
        val id = activity.value.passwordFor ?: return
        val pdf = pdfsIn(session.value).firstOrNull { it.id == id } ?: return
        if (activity.value.checkingPassword) return
        activity.update { it.copy(checkingPassword = true) }
        viewModelScope.launch {
            val checked = try {
                deps.intake.unlock(pdf, password)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                pdf.copy(status = PdfStatus.Unreadable)
            }
            updateSession { replacePdf(it, checked) }
            activity.update {
                it.copy(checkingPassword = false, passwordFor = if (checked.status == PdfStatus.WrongPassword) id else null)
            }
        }
    }

    // Picking files

    protected fun importPdfs(uris: List<Uri>, onImported: (S, List<PickedPdf>) -> S) {
        if (uris.isEmpty() || activity.value.isImporting) return
        activity.update { it.copy(isImporting = true, problem = null) }
        viewModelScope.launch {
            try {
                val workspace = workspace()
                val imported = uris.map { deps.intake.importPdf(it, workspace) }
                updateSession { onImported(it, imported) }
                imported.firstOrNull { it.needsPassword }?.let { askPassword(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PdfProblem.ImportFailed)
            } finally {
                activity.update { it.copy(isImporting = false) }
            }
        }
    }

    protected fun importImages(uris: List<Uri>, onImported: (S, List<PickedImage>) -> S) {
        if (uris.isEmpty() || activity.value.isImporting) return
        activity.update { it.copy(isImporting = true, problem = null) }
        viewModelScope.launch {
            try {
                val workspace = workspace()
                val imported = uris.map { deps.intake.importImage(it, workspace) }
                updateSession { onImported(it, imported) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PdfProblem.ImportFailed)
            } finally {
                activity.update { it.copy(isImporting = false) }
            }
        }
    }

    // Work

    protected fun runWork(block: suspend () -> Unit) {
        if (workJob?.isActive == true) return
        activity.update { it.copy(isProcessing = true, progress = null, problem = null) }
        workJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: OutOfMemoryError) {
                showProblem(PdfProblem.OutOfMemory)
            } catch (_: Exception) {
                showProblem(PdfProblem.Failed)
            } finally {
                activity.update { it.copy(isProcessing = false, progress = null) }
            }
        }
    }

    fun cancelWork() {
        workJob?.cancel()
    }

    /** Safe to call from any thread. */
    protected fun setProgress(progress: WorkProgress) {
        activity.update { it.copy(progress = progress) }
    }

    protected fun runSave(block: suspend () -> Unit) {
        if (activity.value.isSaving) return
        activity.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PdfProblem.SaveFailed)
            } finally {
                activity.update { it.copy(isSaving = false) }
            }
        }
    }

    /** Shares the saved copies when every file was saved, otherwise readable-named copies of the work files. */
    protected fun shareOutputs(files: List<Pair<File, String>>, saved: List<SavedFile>, mimeType: String) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            try {
                val uris = if (saved.size == files.size) {
                    saved.map { Uri.parse(it.uri) }
                } else {
                    files.map { (file, name) -> deps.exports.shareableCopy(file, name) }
                }
                eventChannel.send(PdfEvent.Share(uris, mimeType))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showProblem(PdfProblem.Failed)
            }
        }
    }

    protected suspend fun send(event: PdfEvent) = eventChannel.send(event)

    protected fun showProblem(problem: PdfProblem) {
        activity.update { it.copy(problem = problem) }
    }

    fun dismissProblem() {
        activity.update { it.copy(problem = null) }
    }

    /** Back to the start screen with nothing picked. */
    fun startOver() {
        workJob?.cancel()
        val previous = workspacePath
        workspacePath = null
        savedStateHandle.remove<String>(KEY_WORKSPACE)
        updateSession { initial }
        activity.value = ToolActivity()
        previous?.let(::deleteWorkspaceLater)
    }

    override fun onCleared() {
        workspacePath?.let(::deleteWorkspaceLater)
    }

    protected suspend fun workspace(): File {
        workspacePath?.let { path -> return withContext(deps.ioDispatcher) { File(path).apply { mkdirs() } } }
        val created = deps.workspaces.create(workspacePrefix)
        workspacePath = created.path
        savedStateHandle[KEY_WORKSPACE] = created.path
        return created
    }

    protected fun updateSession(transform: (S) -> S) {
        val updated = session.updateAndGet(transform)
        savedStateHandle[KEY_SESSION] = json.encodeToString(serializer, updated)
    }

    private fun restoreSession(): S =
        savedStateHandle.get<String>(KEY_SESSION)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            ?: initial

    private fun deleteWorkspaceLater(path: String) {
        deps.appScope.launch { deps.workspaces.delete(path) }
    }

    private companion object {
        const val KEY_SESSION = "pdf_tool_session"
        const val KEY_WORKSPACE = "pdf_tool_workspace"
        val json = Json { ignoreUnknownKeys = true }
    }
}
