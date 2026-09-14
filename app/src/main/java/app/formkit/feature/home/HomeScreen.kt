package app.formkit.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.formkit.R
import app.formkit.core.ui.theme.FormKitTheme
import app.formkit.core.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onToolClick: (Tool) -> Unit,
    onRecentFilesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onRecentFilesClick) {
                        Icon(painterResource(R.drawable.ic_history), stringResource(R.string.cd_recent_files))
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.cd_settings))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        // Two columns normally; one when the user has large text so nothing gets squeezed.
        val largeText = LocalDensity.current.fontScale > 1.3f
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (largeText) 280.dp else 156.dp),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + Spacing.medium,
                end = innerPadding.calculateEndPadding(layoutDirection) + Spacing.medium,
                top = innerPadding.calculateTopPadding() + Spacing.small,
                bottom = innerPadding.calculateBottomPadding() + Spacing.large,
            ),
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            fullWidth { HomeHeader() }
            fullWidth { FeaturedToolCard(tool = Tool.featured, onClick = { onToolClick(Tool.featured) }) }

            fullWidth { SectionLabel(stringResource(R.string.home_section_photos)) }
            items(Tool.entries.filter { it.category == Tool.Category.Photo && it != Tool.featured }) { tool ->
                ToolCard(tool = tool, onClick = { onToolClick(tool) })
            }

            fullWidth { SectionLabel(stringResource(R.string.home_section_pdf)) }
            items(Tool.entries.filter { it.category == Tool.Category.Pdf }) { tool ->
                ToolCard(tool = tool, onClick = { onToolClick(tool) })
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun HomeHeader() {
    Column(
        modifier = Modifier.padding(top = Spacing.small, bottom = Spacing.small),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        Text(
            text = stringResource(R.string.home_headline),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(top = Spacing.small),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.home_privacy_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = Spacing.small)
            .semantics { heading() },
    )
}

@Composable
private fun FeaturedToolCard(tool: Tool, onClick: () -> Unit) {
    // Solid indigo in light mode. In dark mode the "primary" colour is a pale lavender, which
    // makes a glaring slab at night, so the card uses the deep indigo container colour instead.
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val container = if (dark) colors.primaryContainer else colors.primary
    val content = if (dark) colors.onPrimaryContainer else colors.onPrimary
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.large),
            verticalArrangement = Arrangement.spacedBy(Spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                IconTile(
                    icon = tool.icon,
                    background = content.copy(alpha = 0.16f),
                    tint = content,
                )
                Surface(shape = CircleShape, color = content.copy(alpha = 0.16f), contentColor = content) {
                    Text(
                        text = stringResource(R.string.home_featured_label),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = Spacing.small + 4.dp, vertical = 4.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(tool.title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(tool.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 152.dp),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            IconTile(
                icon = tool.icon,
                background = MaterialTheme.colorScheme.primaryContainer,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(tool.title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = Spacing.small),
            )
            Text(
                text = stringResource(tool.description),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IconTile(icon: Int, background: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color) {
    Surface(shape = MaterialTheme.shapes.small, color = background) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    FormKitTheme { HomeScreen(onToolClick = {}, onRecentFilesClick = {}, onSettingsClick = {}) }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenDarkPreview() {
    FormKitTheme(darkTheme = true) { HomeScreen(onToolClick = {}, onRecentFilesClick = {}, onSettingsClick = {}) }
}
