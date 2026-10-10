package com.agency.timeflip.ui.screens.welcome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agency.timeflip.ui.ports.OnboardingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WelcomeUiState(val starting: Boolean = false)

class WelcomeViewModel(private val onboarding: OnboardingStore) : ViewModel() {

    private val _state = MutableStateFlow(WelcomeUiState())
    val state: StateFlow<WelcomeUiState> = _state.asStateFlow()

    /**
     * Stores the first-run flag and then calls [onStarted]. If the flag cannot be stored, the
     * welcome screen appears again next launch; the user still reaches the library now.
     */
    fun start(onStarted: () -> Unit) {
        if (_state.value.starting) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                onboarding.markCompleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Intentionally ignored: see the KDoc above. The flag holds no user data.
            }
            onStarted()
        }
    }
}
