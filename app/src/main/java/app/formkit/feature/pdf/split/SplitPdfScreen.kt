package app.formkit.feature.pdf.split

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.pdf.RangeParse
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.rememberStorageAwareSave
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfPageThumbnail
import app.formkit.feature.pdf.common.PdfResultLayout
import app.formkit.feature.pdf.common.PdfToolDialogs
import app.formkit.feature.pdf.common.PdfToolEffects
import app.formkit.feature.pdf.common.PdfToolStartScreen
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.PickedPdfCard
import app.formkit.feature.pdf.common.SegmentedChoice
import app.formkit.feature.pdf.common.ToolActivity
import app.formkit.feature.pdf.common.pdfDetails

@Composable
fun SplitPdfRoute(
    onBack: () -> Unit,
    viewModel: SplitPdfViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val activity by viewModel.activityState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pickPdf(uri)
    }
    val pickPdf: () -> Unit = { picker.launch(arrayOf(PDF_MIME)) }
    val saveAll = rememberStorageAwareSave(snackbarHostState, viewModel::saveAll)
    PdfToolEffects(viewModel.events, snackbarHostState)

    val pdf = state.pdf
    when {
        pdf == null -> PdfToolStartScreen(
            title = stringResource(R.string.tool_split_pdf_title),
            heading = stringResource(R.string.split_heading),
            body = stringResource(R.string.split_body),
            pickLabel = stringResource(R.string.split_pick),
            icon = R.drawable.ic_tool_split_pdf,
            isLoading = activity.isImporting,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onPick = pickPdf,
        )
        state.outputs.isNotEmpty() -> {
            BackHandler(onBack = viewModel::backToOptions)
            SplitResultScreen(
                state = state,
                isSaving = activity.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToOptions,
                onSave = saveAll,
                onShare = viewModel::shareAll,
                onDoAnother = viewModel::startOver,
            )
        }
        else -> {
            BackHandler(onBack = viewModel::startOver)
            SplitOptionsScreen(
                state = state,
                pdf = pdf,
                activity = activity,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onChangePdf = pickPdf,
                onEnterPassword = { viewModel.askPassword(pdf.id) },
                onMode = viewModel::chooseMode,
                onRanges = viewModel::typeRanges,
                onTogglePage = viewModel::togglePage,
                onSelectAll = viewModel::selectAll,
                onClear = viewModel::clearSelection,
                onSplit = viewModel::split,
            )
        }
    }

    PdfToolDialogs(
        activity = activity,
        pdfs = listOfNotNull(pdf),
        processingTitle = stringResource(R.string.split_processing),
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        onCancelWork = viewModel::cancelWork,
        onDismissProblem = viewModel::dismissProblem,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitOptionsScreen(
    state: SplitSession,
    pdf: PickedPdf,
    activity: ToolActivity,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onChangePdf: () -> Unit,
    onEnterPassword: () -> Unit,
    onMode: (SplitMode) -> Unit,
    onRanges: (String) -> Unit,
    onTogglePage: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onSplit: () -> Unit,
) {
    val readable = pdf.readablePath
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_split_pdf_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(if (state.mode == SplitMode.Ranges) R.string.split_action else R.string.split_action_extract),
                    onClick = onSplit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canSplit && !activity.isProcessing,
                )
            }
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = Spacing.small),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        PickedPdfCard(pdf = pdf, onEnterPassword = onEnterPassword)
                        TextButton(onClick = onChangePdf) { Text(stringResource(R.string.pdf_change_file)) }
                    }
                    if (pdf.isReady) {
                        SegmentedChoice(
                            options = SplitMode.entries,
                            selected = state.mode,
                            onSelect = onMode,
                            label = { mode -> stringResource(if (mode == SplitMode.Ranges) R.string.split_mode_ranges else R.string.split_mode_pick) },
                        )
                        if (state.mode == SplitMode.Ranges) {
                            RangeField(state, pdf.pageCount, onRanges)
                        } else {
                            Text(
                                stringResource(R.string.split_pick_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.split_selected, state.selected.size, pdf.pageCount),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.split_select_all)) }
                                TextButton(onClick = onClear, enabled = state.selected.isNotEmpty()) { Text(stringResource(R.string.split_clear)) }
                            }
                        }
                    }
                }
            }
            if (state.mode == SplitMode.Pick && pdf.isReady && readable != null) {
                items(count = pdf.pageCount, key = { it }) { index ->
                    PageTile(path = readable, index = index, selected = index in state.selected, onToggle = { onTogglePage(index) })
                }
            }
        }
    }
}

@Composable
private fun RangeField(state: SplitSession, pageCount: Int, onRanges: (String) -> Unit) {
    val error = when (val parsed = state.parsedRanges()) {
        is RangeParse.Invalid -> stringResource(R.string.split_error_invalid, parsed.token)
        is RangeParse.OutOfBounds -> stringResource(R.string.split_error_out_of_bounds, parsed.page, parsed.pageCount)
        else -> null
    }
    OutlinedTextField(
        value = state.rangeText,
        onValueChange = onRanges,
        label = { Text(stringResource(R.string.split_range_label)) },
        placeholder = { Text(stringResource(R.string.split_range_placeholder, pageCount)) },
        singleLine = true,
        isError = error != null,
        supportingText = { Text(error ?: stringResource(R.string.split_range_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PageTile(path: String, index: Int, selected: Boolean, onToggle: () -> Unit) {
    val description = stringResource(R.string.cd_pdf_page, index + 1)
    Column(
        modifier = Modifier
            .toggleable(value = selected, onValueChange = { onToggle() }, role = Role.Checkbox)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            PdfPageThumbnail(
                path = path,
                pageIndex = index,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(PAGE_TILE_RATIO)
                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
                maxEdge = 240,
            )
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_circle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .background(Color.White, CircleShape),
                )
            }
        }
        Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
    }
}

private const val PAGE_TILE_RATIO = 0.72f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SplitResultScreen(
    state: SplitSession,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    val outputs = state.outputs
    PdfResultLayout(
        resultKey = outputs.first().path,
        title = stringResource(R.string.split_result_title),
        sizeHeadline = pluralStringResource(R.plurals.pdf_file_count, outputs.size, outputs.size),
        details = stringResource(R.string.split_result_details, formatSize(outputs.sumOf { it.sizeBytes })),
        isSaved = state.saved.size == outputs.size,
        isSaving = isSaving,
        savedLabel = stringResource(R.string.export_saved_indicator_documents),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSave = onSave,
        onShare = onShare,
        onDoAnother = onDoAnother,
    ) {
        outputs.forEach { output ->
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                ) {
                    PdfPageThumbnail(output.path, 0, contentDescription = null, modifier = Modifier.width(56.dp).height(76.dp), maxEdge = 200)
                    Column(Modifier.weight(1f)) {
                        Text(output.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            pdfDetails(output.pageCount, output.sizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
