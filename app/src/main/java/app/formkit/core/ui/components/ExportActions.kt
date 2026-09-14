package app.formkit.core.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.formkit.R
import app.formkit.core.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Save, Share and Do another, shared by every tool's result screen. */
@Composable
fun ExportActionsBar(
    isSaved: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
    savedLabel: String = stringResource(R.string.export_saved_indicator),
) {
    BottomActionBar {
        if (isSaved) {
            SavedIndicator(savedLabel)
        } else {
            PrimaryButton(
                text = stringResource(R.string.export_save),
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            )
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
                Text(stringResource(R.string.export_share))
            }
            OutlinedButton(
                onClick = onDoAnother,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.export_do_another), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SavedIndicator(label: String) {
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
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * A save action that first asks for storage access on Android 9 and older, the only versions
 * that need it to write into Pictures. If the user says no, a snackbar points them to Share.
 */
@Composable
fun rememberStorageAwareSave(snackbarHostState: SnackbarHostState, onSave: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnSave by rememberUpdatedState(onSave)
    val denied = stringResource(R.string.export_storage_permission_denied)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) latestOnSave() else scope.launch { snackbarHostState.showSnackbar(denied) }
    }
    return remember(launcher) {
        {
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else latestOnSave()
        }
    }
}
