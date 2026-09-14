package app.formkit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.formkit.core.ui.theme.FormKitTheme
import app.formkit.feature.home.HomeScreen
import app.formkit.feature.home.Tool
import app.formkit.feature.onboarding.OnboardingContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationFlowsTest {

    @get:Rule val compose = createComposeRule()

    private fun string(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun skippingOnboardingFinishesIt() {
        var finished = 0
        compose.setContent { FormKitTheme { OnboardingContent(onFinish = { finished++ }) } }

        compose.onNodeWithText(string(R.string.onboarding_skip)).performClick()

        assertEquals(1, finished)
    }

    @Test
    fun walkingThroughOnboardingEndsOnGetStarted() {
        var finished = 0
        compose.setContent { FormKitTheme { OnboardingContent(onFinish = { finished++ }) } }

        compose.onNodeWithText(string(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(string(R.string.onboarding_next)).performClick()
        compose.onNodeWithText(string(R.string.onboarding_get_started)).assertIsDisplayed().performClick()

        assertEquals(1, finished)
    }

    @Test
    fun tappingAToolCardOpensThatTool() {
        var opened: Tool? = null
        compose.setContent {
            FormKitTheme { HomeScreen(onToolClick = { opened = it }, onRecentFilesClick = {}, onSettingsClick = {}) }
        }

        compose.onNodeWithText(string(R.string.tool_passport_title)).performClick()

        assertEquals(Tool.PassportPhoto, opened)
    }
}
