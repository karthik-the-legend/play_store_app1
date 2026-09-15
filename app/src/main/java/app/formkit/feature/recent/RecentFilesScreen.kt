package app.formkit.feature.recent

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.history.ExportRecord
import app.formkit.core.imaging.PixelSize
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.EmptyState
import app.formkit.core.ui.components.NativeAdCard
import app.formkit.core.ui.components.RecentFilesIllustration
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.shareFile
import app.formkit.core.ui.theme.Spacing
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    onBack: () -> Unit,
    onStartResize: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecentFilesViewModel = hiltViewModel(),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDeleteId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val message = when (event) {
                RecentFilesEvent.Deleted -> R.string.recent_deleted
                RecentFilesEvent.RemovedFromList -> R.string.recent_removed_from_list
            }
            snackbarHostState.showSnackbar(context.getString(message))
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(title = stringResource(R.string.recent_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val current = records
        when {
            current == null -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            current.isEmpty() -> EmptyState(
                illustration = { RecentFilesIllustration() },
                title = stringResource(R.string.recent_empty_title),
                body = stringResource(R.string.recent_empty_body),
                actionLabel = stringResource(R.string.recent_empty_action),
                onAction = onStartResize,
                modifier = Modifier.padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                items(current, key = { it.id }) { record ->
                    RecordRow(
                        record = record,
                        onShare = {
                            val shared = context.shareFile(
                                Uri.parse(record.uri),
                                record.mimeType,
                                context.getString(R.string.recent_share_chooser),
                            )
                            if (!shared) scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_no_share_app)) }
                        },
                        onDelete = { pendingDeleteId = record.id },
                    )
                }
                // Last in the list, so an ad arriving late never pushes a file the user is reading.
                item(key = NATIVE_AD_KEY) { NativeAdCard(Modifier.fillMaxWidth()) }
            }
        }
    }

    val pending = records?.firstOrNull { it.id == pendingDeleteId }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.recent_delete_title)) },
            text = { Text(stringResource(R.string.recent_delete_body, pending.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(pending)
                        pendingDeleteId = null
                    },
                ) { Text(stringResource(R.string.recent_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text(stringResource(R.string.resize_cancel)) }
            },
        )
    }
}

private const val NATIVE_AD_KEY = "native-ad"

@Composable
private fun RecordRow(record: ExportRecord, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val size = formatSize(record.sizeBytes)
    val details = if (record.width != null && record.height != null) {
        stringResource(R.string.recent_record_details, size, PixelSize(record.width, record.height).toString())
    } else {
        size
    }
    val date = remember(record.createdAtMillis) {
        DateUtils.formatDateTime(
            context,
            record.createdAtMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.small, top = Spacing.small, bottom = Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            AsyncImage(
                model = Uri.parse(record.uri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.small),
            ) {
                Text(
                    text = record.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(details, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onShare) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.cd_share_file, record.displayName))
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.cd_delete_file, record.displayName))
            }
        }
    }
}
