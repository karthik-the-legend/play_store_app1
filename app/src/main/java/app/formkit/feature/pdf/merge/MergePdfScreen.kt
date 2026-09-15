package app.formkit.feature.pdf.merge

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.rememberStorageAwareSave
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfPreviewCard
import app.formkit.feature.pdf.common.PdfResultLayout
import app.formkit.feature.pdf.common.PdfToolDialogs
import app.formkit.feature.pdf.common.PdfToolEffects
import app.formkit.feature.pdf.common.PdfToolStartScreen
import app.formkit.feature.pdf.common.PickedPdfCard
import app.formkit.feature.pdf.common.ReorderableColumn
import app.formkit.feature.pdf.common.ToolActivity

@Composable
fun MergePdfRoute(
    onBack: () -> Unit,
    viewModel: MergePdfViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val activity by viewModel.activityState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addPdfs(uris)
    }
    val pickPdfs: () -> Unit = { picker.launch(arrayOf(PDF_MIME)) }
    val save = rememberStorageAwareSave(snackbarHostState, viewModel::save)
    PdfToolEffects(viewModel.events, snackbarHostState)

    val result = state.result
    when {
        state.pdfs.isEmpty() -> PdfToolStartScreen(
            title = stringResource(R.string.tool_merge_pdf_title),
            heading = stringResource(R.string.merge_heading),
            body = stringResource(R.string.merge_body),
            pickLabel = stringResource(R.string.merge_pick),
            icon = R.drawable.ic_tool_merge_pdf,
            isLoading = activity.isImporting,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onPick = pickPdfs,
        )
        result != null -> {
            BackHandler(onBack = viewModel::backToList)
            MergeResultScreen(
                result = result,
                isSaving = activity.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToList,
                onSave = save,
                onShare = viewModel::share,
                onDoAnother = viewModel::startOver,
            )
        }
        else -> {
            BackHandler(onBack = viewModel::startOver)
            MergeListScreen(
                state = state,
                activity = activity,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onAddMore = pickPdfs,
                onEnterPassword = viewModel::askPassword,
                onRemove = viewModel::remove,
                onMove = viewModel::move,
                onMerge = viewModel::merge,
            )
        }
    }

    PdfToolDialogs(
        activity = activity,
        pdfs = state.pdfs,
        processingTitle = stringResource(R.string.merge_processing),
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        onCancelWork = viewModel::cancelWork,
        onDismissProblem = viewModel::dismissProblem,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun MergeListScreen(
    state: MergeSession,
    activity: ToolActivity,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAddMore: () -> Unit,
    onEnterPassword: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onMerge: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_merge_pdf_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.merge_action),
                    onClick = onMerge,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canMerge && !activity.isProcessing && !activity.isImporting,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Text(
                stringResource(R.string.merge_reorder_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReorderableColumn(
                items = state.pdfs,
                key = { it.id },
                onMove = onMove,
                spacing = Spacing.small,
            ) { pdf, index, handle, _ ->
                val moveUp: (() -> Unit)? = if (index > 0) {
                    { onMove(index, index - 1) }
                } else {
                    null
                }
                val moveDown: (() -> Unit)? = if (index < state.pdfs.lastIndex) {
                    { onMove(index, index + 1) }
                } else {
                    null
                }
                PickedPdfCard(
                    pdf = pdf,
                    onEnterPassword = { onEnterPassword(pdf.id) },
                    onRemove = { onRemove(pdf.id) },
                    handle = handle,
                    onMoveUp = moveUp,
                    onMoveDown = moveDown,
                )
            }
            OutlinedButton(
                onClick = onAddMore,
                enabled = !activity.isImporting,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.small))
                Text(stringResource(R.string.merge_add_more))
            }
            val files = pluralStringResource(R.plurals.pdf_file_count, state.pdfs.size, state.pdfs.size)
            val pages = pluralStringResource(R.plurals.pdf_page_count, state.totalPages, state.totalPages)
            Text(
                text = when {
                    state.pdfs.size < MergeSession.MIN_FILES -> stringResource(R.string.merge_need_two)
                    state.pdfs.any { !it.isReady } -> stringResource(R.string.pdf_unlock_all_hint)
                    else -> stringResource(R.string.pdf_file_details, files, pages)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.canMerge) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MergeResultScreen(
    result: MergeResult,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    PdfResultLayout(
        title = stringResource(R.string.merge_result_title),
        sizeHeadline = formatSize(result.sizeBytes),
        details = stringResource(
            R.string.pdf_file_details,
            pluralStringResource(R.plurals.pdf_file_count, result.fileCount, result.fileCount),
            pluralStringResource(R.plurals.pdf_page_count, result.pageCount, result.pageCount),
        ),
        isSaved = result.saved != null,
        isSaving = isSaving,
        savedLabel = stringResource(R.string.export_saved_indicator_documents),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSave = onSave,
        onShare = onShare,
        onDoAnother = onDoAnother,
    ) {
        PdfPreviewCard(result.path)
    }
}
