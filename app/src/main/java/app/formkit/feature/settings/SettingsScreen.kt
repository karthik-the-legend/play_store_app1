package app.formkit.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.formkit.BuildConfig
import app.formkit.R
import app.formkit.core.settings.ThemeMode
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.TempFilesCleared -> snackbarHostState.showSnackbar(
                    if (event.freedBytes > 0) {
                        context.getString(R.string.settings_temp_files_cleared, context.formatSize(event.freedBytes))
                    } else {
                        context.getString(R.string.settings_temp_files_nothing)
                    },
                )
            }
        }
    }

    SettingsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onThemeModeChange = viewModel::setThemeMode,
        onClearTempFiles = viewModel::clearTempFiles,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onClearTempFiles: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    fun showError(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { BackTopBar(stringResource(R.string.settings_title), onBack, scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.large),
        ) {
            SectionHeader(stringResource(R.string.settings_section_appearance))
            Column(
                modifier = Modifier.padding(horizontal = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge)
                ThemeModeSelector(selected = uiState.themeMode, onSelect = onThemeModeChange)
            }

            SectionHeader(stringResource(R.string.settings_section_storage))
            SettingsItem(
                title = stringResource(R.string.settings_save_location),
                supporting = stringResource(R.string.settings_save_location_value),
            )
            SettingsItem(
                title = stringResource(R.string.settings_temp_files),
                supporting = uiState.tempFilesBytes
                    ?.let { stringResource(R.string.settings_temp_files_value, context.formatSize(it)) }
                    ?: stringResource(R.string.settings_temp_files_calculating),
                trailing = {
                    TextButton(onClick = onClearTempFiles, enabled = !uiState.isClearing) {
                        Text(stringResource(R.string.settings_temp_files_clear))
                    }
                },
            )

            SectionHeader(stringResource(R.string.settings_section_pro))
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.medium),
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    Text(stringResource(R.string.settings_pro_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.settings_pro_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            SectionHeader(stringResource(R.string.settings_section_about))
            SettingsItem(
                title = stringResource(R.string.settings_privacy),
                supporting = stringResource(R.string.settings_privacy_value),
                onClick = { showPrivacy = true },
            )
            val noStore = stringResource(R.string.error_no_store)
            SettingsItem(
                title = stringResource(R.string.settings_rate),
                onClick = { if (!context.openStoreListing()) showError(noStore) },
            )
            val noShareTarget = stringResource(R.string.error_no_share_target)
            SettingsItem(
                title = stringResource(R.string.settings_share),
                onClick = { if (!context.shareApp()) showError(noShareTarget) },
            )
            SettingsItem(
                title = stringResource(R.string.settings_version),
                supporting = BuildConfig.VERSION_NAME,
            )
        }
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text(stringResource(R.string.privacy_dialog_title)) },
            text = { Text(stringResource(R.string.privacy_dialog_body)) },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) { Text(stringResource(R.string.privacy_dialog_ok)) }
            },
        )
    }
}

@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.System to R.string.settings_theme_system,
        ThemeMode.Light to R.string.settings_theme_light,
        ThemeMode.Dark to R.string.settings_theme_dark,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = Spacing.medium, end = Spacing.medium, top = Spacing.large, bottom = Spacing.small)
            .semantics { heading() },
    )
}

@Composable
private fun SettingsItem(
    title: String,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val supportingContent: (@Composable () -> Unit)? = if (supporting == null) null else {
        { Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    val trailingContent: (@Composable () -> Unit)? = when {
        trailing != null -> trailing
        onClick != null -> {
            { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }
        }
        else -> null
    }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

private fun Context.openStoreListing(): Boolean {
    val market = Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri())
    val web = Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri())
    return tryStart(market) || tryStart(web)
}

private fun Context.shareApp(): Boolean {
    val link = "https://play.google.com/store/apps/details?id=$packageName"
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_text, link))
    return tryStart(Intent.createChooser(send, getString(R.string.share_app_chooser)))
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}
