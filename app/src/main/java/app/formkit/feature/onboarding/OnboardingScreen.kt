package app.formkit.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.formkit.R
import app.formkit.core.ui.components.PhotoAndSignatureIllustration
import app.formkit.core.ui.components.PrimaryButton
import app.formkit.core.ui.components.PrivacyIllustration
import app.formkit.core.ui.components.ResizeIllustration
import app.formkit.core.ui.theme.FormKitTheme
import app.formkit.core.ui.theme.Spacing
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    OnboardingContent(
        onFinish = {
            if (!finishing) {
                finishing = true
                // Persist before leaving, so a quick kill can't show onboarding a second time.
                scope.launch {
                    viewModel.completeOnboarding()
                    onFinished()
                }
            }
        },
    )
}

private class OnboardingPage(
    val title: Int,
    val body: Int,
    val illustration: @Composable () -> Unit,
)

private val pages = listOf(
    OnboardingPage(R.string.onboarding_1_title, R.string.onboarding_1_body) { ResizeIllustration() },
    OnboardingPage(R.string.onboarding_2_title, R.string.onboarding_2_body) { PhotoAndSignatureIllustration() },
    OnboardingPage(R.string.onboarding_3_title, R.string.onboarding_3_body) { PrivacyIllustration() },
)

@Composable
fun OnboardingContent(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.small),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (!isLastPage) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { index ->
            val page = pages[index]
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.heightIn(min = 240.dp),
                    contentAlignment = Alignment.Center,
                ) { page.illustration() }
                Spacer(Modifier.height(Spacing.xLarge))
                Text(
                    text = stringResource(page.title),
                    style = MaterialTheme.typography.displaySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .semantics { heading() },
                )
                Spacer(Modifier.height(Spacing.medium))
                Text(
                    text = stringResource(page.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.large),
        ) {
            PageIndicator(current = pagerState.currentPage, count = pages.size)
            PrimaryButton(
                text = stringResource(if (isLastPage) R.string.onboarding_get_started else R.string.onboarding_next),
                onClick = {
                    if (isLastPage) onFinish()
                    else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
            )
        }
    }
}

@Composable
private fun PageIndicator(current: Int, count: Int) {
    val description = stringResource(R.string.onboarding_page_indicator, current + 1, count)
    Row(
        modifier = Modifier.semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "indicator")
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun OnboardingPreview() {
    FormKitTheme { OnboardingContent(onFinish = {}) }
}
