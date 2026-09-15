package app.formkit.feature.pdf.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.ExportActionsBar
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.theme.Spacing

/** The first screen of a PDF tool: what it does and the button that picks files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToolStartScreen(
    title: String,
    heading: String,
    body: String,
    pickLabel: String,
    @DrawableRes icon: Int,
    isLoading: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onPick: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Scaffold(
        topBar = { BackTopBar(title, onBack) },
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
                Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.medium)) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(heading, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isLoading) {
                        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        OutlinedButton(
                            onClick = onPick,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(pickLabel) }
                    }
                }
            }
            footer()
        }
    }
}

/** A notice in the secondary container colour, for things the user should know before starting. */
@Composable
fun NoticeCard(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.medium), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "3 pages · 1.2 MB" */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun pdfDetails(pageCount: Int, sizeBytes: Long): String =
    stringResource(R.string.pdf_file_details, pluralStringResource(R.plurals.pdf_page_count, pageCount, pageCount), formatSize(sizeBytes))

/**
 * One picked PDF: its first page, name, pages and size, and what's needed if it can't be used yet.
 * [handle] is the drag handle modifier when the list can be reordered.
 */
@Composable
fun PickedPdfCard(
    pdf: PickedPdf,
    onEnterPassword: () -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    handle: Modifier? = null,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            if (handle != null) {
                Icon(
                    painter = painterResource(R.drawable.ic_drag_handle),
                    contentDescription = stringResource(R.string.cd_drag_handle, pdf.displayName),
                    modifier = handle.size(48.dp).padding(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val readable = pdf.readablePath
            if (pdf.isReady && readable != null) {
                PdfPageThumbnail(readable, 0, contentDescription = null, modifier = Modifier.width(48.dp).heightIn(min = 64.dp, max = 64.dp), maxEdge = 160)
            } else {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Box(Modifier.size(width = 48.dp, height = 64.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(if (pdf.needsPassword) R.drawable.ic_lock else R.drawable.ic_tool_compress_pdf),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(pdf.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when (pdf.status) {
                    PdfStatus.Ready -> {
                        Text(pdfDetails(pdf.pageCount, pdf.sizeBytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (pdf.wasProtected) {
                            Text(stringResource(R.string.pdf_unlocked_note), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    PdfStatus.NeedsPassword, PdfStatus.WrongPassword -> {
                        Text(stringResource(R.string.pdf_status_locked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onEnterPassword, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Text(stringResource(R.string.pdf_enter_password))
                        }
                    }
                    PdfStatus.CertificateProtected ->
                        Text(stringResource(R.string.pdf_status_certificate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    PdfStatus.Unreadable ->
                        Text(stringResource(R.string.pdf_status_unreadable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
            if (onMoveUp != null || onMoveDown != null) {
                Column {
                    IconButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) {
                        Icon(painterResource(R.drawable.ic_arrow_up), stringResource(R.string.cd_move_up, pdf.displayName))
                    }
                    IconButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) {
                        Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.cd_move_down, pdf.displayName))
                    }
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.cd_remove_file, pdf.displayName))
                }
            }
        }
    }
}

/** Asks for a PDF's password. [wrongPassword] shows that the last attempt failed. */
@Composable
fun PdfPasswordDialog(
    fileName: String,
    wrongPassword: Boolean,
    isChecking: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val submit = { if (password.isNotEmpty() && !isChecking) onSubmit(password) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                Text(stringResource(R.string.pdf_password_body, fileName), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.pdf_password_label)) },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = {
                        Text(stringResource(if (wrongPassword) R.string.pdf_password_wrong else R.string.pdf_password_note))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty() && !isChecking) {
                if (isChecking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.pdf_password_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.resize_cancel)) } },
    )
}

/** A finished file: its size up top, a preview, and Save, Share and Do another at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfResultLayout(
    title: String,
    sizeHeadline: String,
    details: String,
    isSaved: Boolean,
    isSaving: Boolean,
    savedLabel: String,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDoAnother: () -> Unit,
    badge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(title, onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            ExportActionsBar(
                isSaved = isSaved,
                isSaving = isSaving,
                onSave = onSave,
                onShare = onShare,
                onDoAnother = onDoAnother,
                savedLabel = savedLabel,
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
                Column(Modifier.padding(Spacing.large), verticalArrangement = Arrangement.spacedBy(Spacing.small)) {
                    if (badge != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
                            Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, modifier = Modifier.size(20.dp))
                            Text(badge, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(sizeHeadline, style = MaterialTheme.typography.displayMedium)
                    Text(details, style = MaterialTheme.typography.bodyMedium)
                }
            }
            content()
        }
    }
}
