package app.formkit.feature.pdf.images

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.pdf.RangeParse
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.Section
import app.formkit.core.ui.components.rememberStorageAwareSave
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import app.formkit.feature.pdf.common.ChipChoice
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfResultLayout
import app.formkit.feature.pdf.common.PdfToolDialogs
import app.formkit.feature.pdf.common.PdfToolEffects
import app.formkit.feature.pdf.common.PdfToolStartScreen
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.PickedPdfCard
import app.formkit.feature.pdf.common.SegmentedChoice
import app.formkit.feature.pdf.common.ToolActivity
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun PdfToImagesRoute(
    onBack: () -> Unit,
    viewModel: PdfToImagesViewModel = hiltViewModel(),
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
            title = stringResource(R.string.tool_pdf_to_images_title),
            heading = stringResource(R.string.pdf_images_heading),
            body = stringResource(R.string.pdf_images_body),
            pickLabel = stringResource(R.string.pdf_images_pick),
            icon = R.drawable.ic_tool_pdf_to_images,
            isLoading = activity.isImporting,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onPick = pickPdf,
        )
        state.pages.isNotEmpty() -> {
            BackHandler(onBack = viewModel::backToOptions)
            PdfImagesResultScreen(
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
            PdfImagesOptionsScreen(
                state = state,
                pdf = pdf,
                activity = activity,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onChangePdf = pickPdf,
                onEnterPassword = { viewModel.askPassword(pdf.id) },
                onSelection = viewModel::chooseSelection,
                onRanges = viewModel::typeRanges,
                onDpi = viewModel::chooseDpi,
                onFormat = viewModel::chooseFormat,
                onExport = viewModel::export,
            )
        }
    }

    PdfToolDialogs(
        activity = activity,
        pdfs = listOfNotNull(pdf),
        processingTitle = stringResource(R.string.pdf_images_processing),
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        onCancelWork = viewModel::cancelWork,
        onDismissProblem = viewModel::dismissProblem,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfImagesOptionsScreen(
    state: PdfToImagesSession,
    pdf: PickedPdf,
    activity: ToolActivity,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onChangePdf: () -> Unit,
    onEnterPassword: () -> Unit,
    onSelection: (PageSelection) -> Unit,
    onRanges: (String) -> Unit,
    onDpi: (ImageDpi) -> Unit,
    onFormat: (OutputFormat) -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_pdf_to_images_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.pdf_images_action),
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.pageIndices().isNullOrEmpty() && !activity.isProcessing,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                PickedPdfCard(pdf = pdf, onEnterPassword = onEnterPassword)
                TextButton(onClick = onChangePdf) { Text(stringResource(R.string.pdf_change_file)) }
            }
            if (!pdf.isReady) return@Column
            Section(stringResource(R.string.pdf_images_section_pages)) {
                SegmentedChoice(
                    options = PageSelection.entries,
                    selected = state.selection,
                    onSelect = onSelection,
                    label = { selection -> stringResource(if (selection == PageSelection.All) R.string.pdf_images_all else R.string.pdf_images_some) },
                )
                if (state.selection == PageSelection.Some) {
                    val error = when (val parsed = state.parsedRanges()) {
                        is RangeParse.Invalid -> stringResource(R.string.split_error_invalid, parsed.token)
                        is RangeParse.OutOfBounds -> stringResource(R.string.split_error_out_of_bounds, parsed.page, parsed.pageCount)
                        else -> null
                    }
                    OutlinedTextField(
                        value = state.rangeText,
                        onValueChange = onRanges,
                        label = { Text(stringResource(R.string.split_range_label)) },
                        placeholder = { Text(stringResource(R.string.split_range_placeholder, pdf.pageCount)) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = { Text(error ?: stringResource(R.string.pdf_images_range_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Section(stringResource(R.string.pdf_images_section_quality), stringResource(R.string.pdf_images_dpi_hint)) {
                ChipChoice(
                    options = ImageDpi.entries,
                    selected = state.dpi,
                    onSelect = onDpi,
                    label = { dpi -> stringResource(R.string.pdf_images_dpi, dpi.dpi) },
                )
            }
            Section(stringResource(R.string.pdf_images_section_format)) {
                SegmentedChoice(
                    options = OutputFormat.entries,
                    selected = state.format,
                    onSelect = onFormat,
                    label = { format -> stringResource(if (format == OutputFormat.Jpeg) R.string.format_jpeg else R.string.format_png) },
                )
                Text(
                    stringResource(if (state.format == OutputFormat.Jpeg) R.string.pdf_images_format_hint_jpeg else R.string.pdf_images_format_hint_png),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PdfImagesResultScreen(
    state: PdfToImagesSession,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    val pages = state.pages
    val format = state.madeWithFormat ?: state.format
    PdfResultLayout(
        title = stringResource(R.string.pdf_images_result_title),
        sizeHeadline = pluralStringResource(R.plurals.pdf_image_count, pages.size, pages.size),
        details = stringResource(
            R.string.pdf_images_result_details,
            formatSize(pages.sumOf { it.sizeBytes }),
            state.madeWithDpi ?: state.dpi.dpi,
            stringResource(if (format == OutputFormat.Jpeg) R.string.format_jpeg else R.string.format_png),
        ),
        isSaved = state.saved.size == pages.size,
        isSaving = isSaving,
        savedLabel = stringResource(R.string.pdf_images_saved_indicator, state.folderName),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSave = onSave,
        onShare = onShare,
        onDoAnother = onDoAnother,
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            pages.forEach { page ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AsyncImage(
                        model = File(page.path),
                        contentDescription = stringResource(R.string.cd_pdf_page, page.pageNumber),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(96.dp)
                            .aspectRatio(page.width.toFloat() / page.height)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    )
                    Text("${page.pageNumber}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
