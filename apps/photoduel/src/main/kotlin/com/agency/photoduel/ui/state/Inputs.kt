package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.rules.RuleResult
import com.agency.photoduel.domain.rules.Validation
import com.agency.photoduel.domain.rules.ValidationError

// Input checks used by forms. They delegate to the domain rules (the same ones the in-memory service
// enforces) so the UI never disagrees with the server about what a valid value is. The result names
// the problem; the screen chooses the words.

enum class NameProblem { EMPTY, TOO_LONG, ILLEGAL_CHARACTER }

/** Result of checking one text field. [clean] is the canonical value to send when [problem] is null. */
data class FieldCheck(val clean: String?, val problem: NameProblem?) {
    val isValid: Boolean get() = problem == null && clean != null
}

private fun RuleResult<String, ValidationError>.toFieldCheck(): FieldCheck = when (this) {
    is RuleResult.Ok -> FieldCheck(value, null)
    is RuleResult.Refused -> FieldCheck(
        null,
        when (reason) {
            is ValidationError.Blank -> NameProblem.EMPTY
            is ValidationError.TooLong -> NameProblem.TOO_LONG
            else -> NameProblem.ILLEGAL_CHARACTER
        },
    )
}

object Inputs {
    const val DISPLAY_NAME_MAX = Validation.DISPLAY_NAME_MAX_LENGTH
    const val ROOM_NAME_MAX = Validation.ROOM_NAME_MAX_LENGTH
    const val REPORT_DETAILS_MAX = 280

    /** Typed word the user must enter to delete the account (api-contract.md: body `confirm` = "DELETE"). */
    const val DELETE_CONFIRMATION_WORD = "DELETE"

    fun displayName(raw: String): FieldCheck = Validation.displayName(raw).toFieldCheck()

    fun roomName(raw: String): FieldCheck = Validation.roomName(raw).toFieldCheck()

    /** Canonical `XXXX-XXXX-XXXX-XXXX` form, or null when [raw] cannot be a recovery code. */
    fun recoveryCode(raw: String): String? = when (val checked = Validation.recoveryCode(raw)) {
        is RuleResult.Ok -> Validation.formatRecoveryCode(checked.value)
        is RuleResult.Refused -> null
    }

    /** True only when the user typed the confirmation word exactly (case-sensitive, no padding). */
    fun isDeleteConfirmed(typed: String): Boolean = typed == DELETE_CONFIRMATION_WORD

    /** Trimmed report details, or null when empty. Longer than [REPORT_DETAILS_MAX] is cut by the field itself. */
    fun reportDetails(raw: String): String? = raw.trim().ifEmpty { null }
}

/** Reads an invitation token from pasted text: the `photoduel://invite/{token}` link or the bare token. */
object InvitationInput {
    private const val PREFIX = "photoduel://invite/"
    private val TOKEN = Regex("[A-Za-z0-9_-]{20,128}")

    fun tokenFrom(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 2048) return null
        val candidate = if (text.startsWith(PREFIX, ignoreCase = true)) {
            text.substring(PREFIX.length).substringBefore('?').substringBefore('#').trimEnd('/')
        } else {
            text
        }
        return candidate.takeIf { TOKEN.matches(it) }
    }

    fun urlFor(token: String): String = PREFIX + token
}

/** Duration choices offered when creating a challenge. They must equal the frozen allowed values. */
object ChallengeDurations {
    val SUBMISSION_HOURS: List<Int> = listOf(1, 6, 24, 48, 72)
    val VOTING_HOURS: List<Int> = listOf(1, 6, 24)
    const val DEFAULT_SUBMISSION_HOURS: Int = 24
    const val DEFAULT_VOTING_HOURS: Int = 6
}
