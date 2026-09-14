package app.formkit.feature.home

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.formkit.R
import app.formkit.core.ui.components.BackTopBar
import app.formkit.core.ui.components.EmptyState
import app.formkit.core.ui.components.ToolIllustration

/** Stands in for each tool until its milestone replaces it with the real screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPlaceholderScreen(
    tool: Tool,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(tool.title)
    Scaffold(
        modifier = modifier,
        topBar = { BackTopBar(title = title, onBack = onBack) },
    ) { innerPadding ->
        EmptyState(
            illustration = { ToolIllustration(tool.icon) },
            title = stringResource(R.string.coming_soon_title),
            body = stringResource(R.string.coming_soon_body, title),
            actionLabel = stringResource(R.string.coming_soon_action),
            onAction = onBack,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
