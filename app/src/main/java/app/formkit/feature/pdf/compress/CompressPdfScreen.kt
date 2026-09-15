package app.formkit.feature.pdf.compress

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.TargetInput
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.NumberField
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.Section
import app.formkit.core.ui.components.rememberStorageAwareSave
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import app.formkit.feature.pdf.common.ChipChoice
import app.formkit.feature.pdf.common.NoticeCard
import app.formkit.feature.pdf.common.PDF_MIME
import app.formkit.feature.pdf.common.PdfPreviewCard
import app.formkit.feature.pdf.common.PdfResultLayout
import app.formkit.feature.pdf.common.PdfToolDialogs
import app.formkit.feature.pdf.common.PdfToolEffects
import app.formkit.feature.pdf.common.PdfToolStartScreen
import app.formkit.feature.pdf.common.PickedPdf
import app.formkit.feature.pdf.common.PickedPdfCard
import app.formkit.feature.pdf.common.ToolActivity
import kotlin.math.roundToInt

@Composable
fun CompressPdfRoute(
    onBack: () -> Unit,
    viewModel: CompressPdfViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val activity by viewModel.activityState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pickPdf(uri)
    }
    val pickPdf: () -> Unit = { picker.launch(arrayOf(PDF_MIME)) }
    val save = rememberStorageAwareSave(snackbarHostState, viewModel::save)
    PdfToolEffects(viewModel.events, snackbarHostState)

    val pdf = state.pdf
    val result = state.result
    when {
        pdf == null -> PdfToolStartScreen(
            title = stringResource(R.string.tool_compress_pdf_title),
            heading = stringResource(R.string.compress_heading),
            body = stringResource(R.string.compress_body),
            pickLabel = stringResource(R.string.compress_pick),
            icon = R.drawable.ic_tool_compress_pdf,
            isLoading = activity.isImporting,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onPick = pickPdf,
        ) {
            NoticeCard(stringResource(R.string.compress_notice_title), stringResource(R.string.compress_notice_body))
        }
        result != null -> {
            BackHandler(onBack = viewModel::backToOptions)
            CompressResultScreen(
                result = result,
                isSaving = activity.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToOptions,
                onSave = save,
                onShare = viewModel::share,
                onDoAnother = viewModel::startOver,
            )
        }
        else -> {
            BackHandler(onBack = viewModel::startOver)
            CompressOptionsScreen(
                state = state,
                pdf = pdf,
                activity = activity,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onChangePdf = pickPdf,
                onEnterPassword = { viewModel.askPassword(pdf.id) },
                onPreset = viewModel::choosePreset,
                onCustomKb = viewModel::typeCustomKb,
                onCompress = viewModel::compress,
            )
        }
    }

    PdfToolDialogs(
        activity = activity,
        pdfs = listOfNotNull(pdf),
        processingTitle = stringResource(R.string.compress_processing),
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        onCancelWork = viewModel::cancelWork,
        onDismissProblem = viewModel::dismissProblem,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressOptionsScreen(
    state: CompressSession,
    pdf: PickedPdf,
    activity: ToolActivity,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onChangePdf: () -> Unit,
    onEnterPassword: () -> Unit,
    onPreset: (CompressPreset) -> Unit,
    onCustomKb: (String) -> Unit,
    onCompress: () -> Unit,
) {
    val errors = mutableSetOf<OptionsError>()
    val maxBytes = state.maxBytes(errors)
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_compress_pdf_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.compress_action),
                    onClick = onCompress,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = pdf.isReady && maxBytes != null && !activity.isProcessing,
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
            Section(
                title = stringResource(R.string.compress_section_limit),
                subtitle = if (pdf.isReady) stringResource(R.string.compress_limit_current, formatSize(pdf.sizeBytes)) else null,
            ) {
                ChipChoice(
                    options = CompressPreset.entries,
                    selected = state.preset,
                    onSelect = onPreset,
                    label = { preset ->
                        val kilobytes = preset.kilobytes
                        if (kilobytes != null) formatSize(kilobytes * TargetInput.BYTES_PER_KB) else stringResource(R.string.resize_custom)
                    },
                )
                if (state.preset == CompressPreset.Custom) {
                    NumberField(
                        value = state.customKb,
                        onValueChange = onCustomKb,
                        label = stringResource(R.string.resize_custom_size_label),
                        suffix = stringResource(R.string.unit_kb_suffix),
                        error = when {
                            OptionsError.CustomSizeTooSmall in errors -> stringResource(R.string.resize_error_size_too_small, TargetInput.MIN_KB)
                            OptionsError.CustomSizeTooLarge in errors -> stringResource(R.string.resize_error_size_too_large, TargetInput.MAX_KB)
                            else -> null
                        },
                        hint = stringResource(R.string.resize_custom_size_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            NoticeCard(stringResource(R.string.compress_notice_title), stringResource(R.string.compress_notice_body))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CompressResultScreen(
    result: CompressResult,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    val pages = pluralStringResource(R.plurals.pdf_page_count, result.pageCount, result.pageCount)
    val details = if (result.sizeBytes < result.originalBytes) {
        val percent = ((1.0 - result.sizeBytes.toDouble() / result.originalBytes) * 100).roundToInt()
        stringResource(R.string.compress_result_details, formatSize(result.originalBytes), "$percent%", pages)
    } else {
        stringResource(R.string.compress_result_details_plain, formatSize(result.originalBytes), pages)
    }
    PdfResultLayout(
        resultKey = result.path,
        title = stringResource(R.string.compress_result_title),
        sizeHeadline = formatSize(result.sizeBytes),
        details = details,
        isSaved = result.saved != null,
        isSaving = isSaving,
        savedLabel = stringResource(R.string.export_saved_indicator_documents),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSave = onSave,
        onShare = onShare,
        onDoAnother = onDoAnother,
        badge = stringResource(R.string.resize_result_under_limit, formatSize(result.maxBytes)),
    ) {
        PdfPreviewCard(result.path)
    }
}
