package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.AppResult
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PhotoDuelError
import com.agency.photoduel.domain.model.SubmissionStatus

// Shared value types for the pure PhotoDuel rules. Nothing here reads a clock, a file or the network:
// every instant arrives as an epoch-millisecond parameter taken from the server clock.

/** A refusal produced by a rule. Each refusal maps to exactly one frozen [ErrorCode]. */
interface DomainRefusal {
    val code: ErrorCode
    val message: String
}

/** Converts a refusal into the frozen error shape. Rule refusals are never retryable. */
fun DomainRefusal.toError(): PhotoDuelError = PhotoDuelError(code = code, message = message, retryable = false)

/**
 * Outcome of a rule check. A rule never throws for user input and never silently does nothing:
 * it either allows the action ([Ok]) or names the reason it refuses it ([Refused]).
 */
sealed interface RuleResult<out T, out E : DomainRefusal> {
    data class Ok<out T>(val value: T) : RuleResult<T, Nothing>
    data class Refused<out E : DomainRefusal>(val reason: E) : RuleResult<Nothing, E>
}

val RuleResult<*, *>.isOk: Boolean get() = this is RuleResult.Ok

/** The allowed value, or null when refused. */
fun <T> RuleResult<T, *>.valueOrNull(): T? = (this as? RuleResult.Ok<T>)?.value

/** The refusal, or null when allowed. */
fun <E : DomainRefusal> RuleResult<*, E>.refusalOrNull(): E? = (this as? RuleResult.Refused<E>)?.reason

/** Chains a second rule that runs only when the first one allowed the action. */
inline fun <T, R, E : DomainRefusal> RuleResult<T, E>.andThen(next: (T) -> RuleResult<R, E>): RuleResult<R, E> =
    when (this) {
        is RuleResult.Ok -> next(value)
        is RuleResult.Refused -> this
    }

/** Converts a rule result into the service result type used at the port boundary. */
fun <T> RuleResult<T, DomainRefusal>.toAppResult(): AppResult<T> = when (this) {
    is RuleResult.Ok -> AppResult.Success(value)
    is RuleResult.Refused -> AppResult.Failure(reason.toError())
}

/**
 * Block relations as directed edges (blocker to blocked). Every rule treats a block in either
 * direction as a block of the pair, so `blockedEitherWay(a, b) == blockedEitherWay(b, a)`.
 */
class BlockGraph private constructor(private val edges: Set<Pair<String, String>>) {

    /** True when either user blocked the other. A null id (deleted account) is never blocked. */
    fun blockedEitherWay(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        return (first to second) in edges || (second to first) in edges
    }

    val size: Int get() = edges.size

    override fun equals(other: Any?): Boolean = other is BlockGraph && other.edges == edges
    override fun hashCode(): Int = edges.hashCode()
    override fun toString(): String = "BlockGraph($edges)"

    companion object {
        val EMPTY: BlockGraph = BlockGraph(emptySet())

        /** [edges] are (blocker, blocked) pairs. */
        fun of(edges: Collection<Pair<String, String>>): BlockGraph = BlockGraph(edges.toSet())

        fun of(vararg edges: Pair<String, String>): BlockGraph = BlockGraph(edges.toSet())
    }
}

/** The facts about one submission that the rules need. [authorUserId] is null for a deleted account. */
data class SubmissionRef(
    val id: String,
    val authorUserId: String?,
    val status: SubmissionStatus,
)

/** One recorded ballot. [voterUserId] is null when the voter deleted their account. */
data class BallotRef(
    val voterUserId: String?,
    val submissionId: String,
)

/** Milliseconds per hour and per day, as exact longs, so deadline arithmetic never overflows an Int. */
object Durations {
    const val HOUR_MILLIS: Long = 60L * 60L * 1000L
    const val DAY_MILLIS: Long = 24L * HOUR_MILLIS

    fun hours(count: Int): Long = count.toLong() * HOUR_MILLIS
    fun days(count: Int): Long = count.toLong() * DAY_MILLIS
}
