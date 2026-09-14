package app.formkit.feature.recent

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.formkit.R
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.EmptyState
import app.formkit.core.ui.components.RecentFilesIllustration

/** Empty state only for now; the export history arrives with the first tool in Milestone 2. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentFilesScreen(
    onBack: () -> Unit,
    onStartResize: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(title = stringResource(R.string.recent_title), onBack = onBack) },
    ) { innerPadding ->
        EmptyState(
            illustration = { RecentFilesIllustration() },
            title = stringResource(R.string.recent_empty_title),
            body = stringResource(R.string.recent_empty_body),
            actionLabel = stringResource(R.string.recent_empty_action),
            onAction = onStartResize,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
