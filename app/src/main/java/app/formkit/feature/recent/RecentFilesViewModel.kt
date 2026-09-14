package app.formkit.feature.recent

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.formkit.core.history.ExportHistoryRepository
import app.formkit.core.history.ExportRecord
import app.formkit.core.storage.FileExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RecentFilesEvent {
    data object Deleted : RecentFilesEvent

    /** The row is gone but the file couldn't be deleted, e.g. it belongs to an earlier install. */
    data object RemovedFromList : RecentFilesEvent
}

@HiltViewModel
class RecentFilesViewModel @Inject constructor(
    private val history: ExportHistoryRepository,
    private val exporter: FileExporter,
) : ViewModel() {

    /** Null until the history has loaded, so the empty state doesn't flash first. */
    val records: StateFlow<List<ExportRecord>?> =
        history.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<RecentFilesEvent>(Channel.BUFFERED)
    val events: Flow<RecentFilesEvent> = _events.receiveAsFlow()

    init {
        // Files deleted in the Gallery shouldn't linger here as broken rows.
        viewModelScope.launch {
            val missing = history.records.first()
                .filterNot { exporter.exists(Uri.parse(it.uri)) }
                .map { it.id }
                .toSet()
            history.removeAll(missing)
        }
    }

    fun delete(record: ExportRecord) {
        viewModelScope.launch {
            val deleted = exporter.delete(Uri.parse(record.uri))
            history.remove(record.id)
            _events.send(if (deleted) RecentFilesEvent.Deleted else RecentFilesEvent.RemovedFromList)
        }
    }
}
