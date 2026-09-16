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
import app.formkit.feature.onboarding.OnboardingScreen
import app.formkit.feature.passport.PassportRoute
import app.formkit.feature.pdf.compress.CompressPdfRoute
import app.formkit.feature.pdf.images.PdfToImagesRoute
import app.formkit.feature.pdf.merge.MergePdfRoute
import app.formkit.feature.pdf.photos.ImagesToPdfRoute
import app.formkit.feature.pdf.split.SplitPdfRoute
import app.formkit.feature.recent.RecentFilesScreen
import app.formkit.feature.resize.ResizeRoute
import app.formkit.feature.scan.ScanRoute
import app.formkit.feature.settings.SettingsScreen
import app.formkit.feature.signature.SignatureRoute
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
            val onBack = dropUnlessResumed { navController.popBackStack() }
            when (entry.toRoute<ToolRoute>().tool) {
                Tool.ResizeKb -> ResizeRoute(onBack = onBack)
                Tool.Signature -> SignatureRoute(onBack = onBack)
                Tool.PassportPhoto -> PassportRoute(onBack = onBack)
                Tool.ImagesToPdf -> ImagesToPdfRoute(onBack = onBack)
                Tool.ScanToPdf -> ScanRoute(onBack = onBack)
                Tool.CompressPdf -> CompressPdfRoute(onBack = onBack)
                Tool.MergePdf -> MergePdfRoute(onBack = onBack)
                Tool.SplitPdf -> SplitPdfRoute(onBack = onBack)
                Tool.PdfToImages -> PdfToImagesRoute(onBack = onBack)
            }
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
