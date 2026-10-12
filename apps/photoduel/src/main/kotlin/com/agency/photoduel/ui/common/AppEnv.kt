package com.agency.photoduel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.agency.photoduel.ui.UiDependencies
import com.agency.photoduel.ui.state.NetworkStatus
import kotlinx.coroutines.CoroutineScope

/**
 * What every screen receives from the root: the frozen [UiDependencies], the shared [NetworkStatus] and
 * the signed-in user's id.
 *
 * [liveClock] turns the once-per-second countdown refresh on. Compose UI tests switch it off so the
 * test clock can go idle; the countdown is then computed once from the server time.
 */
@Stable
class AppEnv(
    val deps: UiDependencies,
    val network: NetworkStatus,
    val viewerId: String?,
    val liveClock: Boolean = true,
)

/**
 * Holds one presentation-state object (from ui/state) for as long as its screen is on the back stack.
 * The object gets the holder's scope, which is cancelled when the screen is popped, and survives
 * configuration changes.
 */
class StateHolder<T : Any>(create: (CoroutineScope) -> T) : ViewModel() {
    val state: T = create(viewModelScope)
}

/**
 * Returns the state object for the current screen, creating it with [create] the first time. [key]
 * must identify the screen's arguments (for example the challenge id).
 */
@Composable
inline fun <reified T : Any> rememberScreenState(key: String, noinline create: (CoroutineScope) -> T): T {
    val holder: StateHolder<T> = viewModel(key = T::class.java.name + ":" + key) { StateHolder(create) }
    return holder.state
}
