package com.agency.stickerjet.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agency.stickerjet.app.AppContainer
import com.agency.stickerjet.app.uiPorts
import com.agency.stickerjet.ui.editor.EditorScreen
import com.agency.stickerjet.ui.editor.EditorViewModel
import com.agency.stickerjet.ui.importing.ImportRoute
import com.agency.stickerjet.ui.importing.ImportViewModel
import com.agency.stickerjet.ui.library.LibraryScreen
import com.agency.stickerjet.ui.library.LibraryViewModel
import com.agency.stickerjet.ui.ports.UiPorts
import com.agency.stickerjet.ui.settings.SettingsScreen
import com.agency.stickerjet.ui.settings.SettingsViewModel
import com.agency.stickerjet.ui.welcome.WelcomeScreen
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * App entry point from MainActivity. Ports are built once from the composition root. [StickerJetHost]
 * does the rest, and it is also what instrumented tests render with fake ports.
 */
@Composable
fun StickerJetRoot(container: AppContainer) {
    val ports = remember(container) { container.uiPorts() }
    StickerJetHost(ports = ports)
}

/**
 * Navigation graph. The start destination is Welcome until onboarding is complete; the screen stays
 * blank until the first-run flag has been read, so Welcome never flashes for returning users.
 */
@Composable
fun StickerJetHost(ports: UiPorts) {
    val completed by ports.onboarding.completed.collectAsStateWithLifecycle(initialValue = null)
    val first = completed
    if (first == null) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    NavHost(
        navController = navController,
        startDestination = if (first) Routes.LIBRARY else Routes.WELCOME,
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onContinue = {
                    scope.launch { ports.onboarding.markCompleted() }
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { LibraryViewModel(ports.projects, ports.files) }
                },
            )
            LibraryScreen(
                viewModel = viewModel,
                onOpenProject = { id -> navController.navigate(Routes.editor(id)) },
                onImport = { navController.navigate(Routes.IMPORT) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.IMPORT) {
            val viewModel: ImportViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        ImportViewModel(ports.importer, newProjectId = { UUID.randomUUID().toString() })
                    }
                },
            )
            ImportRoute(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onImported = { id ->
                    navController.navigate(Routes.editor(id)) {
                        popUpTo(Routes.LIBRARY)
                    }
                },
            )
        }
        composable(
            route = Routes.EDITOR,
            arguments = listOf(navArgument(Routes.PROJECT_ID_ARG) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.PROJECT_ID_ARG).orEmpty()
            val viewModel: EditorViewModel = viewModel(
                key = id,
                factory = viewModelFactory {
                    initializer {
                        EditorViewModel(
                            savedState = createSavedStateHandle(),
                            projects = ports.projects,
                            files = ports.files,
                            frames = ports.frames,
                            segmenter = ports.segmenter,
                            masks = ports.masks,
                            exporter = ports.exporter,
                            preview = ports.preview,
                            storage = ports.storage,
                        )
                    }
                },
            )
            EditorScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { SettingsViewModel(ports.maintenance) }
                },
            )
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onDataDeleted = { navController.popBackStack(Routes.LIBRARY, inclusive = false) },
            )
        }
    }
}
