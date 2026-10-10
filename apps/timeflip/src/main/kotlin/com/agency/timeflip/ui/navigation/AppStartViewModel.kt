package com.agency.timeflip.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.ui.ports.OnboardingStore
import com.agency.timeflip.ui.ports.StartScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/**
 * Chooses the first screen. The value stays null until the onboarding flag has been read, so the
 * app never flashes the wrong screen. If the flag cannot be read, the welcome screen is shown.
 */
class AppStartViewModel(onboarding: OnboardingStore) : ViewModel() {

    val startScreen: StateFlow<StartScreen?> = onboarding.observeCompleted()
        .map { completed -> if (completed) StartScreen.LIBRARY else StartScreen.WELCOME }
        .catch { emit(StartScreen.WELCOME) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
