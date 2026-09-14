package app.formkit.feature.signature

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.TargetInput
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

@Composable
fun SignatureRoute(
    onBack: () -> Unit,
    viewModel: SignatureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoPicked(uri)
    }
    val pickPhoto: () -> Unit = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        viewModel.onPhotoCaptured(saved)
    }
    val save = rememberStorageAwareSave(snackbarHostState, viewModel::save)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SignatureEvent.LaunchCamera -> try {
                    camera.launch(event.uri)
                } catch (_: ActivityNotFoundException) {
                    viewModel.onPhotoCaptured(false)
                    snackbarHostState.showSnackbar(context.getString(R.string.signature_no_camera))
                }
                is SignatureEvent.Saved ->
                    snackbarHostState.showSnackbar(context.getString(R.string.export_saved, event.displayName))
                is SignatureEvent.Share ->
                    if (!context.shareFile(event.uri, event.mimeType, context.getString(R.string.signature_share_chooser))) {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_no_share_app))
                    }
            }
        }
    }

    val session = state.session
    val result = session.result
    when {
        result != null -> {
            BackHandler(onBack = viewModel::backToEditing)
            SignatureResultScreen(
                result = result,
                isSaving = state.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToEditing,
                onSave = save,
                onShare = viewModel::share,
                onDoAnother = viewModel::startOver,
            )
        }
        session.mode == SignatureMode.Photo -> {
            BackHandler(onBack = viewModel::startOver)
            SignaturePhotoScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onChangePhoto = pickPhoto,
                onCropChange = viewModel::updateCrop,
                onAutoCrop = viewModel::autoCrop,
                onWholePhoto = viewModel::useWholePhoto,
                onInkStrengthChange = viewModel::setInkStrength,
                onOptionsChange = viewModel::updateOptions,
                onCreate = viewModel::createFile,
            )
        }
        session.mode == SignatureMode.Draw -> {
            BackHandler(onBack = viewModel::startOver)
            SignatureDrawScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onStroke = viewModel::addStroke,
                onUndo = viewModel::undoStroke,
                onClear = viewModel::clearDrawing,
                onStrokeWidthChange = viewModel::setStrokeWidth,
                onOptionsChange = viewModel::updateOptions,
                onCreate = viewModel::createFile,
            )
        }
        else -> SignatureStartScreen(
            snackbarHostState = snackbarHostState,
            isLoadingPhoto = state.isLoadingPhoto,
            onBack = onBack,
            onTakePhoto = viewModel::takePhoto,
            onChoosePhoto = pickPhoto,
            onDraw = viewModel::startDrawing,
        )
    }

    if (state.isProcessing) {
        ProgressDialog(stringResource(R.string.signature_processing), detail = null, onCancel = viewModel::cancelCreate)
    }
    state.problem?.let { SignatureProblemDialog(it, onDismiss = viewModel::dismissProblem) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureStartScreen(
    snackbarHostState: SnackbarHostState,
    isLoadingPhoto: Boolean,
    onBack: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    onDraw: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_signature_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            ChoiceCard(
                icon = R.drawable.ic_camera,
                title = stringResource(R.string.signature_photo_title),
                body = stringResource(R.string.signature_photo_body),
            ) {
                if (isLoadingPhoto) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        OutlinedButton(
                            onClick = onTakePhoto,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.signature_take_photo), textAlign = TextAlign.Center)
                        }
                        OutlinedButton(
                            onClick = onChoosePhoto,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.signature_choose_photo), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            ChoiceCard(
                icon = R.drawable.ic_draw,
                title = stringResource(R.string.signature_draw_title),
                body = stringResource(R.string.signature_draw_body),
            ) {
                PrimaryButton(stringResource(R.string.signature_draw_action), onDraw, Modifier.fillMaxWidth())
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(stringResource(R.string.signature_tips_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.signature_tips_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    actions: @Composable () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            ) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SignaturePhotoScreen(
    state: SignatureUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onChangePhoto: () -> Unit,
    onCropChange: (CropRect) -> Unit,
    onAutoCrop: () -> Unit,
    onWholePhoto: () -> Unit,
    onInkStrengthChange: (Float) -> Unit,
    onOptionsChange: ((SignatureOptions) -> SignatureOptions) -> Unit,
    onCreate: () -> Unit,
) {
    val options = state.session.options
    val target = (state.validation as? OptionsValidation.Valid)?.target
    val preview = state.preview

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.signature_clean_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.signature_create),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = target != null && preview != null && !state.isLoadingPhoto && !state.isProcessing,
                )
            }
        },
    ) { innerPadding ->
        EditorColumn(innerPadding) {
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    if (preview == null || state.isLoadingPhoto) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Spacing.medium, Alignment.CenterVertically),
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.signature_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        val image = remember(preview) { preview.asImageBitmap() }
                        CropOverlayImage(
                            bitmap = image,
                            crop = state.session.crop ?: CropRect.Full,
                            onCropChange = onCropChange,
                            contentDescription = stringResource(R.string.cd_signature_preview),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(if (state.inkFound) R.string.signature_crop_hint else R.string.signature_no_ink_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.inkFound) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        TextButton(onClick = onAutoCrop, enabled = preview != null) {
                            Text(stringResource(R.string.signature_auto_crop))
                        }
                        TextButton(onClick = onWholePhoto, enabled = preview != null) {
                            Text(stringResource(R.string.signature_whole_photo))
                        }
                        TextButton(onClick = onChangePhoto) {
                            Text(stringResource(R.string.signature_change_photo))
                        }
                    }
                }
            }

            Section(stringResource(R.string.signature_ink_title), stringResource(R.string.signature_ink_hint)) {
                val sliderDescription = stringResource(R.string.cd_ink_strength)
                Slider(
                    value = options.inkStrength,
                    onValueChange = onInkStrengthChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = sliderDescription },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.signature_ink_lighter), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.signature_ink_bolder), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SignatureOutputOptions(options, state.validation, onOptionsChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureDrawScreen(
    state: SignatureUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onStroke: (DrawnStroke) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onStrokeWidthChange: (StrokeWidth) -> Unit,
    onOptionsChange: ((SignatureOptions) -> SignatureOptions) -> Unit,
    onCreate: () -> Unit,
) {
    val options = state.session.options
    val drawing = state.session.drawing
    val target = (state.validation as? OptionsValidation.Valid)?.target

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.signature_draw_screen_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.signature_create),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = target != null && !drawing.isEmpty && !state.isProcessing,
                )
            }
        },
    ) { innerPadding ->
        EditorColumn(innerPadding) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SignaturePad(
                    drawing = drawing,
                    strokeWidth = options.strokeWidth,
                    onStrokeFinished = onStroke,
                    contentDescription = stringResource(R.string.cd_signature_pad),
                    hint = stringResource(R.string.signature_pad_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StrokeWidth.entries.forEachIndexed { index, width ->
                        SegmentedButton(
                            selected = options.strokeWidth == width,
                            onClick = { onStrokeWidthChange(width) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = StrokeWidth.entries.size),
                        ) {
                            Text(
                                stringResource(
                                    when (width) {
                                        StrokeWidth.Thin -> R.string.signature_stroke_thin
                                        StrokeWidth.Medium -> R.string.signature_stroke_medium
                                        StrokeWidth.Bold -> R.string.signature_stroke_bold
                                    },
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onUndo, enabled = !drawing.isEmpty) {
                        Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.cd_undo_stroke))
                    }
                    TextButton(onClick = onClear, enabled = !drawing.isEmpty) {
                        Text(stringResource(R.string.signature_clear))
                    }
                }
            }
            SignatureOutputOptions(options, state.validation, onOptionsChange)
        }
    }
}

@Composable
private fun EditorColumn(innerPadding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.medium, vertical = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.large),
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignatureOutputOptions(
    options: SignatureOptions,
    validation: OptionsValidation,
    onOptionsChange: ((SignatureOptions) -> SignatureOptions) -> Unit,
) {
    val errors = (validation as? OptionsValidation.Invalid)?.errors.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.large)) {
        Section(stringResource(R.string.signature_background_title)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SignatureBackground.entries.forEachIndexed { index, background ->
                    SegmentedButton(
                        selected = options.background == background,
                        onClick = { onOptionsChange { it.copy(background = background) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = SignatureBackground.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (background) {
                                    SignatureBackground.White -> R.string.signature_background_white
                                    SignatureBackground.Transparent -> R.string.signature_background_transparent
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    when (options.background) {
                        SignatureBackground.White -> R.string.signature_background_white_hint
                        SignatureBackground.Transparent -> R.string.signature_background_transparent_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.resize_section_size), stringResource(R.string.resize_section_size_hint)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SignatureSizePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = options.sizePreset == preset,
                        onClick = { onOptionsChange { it.copy(sizePreset = preset) } },
                        label = { Text(sizeLabel(preset)) },
                    )
                }
            }
            if (options.sizePreset == SignatureSizePreset.Custom) {
                NumberField(
                    value = options.customKb,
                    onValueChange = { typed -> onOptionsChange { it.copy(customKb = typed) } },
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

        Section(stringResource(R.string.resize_section_dimensions), stringResource(R.string.signature_dimensions_hint)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                SignatureDimensionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = options.dimensionPreset == preset,
                        onClick = { onOptionsChange { it.copy(dimensionPreset = preset) } },
                        label = { Text(dimensionLabel(preset)) },
                    )
                }
            }
            if (options.dimensionPreset == SignatureDimensionPreset.Custom) {
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
                    OptionsError.DimensionsTooSmall in errors -> stringResource(R.string.resize_error_dimensions_too_small, TargetInput.MIN_EDGE)
                    OptionsError.DimensionsTooLarge in errors -> stringResource(R.string.resize_error_dimensions_too_large, TargetInput.MAX_EDGE)
                    else -> null
                }
                Text(
                    text = dimensionsError ?: stringResource(R.string.resize_dimensions_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dimensionsError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (options.dimensionPreset != SignatureDimensionPreset.Fit) {
                Text(
                    text = stringResource(R.string.signature_fit_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun sizeLabel(preset: SignatureSizePreset): String = when (preset) {
    SignatureSizePreset.NoLimit -> stringResource(R.string.signature_size_no_limit)
    SignatureSizePreset.Custom -> stringResource(R.string.resize_custom)
    else -> formatSize((preset.kilobytes ?: 0) * TargetInput.BYTES_PER_KB)
}

@Composable
private fun dimensionLabel(preset: SignatureDimensionPreset): String = when (preset) {
    SignatureDimensionPreset.Fit -> stringResource(R.string.signature_dimension_fit)
    SignatureDimensionPreset.Custom -> stringResource(R.string.resize_custom)
    else -> preset.size.toString()
}

@Composable
private fun SignatureProblemDialog(problem: SignatureProblem, onDismiss: () -> Unit) {
    val (title, body) = when (problem) {
        is SignatureProblem.TooLarge ->
            stringResource(R.string.resize_problem_too_large_title, formatSize(problem.maxBytes)) to
                stringResource(R.string.resize_problem_too_large_fixed, problem.size.toString(), formatSize(problem.smallestBytes))
        SignatureProblem.NoInk ->
            stringResource(R.string.signature_problem_no_ink_title) to stringResource(R.string.signature_problem_no_ink_body)
        SignatureProblem.UnreadableImage ->
            stringResource(R.string.resize_problem_unreadable_title) to stringResource(R.string.resize_problem_unreadable_body)
        SignatureProblem.OutOfMemory ->
            stringResource(R.string.resize_problem_memory_title) to stringResource(R.string.resize_problem_memory_body)
        SignatureProblem.SaveFailed ->
            stringResource(R.string.resize_problem_save_title) to stringResource(R.string.resize_problem_save_body)
        SignatureProblem.Unexpected ->
            stringResource(R.string.resize_problem_unexpected_title) to stringResource(R.string.signature_problem_unexpected_body)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
