package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeState
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.SubmissionStatus

/** Why a submission (new, replacement or withdrawal) was refused. */
sealed class SubmissionRefusal(override val code: ErrorCode, override val message: String) : DomainRefusal {

    /** The caller is a room member but not on the frozen roster (403 FORBIDDEN). */
    data object NotOnRoster :
        SubmissionRefusal(ErrorCode.FORBIDDEN, "Only players on this challenge's roster can submit.")

    /** The challenge never opened for submissions, or was cancelled (409 INVALID_TRANSITION). */
    data class WrongState(val state: ChallengeState) :
        SubmissionRefusal(ErrorCode.INVALID_TRANSITION, "This challenge is not open for submissions.")

    /** The submission window has closed (410 DEADLINE_PASSED). */
    data class DeadlinePassed(val deadlineEpochMillis: Long?, val nowEpochMillis: Long) :
        SubmissionRefusal(ErrorCode.DEADLINE_PASSED, "The submission deadline has passed.")

    /** Withdraw was requested but the caller has no active submission (404 NOT_FOUND). */
    data object NothingToWithdraw :
        SubmissionRefusal(ErrorCode.NOT_FOUND, "There is no submission to withdraw.")
}

/** Why a ballot was refused. Checks run in the order of api-contract.md section 8. */
sealed class BallotRefusal(override val code: ErrorCode, override val message: String) : DomainRefusal {

    /** Check 1: the voter is not on the frozen roster (403 FORBIDDEN). */
    data object NotOnRoster :
        BallotRefusal(ErrorCode.FORBIDDEN, "Only players on this challenge's roster can vote.")

    /** Check 1: the voter is on the roster but no longer a room member (403 FORBIDDEN). */
    data object NoLongerMember :
        BallotRefusal(ErrorCode.FORBIDDEN, "Only current room members can vote.")

    /** Check 2: voting has not opened, or the challenge was cancelled (409 INVALID_TRANSITION). */
    data class WrongState(val state: ChallengeState) :
        BallotRefusal(ErrorCode.INVALID_TRANSITION, "This challenge is not in voting.")

    /** Check 3: the voting window has closed (410 DEADLINE_PASSED). */
    data class DeadlinePassed(val deadlineEpochMillis: Long?, val nowEpochMillis: Long) :
        BallotRefusal(ErrorCode.DEADLINE_PASSED, "The voting deadline has passed.")

    /** Check 4: the target is not a submission of this challenge (422 INVALID_BALLOT). */
    data object TargetNotInChallenge :
        BallotRefusal(ErrorCode.INVALID_BALLOT, "That photo is not part of this challenge.")

    /** Check 4: the target is withdrawn, removed or hidden, or its author is not on the roster (422). */
    data class TargetNotActive(val status: SubmissionStatus) :
        BallotRefusal(ErrorCode.INVALID_BALLOT, "That photo can no longer receive votes.")

    /** Check 4: self-votes are forbidden (422 INVALID_BALLOT). */
    data object SelfVote :
        BallotRefusal(ErrorCode.INVALID_BALLOT, "You cannot vote for your own photo.")

    /** Check 4: voter and author are blocked in either direction (422 INVALID_BALLOT). */
    data object BlockedPair :
        BallotRefusal(ErrorCode.INVALID_BALLOT, "You cannot vote for this photo.")

    /** Check 5: the voter already has a ballot for a different submission (409 ALREADY_VOTED). */
    data class AlreadyVoted(val existingSubmissionId: String) :
        BallotRefusal(ErrorCode.ALREADY_VOTED, "You have already voted in this challenge.")
}

/** What to do with an allowed ballot. */
enum class BallotDecision {
    /** Record a new, immutable ballot (201). */
    RECORD_NEW,

    /** The same ballot already exists; reply success without writing (200, idempotent retry). */
    ALREADY_RECORDED,
}

/** Everything a ballot check needs. [target] is null when the submission id is not in this challenge. */
data class BallotAttempt(
    val voterUserId: String,
    val roster: Set<String>,
    val currentMembers: Set<String>,
    val state: ChallengeState,
    val votingDeadlineEpochMillis: Long?,
    val nowEpochMillis: Long,
    val target: SubmissionRef?,
    val blocks: BlockGraph,
    val existingBallotSubmissionId: String?,
)

/**
 * Who may submit and who may vote (architecture section 6, api-contract.md sections 7 and 8).
 *
 * Determinism reading used by both checks: once a window's deadline has been reached the refusal is
 * DEADLINE_PASSED whether the stored state still shows the open window (the lazy transition has not been
 * evaluated yet) or already shows a later state. The answer therefore never depends on when the worker
 * last ran. States in which the window never opened (CREATED, and OPEN_FOR_SUBMISSIONS for ballots) or
 * that ended by cancellation refuse with INVALID_TRANSITION.
 */
object Eligibility {

    private val AFTER_SUBMISSION_WINDOW =
        setOf(ChallengeState.VOTING, ChallengeState.COMPLETED, ChallengeState.ARCHIVED)
    private val AFTER_VOTING_WINDOW = setOf(ChallengeState.COMPLETED, ChallengeState.ARCHIVED)

    /**
     * Submit or replace a photo: the caller is on the frozen roster, the state is OPEN_FOR_SUBMISSIONS
     * and `now < submissionDeadline` (at the deadline the window is closed).
     */
    fun checkSubmission(
        userId: String,
        roster: Set<String>,
        state: ChallengeState,
        submissionDeadlineEpochMillis: Long?,
        nowEpochMillis: Long,
    ): RuleResult<Unit, SubmissionRefusal> {
        if (userId !in roster) return RuleResult.Refused(SubmissionRefusal.NotOnRoster)
        return when {
            state == ChallengeState.OPEN_FOR_SUBMISSIONS ->
                if (submissionDeadlineEpochMillis != null && nowEpochMillis < submissionDeadlineEpochMillis) {
                    RuleResult.Ok(Unit)
                } else {
                    RuleResult.Refused(SubmissionRefusal.DeadlinePassed(submissionDeadlineEpochMillis, nowEpochMillis))
                }
            state in AFTER_SUBMISSION_WINDOW ->
                RuleResult.Refused(SubmissionRefusal.DeadlinePassed(submissionDeadlineEpochMillis, nowEpochMillis))
            else -> RuleResult.Refused(SubmissionRefusal.WrongState(state))
        }
    }

    /**
     * Withdraw one's own submission: only while the submission window is open (api-contract.md section 7
     * returns 409 INVALID_TRANSITION otherwise). After the deadline a withdrawal would let an author pull
     * a photo out of voting, so it is refused with INVALID_TRANSITION even if the stored state still
     * reads OPEN_FOR_SUBMISSIONS.
     */
    fun checkWithdrawal(
        userId: String,
        roster: Set<String>,
        state: ChallengeState,
        submissionDeadlineEpochMillis: Long?,
        nowEpochMillis: Long,
        hasActiveSubmission: Boolean,
    ): RuleResult<Unit, SubmissionRefusal> {
        if (userId !in roster) return RuleResult.Refused(SubmissionRefusal.NotOnRoster)
        val windowOpen = state == ChallengeState.OPEN_FOR_SUBMISSIONS &&
            submissionDeadlineEpochMillis != null && nowEpochMillis < submissionDeadlineEpochMillis
        if (!windowOpen) return RuleResult.Refused(SubmissionRefusal.WrongState(state))
        if (!hasActiveSubmission) return RuleResult.Refused(SubmissionRefusal.NothingToWithdraw)
        return RuleResult.Ok(Unit)
    }

    /**
     * Cast a ballot. Checks, in the contract's order:
     * 1. voter on the roster and still a member (FORBIDDEN);
     * 2. state is VOTING (INVALID_TRANSITION; see the determinism note on [Eligibility]);
     * 3. `now < votingDeadline` (DEADLINE_PASSED);
     * 4. target is an ACTIVE submission of this challenge by a roster member, not the voter's own, and
     *    not blocked in either direction (INVALID_BALLOT);
     * 5. no existing ballot: the same target is an idempotent [BallotDecision.ALREADY_RECORDED], a
     *    different target is ALREADY_VOTED.
     */
    fun checkBallot(attempt: BallotAttempt): RuleResult<BallotDecision, BallotRefusal> {
        val voter = attempt.voterUserId
        if (voter !in attempt.roster) return RuleResult.Refused(BallotRefusal.NotOnRoster)
        if (voter !in attempt.currentMembers) return RuleResult.Refused(BallotRefusal.NoLongerMember)

        when {
            attempt.state == ChallengeState.VOTING -> {
                val deadline = attempt.votingDeadlineEpochMillis
                if (deadline == null || attempt.nowEpochMillis >= deadline) {
                    return RuleResult.Refused(BallotRefusal.DeadlinePassed(deadline, attempt.nowEpochMillis))
                }
            }
            attempt.state in AFTER_VOTING_WINDOW ->
                return RuleResult.Refused(BallotRefusal.DeadlinePassed(attempt.votingDeadlineEpochMillis, attempt.nowEpochMillis))
            else -> return RuleResult.Refused(BallotRefusal.WrongState(attempt.state))
        }

        val target = attempt.target ?: return RuleResult.Refused(BallotRefusal.TargetNotInChallenge)
        if (target.status != SubmissionStatus.ACTIVE) {
            return RuleResult.Refused(BallotRefusal.TargetNotActive(target.status))
        }
        val author = target.authorUserId
        if (author == null || author !in attempt.roster) {
            return RuleResult.Refused(BallotRefusal.TargetNotActive(target.status))
        }
        if (author == voter) return RuleResult.Refused(BallotRefusal.SelfVote)
        if (attempt.blocks.blockedEitherWay(voter, author)) return RuleResult.Refused(BallotRefusal.BlockedPair)

        val existing = attempt.existingBallotSubmissionId
        return when {
            existing == null -> RuleResult.Ok(BallotDecision.RECORD_NEW)
            existing == target.id -> RuleResult.Ok(BallotDecision.ALREADY_RECORDED)
            else -> RuleResult.Refused(BallotRefusal.AlreadyVoted(existing))
        }
    }

    /**
     * Submissions a viewer may see in a challenge view (api-contract.md section 6):
     * - CREATED and CANCELLED: none;
     * - OPEN_FOR_SUBMISSIONS: only the viewer's own submission;
     * - VOTING, COMPLETED, ARCHIVED: every ACTIVE submission whose author is not blocked in either
     *   direction with the viewer, plus the viewer's own submission.
     *
     * The viewer's own submission is included in any status except WITHDRAWN, so an author can see that
     * a photo was hidden or removed. Order of [submissions] is preserved.
     */
    fun visibleSubmissions(
        viewerUserId: String,
        state: ChallengeState,
        submissions: List<SubmissionRef>,
        blocks: BlockGraph,
    ): List<SubmissionRef> {
        val own: (SubmissionRef) -> Boolean = {
            it.authorUserId == viewerUserId && it.status != SubmissionStatus.WITHDRAWN
        }
        return when (state) {
            ChallengeState.CREATED, ChallengeState.CANCELLED -> emptyList()
            ChallengeState.OPEN_FOR_SUBMISSIONS -> submissions.filter(own)
            ChallengeState.VOTING, ChallengeState.COMPLETED, ChallengeState.ARCHIVED -> submissions.filter {
                own(it) || (
                    it.status == SubmissionStatus.ACTIVE &&
                        it.authorUserId != viewerUserId &&
                        !blocks.blockedEitherWay(viewerUserId, it.authorUserId)
                    )
            }
        }
    }
}
