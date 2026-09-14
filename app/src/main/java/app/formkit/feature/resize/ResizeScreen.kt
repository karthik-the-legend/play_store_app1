package app.formkit.feature.resize

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.imaging.TargetProgress
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.NumberField
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.ProgressDialog
import app.formkit.core.ui.components.Section
import app.formkit.core.ui.components.rememberStorageAwareSave
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.shareFile
import app.formkit.core.ui.theme.Spacing
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun ResizeRoute(
    onBack: () -> Unit,
    viewModel: ResizeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onImagePicked(uri)
    }
    val pickImage: () -> Unit = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val save = rememberStorageAwareSave(snackbarHostState, viewModel::save)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ResizeEvent.Saved ->
                    snackbarHostState.showSnackbar(context.getString(R.string.export_saved, event.displayName))
                is ResizeEvent.Share ->
                    if (!context.shareFile(event.uri, event.mimeType, context.getString(R.string.resize_share_chooser))) {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_no_share_app))
                    }
            }
        }
    }

    val source = state.session.source
    val result = state.session.result
    if (source != null && result != null) {
        BackHandler(onBack = viewModel::backToEditing)
        ResizeResultScreen(
            source = source,
            result = result,
            isSaving = state.isSaving,
            snackbarHostState = snackbarHostState,
            onBack = viewModel::backToEditing,
            onSave = save,
            onShare = viewModel::share,
            onDoAnother = {
                viewModel.doAnother()
                pickImage()
            },
        )
    } else {
        ResizeEditorScreen(
            state = state,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onPickImage = pickImage,
            onOptionsChange = viewModel::updateOptions,
            onResize = viewModel::resize,
        )
    }

    if (state.isProcessing) ProcessingDialog(state.progress, onCancel = viewModel::cancelResize)
    state.problem?.let { problem ->
        ProblemDialog(problem, onDismiss = viewModel::dismissProblem, onAllowDownscale = viewModel::allowDownscaleAndRetry)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResizeEditorScreen(
    state: ResizeUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onPickImage: () -> Unit,
    onOptionsChange: ((ResizeOptions) -> ResizeOptions) -> Unit,
    onResize: () -> Unit,
) {
    val source = state.session.source
    val options = state.session.options
    val errors = (state.validation as? OptionsValidation.Invalid)?.errors.orEmpty()
    val target = (state.validation as? OptionsValidation.Valid)?.target

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_resize_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                if (source == null) {
                    PrimaryButton(
                        text = stringResource(R.string.resize_choose_photo),
                        onClick = onPickImage,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isImporting,
                    )
                } else {
                    PrimaryButton(
                        text = target?.let { stringResource(R.string.resize_action, formatSize(it.maxBytes)) }
                            ?: stringResource(R.string.resize_action_generic),
                        onClick = onResize,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = target != null && !state.isProcessing && !state.isImporting,
                    )
                }
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
            SourceCard(source, state.isImporting, onPickImage)
            TargetSizeSection(options, errors, onOptionsChange)
            DimensionsSection(options, errors, onOptionsChange)
            FormatSection(options, onOptionsChange)
            DownscaleSetting(options, onOptionsChange)
        }
    }
}

@Composable
private fun SourceCard(source: SourceImage?, isImporting: Boolean, onPickImage: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        when {
            isImporting -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.medium, Alignment.CenterVertically),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.resize_source_opening), style = MaterialTheme.typography.bodyMedium)
            }

            source == null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onPickImage)
                    .padding(Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        painter = painterResource(R.drawable.ic_tool_resize),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(Spacing.medium)
                            .size(32.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.resize_source_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Spacing.small),
                )
                Text(
                    text = stringResource(R.string.resize_source_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            else -> Column {
                AsyncImage(
                    model = File(source.path),
                    contentDescription = stringResource(R.string.cd_original_photo),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                )
                Row(
                    modifier = Modifier.padding(start = Spacing.medium, end = Spacing.small, top = Spacing.small, bottom = Spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = source.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.resize_source_details, formatSize(source.sizeBytes), source.size.toString()),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onPickImage) { Text(stringResource(R.string.resize_change_photo)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetSizeSection(
    options: ResizeOptions,
    errors: Set<OptionsError>,
    onOptionsChange: ((ResizeOptions) -> ResizeOptions) -> Unit,
) {
    Section(stringResource(R.string.resize_section_size), stringResource(R.string.resize_section_size_hint)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            SizePreset.entries.forEach { preset ->
                FilterChip(
                    selected = options.sizePreset == preset,
                    onClick = { onOptionsChange { it.copy(sizePreset = preset) } },
                    label = {
                        Text(preset.kilobytes?.let { formatSize(it * ResizeOptions.BYTES_PER_KB) } ?: stringResource(R.string.resize_custom))
                    },
                )
            }
        }
        if (options.sizePreset == SizePreset.Custom) {
            NumberField(
                value = options.customKb,
                onValueChange = { typed -> onOptionsChange { it.copy(customKb = typed) } },
                label = stringResource(R.string.resize_custom_size_label),
                suffix = stringResource(R.string.unit_kb_suffix),
                error = when {
                    OptionsError.CustomSizeTooSmall in errors -> stringResource(R.string.resize_error_size_too_small, ResizeOptions.MIN_KB)
                    OptionsError.CustomSizeTooLarge in errors -> stringResource(R.string.resize_error_size_too_large, ResizeOptions.MAX_KB)
                    else -> null
                },
                hint = stringResource(R.string.resize_custom_size_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (options.showMinimum) {
            NumberField(
                value = options.minKb,
                onValueChange = { typed -> onOptionsChange { it.copy(minKb = typed) } },
                label = stringResource(R.string.resize_minimum_label),
                suffix = stringResource(R.string.unit_kb_suffix),
                error = when {
                    OptionsError.MinimumTooSmall in errors -> stringResource(R.string.resize_error_minimum_too_small)
                    OptionsError.MinimumAboveMaximum in errors -> stringResource(R.string.resize_error_minimum_above_max)
                    else -> null
                },
                hint = stringResource(R.string.resize_minimum_hint),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onOptionsChange { it.copy(showMinimum = false, minKb = "") } }) {
                Text(stringResource(R.string.resize_minimum_remove))
            }
        } else {
            TextButton(onClick = { onOptionsChange { it.copy(showMinimum = true) } }) {
                Text(stringResource(R.string.resize_minimum_add))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DimensionsSection(
    options: ResizeOptions,
    errors: Set<OptionsError>,
    onOptionsChange: ((ResizeOptions) -> ResizeOptions) -> Unit,
) {
    Section(stringResource(R.string.resize_section_dimensions), stringResource(R.string.resize_section_dimensions_hint)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            DimensionPreset.entries.forEach { preset ->
                FilterChip(
                    selected = options.dimensionPreset == preset,
                    onClick = { onOptionsChange { it.copy(dimensionPreset = preset) } },
                    label = { Text(dimensionLabel(preset)) },
                )
            }
        }
        if (options.dimensionPreset == DimensionPreset.Custom) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumberField(
                    value = options.customWidth,
                    onValueChange = { typed -> onOptionsChange { it.copy(customWidth = typed) } },
                    label = stringResource(R.string.resize_width),
                    suffix = stringResource(R.string.unit_px_suffix),
                    modifier = Modifier.weight(1f),
                )
                Text("×", style = MaterialTheme.typography.titleMedium)
                NumberField(
                    value = options.customHeight,
                    onValueChange = { typed -> onOptionsChange { it.copy(customHeight = typed) } },
                    label = stringResource(R.string.resize_height),
                    suffix = stringResource(R.string.unit_px_suffix),
                    modifier = Modifier.weight(1f),
                )
            }
            val dimensionsError = when {
                OptionsError.DimensionsTooSmall in errors -> stringResource(R.string.resize_error_dimensions_too_small, ResizeOptions.MIN_EDGE)
                OptionsError.DimensionsTooLarge in errors -> stringResource(R.string.resize_error_dimensions_too_large, ResizeOptions.MAX_EDGE)
                else -> null
            }
            Text(
                text = dimensionsError ?: stringResource(R.string.resize_dimensions_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimensionsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (options.hasExactSize) {
            Text(
                text = stringResource(R.string.resize_crop_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun dimensionLabel(preset: DimensionPreset): String = when (preset) {
    DimensionPreset.Original -> stringResource(R.string.resize_dimension_original)
    DimensionPreset.Custom -> stringResource(R.string.resize_custom)
    DimensionPreset.P413x531 -> stringResource(R.string.resize_dimension_passport, preset.size.toString())
    else -> preset.size.toString()
}

@Composable
private fun FormatSection(
    options: ResizeOptions,
    onOptionsChange: ((ResizeOptions) -> ResizeOptions) -> Unit,
) {
    Section(stringResource(R.string.resize_section_format)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            OutputFormat.entries.forEachIndexed { index, format ->
                SegmentedButton(
                    selected = options.format == format,
                    onClick = { onOptionsChange { it.copy(format = format) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = OutputFormat.entries.size),
                ) {
                    Text(
                        stringResource(
                            when (format) {
                                OutputFormat.Jpeg -> R.string.format_jpeg
                                OutputFormat.Png -> R.string.format_png
                            },
                        ),
                    )
                }
            }
        }
        Text(
            text = stringResource(
                when (options.format) {
                    OutputFormat.Jpeg -> R.string.resize_format_jpeg_hint
                    OutputFormat.Png -> R.string.resize_format_png_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownscaleSetting(
    options: ResizeOptions,
    onOptionsChange: ((ResizeOptions) -> ResizeOptions) -> Unit,
) {
    val lockedByExactSize = options.hasExactSize
    val checked = options.allowDownscale && !lockedByExactSize
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = !lockedByExactSize,
                role = Role.Switch,
                onValueChange = { allow -> onOptionsChange { it.copy(allowDownscale = allow) } },
            )
            .padding(vertical = Spacing.small),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.resize_downscale_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(if (lockedByExactSize) R.string.resize_downscale_off_exact else R.string.resize_downscale_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = !lockedByExactSize)
    }
}

@Composable
private fun ProcessingDialog(progress: TargetProgress?, onCancel: () -> Unit) {
    val detail = when {
        progress == null -> stringResource(R.string.resize_processing_reading)
        progress.quality != null ->
            stringResource(R.string.resize_processing_trying, progress.size.toString(), progress.quality)
        else -> stringResource(R.string.resize_processing_trying_png, progress.size.toString())
    }
    ProgressDialog(stringResource(R.string.resize_processing_title), detail, onCancel)
}

private class ProblemText(
    val title: String,
    val body: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    val dismissLabel: String? = null,
)

@Composable
private fun ProblemDialog(problem: ResizeProblem, onDismiss: () -> Unit, onAllowDownscale: () -> Unit) {
    val ok = stringResource(R.string.ok)
    val text = when (problem) {
        is ResizeProblem.TooLarge -> if (problem.canDownscale) {
            ProblemText(
                title = stringResource(R.string.resize_problem_too_large_title, formatSize(problem.maxBytes)),
                body = stringResource(R.string.resize_problem_too_large_downscale, problem.size.toString(), formatSize(problem.smallestBytes)),
                confirmLabel = stringResource(R.string.resize_problem_allow_downscale),
                onConfirm = onAllowDownscale,
                dismissLabel = stringResource(R.string.resize_problem_not_now),
            )
        } else {
            ProblemText(
                title = stringResource(R.string.resize_problem_too_large_title, formatSize(problem.maxBytes)),
                body = stringResource(R.string.resize_problem_too_large_fixed, problem.size.toString(), formatSize(problem.smallestBytes)),
                confirmLabel = ok,
                onConfirm = onDismiss,
            )
        }
        is ResizeProblem.TooSmall -> ProblemText(
            title = stringResource(R.string.resize_problem_too_small_title, formatSize(problem.minBytes)),
            body = stringResource(R.string.resize_problem_too_small_body, problem.size.toString(), formatSize(problem.largestBytes)),
            confirmLabel = ok,
            onConfirm = onDismiss,
        )
        ResizeProblem.UnreadableImage -> ProblemText(
            stringResource(R.string.resize_problem_unreadable_title),
            stringResource(R.string.resize_problem_unreadable_body),
            ok,
            onDismiss,
        )
        ResizeProblem.OutOfMemory -> ProblemText(
            stringResource(R.string.resize_problem_memory_title),
            stringResource(R.string.resize_problem_memory_body),
            ok,
            onDismiss,
        )
        ResizeProblem.SaveFailed -> ProblemText(
            stringResource(R.string.resize_problem_save_title),
            stringResource(R.string.resize_problem_save_body),
            ok,
            onDismiss,
        )
        ResizeProblem.Unexpected -> ProblemText(
            stringResource(R.string.resize_problem_unexpected_title),
            stringResource(R.string.resize_problem_unexpected_body),
            ok,
            onDismiss,
        )
    }

    val dismissLabel = text.dismissLabel
    val dismissButton: (@Composable () -> Unit)? = if (dismissLabel == null) null else {
        { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text.title) },
        text = { Text(text.body) },
        confirmButton = { TextButton(onClick = text.onConfirm) { Text(text.confirmLabel) } },
        dismissButton = dismissButton,
    )
}
