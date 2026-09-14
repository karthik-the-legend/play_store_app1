package app.formkit.feature.passport

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.OptionsValidation
import app.formkit.core.imaging.TargetInput
import app.formkit.core.imaging.passport.BrushMode
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
fun PassportRoute(
    onBack: () -> Unit,
    viewModel: PassportViewModel = hiltViewModel(),
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
    val savePhoto = rememberStorageAwareSave(snackbarHostState, viewModel::savePhoto)
    val saveSheet = rememberStorageAwareSave(snackbarHostState, viewModel::saveSheet)
    val watermark = stringResource(R.string.passport_watermark)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PassportEvent.LaunchCamera -> try {
                    camera.launch(event.uri)
                } catch (_: ActivityNotFoundException) {
                    viewModel.onPhotoCaptured(false)
                    snackbarHostState.showSnackbar(context.getString(R.string.signature_no_camera))
                }
                is PassportEvent.Saved -> snackbarHostState.showSnackbar(
                    context.getString(if (event.inDocuments) R.string.passport_saved_documents else R.string.export_saved, event.displayName),
                )
                is PassportEvent.Share ->
                    if (!context.shareFile(event.uri, event.mimeType, context.getString(R.string.passport_share_chooser))) {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_no_share_app))
                    }
            }
        }
    }

    val session = state.session
    val photo = session.photo
    val sheet = session.sheet
    when {
        session.source == null -> PassportStartScreen(
            snackbarHostState = snackbarHostState,
            isLoading = state.isPreparing,
            onBack = onBack,
            onTakePhoto = viewModel::takePhoto,
            onChoosePhoto = pickPhoto,
        )
        session.step == PassportStep.SheetResult && sheet != null -> {
            BackHandler(onBack = viewModel::backToSheetOptions)
            SheetResultScreen(
                result = sheet,
                isSaving = state.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToSheetOptions,
                onSave = saveSheet,
                onShare = viewModel::shareSheet,
                onDoAnother = viewModel::startOver,
            )
        }
        session.step == PassportStep.Sheet && photo != null -> {
            BackHandler(onBack = viewModel::backToPhotoResult)
            SheetOptionsScreen(
                photo = photo,
                options = session.options,
                frame = state.frame ?: photo.size,
                snackbarHostState = snackbarHostState,
                isProcessing = state.isProcessing,
                onBack = viewModel::backToPhotoResult,
                onOptionsChange = viewModel::updateOptions,
                onCreate = { viewModel.createSheet(watermark) },
            )
        }
        session.step == PassportStep.Result && photo != null -> {
            BackHandler(onBack = viewModel::backToEditor)
            PassportResultScreen(
                result = photo,
                isSaving = state.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToEditor,
                onSave = savePhoto,
                onShare = viewModel::sharePhoto,
                onDoAnother = viewModel::startOver,
                onMakeSheet = viewModel::openSheet,
            )
        }
        else -> {
            BackHandler(onBack = viewModel::startOver)
            PassportEditorScreen(
                state = state,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onChangePhoto = pickPhoto,
                onModeChange = viewModel::setMode,
                onPlacementChange = viewModel::updatePlacement,
                onAutoFit = viewModel::autoFit,
                onBrushStart = viewModel::beginStroke,
                onBrushMove = viewModel::extendStroke,
                onBrushEnd = viewModel::endStroke,
                onUndo = viewModel::undoTouchUp,
                onClearTouchUps = viewModel::clearTouchUps,
                onOptionsChange = viewModel::updateOptions,
                onCreate = viewModel::createPhoto,
            )
        }
    }

    if (state.isProcessing) {
        val title = stringResource(if (session.step == PassportStep.Sheet) R.string.passport_sheet_processing else R.string.passport_processing)
        ProgressDialog(title, detail = null, onCancel = viewModel::cancelWork)
    }
    state.problem?.let { PassportProblemDialog(it, onDismiss = viewModel::dismissProblem) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportStartScreen(
    snackbarHostState: SnackbarHostState,
    isLoading: Boolean,
    onBack: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_passport_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
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
                                    painter = painterResource(R.drawable.ic_tool_passport),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Text(stringResource(R.string.passport_photo_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(
                        stringResource(R.string.passport_photo_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isLoading) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            OutlinedButton(
                                onClick = onTakePhoto,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.passport_take_photo), textAlign = TextAlign.Center) }
                            OutlinedButton(
                                onClick = onChoosePhoto,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.passport_choose_photo), textAlign = TextAlign.Center) }
                        }
                    }
                }
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(stringResource(R.string.passport_tips_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.passport_tips_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PassportEditorScreen(
    state: PassportUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onChangePhoto: () -> Unit,
    onModeChange: (EditorMode) -> Unit,
    onPlacementChange: (app.formkit.core.imaging.passport.Placement) -> Unit,
    onAutoFit: () -> Unit,
    onBrushStart: (Float, Float, Float) -> Unit,
    onBrushMove: (Float, Float) -> Unit,
    onBrushEnd: () -> Unit,
    onUndo: () -> Unit,
    onClearTouchUps: () -> Unit,
    onOptionsChange: ((PassportOptions) -> PassportOptions) -> Unit,
    onCreate: () -> Unit,
) {
    val session = state.session
    val options = session.options
    val frame = state.previewFrame
    val target = (state.validation as? OptionsValidation.Valid)?.target
    val image = remember(state.cutout) { state.cutout?.asImageBitmap() }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_passport_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.passport_create),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = target != null && image != null && !state.isPreparing && !state.isProcessing,
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
                PassportPreview(
                    cutout = image,
                    cutoutVersion = state.cutoutVersion,
                    frame = frame,
                    placement = session.placement,
                    background = Color(options.backgroundColor),
                    guide = options.guide(frame),
                    mode = session.mode,
                    brushRadius = brushRadiusFor(options.brushSize),
                    isBusy = state.isPreparing,
                    busyLabel = stringResource(R.string.passport_preparing),
                    contentDescription = stringResource(R.string.cd_passport_preview),
                    onPlacementChange = onPlacementChange,
                    onBrushStart = onBrushStart,
                    onBrushMove = onBrushMove,
                    onBrushEnd = onBrushEnd,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.personFound && !state.isPreparing) {
                    Text(
                        stringResource(R.string.passport_no_person),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EditorMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = session.mode == mode,
                            onClick = { onModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = EditorMode.entries.size),
                        ) {
                            Text(
                                stringResource(if (mode == EditorMode.Position) R.string.passport_mode_position else R.string.passport_mode_touch_up),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (session.mode == EditorMode.Position) {
                    Text(
                        stringResource(R.string.passport_position_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        TextButton(onClick = onAutoFit, enabled = image != null) { Text(stringResource(R.string.passport_auto_fit)) }
                        TextButton(onClick = onChangePhoto) { Text(stringResource(R.string.signature_change_photo)) }
                    }
                } else {
                    TouchUpControls(
                        options = options,
                        canUndo = state.canUndo,
                        onOptionsChange = onOptionsChange,
                        onUndo = onUndo,
                        onClearTouchUps = onClearTouchUps,
                    )
                }
            }
            PhotoSizeSection(options, state.validation, onOptionsChange)
            BackgroundSection(options, onOptionsChange)
            SizeLimitSection(options, state.validation, onOptionsChange)
        }
    }
}

@Composable
private fun TouchUpControls(
    options: PassportOptions,
    canUndo: Boolean,
    onOptionsChange: ((PassportOptions) -> PassportOptions) -> Unit,
    onUndo: () -> Unit,
    onClearTouchUps: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
        Text(
            stringResource(R.string.passport_touch_up_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            BrushMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = options.brushMode == mode,
                    onClick = { onOptionsChange { it.copy(brushMode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = BrushMode.entries.size),
                ) {
                    Text(stringResource(if (mode == BrushMode.Erase) R.string.passport_brush_erase else R.string.passport_brush_restore), maxLines = 1)
                }
            }
        }
        val brushLabel = stringResource(R.string.passport_brush_size)
        Text(brushLabel, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = options.brushSize,
            onValueChange = { size -> onOptionsChange { it.copy(brushSize = size) } },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = brushLabel },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(painterResource(R.drawable.ic_undo), stringResource(R.string.cd_undo_touch_up))
            }
            TextButton(onClick = onClearTouchUps, enabled = canUndo) { Text(stringResource(R.string.passport_clear_touch_ups)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoSizeSection(
    options: PassportOptions,
    validation: OptionsValidation,
    onOptionsChange: ((PassportOptions) -> PassportOptions) -> Unit,
) {
    val errors = (validation as? OptionsValidation.Invalid)?.errors.orEmpty()
    val frame = options.frameSize()
    Section(
        title = stringResource(R.string.passport_section_size),
        subtitle = frame?.let { stringResource(R.string.passport_size_details, it.toString(), options.dpi) },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            PhotoSizeChoice.entries.forEach { choice ->
                FilterChip(
                    selected = options.sizeChoice == choice,
                    onClick = { onOptionsChange { it.copy(sizeChoice = choice) } },
                    label = { Text(sizeChoiceLabel(choice)) },
                )
            }
        }
        if (options.sizeChoice == PhotoSizeChoice.Custom) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CustomUnit.entries.forEachIndexed { index, unit ->
                    SegmentedButton(
                        selected = options.customUnit == unit,
                        onClick = { onOptionsChange { it.copy(customUnit = unit) } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = CustomUnit.entries.size),
                    ) { Text(unitLabel(unit), maxLines = 1) }
                }
            }
            val suffix = unitLabel(options.customUnit)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                DecimalField(
                    value = options.customWidth,
                    onValueChange = { typed -> onOptionsChange { it.copy(customWidth = typed) } },
                    label = stringResource(R.string.resize_width),
                    suffix = suffix,
                    modifier = Modifier.weight(1f),
                )
                Text("×", style = MaterialTheme.typography.titleMedium)
                DecimalField(
                    value = options.customHeight,
                    onValueChange = { typed -> onOptionsChange { it.copy(customHeight = typed) } },
                    label = stringResource(R.string.resize_height),
                    suffix = suffix,
                    modifier = Modifier.weight(1f),
                )
            }
            if (options.customUnit != CustomUnit.Pixels) {
                NumberField(
                    value = options.customDpi,
                    onValueChange = { typed -> onOptionsChange { it.copy(customDpi = typed) } },
                    label = stringResource(R.string.passport_dpi),
                    suffix = stringResource(R.string.passport_dpi),
                    error = if (OptionsError.DpiInvalid in errors) {
                        stringResource(R.string.passport_error_dpi, PassportOptions.MIN_DPI, PassportOptions.MAX_DPI)
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val sizeError = when {
                OptionsError.DimensionsTooSmall in errors -> stringResource(R.string.resize_error_dimensions_too_small, TargetInput.MIN_EDGE)
                OptionsError.DimensionsTooLarge in errors -> stringResource(R.string.passport_error_too_large, PassportOptions.MAX_PHOTO_EDGE)
                else -> null
            }
            Text(
                text = sizeError ?: stringResource(R.string.passport_custom_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (sizeError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSection(
    options: PassportOptions,
    onOptionsChange: ((PassportOptions) -> PassportOptions) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.passport_section_background)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Backdrop.entries.forEach { backdrop ->
                ColorSwatch(
                    color = Color(backdrop.argb ?: options.customColor),
                    label = backdropLabel(backdrop),
                    selected = options.backdrop == backdrop,
                    onClick = {
                        if (backdrop == Backdrop.Custom) showPicker = true else onOptionsChange { it.copy(backdrop = backdrop) }
                    },
                )
            }
        }
    }
    if (showPicker) {
        ColorPickerDialog(
            initial = options.customColor,
            onDismiss = { showPicker = false },
            onPick = { picked ->
                onOptionsChange { it.copy(backdrop = Backdrop.Custom, customColor = picked) }
                showPicker = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeLimitSection(
    options: PassportOptions,
    validation: OptionsValidation,
    onOptionsChange: ((PassportOptions) -> PassportOptions) -> Unit,
) {
    val errors = (validation as? OptionsValidation.Invalid)?.errors.orEmpty()
    Section(stringResource(R.string.passport_section_limit), stringResource(R.string.passport_limit_hint)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
            PhotoKbPreset.entries.forEach { preset ->
                FilterChip(
                    selected = options.sizePreset == preset,
                    onClick = { onOptionsChange { it.copy(sizePreset = preset) } },
                    label = {
                        Text(
                            when (preset) {
                                PhotoKbPreset.NoLimit -> stringResource(R.string.signature_size_no_limit)
                                PhotoKbPreset.Custom -> stringResource(R.string.resize_custom)
                                else -> formatSize((preset.kilobytes ?: 0) * TargetInput.BYTES_PER_KB)
                            },
                        )
                    },
                )
            }
        }
        if (options.sizePreset == PhotoKbPreset.Custom) {
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
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed ->
            val cleaned = typed.filter { it.isDigit() || it == '.' }
            val firstDot = cleaned.indexOf('.')
            val singleDot = if (firstDot < 0) cleaned else cleaned.substring(0, firstDot + 1) + cleaned.substring(firstDot + 1).replace(".", "")
            onValueChange(singleDot.take(MAX_DECIMAL_LENGTH))
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private const val MAX_DECIMAL_LENGTH = 7

@Composable
private fun sizeChoiceLabel(choice: PhotoSizeChoice): String = stringResource(
    when (choice) {
        PhotoSizeChoice.India -> R.string.passport_size_india
        PhotoSizeChoice.UnitedStates -> R.string.passport_size_us
        PhotoSizeChoice.Square51 -> R.string.passport_size_51
        PhotoSizeChoice.Schengen -> R.string.passport_size_schengen
        PhotoSizeChoice.Custom -> R.string.resize_custom
    },
)

@Composable
private fun unitLabel(unit: CustomUnit): String = stringResource(
    when (unit) {
        CustomUnit.Millimetres -> R.string.passport_unit_mm
        CustomUnit.Inches -> R.string.passport_unit_in
        CustomUnit.Pixels -> R.string.unit_px_suffix
    },
)

@Composable
private fun backdropLabel(backdrop: Backdrop): String = stringResource(
    when (backdrop) {
        Backdrop.White -> R.string.passport_backdrop_white
        Backdrop.LightBlue -> R.string.passport_backdrop_light_blue
        Backdrop.LightGrey -> R.string.passport_backdrop_light_grey
        Backdrop.Red -> R.string.passport_backdrop_red
        Backdrop.Custom -> R.string.passport_backdrop_custom
    },
)

@Composable
private fun PassportProblemDialog(problem: PassportProblem, onDismiss: () -> Unit) {
    val (title, body) = when (problem) {
        is PassportProblem.TooLarge ->
            stringResource(R.string.resize_problem_too_large_title, formatSize(problem.maxBytes)) to
                stringResource(R.string.resize_problem_too_large_fixed, problem.size.toString(), formatSize(problem.smallestBytes))
        PassportProblem.SheetDoesNotFit ->
            stringResource(R.string.passport_problem_sheet_title) to stringResource(R.string.passport_problem_sheet_body)
        PassportProblem.UnreadableImage ->
            stringResource(R.string.resize_problem_unreadable_title) to stringResource(R.string.resize_problem_unreadable_body)
        PassportProblem.OutOfMemory ->
            stringResource(R.string.resize_problem_memory_title) to stringResource(R.string.resize_problem_memory_body)
        PassportProblem.SaveFailed ->
            stringResource(R.string.resize_problem_save_title) to stringResource(R.string.resize_problem_save_body)
        PassportProblem.Unexpected ->
            stringResource(R.string.resize_problem_unexpected_title) to stringResource(R.string.resize_problem_unexpected_body)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
