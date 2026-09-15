package app.formkit.feature.pdf.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.ui.components.ProgressDialog
import app.formkit.core.ui.formatSize
import app.formkit.core.ui.shareFiles
import app.formkit.core.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

/** Snackbars for saves and the share sheet for shares, for every PDF tool. */
@Composable
fun PdfToolEffects(events: Flow<PdfEvent>, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is PdfEvent.Saved -> snackbarHostState.showSnackbar(
                    when {
                        event.count > 1 && event.inDocuments ->
                            context.resources.getQuantityString(R.plurals.pdf_saved_pdfs, event.count, event.count)
                        event.count > 1 ->
                            context.resources.getQuantityString(R.plurals.pdf_saved_images, event.count, event.count, event.folder.orEmpty())
                        event.inDocuments -> context.getString(R.string.passport_saved_documents, event.displayName)
                        else -> context.getString(R.string.export_saved, event.displayName)
                    },
                )
                is PdfEvent.Share ->
                    if (!context.shareFiles(event.uris, event.mimeType, context.getString(R.string.pdf_share_chooser))) {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_no_share_app))
                    }
            }
        }
    }
}

/** Progress, problems and password prompts: the dialogs every PDF tool can show. */
@Composable
fun PdfToolDialogs(
    activity: ToolActivity,
    pdfs: List<PickedPdf>,
    processingTitle: String,
    onSubmitPassword: (String) -> Unit,
    onDismissPassword: () -> Unit,
    onCancelWork: () -> Unit,
    onDismissProblem: () -> Unit,
) {
    if (activity.isProcessing) {
        ProgressDialog(processingTitle, detail = progressDetail(activity.progress), onCancel = onCancelWork)
    }
    activity.problem?.let { PdfProblemDialog(it, onDismissProblem) }
    val pdf = activity.passwordFor?.let { id -> pdfs.firstOrNull { it.id == id } }
    if (pdf != null && !activity.isProcessing) {
        key(pdf.id) {
            PdfPasswordDialog(
                fileName = pdf.displayName,
                wrongPassword = pdf.status == PdfStatus.WrongPassword,
                isChecking = activity.checkingPassword,
                onSubmit = onSubmitPassword,
                onDismiss = onDismissPassword,
            )
        }
    }
}

@Composable
private fun progressDetail(progress: WorkProgress?): String? = progress?.let {
    when (it.stage) {
        ProgressStage.Analysing -> stringResource(R.string.pdf_progress_checking, it.done, it.total)
        ProgressStage.Pages -> stringResource(R.string.pdf_progress_page, it.done, it.total)
        ProgressStage.Photos -> stringResource(R.string.pdf_progress_photo, it.done, it.total)
        ProgressStage.Writing -> stringResource(R.string.pdf_progress_writing)
    }
}

@Composable
fun PdfProblemDialog(problem: PdfProblem, onDismiss: () -> Unit) {
    val (title, body) = when (problem) {
        PdfProblem.ImportFailed ->
            stringResource(R.string.pdf_problem_import_title) to stringResource(R.string.pdf_problem_import_body)
        PdfProblem.Failed ->
            stringResource(R.string.pdf_problem_failed_title) to stringResource(R.string.pdf_problem_failed_body)
        PdfProblem.OutOfMemory ->
            stringResource(R.string.resize_problem_memory_title) to stringResource(R.string.resize_problem_memory_body)
        PdfProblem.SaveFailed ->
            stringResource(R.string.resize_problem_save_title) to stringResource(R.string.resize_problem_save_body)
        is PdfProblem.TooLarge ->
            stringResource(R.string.pdf_problem_too_large_title, formatSize(problem.maxBytes)) to
                stringResource(R.string.pdf_problem_too_large_body, formatSize(problem.smallestBytes))
        is PdfProblem.AlreadySmall ->
            stringResource(R.string.pdf_problem_already_small_title, formatSize(problem.maxBytes)) to
                stringResource(R.string.pdf_problem_already_small_body, formatSize(problem.sizeBytes))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                enabled = enabled,
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipChoice(options: List<T>, selected: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.small)) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) })
        }
    }
}

/** The first page of a finished PDF, large enough to check it looks right. */
@Composable
fun PdfPreviewCard(path: String, modifier: Modifier = Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        PdfPageThumbnail(
            path = path,
            pageIndex = 0,
            contentDescription = stringResource(R.string.cd_pdf_first_page),
            modifier = Modifier
                .padding(Spacing.medium)
                .fillMaxWidth()
                .height(360.dp),
            maxEdge = 900,
        )
    }
}
