package app.formkit.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.formkit.feature.home.HomeScreen
import app.formkit.feature.home.Tool
import app.formkit.feature.home.ToolPlaceholderScreen
import app.formkit.feature.onboarding.OnboardingScreen
import app.formkit.feature.recent.RecentFilesScreen
import app.formkit.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable data object OnboardingRoute
@Serializable data object HomeRoute
@Serializable data object RecentFilesRoute
@Serializable data object SettingsRoute
@Serializable data class ToolRoute(val tool: Tool)

private const val TRANSITION_MILLIS = 300

@Composable
fun FormKitNavHost(
    startWithOnboarding: Boolean,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = if (startWithOnboarding) OnboardingRoute else HomeRoute,
        enterTransition = {
            slideIntoContainer(SlideDirection.Start, tween(TRANSITION_MILLIS)) { it / 8 } + fadeIn(tween(TRANSITION_MILLIS))
        },
        exitTransition = { fadeOut(tween(TRANSITION_MILLIS)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MILLIS)) },
        popExitTransition = {
            slideOutOfContainer(SlideDirection.End, tween(TRANSITION_MILLIS)) { it / 8 } + fadeOut(tween(TRANSITION_MILLIS))
        },
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(HomeRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }

        composable<HomeRoute> {
            HomeScreen(
                onToolClick = { tool -> navController.navigate(ToolRoute(tool)) { launchSingleTop = true } },
                onRecentFilesClick = dropUnlessResumed { navController.navigate(RecentFilesRoute) },
                onSettingsClick = dropUnlessResumed { navController.navigate(SettingsRoute) },
            )
        }

        composable<ToolRoute> { entry ->
            ToolPlaceholderScreen(
                tool = entry.toRoute<ToolRoute>().tool,
                onBack = dropUnlessResumed { navController.popBackStack() },
            )
        }

        composable<RecentFilesRoute> {
            RecentFilesScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onStartResize = dropUnlessResumed {
                    navController.navigate(ToolRoute(Tool.ResizeKb)) {
                        popUpTo(HomeRoute)
                    }
                },
            )
        }

        composable<SettingsRoute> {
            SettingsScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}
