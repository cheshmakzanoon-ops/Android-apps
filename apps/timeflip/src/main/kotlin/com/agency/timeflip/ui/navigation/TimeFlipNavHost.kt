package com.agency.timeflip.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.common.ROUTE_ARG_PROJECT_ID
import com.agency.timeflip.ui.ports.StartScreen
import com.agency.timeflip.ui.screens.alignreview.AlignReviewRoute
import com.agency.timeflip.ui.screens.editor.EditorRoute
import com.agency.timeflip.ui.screens.export.ExportRoute
import com.agency.timeflip.ui.screens.library.LibraryRoute
import com.agency.timeflip.ui.screens.library.OpenTarget
import com.agency.timeflip.ui.screens.manualalign.ManualAlignRoute
import com.agency.timeflip.ui.screens.newpair.NewPairRoute
import com.agency.timeflip.ui.screens.settings.SettingsRoute
import com.agency.timeflip.ui.screens.welcome.WelcomeRoute

/**
 * The app's navigation graph.
 *
 * Flow rules:
 * - The library is always the root after onboarding.
 * - The creation wizard (new pair, alignment review, manual alignment) ends in the editor with
 *   [navigateToStep], which removes the wizard screens from the back stack.
 * - Export finishes back at the library.
 */
@Composable
fun TimeFlipNavHost(deps: UiDependencies) {
    val startViewModel: AppStartViewModel = viewModel(factory = appStartFactory(deps.onboarding))
    val start by startViewModel.startScreen.collectAsStateWithLifecycle()
    val startRoute = when (start) {
        null -> return
        StartScreen.WELCOME -> Routes.WELCOME
        StartScreen.LIBRARY -> Routes.LIBRARY
    }

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.WELCOME) {
            WelcomeRoute(
                deps = deps,
                onStarted = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.LIBRARY) {
            LibraryRoute(
                deps = deps,
                onNewTransformation = { navController.navigate(Routes.newPair()) },
                onOpenProject = { id, target ->
                    val route = when (target) {
                        OpenTarget.ALIGN_REVIEW -> Routes.alignReview(id)
                        OpenTarget.EDITOR -> Routes.editor(id)
                    }
                    navController.navigate(route)
                },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsRoute(deps = deps, onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.NEW_PAIR,
            arguments = listOf(
                navArgument(ROUTE_ARG_PROJECT_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            NewPairRoute(
                deps = deps,
                onLeave = { navController.popBackStack() },
                onReady = { id -> navController.navigateToStep(Routes.alignReview(id)) },
            )
        }

        composable(Routes.ALIGN_REVIEW, arguments = projectArguments()) { entry ->
            val id = entry.projectId()
            AlignReviewRoute(
                deps = deps,
                onBack = { navController.popBackStack() },
                onOpenEditor = { navController.navigateToStep(Routes.editor(id)) },
                onAdjustManually = { navController.navigate(Routes.manualAlign(id, fromWizard = true)) },
                onChangePhotos = { navController.navigate(Routes.newPair(id)) },
            )
        }

        composable(Routes.MANUAL_ALIGN, arguments = projectArguments(withWizard = true)) { entry ->
            val id = entry.projectId()
            val fromWizard = entry.arguments?.getBoolean(Routes.ARG_WIZARD) ?: false
            ManualAlignRoute(
                deps = deps,
                onFinished = {
                    if (fromWizard) {
                        navController.navigateToStep(Routes.editor(id))
                    } else {
                        navController.popBackStack()
                    }
                },
            )
        }

        composable(Routes.EDITOR, arguments = projectArguments()) { entry ->
            val id = entry.projectId()
            EditorRoute(
                deps = deps,
                onBack = { navController.popBackStack() },
                onExport = { navController.navigate(Routes.export(id)) },
                onAdjustAlignment = { navController.navigate(Routes.manualAlign(id, fromWizard = false)) },
                onChangePhotos = { navController.navigate(Routes.newPair(id)) },
            )
        }

        composable(Routes.EXPORT, arguments = projectArguments()) {
            ExportRoute(
                deps = deps,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack(Routes.LIBRARY, inclusive = false) },
            )
        }
    }
}

/** Continues the creation flow: the wizard screens are removed, so Back returns to the library. */
private fun NavHostController.navigateToStep(route: String) {
    navigate(route) {
        popUpTo(Routes.LIBRARY) { inclusive = false }
    }
}

private fun projectArguments(withWizard: Boolean = false) = buildList {
    add(
        navArgument(ROUTE_ARG_PROJECT_ID) {
            type = NavType.StringType
        },
    )
    if (withWizard) {
        add(
            navArgument(Routes.ARG_WIZARD) {
                type = NavType.BoolType
                defaultValue = false
            },
        )
    }
}

private fun NavBackStackEntry.projectId(): ProjectId {
    val value = arguments?.getString(ROUTE_ARG_PROJECT_ID)
    return ProjectId(checkNotNull(value) { "route without a project id" })
}
