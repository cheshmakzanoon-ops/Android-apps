package com.agency.photoduel.platform.deeplink

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * Hands invitation tokens from the activity to the UI exactly once.
 *
 * [tokens] is the SharedFlow behind `UiDependencies.pendingInviteTokens`.
 *  - A token offered while the UI is collecting is emitted at once.
 *  - A token offered while nobody collects (cold start, rotation, UI stopped) waits in a single slot and
 *    is delivered to the next collector, then forgotten. A rotation therefore cannot replay it.
 *  - Nothing else keeps a token: the activity does not store it and the flow has no replay cache.
 *
 * One consumer is expected. Only well formed tokens are accepted (see [InviteLink.isValidToken]).
 * The inbox never logs a token.
 */
class InviteLinkInbox {
    private val lock = Any()
    private var waiting: String? = null

    private val live = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val tokens: SharedFlow<String> = live.onSubscription {
        val queued = synchronized(lock) {
            val token = waiting
            waiting = null
            token
        }
        if (queued != null) emit(queued)
    }

    /** Returns true when the token was accepted (delivered or queued). */
    fun offer(token: String?): Boolean {
        val value = token ?: return false
        if (!InviteLink.isValidToken(value)) return false
        synchronized(lock) {
            if (live.subscriptionCount.value > 0) {
                live.tryEmit(value)
            } else {
                waiting = value
            }
        }
        return true
    }
}
