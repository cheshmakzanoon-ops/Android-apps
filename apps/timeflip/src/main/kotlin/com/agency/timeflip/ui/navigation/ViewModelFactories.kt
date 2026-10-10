package com.agency.timeflip.ui.navigation

import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.agency.timeflip.ui.UiDependencies
import com.agency.timeflip.ui.ports.OnboardingStore
import com.agency.timeflip.ui.screens.alignreview.AlignReviewViewModel
import com.agency.timeflip.ui.screens.editor.EditorViewModel
import com.agency.timeflip.ui.screens.export.ExportViewModel
import com.agency.timeflip.ui.screens.library.LibraryViewModel
import com.agency.timeflip.ui.screens.manualalign.ManualAlignViewModel
import com.agency.timeflip.ui.screens.newpair.NewPairViewModel
import com.agency.timeflip.ui.screens.settings.SettingsViewModel
import com.agency.timeflip.ui.screens.welcome.WelcomeViewModel

/** Factories build each ViewModel with its SavedStateHandle taken from the navigation entry. */
internal fun appStartFactory(onboarding: OnboardingStore): ViewModelProvider.Factory = viewModelFactory {
    initializer { AppStartViewModel(onboarding) }
}

internal fun welcomeFactory(onboarding: OnboardingStore): ViewModelProvider.Factory = viewModelFactory {
    initializer { WelcomeViewModel(onboarding) }
}

internal fun libraryFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer { LibraryViewModel(deps.projects) }
}

internal fun newPairFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        NewPairViewModel(
            savedStateHandle = createSavedStateHandle(),
            projects = deps.projects,
            photos = deps.photos,
            awaitStartup = deps.awaitStartupRecovery,
        )
    }
}

internal fun alignReviewFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        AlignReviewViewModel(
            savedStateHandle = createSavedStateHandle(),
            projects = deps.projects,
            photos = deps.photos,
            aligner = deps.aligner,
        )
    }
}

internal fun manualAlignFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        ManualAlignViewModel(savedStateHandle = createSavedStateHandle(), projects = deps.projects)
    }
}

internal fun editorFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        EditorViewModel(savedStateHandle = createSavedStateHandle(), projects = deps.projects, photos = deps.photos)
    }
}

internal fun exportFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        ExportViewModel(
            savedStateHandle = createSavedStateHandle(),
            projects = deps.projects,
            exporter = deps.exporter,
            awaitStartup = deps.awaitStartupRecovery,
        )
    }
}

internal fun settingsFactory(deps: UiDependencies): ViewModelProvider.Factory = viewModelFactory {
    initializer { SettingsViewModel(deps.projects) }
}
