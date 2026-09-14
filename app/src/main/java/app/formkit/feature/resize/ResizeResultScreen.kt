package app.formkit.feature.resize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.BottomActionBar
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResizeResultScreen(
    source: SourceImage,
    result: ResizeResult,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.resize_result_title), onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            BottomActionBar {
                if (result.savedUri == null) {
                    PrimaryButton(
                        text = stringResource(R.string.resize_save),
                        onClick = onSave,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                    )
                } else {
                    SavedIndicator()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    OutlinedButton(
                        onClick = onShare,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.small))
                        Text(stringResource(R.string.resize_share))
                    }
                    OutlinedButton(
                        onClick = onDoAnother,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp),
                    ) {
                        Text(stringResource(R.string.resize_do_another), textAlign = TextAlign.Center)
                    }
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
            ResultHeadline(result)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                PreviewTile(
                    label = stringResource(R.string.resize_result_before),
                    image = File(source.path),
                    contentDescription = stringResource(R.string.cd_original_photo),
                    size = formatSize(source.sizeBytes),
                    dimensions = source.size.toString(),
                    modifier = Modifier.weight(1f),
                )
                PreviewTile(
                    label = stringResource(R.string.resize_result_after),
                    image = File(result.path),
                    contentDescription = stringResource(R.string.cd_resized_photo),
                    size = formatSize(result.sizeBytes),
                    dimensions = result.size.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            ResultStats(source, result)
            if (result.cropped) {
                Text(
                    text = stringResource(R.string.resize_result_cropped, result.size.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultHeadline(result: ResizeResult) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(R.string.resize_result_under_limit, formatSize(result.maxBytes)),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(formatSize(result.sizeBytes), style = MaterialTheme.typography.displayMedium)
            Text(
                text = result.quality?.let { stringResource(R.string.resize_result_jpeg_quality, it) }
                    ?: stringResource(R.string.format_png),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PreviewTile(
    label: String,
    image: File,
    contentDescription: String,
    size: String,
    dimensions: String,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.small),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(
                model = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
            Column {
                Text(size, style = MaterialTheme.typography.titleMedium)
                Text(dimensions, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ResultStats(source: SourceImage, result: ResizeResult) {
    val reduction = if (result.sizeBytes < source.sizeBytes) {
        stringResource(R.string.resize_stat_smaller, ((source.sizeBytes - result.sizeBytes) * 100 / source.sizeBytes).toInt())
    } else {
        stringResource(R.string.resize_stat_not_smaller)
    }
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            StatRow(
                stringResource(R.string.resize_stat_size),
                stringResource(R.string.resize_stat_change, formatSize(source.sizeBytes), formatSize(result.sizeBytes)),
            )
            StatRow(
                stringResource(R.string.resize_stat_dimensions),
                stringResource(R.string.resize_stat_change, source.size.toString(), result.size.toString()),
            )
            StatRow(stringResource(R.string.resize_stat_reduction), reduction)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f),
        )
    }
}

@Composable
private fun SavedIndicator() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.medium),
            horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.resize_saved_indicator), style = MaterialTheme.typography.labelLarge)
        }
    }
}
