package app.formkit.feature.passport

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.imaging.PixelSize
import app.formkit.core.imaging.passport.PrintSheets
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.ExportActionsBar
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.ProOfferCard
import app.formkit.core.ui.components.ResultShownEffect
import app.formkit.core.ui.components.Section
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PassportResultScreen(
    result: PassportPhotoResult,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
    onMakeSheet: () -> Unit,
) {
    ResultShownEffect(resultKey = result.path)
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.passport_result_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ExportActionsBar(
                isSaved = result.savedUri != null,
                isSaving = isSaving,
                onSave = onSave,
                onShare = onShare,
                onDoAnother = onDoAnother,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            val physical = stringResource(
                R.string.passport_result_physical_mm,
                pixelsToMillimetres(result.width, result.dpi).toString(),
                pixelsToMillimetres(result.height, result.dpi).toString(),
            )
            Headline(
                limitBytes = result.maxBytes,
                sizeBytes = result.sizeBytes,
                details = stringResource(R.string.passport_result_details, result.size.toString(), physical, result.dpi),
            )
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().padding(Spacing.medium), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = File(result.path),
                        contentDescription = stringResource(R.string.cd_passport_result),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .aspectRatio(result.width.toFloat() / result.height)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Icon(painterResource(R.drawable.ic_print), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.passport_sheet_card_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.passport_sheet_card_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onMakeSheet,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.passport_sheet_card_action)) }
                }
            }
            ProOfferCard()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SheetOptionsScreen(
    photo: PassportPhotoResult,
    options: PassportOptions,
    frame: PixelSize,
    snackbarHostState: SnackbarHostState,
    isProcessing: Boolean,
    watermarkFree: Boolean,
    isLoadingAd: Boolean,
    onBack: () -> Unit,
    onOptionsChange: ((PassportOptions) -> PassportOptions) -> Unit,
    onCreate: () -> Unit,
    onWatchAd: () -> Unit,
    onGetPro: () -> Unit,
) {
    val dpi = options.dpi
    val counts = PrintSheets.countOptions(frame, dpi)
    val count = options.sheetCount?.takeIf { it in counts } ?: PrintSheets.defaultCount(frame, dpi)
    val layout = if (count > 0) PrintSheets.layout(frame, count, dpi) else null
    val photoImage by produceState<ImageBitmap?>(null, photo.path) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(photo.path)?.asImageBitmap() }
    }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.passport_sheet_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                PrimaryButton(
                    text = stringResource(R.string.passport_sheet_create),
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = layout != null && !isProcessing,
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
            if (layout != null) {
                val description = stringResource(R.string.cd_sheet_preview)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Canvas(
                        Modifier
                            .heightIn(max = 360.dp)
                            .aspectRatio(layout.sheet.width.toFloat() / layout.sheet.height)
                            .clip(MaterialTheme.shapes.small)
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                            .semantics { contentDescription = description },
                    ) {
                        val scale = size.width / layout.sheet.width
                        val image = photoImage
                        layout.slots.forEach { slot ->
                            val topLeft = Offset(slot.left * scale, slot.top * scale)
                            val slotSize = Size(slot.width * scale, slot.height * scale)
                            if (image != null) {
                                drawImage(
                                    image = image,
                                    dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                                    dstSize = IntSize(slotSize.width.toInt(), slotSize.height.toInt()),
                                    filterQuality = FilterQuality.Medium,
                                )
                            } else {
                                drawRect(Color(0xFFE8E8EC), topLeft, slotSize)
                            }
                            drawRect(Color(0xFFC8C8CC), topLeft, slotSize, style = Stroke(width = 1f))
                        }
                    }
                }
            }
            Section(stringResource(R.string.passport_sheet_count)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    counts.forEach { option ->
                        FilterChip(
                            selected = option == count,
                            onClick = { onOptionsChange { it.copy(sheetCount = option) } },
                            label = { Text(option.toString()) },
                        )
                    }
                }
                if (layout != null) {
                    val note = when {
                        layout.margin == 0 -> R.string.passport_sheet_edge_note
                        watermarkFree -> R.string.passport_sheet_no_watermark_note
                        else -> R.string.passport_sheet_watermark_note
                    }
                    Text(
                        stringResource(note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (layout != null && layout.margin > 0 && !watermarkFree) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                        Text(stringResource(R.string.passport_sheet_watermark_free_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.passport_sheet_watermark_free_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = onWatchAd,
                                enabled = !isLoadingAd,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) {
                                if (isLoadingAd) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(stringResource(R.string.passport_sheet_watch_ad))
                                }
                            }
                            TextButton(onClick = onGetPro) { Text(stringResource(R.string.pro_offer_action)) }
                        }
                    }
                }
            }
            Section(stringResource(R.string.passport_sheet_format)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SheetFormat.entries.forEachIndexed { index, format ->
                        SegmentedButton(
                            selected = options.sheetFormat == format,
                            onClick = { onOptionsChange { it.copy(sheetFormat = format) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = SheetFormat.entries.size),
                        ) { Text(stringResource(if (format == SheetFormat.Jpeg) R.string.format_jpeg else R.string.format_pdf)) }
                    }
                }
                Text(
                    stringResource(if (options.sheetFormat == SheetFormat.Jpeg) R.string.passport_sheet_jpeg_hint else R.string.passport_sheet_pdf_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetResultScreen(
    result: SheetResult,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    ResultShownEffect(resultKey = result.path)
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.passport_sheet_result_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ExportActionsBar(
                isSaved = result.savedUri != null,
                isSaving = isSaving,
                onSave = onSave,
                onShare = onShare,
                onDoAnother = onDoAnother,
                savedLabel = stringResource(
                    if (result.format == SheetFormat.Pdf) R.string.export_saved_indicator_documents else R.string.export_saved_indicator,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.medium, vertical = Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Headline(
                limitBytes = null,
                sizeBytes = result.sizeBytes,
                details = stringResource(
                    R.string.passport_sheet_result_details,
                    result.count,
                    stringResource(if (result.format == SheetFormat.Jpeg) R.string.format_jpeg else R.string.format_pdf),
                ),
            )
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().padding(Spacing.medium), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = File(result.previewPath),
                        contentDescription = stringResource(R.string.cd_sheet_preview),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .aspectRatio(result.width.toFloat() / result.height)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    )
                }
            }
            ProOfferCard()
        }
    }
}

@Composable
private fun Headline(limitBytes: Long?, sizeBytes: Long, details: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            if (limitBytes != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.resize_result_under_limit, formatSize(limitBytes)), style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(formatSize(sizeBytes), style = MaterialTheme.typography.displayMedium)
            Text(details, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
