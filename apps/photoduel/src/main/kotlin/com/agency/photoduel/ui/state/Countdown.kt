package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.model.Challenge
import com.agency.photoduel.domain.model.ChallengeState
import java.util.Locale

/**
 * Anchors a challenge's server time to a local reading of the display clock.
 *
 * [serverTimeEpochMillis] is the `serverTimeEpochMillis` of the challenge response. [clockAtReceiptMillis]
 * is what the display clock (ServerClock.nowEpochMillis) read when that response arrived. Only the
 * difference between two clock readings is used, so a wrong device clock cancels out. The result is for
 * showing a countdown. The server decides every deadline; the UI never uses this to allow or refuse an action.
 */
class ServerTimeAnchor(val serverTimeEpochMillis: Long, val clockAtReceiptMillis: Long) {
    fun serverNow(clockNowMillis: Long): Long =
        serverTimeEpochMillis + (clockNowMillis - clockAtReceiptMillis).coerceAtLeast(0L)

    companion object {
        fun of(challenge: Challenge, clockNowMillis: Long) = ServerTimeAnchor(challenge.serverTimeEpochMillis, clockNowMillis)
    }
}

enum class CountdownKind { OPEN, URGENT, ENDED }

data class CountdownParts(
    val remainingMillis: Long,
    val days: Int,
    val hours: Int,
    val minutes: Int,
    val seconds: Int,
    val kind: CountdownKind,
) {
    /** Whole minutes left, rounded up. TalkBack announces this, not the seconds. */
    val totalMinutesCeil: Int get() = ((remainingMillis + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
}

object Countdown {
    const val URGENT_THRESHOLD_MILLIS: Long = 10L * 60L * 1000L

    /** The deadline that matters in [state], or null when no clock is running. */
    fun deadlineOf(challenge: Challenge): Long? = when (challenge.state) {
        ChallengeState.OPEN_FOR_SUBMISSIONS -> challenge.submissionDeadlineEpochMillis
        ChallengeState.VOTING -> challenge.votingDeadlineEpochMillis
        else -> null
    }

    fun compute(deadlineEpochMillis: Long, serverNowMillis: Long): CountdownParts {
        val remaining = deadlineEpochMillis - serverNowMillis
        val kind = when {
            remaining <= 0L -> CountdownKind.ENDED
            remaining <= URGENT_THRESHOLD_MILLIS -> CountdownKind.URGENT
            else -> CountdownKind.OPEN
        }
        // Round up so the clock shows 00:00:01 until the deadline has really passed.
        val totalSeconds = if (remaining <= 0L) 0L else (remaining + 999L) / 1000L
        return CountdownParts(
            remainingMillis = remaining.coerceAtLeast(0L),
            days = (totalSeconds / 86_400L).toInt(),
            hours = ((totalSeconds % 86_400L) / 3_600L).toInt(),
            minutes = ((totalSeconds % 3_600L) / 60L).toInt(),
            seconds = (totalSeconds % 60L).toInt(),
            kind = kind,
        )
    }

    /** `HH:MM:SS`, or `Nd HH:MM:SS` when a day or more is left. */
    fun clockText(parts: CountdownParts): String {
        val hms = String.format(Locale.ROOT, "%02d:%02d:%02d", parts.hours, parts.minutes, parts.seconds)
        return if (parts.days > 0) "${parts.days}d $hms" else hms
    }
}
