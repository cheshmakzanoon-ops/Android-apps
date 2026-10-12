package com.agency.photoduel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Calls [onRefresh] each time the screen comes back to the foreground or the user navigates back to
 * it, but not on the first appearance (the state object already loads on creation).
 */
@Composable
fun RefreshOnReturn(onRefresh: () -> Unit) {
    val refresh by rememberUpdatedState(onRefresh)
    val seen = remember { booleanArrayOf(false) }
    LifecycleResumeEffect(Unit) {
        if (seen[0]) refresh() else seen[0] = true
        onPauseOrDispose { }
    }
}
