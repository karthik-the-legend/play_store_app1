package app.formkit.feature.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.imaging.OptionsError
import app.formkit.core.imaging.TargetInput
import app.formkit.core.imaging.scan.PageCorners
import app.formkit.core.imaging.scan.PagePoint
import app.formkit.core.imaging.scan.ScanFilter
import app.formkit.core.pdf.PaperSize
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
import app.formkit.feature.pdf.common.PdfPreviewCard
import app.formkit.feature.pdf.common.PdfResultLayout
import app.formkit.feature.pdf.common.PdfToolDialogs
import app.formkit.feature.pdf.common.PdfToolEffects
import app.formkit.feature.pdf.common.PdfToolStartScreen
import app.formkit.feature.pdf.common.ReorderableColumn
import app.formkit.feature.pdf.common.ToolActivity
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ScanRoute(
    onBack: () -> Unit,
    viewModel: ScanViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val activity by viewModel.activityState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasCamera = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ScanSession.MAX_PAGES)) { uris ->
        viewModel.addPhotos(uris)
    }
    val pickPhotos: () -> Unit = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val deniedMessage = stringResource(R.string.scan_camera_denied)
    val settingsLabel = stringResource(R.string.scan_camera_settings)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.openCamera()
        } else {
            scope.launch {
                val result = snackbarHostState.showSnackbar(deniedMessage, actionLabel = settingsLabel, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) context.openAppSettings()
            }
        }
    }
    val openCamera: () -> Unit = {
        if (context.hasCameraPermission()) viewModel.openCamera() else permission.launch(Manifest.permission.CAMERA)
    }

    val save = rememberStorageAwareSave(snackbarHostState, viewModel::save)
    PdfToolEffects(viewModel.events, snackbarHostState)

    val result = state.result
    val editing = state.editing
    when {
        result != null -> {
            BackHandler(onBack = viewModel::backToPages)
            ScanResultScreen(
                result = result,
                paper = state.paper,
                isSaving = activity.isSaving,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::backToPages,
                onSave = save,
                onShare = viewModel::share,
                onDoAnother = viewModel::startOver,
            )
        }
        state.cameraOpen && context.hasCameraPermission() -> ScanCameraScreen(
            pageCount = state.pages.size,
            isBusy = activity.isImporting,
            newPhotoFile = viewModel::newPhotoFile,
            onCaptured = viewModel::addCapture,
            onProblem = viewModel::cameraFailed,
            onClose = viewModel::closeCamera,
        )
        // The permission can be taken away while FormKit is in the background.
        state.cameraOpen -> LaunchedEffect(Unit) { viewModel.closeCamera() }
        editing != null -> {
            BackHandler { viewModel.doneEditing() }
            AdjustPageScreen(
                page = editing,
                number = state.pages.indexOfFirst { it.id == editing.id } + 1,
                hasCamera = hasCamera,
                onCorners = viewModel::setCorners,
                onFilter = viewModel::chooseFilter,
                onRotate = viewModel::rotatePage,
                onReset = viewModel::resetCorners,
                onDelete = { viewModel.remove(editing.id) },
                onDone = { viewModel.doneEditing() },
                onScanNext = { viewModel.doneEditing(scanNext = true) },
            )
        }
        state.pages.isEmpty() -> ScanStartScreen(
            hasCamera = hasCamera,
            isLoading = activity.isImporting,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onScan = openCamera,
            onPickPhotos = pickPhotos,
        )
        else -> ScanPagesScreen(
            state = state,
            activity = activity,
            hasCamera = hasCamera,
            snackbarHostState = snackbarHostState,
            onDiscard = viewModel::startOver,
            onScan = openCamera,
            onAddPhotos = pickPhotos,
            onEdit = viewModel::edit,
            onRemove = viewModel::remove,
            onMove = viewModel::move,
            onPaper = viewModel::choosePaper,
            onLimit = viewModel::chooseLimit,
            onCustomKb = viewModel::typeCustomKb,
            onCreate = viewModel::create,
        )
    }

    PdfToolDialogs(
        activity = activity,
        pdfs = emptyList(),
        processingTitle = stringResource(R.string.scan_processing),
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        onCancelWork = viewModel::cancelWork,
        onDismissProblem = viewModel::dismissProblem,
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun ScanStartScreen(
    hasCamera: Boolean,
    isLoading: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onScan: () -> Unit,
    onPickPhotos: () -> Unit,
) {
    PdfToolStartScreen(
        title = stringResource(R.string.tool_scan_title),
        heading = stringResource(R.string.scan_heading),
        body = stringResource(R.string.scan_body),
        pickLabel = stringResource(if (hasCamera) R.string.scan_start_camera else R.string.scan_pick_photos),
        icon = R.drawable.ic_tool_scan,
        isLoading = isLoading,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onPick = if (hasCamera) onScan else onPickPhotos,
    ) {
        if (hasCamera) {
            TextButton(onClick = onPickPhotos) { Text(stringResource(R.string.scan_start_photos)) }
        }
        NoticeCard(stringResource(R.string.scan_tips_title), stringResource(R.string.scan_tips_body))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ScanPagesScreen(
    state: ScanSession,
    activity: ToolActivity,
    hasCamera: Boolean,
    snackbarHostState: SnackbarHostState,
    onDiscard: () -> Unit,
    onScan: () -> Unit,
    onAddPhotos: () -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPaper: (PaperSize) -> Unit,
    onLimit: (ScanSizeLimit) -> Unit,
    onCustomKb: (String) -> Unit,
    onCreate: () -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    BackHandler { confirmDiscard = true }
    val errors = mutableSetOf<OptionsError>()
    state.maxBytes(errors)

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_scan_title), onBack = { confirmDiscard = true }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.scan_action),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canCreate && !activity.isProcessing && !activity.isImporting,
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
                Text(
                    stringResource(R.string.merge_reorder_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReorderableColumn(items = state.pages, key = { it.id }, onMove = onMove, spacing = Spacing.small) { page, index, handle, _ ->
                    ScanPageCard(
                        page = page,
                        number = index + 1,
                        handle = handle,
                        onMoveUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
                        onMoveDown = if (index < state.pages.lastIndex) ({ onMove(index, index + 1) }) else null,
                        onEdit = { onEdit(page.id) },
                        onRemove = { onRemove(page.id) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    if (hasCamera) {
                        OutlinedButton(
                            onClick = onScan,
                            enabled = state.canAddMore && !activity.isImporting,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.scan_add_camera)) }
                    }
                    OutlinedButton(
                        onClick = onAddPhotos,
                        enabled = state.canAddMore && !activity.isImporting,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.scan_add_photos)) }
                }
                if (!state.canAddMore) {
                    Text(
                        stringResource(R.string.scan_limit_pages, ScanSession.MAX_PAGES),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section(stringResource(R.string.scan_section_paper)) {
                ChipChoice(
                    options = PaperSize.entries,
                    selected = state.paper,
                    onSelect = onPaper,
                    label = { paper ->
                        stringResource(
                            when (paper) {
                                PaperSize.A4 -> R.string.images_pdf_paper_a4
                                PaperSize.Letter -> R.string.images_pdf_paper_letter
                                PaperSize.FitToImage -> R.string.scan_paper_fit
                            },
                        )
                    },
                )
            }

            Section(stringResource(R.string.scan_section_limit), stringResource(R.string.scan_limit_hint)) {
                ChipChoice(
                    options = ScanSizeLimit.entries,
                    selected = state.limit,
                    onSelect = onLimit,
                    label = { limit ->
                        val kilobytes = limit.kilobytes
                        when {
                            kilobytes != null -> formatSize(kilobytes * TargetInput.BYTES_PER_KB)
                            limit == ScanSizeLimit.None -> stringResource(R.string.scan_limit_none)
                            else -> stringResource(R.string.resize_custom)
                        }
                    },
                )
                if (state.limit == ScanSizeLimit.Custom) {
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
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.scan_discard_title)) },
            text = { Text(stringResource(R.string.scan_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text(stringResource(R.string.scan_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.scan_discard_keep)) }
            },
        )
    }
}

@Composable
private fun ScanPageCard(
    page: ScanPage,
    number: Int,
    handle: Modifier,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val label = stringResource(R.string.scan_page_label, number)
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.cd_adjust_page, number), onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_drag_handle),
                contentDescription = stringResource(R.string.cd_drag_handle, label),
                modifier = handle.size(48.dp).padding(12.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AsyncImage(
                model = File(page.previewPath ?: page.photoPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 56.dp, height = 72.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = stringResource(scanFilterLabel(page.filter)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!page.edgesFound) {
                    Text(
                        stringResource(R.string.scan_page_check_corners),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column {
                IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                    Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.cd_move_up, label))
                }
                IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                    Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.cd_move_down, label))
                }
            }
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.cd_remove_file, label))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustPageScreen(
    page: ScanPage,
    number: Int,
    hasCamera: Boolean,
    onCorners: (PageCorners) -> Unit,
    onFilter: (ScanFilter) -> Unit,
    onRotate: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onScanNext: () -> Unit,
) {
    val usable = page.corners.isUsable(page.photoSize)
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.scan_adjust_title, number), onDone) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.scan_done),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = usable,
                )
                if (hasCamera) {
                    OutlinedButton(
                        onClick = onScanNext,
                        enabled = usable,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.scan_next_page)) }
                }
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
            CornerEditor(
                page = page,
                onCornersChange = onCorners,
                modifier = Modifier.fillMaxWidth().height(360.dp),
            )
            Text(
                text = stringResource(
                    when {
                        !usable -> R.string.scan_adjust_crossed
                        page.edgesFound -> R.string.scan_adjust_found
                        else -> R.string.scan_adjust_not_found
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (usable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                AsyncImage(
                    model = page.previewPath?.let { File(it) },
                    contentDescription = stringResource(R.string.cd_scan_result_preview),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(width = 78.dp, height = 104.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Text(stringResource(R.string.scan_section_look), style = MaterialTheme.typography.titleMedium)
                    ChipChoice(
                        options = ScanFilter.entries,
                        selected = page.filter,
                        onSelect = onFilter,
                        label = { filter -> stringResource(scanFilterLabel(filter)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                TextButton(onClick = onRotate) {
                    Icon(painterResource(R.drawable.ic_rotate_right), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.small))
                    Text(stringResource(R.string.scan_rotate))
                }
                TextButton(onClick = onReset) { Text(stringResource(R.string.scan_reset_corners)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.scan_delete_page), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/**
 * The photo with the page's outline over it. Each corner can be dragged; a magnifier follows the
 * finger, because the corner being placed is exactly what the finger covers. The outline turns red
 * if it folds over itself, and Done waits until it doesn't.
 */
@Composable
private fun CornerEditor(page: ScanPage, onCornersChange: (PageCorners) -> Unit, modifier: Modifier = Modifier) {
    val corners = page.corners
    var live by remember(page.id, corners) { mutableStateOf(corners) }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }

    val outlineColor = MaterialTheme.colorScheme.primary
    val crossedColor = MaterialTheme.colorScheme.error
    val handleColor = MaterialTheme.colorScheme.surface
    val cornerNames = listOf(
        stringResource(R.string.scan_corner_top_left),
        stringResource(R.string.scan_corner_top_right),
        stringResource(R.string.scan_corner_bottom_right),
        stringResource(R.string.scan_corner_bottom_left),
    )
    val nudgeNames = listOf(
        stringResource(R.string.scan_nudge_left),
        stringResource(R.string.scan_nudge_right),
        stringResource(R.string.scan_nudge_up),
        stringResource(R.string.scan_nudge_down),
    )

    BoxWithConstraints(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val density = LocalDensity.current
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()
        val scale = min(boxWidth / page.photoWidth, boxHeight / page.photoHeight)
        val imageWidth = page.photoWidth * scale
        val imageHeight = page.photoHeight * scale
        val originX = (boxWidth - imageWidth) / 2f
        val originY = (boxHeight - imageHeight) / 2f
        val grabRadius = with(density) { 44.dp.toPx() }
        val handleRadius = with(density) { 11.dp.toPx() }
        val strokeWidth = with(density) { 2.dp.toPx() }
        val magnifierLift = with(density) { 96.dp.toPx() }

        fun screenOf(point: PagePoint) = Offset(originX + point.x * imageWidth, originY + point.y * imageHeight)

        fun nudge(corner: Int, dx: Float, dy: Float) {
            val point = live.points[corner]
            val moved = live.moved(corner, PagePoint(point.x + dx, point.y + dy))
            live = moved
            onCornersChange(moved)
        }

        AsyncImage(
            model = File(page.photoPath),
            contentDescription = stringResource(R.string.cd_scan_photo),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(page.id, imageWidth, imageHeight, originX, originY) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val nearest = (0 until 4).minBy { (screenOf(live.points[it]) - start).getDistance() }
                            dragging = if ((screenOf(live.points[nearest]) - start).getDistance() <= grabRadius) nearest else -1
                            dragPoint = if (dragging >= 0) screenOf(live.points[dragging]) else Offset.Unspecified
                        },
                        onDrag = { change, amount ->
                            if (dragging >= 0) {
                                change.consume()
                                val point = live.points[dragging]
                                live = live.moved(dragging, PagePoint(point.x + amount.x / imageWidth, point.y + amount.y / imageHeight))
                                dragPoint = screenOf(live.points[dragging])
                            }
                        },
                        onDragEnd = {
                            if (dragging >= 0) onCornersChange(live)
                            dragging = -1
                            dragPoint = Offset.Unspecified
                        },
                        onDragCancel = {
                            live = corners
                            dragging = -1
                            dragPoint = Offset.Unspecified
                        },
                    )
                }
                .magnifier(
                    sourceCenter = { dragPoint },
                    magnifierCenter = {
                        if (dragPoint == Offset.Unspecified) Offset.Unspecified else Offset(dragPoint.x, dragPoint.y - magnifierLift)
                    },
                    zoom = 2f,
                    size = DpSize(112.dp, 112.dp),
                    cornerRadius = 56.dp,
                ),
        ) {
            val color = if (live.isUsable(page.photoSize)) outlineColor else crossedColor
            val outline = Path().apply {
                val start = screenOf(live.points[0])
                moveTo(start.x, start.y)
                for (i in 1 until 4) {
                    val point = screenOf(live.points[i])
                    lineTo(point.x, point.y)
                }
                close()
            }
            drawPath(outline, color.copy(alpha = 0.15f))
            drawPath(outline, color, style = Stroke(width = strokeWidth))
            for (i in 0 until 4) {
                val centre = screenOf(live.points[i])
                drawCircle(handleColor, radius = handleRadius, center = centre)
                drawCircle(color, radius = handleRadius, center = centre, style = Stroke(width = strokeWidth))
            }
        }

        // Targets for a screen reader: dragging isn't possible there, so each corner can be nudged.
        for (i in 0 until 4) {
            val centre = screenOf(live.points[i])
            Box(
                Modifier
                    .offset { IntOffset((centre.x - 24.dp.toPx()).roundToInt(), (centre.y - 24.dp.toPx()).roundToInt()) }
                    .size(48.dp)
                    .semantics {
                        contentDescription = cornerNames[i]
                        customActions = listOf(
                            CustomAccessibilityAction(nudgeNames[0]) { nudge(i, -NUDGE_STEP, 0f); true },
                            CustomAccessibilityAction(nudgeNames[1]) { nudge(i, NUDGE_STEP, 0f); true },
                            CustomAccessibilityAction(nudgeNames[2]) { nudge(i, 0f, -NUDGE_STEP); true },
                            CustomAccessibilityAction(nudgeNames[3]) { nudge(i, 0f, NUDGE_STEP); true },
                        )
                    },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ScanResultScreen(
    result: ScanResult,
    paper: PaperSize,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    val paperLabel = stringResource(
        when (paper) {
            PaperSize.A4 -> R.string.images_pdf_paper_a4
            PaperSize.Letter -> R.string.images_pdf_paper_letter
            PaperSize.FitToImage -> R.string.scan_paper_fit
        },
    )
    PdfResultLayout(
        resultKey = result.path,
        title = stringResource(R.string.scan_result_title),
        sizeHeadline = formatSize(result.sizeBytes),
        details = stringResource(
            R.string.pdf_file_details,
            pluralStringResource(R.plurals.pdf_page_count, result.pageCount, result.pageCount),
            paperLabel,
        ),
        isSaved = result.saved != null,
        isSaving = isSaving,
        savedLabel = stringResource(R.string.export_saved_indicator_documents),
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSave = onSave,
        onShare = onShare,
        onDoAnother = onDoAnother,
        badge = result.maxBytes?.let { stringResource(R.string.resize_result_under_limit, formatSize(it)) },
    ) {
        PdfPreviewCard(result.path)
    }
}

private fun scanFilterLabel(filter: ScanFilter): Int = when (filter) {
    ScanFilter.Original -> R.string.scan_filter_original
    ScanFilter.Greyscale -> R.string.scan_filter_greyscale
    ScanFilter.BlackWhite -> R.string.scan_filter_black_white
    ScanFilter.Enhanced -> R.string.scan_filter_enhanced
}

/** How far a nudge from a screen reader moves a corner: 1% of the photo. */
private const val NUDGE_STEP = 0.01f
