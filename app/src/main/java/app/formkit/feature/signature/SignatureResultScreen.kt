package app.formkit.feature.signature

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.imaging.OutputFormat
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.ExportActionsBar
import app.formkit.core.ui.components.ProOfferCard
import app.formkit.core.ui.components.ResultShownEffect
import app.formkit.core.ui.components.checkerboard
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import coil3.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignatureResultScreen(
    result: SignatureResult,
    isSaving: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
) {
    val transparent = result.format == OutputFormat.Png
    ResultShownEffect(resultKey = result.path)
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.signature_result_title), onBack) },
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
                    val limit = result.maxBytes
                    if (limit != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        ) {
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.resize_result_under_limit, formatSize(limit)),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    Text(formatSize(result.sizeBytes), style = MaterialTheme.typography.displayMedium)
                    Text(
                        text = stringResource(
                            R.string.signature_result_details,
                            result.size.toString(),
                            stringResource(if (transparent) R.string.signature_format_png_transparent else R.string.signature_format_jpeg_white),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    val backdrop = if (transparent) Modifier.checkerboard() else Modifier.background(Color.White)
                    AsyncImage(
                        model = File(result.path),
                        contentDescription = stringResource(R.string.cd_signature_result),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .aspectRatio(result.width.toFloat() / result.height)
                            .clip(MaterialTheme.shapes.small)
                            .then(backdrop),
                    )
                }
            }

            if (transparent) {
                Text(
                    text = stringResource(R.string.signature_transparent_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProOfferCard()
        }
    }
}
