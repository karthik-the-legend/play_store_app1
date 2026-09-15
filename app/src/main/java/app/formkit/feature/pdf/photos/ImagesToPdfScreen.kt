package app.formkit.feature.pdf.photos

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.R
import app.formkit.core.pdf.PageMargin
import app.formkit.core.pdf.PageOrientation
import app.formkit.core.pdf.PaperSize
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.Section
import app.formkit.core.ui.components.rememberStorageAwareSave
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import app.formkit.feature.pdf.common.ChipChoice
import app.formkit.feature.pdf.common.PdfPreviewCard
import app.formkit.feature.pdf.common.PdfResultLayout
import app.formkit.feature.pdf.common.PdfToolDialogs
import app.formkit.feature.pdf.common.PdfToolEffects
import app.formkit.feature.pdf.common.PdfToolStartScreen
import app.formkit.feature.pdf.common.PickedImage
import app.formkit.feature.pdf.common.ReorderableColumn
import app.formkit.feature.pdf.common.SegmentedChoice
import app.formkit.feature.pdf.common.ToolActivity
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun ImagesToPdfRoute(
    onBack: () -> Unit,
    viewModel: ImagesToPdfViewModel = hiltViewModel(),
) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    val activity by viewModel.activityState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImagesToPdfSession.MAX_IMAGES)) { uris ->
        viewModel.addImages(uris)
    }
    val pickImages: () -> Unit = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val save = rememberStorageAwareSave(snackbarHostState, viewModel::save)
    PdfToolEffects(viewModel.events, snackbarHostState)

    val result = state.result
    when {
        state.images.isEmpty() -> PdfToolStartScreen(
            title = stringResource(R.string.tool_images_to_pdf_title),
            heading = stringResource(R.string.images_pdf_heading),
            body = stringResource(R.string.images_pdf_body),
            pickLabel = stringResource(R.string.images_pdf_pick),
            icon = R.drawable.ic_tool_images_to_pdf,
            isLoading = activity.isImporting,
            snackbarHostState = snackbarHostState,
            onBack = onBack,
            onPick = pickImages,
        )
        result != null -> {
            BackHandler(onBack = viewModel::backToList)
            ImagesPdfResultScreen(
                result = result,
                paper = state.paper,
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
            ImagesListScreen(
                state = state,
                activity = activity,
                snackbarHostState = snackbarHostState,
                onBack = viewModel::startOver,
                onAddMore = pickImages,
                onRemove = viewModel::remove,
                onMove = viewModel::move,
                onPaper = viewModel::choosePaper,
                onOrientation = viewModel::chooseOrientation,
                onMargin = viewModel::chooseMargin,
                onCreate = viewModel::create,
            )
        }
    }

    PdfToolDialogs(
        activity = activity,
        pdfs = emptyList(),
        processingTitle = stringResource(R.string.images_pdf_processing),
        onSubmitPassword = viewModel::submitPassword,
        onDismissPassword = viewModel::dismissPassword,
        onCancelWork = viewModel::cancelWork,
        onDismissProblem = viewModel::dismissProblem,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImagesListScreen(
    state: ImagesToPdfSession,
    activity: ToolActivity,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onAddMore: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPaper: (PaperSize) -> Unit,
    onOrientation: (PageOrientation) -> Unit,
    onMargin: (PageMargin) -> Unit,
    onCreate: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.tool_images_to_pdf_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.images_pdf_action),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.images.isNotEmpty() && !activity.isProcessing && !activity.isImporting,
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
                ReorderableColumn(items = state.images, key = { it.id }, onMove = onMove, spacing = Spacing.small) { image, index, handle, _ ->
                    val moveUp: (() -> Unit)? = if (index > 0) {
                        { onMove(index, index - 1) }
                    } else {
                        null
                    }
                    val moveDown: (() -> Unit)? = if (index < state.images.lastIndex) {
                        { onMove(index, index + 1) }
                    } else {
                        null
                    }
                    ImageCard(image, index, handle, moveUp, moveDown, onRemove = { onRemove(image.id) })
                }
                OutlinedButton(
                    onClick = onAddMore,
                    enabled = state.canAddMore && !activity.isImporting,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.small))
                    Text(stringResource(R.string.images_pdf_add_more))
                }
                if (!state.canAddMore) {
                    Text(
                        stringResource(R.string.images_pdf_limit, ImagesToPdfSession.MAX_IMAGES),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Section(stringResource(R.string.images_pdf_section_paper)) {
                ChipChoice(
                    options = PaperSize.entries,
                    selected = state.paper,
                    onSelect = onPaper,
                    label = { paper ->
                        stringResource(
                            when (paper) {
                                PaperSize.A4 -> R.string.images_pdf_paper_a4
                                PaperSize.Letter -> R.string.images_pdf_paper_letter
                                PaperSize.FitToImage -> R.string.images_pdf_paper_fit
                            },
                        )
                    },
                )
            }
            if (state.paper != PaperSize.FitToImage) {
                Section(stringResource(R.string.images_pdf_section_orientation), stringResource(R.string.images_pdf_orientation_hint)) {
                    SegmentedChoice(
                        options = PageOrientation.entries,
                        selected = state.orientation,
                        onSelect = onOrientation,
                        label = { orientation ->
                            stringResource(
                                when (orientation) {
                                    PageOrientation.Auto -> R.string.images_pdf_orientation_auto
                                    PageOrientation.Portrait -> R.string.images_pdf_orientation_portrait
                                    PageOrientation.Landscape -> R.string.images_pdf_orientation_landscape
                                },
                            )
                        },
                    )
                }
            }
            Section(stringResource(R.string.images_pdf_section_margin)) {
                SegmentedChoice(
                    options = PageMargin.entries,
                    selected = state.margin,
                    onSelect = onMargin,
                    label = { margin ->
                        stringResource(
                            when (margin) {
                                PageMargin.None -> R.string.images_pdf_margin_none
                                PageMargin.Small -> R.string.images_pdf_margin_small
                                PageMargin.Large -> R.string.images_pdf_margin_large
                            },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun ImageCard(
    image: PickedImage,
    index: Int,
    handle: Modifier,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    // The Photo Picker often hides real names behind numbers like "29.jpg", which mean nothing here.
    val label = if (image.displayName.substringBeforeLast('.').all { it.isDigit() }) {
        stringResource(R.string.images_pdf_photo_label, index + 1)
    } else {
        image.displayName
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
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
                model = File(image.path),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 56.dp, height = 72.dp)
                    .clip(MaterialTheme.shapes.small),
            )
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.images_pdf_item_details, index + 1, formatSize(image.sizeBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ImagesPdfResultScreen(
    result: ImagesPdfResult,
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
            PaperSize.FitToImage -> R.string.images_pdf_paper_fit
        },
    )
    PdfResultLayout(
        title = stringResource(R.string.images_pdf_result_title),
        sizeHeadline = formatSize(result.sizeBytes),
        details = stringResource(R.string.pdf_file_details, pluralStringResource(R.plurals.pdf_page_count, result.pageCount, result.pageCount), paperLabel),
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
