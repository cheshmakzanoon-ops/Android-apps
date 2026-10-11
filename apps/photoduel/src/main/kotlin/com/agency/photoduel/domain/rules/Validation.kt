package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PromptCatalog
import java.text.Normalizer

/** Why a user-supplied value was refused. Every case maps to [ErrorCode.VALIDATION]. */
sealed class ValidationError(val field: String, override val message: String) : DomainRefusal {
    override val code: ErrorCode get() = ErrorCode.VALIDATION

    data class Blank(val fieldName: String) : ValidationError(fieldName, "$fieldName must not be empty.")

    data class TooLong(val fieldName: String, val maxLength: Int, val actualLength: Int) :
        ValidationError(fieldName, "$fieldName must be at most $maxLength characters.")

    /** The offending code point is kept for diagnostics; the message never echoes it. */
    data class IllegalCharacter(val fieldName: String, val codePoint: Int) :
        ValidationError(fieldName, "$fieldName contains a character that is not allowed.")

    data class UnsupportedSubmissionHours(val hours: Int) :
        ValidationError("submissionHours", "submissionHours must be one of ${Validation.SUBMISSION_HOURS.sorted()}.")

    data class UnsupportedVotingHours(val hours: Int) :
        ValidationError("votingHours", "votingHours must be one of ${Validation.VOTING_HOURS.sorted()}.")

    data class UnknownPrompt(val promptId: String) :
        ValidationError("promptId", "promptId is not in the prompt catalogue.")

    data class RecoveryCodeLength(val actualLength: Int) :
        ValidationError("recoveryCode", "recoveryCode must have ${Validation.RECOVERY_CODE_LENGTH} characters.")

    data class RecoveryCodeCharacter(val character: Char) :
        ValidationError("recoveryCode", "recoveryCode contains a character that is not allowed.")
}

/**
 * Input validation shared by the in-memory service and the UI (api-contract.md sections 2, 4 and 6).
 * Every validator returns a typed [ValidationError] and never throws for user input.
 */
object Validation {
    const val DISPLAY_NAME_MAX_LENGTH = 24
    const val ROOM_NAME_MAX_LENGTH = 40
    const val RECOVERY_CODE_LENGTH = 16

    val SUBMISSION_HOURS: Set<Int> = setOf(1, 6, 24, 48, 72)
    val VOTING_HOURS: Set<Int> = setOf(1, 6, 24)

    /** Crockford base32 alphabet: digits and upper-case letters without I, L, O and U. */
    const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Display name: 1 to 24 characters after trimming; letters, digits, the ASCII space and `- _ .` only.
     *
     * Choices (documented because the contract leaves them open):
     * - The input is NFC-normalized before checking, so "e" plus a combining accent and the precomposed
     *   letter are the same name. Length counts Unicode code points, not UTF-16 units.
     * - "Letters" and "digits" are Unicode letters and decimal digits, so names such as "José" pass.
     *   Emoji, symbols, control, format (including bidirectional overrides) and non-ASCII space
     *   characters are refused.
     *
     * @return the canonical (normalized and trimmed) name.
     */
    fun displayName(raw: String): RuleResult<String, ValidationError> {
        val field = "displayName"
        val value = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (value.isEmpty()) return RuleResult.Refused(ValidationError.Blank(field))
        val length = value.codePointCount(0, value.length)
        if (length > DISPLAY_NAME_MAX_LENGTH) {
            return RuleResult.Refused(ValidationError.TooLong(field, DISPLAY_NAME_MAX_LENGTH, length))
        }
        val bad = value.codePoints().filter { !isDisplayNameCodePoint(it) }.findFirst()
        if (bad.isPresent) return RuleResult.Refused(ValidationError.IllegalCharacter(field, bad.asInt))
        return RuleResult.Ok(value)
    }

    /**
     * Room name: 1 to 40 characters after trimming. The contract sets no character class, so any
     * printable text (emoji included) is accepted. Control characters, line and paragraph separators
     * and bidirectional override or isolate characters are refused because they can spoof how the
     * name looks to other members. Length counts Unicode code points after NFC normalization.
     *
     * @return the canonical (normalized and trimmed) name.
     */
    fun roomName(raw: String): RuleResult<String, ValidationError> {
        val field = "name"
        val value = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (value.isEmpty()) return RuleResult.Refused(ValidationError.Blank(field))
        val length = value.codePointCount(0, value.length)
        if (length > ROOM_NAME_MAX_LENGTH) {
            return RuleResult.Refused(ValidationError.TooLong(field, ROOM_NAME_MAX_LENGTH, length))
        }
        val bad = value.codePoints().filter { isForbiddenInRoomName(it) }.findFirst()
        if (bad.isPresent) return RuleResult.Refused(ValidationError.IllegalCharacter(field, bad.asInt))
        return RuleResult.Ok(value)
    }

    /**
     * Challenge spec: `submissionHours` in {1, 6, 24, 48, 72}, `votingHours` in {1, 6, 24} and a
     * `promptId` that exists in [catalog] (exact, case-sensitive match). Fields are checked in that order.
     */
    fun challengeSpec(spec: ChallengeSpec, catalog: PromptCatalog): RuleResult<ChallengeSpec, ValidationError> {
        if (spec.submissionHours !in SUBMISSION_HOURS) {
            return RuleResult.Refused(ValidationError.UnsupportedSubmissionHours(spec.submissionHours))
        }
        if (spec.votingHours !in VOTING_HOURS) {
            return RuleResult.Refused(ValidationError.UnsupportedVotingHours(spec.votingHours))
        }
        if (catalog.prompts.none { it.id == spec.promptId }) {
            return RuleResult.Refused(ValidationError.UnknownPrompt(spec.promptId))
        }
        return RuleResult.Ok(spec)
    }

    /**
     * Recovery code: Crockford base32, 16 symbols (80 bits), case-insensitive, hyphens optional and
     * ignored wherever they appear, surrounding whitespace trimmed. Following the Crockford decoding
     * rules, `I` and `L` are read as `1` and `O` as `0`, so a code typed from a screenshot still works;
     * `U` and every other character are refused.
     *
     * @return the canonical 16-symbol upper-case code without hyphens.
     */
    fun recoveryCode(raw: String): RuleResult<String, ValidationError> {
        val compact = StringBuilder(RECOVERY_CODE_LENGTH)
        for (ch in raw.trim()) {
            if (ch == '-') continue
            val canonical = when (val upper = ch.uppercaseChar()) {
                'I', 'L' -> '1'
                'O' -> '0'
                else -> upper
            }
            if (canonical !in CROCKFORD_ALPHABET || !ch.isAsciiLetterOrDigit()) {
                return RuleResult.Refused(ValidationError.RecoveryCodeCharacter(ch))
            }
            compact.append(canonical)
        }
        if (compact.length != RECOVERY_CODE_LENGTH) {
            return RuleResult.Refused(ValidationError.RecoveryCodeLength(compact.length))
        }
        return RuleResult.Ok(compact.toString())
    }

    /** Formats a canonical 16-symbol code as `XXXX-XXXX-XXXX-XXXX` for display. */
    fun formatRecoveryCode(canonical: String): String = canonical.chunked(4).joinToString("-")

    private fun isDisplayNameCodePoint(cp: Int): Boolean =
        Character.isLetter(cp) ||
            Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() ||
            cp == ' '.code || cp == '-'.code || cp == '_'.code || cp == '.'.code

    private fun isForbiddenInRoomName(cp: Int): Boolean =
        Character.getType(cp) == Character.CONTROL.toInt() ||
            Character.getType(cp) == Character.LINE_SEPARATOR.toInt() ||
            Character.getType(cp) == Character.PARAGRAPH_SEPARATOR.toInt() ||
            cp in 0x202A..0x202E || // bidirectional embeddings and overrides
            cp in 0x2066..0x2069 // bidirectional isolates

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z'
}
