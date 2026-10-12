package com.agency.photoduel.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.agency.photoduel.R
import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.InvitationTtl
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.ReportReason
import com.agency.photoduel.ui.state.CountdownParts
import com.agency.photoduel.ui.state.NameProblem
import java.text.DateFormat
import java.util.Date

// User-facing wording for values that come from the domain. The state layer names problems; these
// functions pick the words from string resources, so every message can be translated.

/** The message for [error]. Server messages are not shown: they are English and written for developers. */
@Composable
fun errorMessage(error: PhotoDuelError): String = when (error.code) {
    ErrorCode.VALIDATION -> stringResource(R.string.error_validation)
    ErrorCode.UNAUTHENTICATED -> stringResource(R.string.error_unauthenticated)
    ErrorCode.FORBIDDEN -> stringResource(R.string.error_forbidden)
    ErrorCode.NOT_FOUND -> stringResource(R.string.error_not_found)
    ErrorCode.INVITE_UNAVAILABLE -> stringResource(R.string.error_invite_unavailable)
    ErrorCode.CONFLICT -> stringResource(R.string.error_conflict)
    ErrorCode.INVALID_TRANSITION -> stringResource(R.string.error_invalid_transition)
    ErrorCode.ALREADY_VOTED -> stringResource(R.string.error_already_voted)
    ErrorCode.ROSTER_FROZEN -> stringResource(R.string.error_roster_frozen)
    ErrorCode.ROOM_FULL -> stringResource(R.string.error_room_full)
    ErrorCode.DEADLINE_PASSED -> stringResource(R.string.error_deadline_passed)
    ErrorCode.PAYLOAD_TOO_LARGE -> stringResource(R.string.error_payload_too_large)
    ErrorCode.UNSUPPORTED_MEDIA -> stringResource(R.string.error_unsupported_media)
    ErrorCode.INVALID_BALLOT -> stringResource(R.string.error_invalid_ballot)
    ErrorCode.RATE_LIMITED -> {
        val seconds = error.retryAfterSeconds
        if (seconds != null && seconds > 0) {
            val minutes = (seconds + 59) / 60
            pluralStringResource(R.plurals.error_rate_limited_minutes, minutes, minutes)
        } else {
            stringResource(R.string.error_rate_limited)
        }
    }
    ErrorCode.SERVER -> stringResource(R.string.error_server)
    ErrorCode.NETWORK -> stringResource(R.string.error_network)
    ErrorCode.TIMEOUT -> stringResource(R.string.error_timeout)
    ErrorCode.UNKNOWN -> stringResource(R.string.error_unknown)
}

/** True when offering "Try again" makes sense for [error]. */
fun PhotoDuelError.canRetry(): Boolean =
    retryable || code == ErrorCode.NETWORK || code == ErrorCode.TIMEOUT || code == ErrorCode.SERVER

@Composable
fun displayNameProblem(problem: NameProblem, maxLength: Int): String = when (problem) {
    NameProblem.EMPTY -> stringResource(R.string.name_problem_empty)
    NameProblem.TOO_LONG -> stringResource(R.string.name_problem_too_long, maxLength)
    NameProblem.ILLEGAL_CHARACTER -> stringResource(R.string.name_problem_illegal)
}

@Composable
fun roomNameProblem(problem: NameProblem, maxLength: Int): String = when (problem) {
    NameProblem.EMPTY -> stringResource(R.string.name_problem_empty)
    NameProblem.TOO_LONG -> stringResource(R.string.name_problem_too_long, maxLength)
    NameProblem.ILLEGAL_CHARACTER -> stringResource(R.string.room_name_problem_illegal)
}

@Composable
fun challengeStateLabel(state: ChallengeState): String = when (state) {
    ChallengeState.CREATED -> stringResource(R.string.state_created)
    ChallengeState.OPEN_FOR_SUBMISSIONS -> stringResource(R.string.state_open)
    ChallengeState.VOTING -> stringResource(R.string.state_voting)
    ChallengeState.COMPLETED -> stringResource(R.string.state_completed)
    ChallengeState.ARCHIVED -> stringResource(R.string.state_archived)
    ChallengeState.CANCELLED -> stringResource(R.string.state_cancelled)
}

@Composable
fun outcomeLabel(outcome: ChallengeOutcome): String = when (outcome) {
    ChallengeOutcome.WINNER -> stringResource(R.string.outcome_winner)
    ChallengeOutcome.TIE -> stringResource(R.string.outcome_tie)
    ChallengeOutcome.INSUFFICIENT_SUBMISSIONS -> stringResource(R.string.outcome_insufficient_submissions)
    ChallengeOutcome.INSUFFICIENT_BALLOTS -> stringResource(R.string.outcome_insufficient_ballots)
}

@Composable
fun ttlLabel(ttl: InvitationTtl): String = when (ttl) {
    InvitationTtl.ONE_DAY -> stringResource(R.string.ttl_one_day)
    InvitationTtl.THREE_DAYS -> stringResource(R.string.ttl_three_days)
    InvitationTtl.ONE_WEEK -> stringResource(R.string.ttl_one_week)
}

@Composable
fun reportReasonLabel(reason: ReportReason): String = when (reason) {
    ReportReason.INAPPROPRIATE -> stringResource(R.string.report_reason_inappropriate)
    ReportReason.HARASSMENT -> stringResource(R.string.report_reason_harassment)
    ReportReason.PRIVACY -> stringResource(R.string.report_reason_privacy)
    ReportReason.SPAM -> stringResource(R.string.report_reason_spam)
    ReportReason.NOT_A_PHOTO_OF_PROMPT -> stringResource(R.string.report_reason_not_prompt)
    ReportReason.OTHER -> stringResource(R.string.report_reason_other)
}

@Composable
fun hoursLabel(hours: Int): String = pluralStringResource(R.plurals.duration_hours, hours, hours)

/**
 * What TalkBack reads for a countdown: whole days, then whole hours, then minutes rounded up. Seconds
 * are never read, so the announcement does not change every second.
 */
@Composable
fun spokenDuration(parts: CountdownParts): String {
    val minutes = parts.totalMinutesCeil
    return when {
        parts.days >= 2 -> pluralStringResource(R.plurals.duration_days, parts.days, parts.days)
        minutes >= 120 -> {
            val hours = minutes / 60
            pluralStringResource(R.plurals.duration_hours, hours, hours)
        }
        else -> pluralStringResource(R.plurals.duration_minutes, minutes, minutes)
    }
}

/** Date and time in the device's locale and time zone, for display only. */
fun formatDateTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))
