package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeOutcome
import com.agency.photoduel.domain.model.ChallengeResult
import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode

/** The events that can move a challenge between states (architecture section 7). */
enum class ChallengeEvent {
    /** Owner starts a CREATED challenge. */
    START,

    /** The submission deadline has been reached. */
    CLOSE_SUBMISSIONS,

    /** The voting deadline has been reached. */
    CLOSE_VOTING,

    /** Thirty days have passed since completion. */
    ARCHIVE,

    /** Owner cancels a CREATED or OPEN_FOR_SUBMISSIONS challenge. */
    CANCEL,
}

/**
 * Pure timing and outcome state of one challenge. Every instant is server time in epoch milliseconds.
 * [result] is set exactly when the challenge reaches COMPLETED and is kept through ARCHIVED.
 */
data class ChallengeLifecycle(
    val state: ChallengeState,
    val submissionHours: Int,
    val votingHours: Int,
    val startedAtEpochMillis: Long? = null,
    val submissionDeadlineEpochMillis: Long? = null,
    val votingDeadlineEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val cancelledAtEpochMillis: Long? = null,
    val result: ChallengeResult? = null,
) {
    val outcome: ChallengeOutcome? get() = result?.outcome

    companion object {
        /** A freshly created challenge: state CREATED, no deadlines until it starts. */
        fun created(spec: ChallengeSpec): ChallengeLifecycle =
            ChallengeLifecycle(
                state = ChallengeState.CREATED,
                submissionHours = spec.submissionHours,
                votingHours = spec.votingHours,
            )
    }
}

/** Why a transition was refused. */
sealed class TransitionFailure(override val code: ErrorCode, override val message: String) : DomainRefusal {

    /** The event is not allowed from [from] at all (409 INVALID_TRANSITION). */
    data class InvalidTransition(val from: ChallengeState, val event: ChallengeEvent) :
        TransitionFailure(ErrorCode.INVALID_TRANSITION, "Cannot apply $event to a challenge in state $from.")

    /** A time-driven event was requested before its instant (409 INVALID_TRANSITION). */
    data class NotYetDue(val event: ChallengeEvent, val dueAtEpochMillis: Long, val nowEpochMillis: Long) :
        TransitionFailure(ErrorCode.INVALID_TRANSITION, "$event is not due yet.")

    /** Start needs at least three members (409 CONFLICT, as in api-contract.md section 6). */
    data class NotEnoughMembers(val memberCount: Int, val required: Int) :
        TransitionFailure(ErrorCode.CONFLICT, "At least $required members are needed")

    /** The stored windows are not among the allowed options (400 VALIDATION). */
    data class InvalidWindow(val submissionHours: Int, val votingHours: Int) :
        TransitionFailure(ErrorCode.VALIDATION, "The challenge windows are not valid.")
}

/** Result of catching a challenge up to a given instant. [applied] lists the events in order. */
data class AdvanceResult(val lifecycle: ChallengeLifecycle, val applied: List<ChallengeEvent>)

/**
 * The server-enforced challenge state machine (architecture section 7):
 *
 * ```
 * CREATED --start (>= 3 members)--> OPEN_FOR_SUBMISSIONS --submission deadline--> VOTING
 * VOTING --voting deadline--> COMPLETED --30 days--> ARCHIVED
 * OPEN_FOR_SUBMISSIONS --submission deadline, fewer than 2 active submissions--> COMPLETED
 * CREATED or OPEN_FOR_SUBMISSIONS --cancel--> CANCELLED (terminal)
 * ```
 *
 * Every function is pure and deterministic: time arrives only as `nowEpochMillis`. Any transition not
 * listed above returns [RuleResult.Refused]; nothing is ever a silent no-op.
 *
 * Boundaries are exact. A window is open only while `now < deadline`; at `now == deadline` it is closed
 * and the time-driven transition is due.
 */
object ChallengeRules {

    const val ARCHIVE_AFTER_DAYS = 30

    /** States whose challenge counts as "active" for the one-active-challenge-per-room rule. */
    val ACTIVE_STATES: Set<ChallengeState> =
        setOf(ChallengeState.CREATED, ChallengeState.OPEN_FOR_SUBMISSIONS, ChallengeState.VOTING)

    /** Terminal states: nothing can follow them. */
    val TERMINAL_STATES: Set<ChallengeState> = setOf(ChallengeState.ARCHIVED, ChallengeState.CANCELLED)

    /** The complete transition table. Any (state, event) pair not listed is refused. */
    val TRANSITIONS: Map<ChallengeState, Map<ChallengeEvent, Set<ChallengeState>>> = mapOf(
        ChallengeState.CREATED to mapOf(
            ChallengeEvent.START to setOf(ChallengeState.OPEN_FOR_SUBMISSIONS),
            ChallengeEvent.CANCEL to setOf(ChallengeState.CANCELLED),
        ),
        ChallengeState.OPEN_FOR_SUBMISSIONS to mapOf(
            ChallengeEvent.CLOSE_SUBMISSIONS to setOf(ChallengeState.VOTING, ChallengeState.COMPLETED),
            ChallengeEvent.CANCEL to setOf(ChallengeState.CANCELLED),
        ),
        ChallengeState.VOTING to mapOf(
            ChallengeEvent.CLOSE_VOTING to setOf(ChallengeState.COMPLETED),
        ),
        ChallengeState.COMPLETED to mapOf(
            ChallengeEvent.ARCHIVE to setOf(ChallengeState.ARCHIVED),
        ),
        ChallengeState.ARCHIVED to emptyMap(),
        ChallengeState.CANCELLED to emptyMap(),
    )

    /** Events allowed from [state] by the table (time conditions are checked separately). */
    fun allowedEvents(state: ChallengeState): Set<ChallengeEvent> = TRANSITIONS.getValue(state).keys

    /** Possible target states of [event] from [state]; empty when the pair is not allowed. */
    fun targetsOf(state: ChallengeState, event: ChallengeEvent): Set<ChallengeState> =
        TRANSITIONS.getValue(state)[event].orEmpty()

    /** Instant at which the submission window closes for a challenge started at [startedAtEpochMillis]. */
    fun submissionDeadline(startedAtEpochMillis: Long, submissionHours: Int): Long =
        startedAtEpochMillis + Durations.hours(submissionHours)

    /** Instant at which voting closes: the submission deadline plus the voting window. */
    fun votingDeadline(submissionDeadlineEpochMillis: Long, votingHours: Int): Long =
        submissionDeadlineEpochMillis + Durations.hours(votingHours)

    /** Instant at which a completed challenge becomes ARCHIVED. */
    fun archiveAt(completedAtEpochMillis: Long): Long = completedAtEpochMillis + Durations.days(ARCHIVE_AFTER_DAYS)

    /** True while submissions are accepted: state OPEN_FOR_SUBMISSIONS and `now < submissionDeadline`. */
    fun isSubmissionWindowOpen(lifecycle: ChallengeLifecycle, nowEpochMillis: Long): Boolean {
        val deadline = lifecycle.submissionDeadlineEpochMillis ?: return false
        return lifecycle.state == ChallengeState.OPEN_FOR_SUBMISSIONS && nowEpochMillis < deadline
    }

    /** True while ballots are accepted: state VOTING and `now < votingDeadline`. */
    fun isVotingWindowOpen(lifecycle: ChallengeLifecycle, nowEpochMillis: Long): Boolean {
        val deadline = lifecycle.votingDeadlineEpochMillis ?: return false
        return lifecycle.state == ChallengeState.VOTING && nowEpochMillis < deadline
    }

    /**
     * CREATED to OPEN_FOR_SUBMISSIONS. Requires at least [RosterRules.MIN_MEMBERS_TO_START] members and
     * valid windows. Fixes both deadlines from the start instant:
     * `submissionDeadline = now + submissionHours` and `votingDeadline = submissionDeadline + votingHours`.
     * Owner authorization and roster freezing are the caller's job.
     */
    fun start(
        lifecycle: ChallengeLifecycle,
        memberCount: Int,
        nowEpochMillis: Long,
    ): RuleResult<ChallengeLifecycle, TransitionFailure> {
        if (lifecycle.state != ChallengeState.CREATED) {
            return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.START))
        }
        if (lifecycle.submissionHours !in Validation.SUBMISSION_HOURS || lifecycle.votingHours !in Validation.VOTING_HOURS) {
            return RuleResult.Refused(TransitionFailure.InvalidWindow(lifecycle.submissionHours, lifecycle.votingHours))
        }
        if (memberCount < RosterRules.MIN_MEMBERS_TO_START) {
            return RuleResult.Refused(TransitionFailure.NotEnoughMembers(memberCount, RosterRules.MIN_MEMBERS_TO_START))
        }
        val submissionDeadline = submissionDeadline(nowEpochMillis, lifecycle.submissionHours)
        return RuleResult.Ok(
            lifecycle.copy(
                state = ChallengeState.OPEN_FOR_SUBMISSIONS,
                startedAtEpochMillis = nowEpochMillis,
                submissionDeadlineEpochMillis = submissionDeadline,
                votingDeadlineEpochMillis = votingDeadline(submissionDeadline, lifecycle.votingHours),
            ),
        )
    }

    /**
     * OPEN_FOR_SUBMISSIONS to VOTING once `now >= submissionDeadline`, or straight to COMPLETED with
     * INSUFFICIENT_SUBMISSIONS when fewer than 2 countable submissions (ACTIVE and authored by a
     * roster member, see [ResultCalculator.countableSubmissions]) exist in [tally].
     *
     * The completion instant recorded for the short-circuit is the submission deadline itself, not the
     * moment the transition happened to be evaluated, so lazy evaluation and the worker agree exactly.
     */
    fun closeSubmissions(
        lifecycle: ChallengeLifecycle,
        nowEpochMillis: Long,
        tally: TallyInput,
    ): RuleResult<ChallengeLifecycle, TransitionFailure> {
        if (lifecycle.state != ChallengeState.OPEN_FOR_SUBMISSIONS) {
            return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.CLOSE_SUBMISSIONS))
        }
        val deadline = lifecycle.submissionDeadlineEpochMillis
            ?: return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.CLOSE_SUBMISSIONS))
        if (nowEpochMillis < deadline) {
            return RuleResult.Refused(TransitionFailure.NotYetDue(ChallengeEvent.CLOSE_SUBMISSIONS, deadline, nowEpochMillis))
        }
        if (ResultCalculator.countableSubmissions(tally).size < 2) {
            return RuleResult.Ok(
                lifecycle.copy(
                    state = ChallengeState.COMPLETED,
                    completedAtEpochMillis = deadline,
                    result = ResultCalculator.calculate(tally),
                ),
            )
        }
        return RuleResult.Ok(lifecycle.copy(state = ChallengeState.VOTING))
    }

    /**
     * VOTING to COMPLETED once `now >= votingDeadline`. The result is computed once, here, from [tally]
     * (architecture section 8). The completion instant is the voting deadline.
     */
    fun closeVoting(
        lifecycle: ChallengeLifecycle,
        nowEpochMillis: Long,
        tally: TallyInput,
    ): RuleResult<ChallengeLifecycle, TransitionFailure> {
        if (lifecycle.state != ChallengeState.VOTING) {
            return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.CLOSE_VOTING))
        }
        val deadline = lifecycle.votingDeadlineEpochMillis
            ?: return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.CLOSE_VOTING))
        if (nowEpochMillis < deadline) {
            return RuleResult.Refused(TransitionFailure.NotYetDue(ChallengeEvent.CLOSE_VOTING, deadline, nowEpochMillis))
        }
        return RuleResult.Ok(
            lifecycle.copy(
                state = ChallengeState.COMPLETED,
                completedAtEpochMillis = deadline,
                result = ResultCalculator.calculate(tally),
            ),
        )
    }

    /**
     * COMPLETED to ARCHIVED once `now >= completedAt + 30 days`. "After 30 days" is read with the same
     * boundary as every deadline: at exactly 30 days the photos are due for purge. The result is kept.
     */
    fun archive(
        lifecycle: ChallengeLifecycle,
        nowEpochMillis: Long,
    ): RuleResult<ChallengeLifecycle, TransitionFailure> {
        if (lifecycle.state != ChallengeState.COMPLETED) {
            return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.ARCHIVE))
        }
        val completedAt = lifecycle.completedAtEpochMillis
            ?: return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.ARCHIVE))
        val due = archiveAt(completedAt)
        if (nowEpochMillis < due) {
            return RuleResult.Refused(TransitionFailure.NotYetDue(ChallengeEvent.ARCHIVE, due, nowEpochMillis))
        }
        return RuleResult.Ok(lifecycle.copy(state = ChallengeState.ARCHIVED))
    }

    /**
     * CREATED or OPEN_FOR_SUBMISSIONS to CANCELLED. Owner authorization is the caller's job.
     *
     * Fairness reading: a challenge stored as OPEN_FOR_SUBMISSIONS whose submission deadline has already
     * been reached is effectively in VOTING (the deadline transition simply has not been evaluated yet),
     * so cancel is refused with INVALID_TRANSITION. Otherwise an owner could cancel after seeing that
     * voting is about to start, and the answer would depend on when the worker last ran.
     */
    fun cancel(
        lifecycle: ChallengeLifecycle,
        nowEpochMillis: Long,
    ): RuleResult<ChallengeLifecycle, TransitionFailure> {
        val allowed = when (lifecycle.state) {
            ChallengeState.CREATED -> true
            ChallengeState.OPEN_FOR_SUBMISSIONS -> isSubmissionWindowOpen(lifecycle, nowEpochMillis)
            else -> false
        }
        if (!allowed) {
            return RuleResult.Refused(TransitionFailure.InvalidTransition(lifecycle.state, ChallengeEvent.CANCEL))
        }
        return RuleResult.Ok(
            lifecycle.copy(state = ChallengeState.CANCELLED, cancelledAtEpochMillis = nowEpochMillis),
        )
    }

    /**
     * Applies every time-driven transition that is due at [nowEpochMillis], in order (close submissions,
     * close voting, archive), exactly as the lazy per-request evaluation and the 30-second worker do.
     * Returns the lifecycle unchanged with an empty [AdvanceResult.applied] list when nothing is due.
     *
     * [tally] is evaluated at most once and only when a deadline transition needs it.
     */
    fun advance(
        lifecycle: ChallengeLifecycle,
        nowEpochMillis: Long,
        tally: () -> TallyInput,
    ): AdvanceResult {
        val cachedTally by lazy(LazyThreadSafetyMode.NONE) { tally() }
        var current = lifecycle
        val applied = ArrayList<ChallengeEvent>(3)
        while (true) {
            val event = dueEvent(current, nowEpochMillis) ?: break
            val step = when (event) {
                ChallengeEvent.CLOSE_SUBMISSIONS -> closeSubmissions(current, nowEpochMillis, cachedTally)
                ChallengeEvent.CLOSE_VOTING -> closeVoting(current, nowEpochMillis, cachedTally)
                ChallengeEvent.ARCHIVE -> archive(current, nowEpochMillis)
                ChallengeEvent.START, ChallengeEvent.CANCEL -> break // never driven by time
            }
            current = step.valueOrNull() ?: break
            applied += event
        }
        return AdvanceResult(current, applied)
    }

    /**
     * The time-driven event that is due for [lifecycle] at [nowEpochMillis], or null when none is.
     * START and CANCEL are user actions and are never returned.
     */
    fun dueEvent(lifecycle: ChallengeLifecycle, nowEpochMillis: Long): ChallengeEvent? = when (lifecycle.state) {
        ChallengeState.OPEN_FOR_SUBMISSIONS -> lifecycle.submissionDeadlineEpochMillis
            ?.takeIf { nowEpochMillis >= it }?.let { ChallengeEvent.CLOSE_SUBMISSIONS }
        ChallengeState.VOTING -> lifecycle.votingDeadlineEpochMillis
            ?.takeIf { nowEpochMillis >= it }?.let { ChallengeEvent.CLOSE_VOTING }
        ChallengeState.COMPLETED -> lifecycle.completedAtEpochMillis
            ?.takeIf { nowEpochMillis >= archiveAt(it) }?.let { ChallengeEvent.ARCHIVE }
        ChallengeState.CREATED, ChallengeState.ARCHIVED, ChallengeState.CANCELLED -> null
    }
}
