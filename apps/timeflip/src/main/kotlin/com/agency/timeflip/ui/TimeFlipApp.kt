package com.agency.timeflip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.agency.timeflip.app.AppContainer
import com.agency.timeflip.ui.navigation.TimeFlipNavHost

/**
 * Entry point of the UI. MainActivity passes the process container; every screen receives its
 * implementations through [UiDependencies] built here, and nothing else.
 */
@Composable
fun TimeFlipApp(container: AppContainer) {
    val dependencies = remember(container) { container.toUiDependencies() }
    TimeFlipTheme {
        TimeFlipNavHost(dependencies)
    }
}

private fun AppContainer.toUiDependencies(): UiDependencies = UiDependencies(
    projects = projects,
    photos = photos,
    bitmaps = BitmapLoader { id, maxEdge -> bitmaps.decode(id, maxEdge) },
    aligner = alignmentRunner,
    exporter = exporter,
    exportsDir = exportsDir,
    onboarding = onboarding,
    awaitStartupRecovery = { startupRecovery.join() },
)
