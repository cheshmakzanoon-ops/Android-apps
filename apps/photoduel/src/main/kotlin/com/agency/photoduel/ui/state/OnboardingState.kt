package com.agency.photoduel.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class OnboardingUi(val page: Int, val pageCount: Int) {
    val isFirst: Boolean get() = page == 0
    val isLast: Boolean get() = page == pageCount - 1
}

/**
 * Three-page welcome. [onFinished] is called once, when the user finishes or skips; the composition
 * root stores the "onboarding completed" flag in that callback, not the UI.
 */
class OnboardingState(
    private val pageCount: Int = 3,
    private val onFinished: () -> Unit,
) {
    init {
        require(pageCount >= 1) { "pageCount must be at least 1" }
    }

    private val _state = MutableStateFlow(OnboardingUi(page = 0, pageCount = pageCount))
    val state: StateFlow<OnboardingUi> = _state.asStateFlow()
    private var finished = false

    fun next() {
        if (_state.value.isLast) finish() else _state.update { it.copy(page = it.page + 1) }
    }

    fun previous() {
        _state.update { if (it.page > 0) it.copy(page = it.page - 1) else it }
    }

    fun skip() = finish()

    private fun finish() {
        if (finished) return
        finished = true
        onFinished()
    }
}
