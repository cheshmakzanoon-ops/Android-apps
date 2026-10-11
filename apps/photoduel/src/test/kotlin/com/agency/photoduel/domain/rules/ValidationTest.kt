package com.agency.photoduel.domain.rules

import com.agency.photoduel.domain.model.ChallengeSpec
import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.domain.model.PromptCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    // ---- Display name ------------------------------------------------------------------------------

    @Test
    fun displayNameAcceptsLettersDigitsSpaceAndPunctuationSet() {
        for (name in listOf("Ada", "a", "Ada Lovelace", "ada_99", "A.B-C_D", "José", "Zoë", "李雷", "123")) {
            assertEquals(name, RuleResult.Ok(name), Validation.displayName(name))
        }
    }

    @Test
    fun displayNameIsTrimmedAndNormalized() {
        assertEquals(RuleResult.Ok("Ada"), Validation.displayName("  Ada \t"))
        assertEquals(RuleResult.Ok("José"), Validation.displayName("José"))
    }

    @Test
    fun displayNameLengthBoundaries() {
        val max = "a".repeat(24)
        assertEquals(RuleResult.Ok(max), Validation.displayName(max))
        assertEquals(RuleResult.Ok(max), Validation.displayName("   $max   "))
        assertEquals(ValidationError.TooLong("displayName", 24, 25), Validation.displayName("a".repeat(25)).refusalOrNull())
        assertEquals(ValidationError.Blank("displayName"), Validation.displayName("").refusalOrNull())
        assertEquals(ValidationError.Blank("displayName"), Validation.displayName("   ").refusalOrNull())
    }

    @Test
    fun displayNameRefusesEmojiSymbolsAndControlCharacters() {
        for (name in listOf("Ada!", "Ada😀", "a\u0000b", "a\tb", "a\nb", "Ada‮", "a b", "a@b", "a/b", "a‍b")) {
            val reason = Validation.displayName(name).refusalOrNull()
            assertTrue(name, reason is ValidationError.IllegalCharacter)
            assertEquals(ErrorCode.VALIDATION, reason!!.code)
        }
    }

    // ---- Room name ---------------------------------------------------------------------------------

    @Test
    fun roomNameLengthBoundaries() {
        val max = "r".repeat(40)
        assertEquals(RuleResult.Ok(max), Validation.roomName(" $max "))
        assertEquals(RuleResult.Ok("x"), Validation.roomName("x"))
        assertEquals(ValidationError.TooLong("name", 40, 41), Validation.roomName("r".repeat(41)).refusalOrNull())
        assertEquals(ValidationError.Blank("name"), Validation.roomName(" \n ").refusalOrNull())
    }

    @Test
    fun roomNameAllowsPrintableTextButRefusesControlAndBidiCharacters() {
        assertEquals(RuleResult.Ok("Sunday walkers 🌞!"), Validation.roomName("Sunday walkers 🌞!"))
        for (name in listOf("a\u0007b", "a\nb", "a b", "evil‮txt", "a⁦b")) {
            assertTrue(name, Validation.roomName(name).refusalOrNull() is ValidationError.IllegalCharacter)
        }
    }

    @Test
    fun roomNameCountsCodePointsNotUtf16Units() {
        val emoji = "😀"
        assertEquals(RuleResult.Ok(emoji.repeat(40)), Validation.roomName(emoji.repeat(40)))
        assertEquals(ValidationError.TooLong("name", 40, 41), Validation.roomName(emoji.repeat(41)).refusalOrNull())
    }

    // ---- Challenge spec ----------------------------------------------------------------------------

    private val catalog = PromptCatalog("1.0.0", listOf(Prompt("p-001", "Light", "Find light."), Prompt("p-002", "Red", "Red.")))

    @Test
    fun everyAllowedWindowCombinationIsValid() {
        for (submission in listOf(1, 6, 24, 48, 72)) {
            for (voting in listOf(1, 6, 24)) {
                val spec = ChallengeSpec("p-002", submission, voting)
                assertEquals(RuleResult.Ok(spec), Validation.challengeSpec(spec, catalog))
            }
        }
    }

    @Test
    fun submissionHoursOutsideTheOptionsAreRefused() {
        for (hours in listOf(0, -1, 2, 12, 25, 47, 73, 96, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(
                ValidationError.UnsupportedSubmissionHours(hours),
                Validation.challengeSpec(ChallengeSpec("p-001", hours, 6), catalog).refusalOrNull(),
            )
        }
    }

    @Test
    fun votingHoursOutsideTheOptionsAreRefused() {
        for (hours in listOf(0, -6, 2, 12, 48, 72)) {
            assertEquals(
                ValidationError.UnsupportedVotingHours(hours),
                Validation.challengeSpec(ChallengeSpec("p-001", 24, hours), catalog).refusalOrNull(),
            )
        }
    }

    @Test
    fun promptMustExistInTheCatalogue() {
        for (id in listOf("p-999", "P-001", "", " p-001")) {
            assertEquals(
                ValidationError.UnknownPrompt(id),
                Validation.challengeSpec(ChallengeSpec(id, 24, 6), catalog).refusalOrNull(),
            )
        }
        assertEquals(ErrorCode.VALIDATION, Validation.challengeSpec(ChallengeSpec("nope", 24, 6), catalog).refusalOrNull()!!.code)
    }

    @Test
    fun specFieldsAreCheckedInOrder() {
        assertTrue(Validation.challengeSpec(ChallengeSpec("nope", 5, 5), catalog).refusalOrNull() is ValidationError.UnsupportedSubmissionHours)
        assertTrue(Validation.challengeSpec(ChallengeSpec("nope", 24, 5), catalog).refusalOrNull() is ValidationError.UnsupportedVotingHours)
    }

    // ---- Recovery code -----------------------------------------------------------------------------

    @Test
    fun recoveryCodeAcceptsHyphensAndAnyCase() {
        val canonical = "ABCDEFGHJKMNPQRS"
        for (raw in listOf("ABCD-EFGH-JKMN-PQRS", "abcd-efgh-jkmn-pqrs", "ABCDEFGHJKMNPQRS", "AbCd-EfGhJkMn-PqRs", " ABCD-EFGH-JKMN-PQRS ", "A-B-C-D-EFGHJKMNPQRS")) {
            assertEquals(raw, RuleResult.Ok(canonical), Validation.recoveryCode(raw))
        }
        assertEquals("ABCD-EFGH-JKMN-PQRS", Validation.formatRecoveryCode(canonical))
    }

    @Test
    fun recoveryCodeReadsCrockfordAliases() {
        assertEquals(RuleResult.Ok("0110000000000000"), Validation.recoveryCode("OIL0-0000-0000-0000"))
        assertEquals(RuleResult.Ok("0111000000000000"), Validation.recoveryCode("oiLl-0000-0000-0000"))
    }

    @Test
    fun recoveryCodeRefusesWrongLengthAndCharacters() {
        assertEquals(ValidationError.RecoveryCodeLength(15), Validation.recoveryCode("ABCD-EFGH-JKMN-PQR").refusalOrNull())
        assertEquals(ValidationError.RecoveryCodeLength(17), Validation.recoveryCode("ABCD-EFGH-JKMN-PQRST").refusalOrNull())
        assertEquals(ValidationError.RecoveryCodeLength(0), Validation.recoveryCode("").refusalOrNull())
        assertEquals(ValidationError.RecoveryCodeLength(0), Validation.recoveryCode("----").refusalOrNull())
        for (raw in listOf("ABCD-EFGH-JKMN-PQRU", "ABCD EFGH JKMN PQRS", "ABCD_EFGH_JKMN_PQRS", "ABCD-EFGH-JKMN-PQRı", "ABCD-EFGH-JKMN-PQR!")) {
            val reason = Validation.recoveryCode(raw).refusalOrNull()
            assertTrue(raw, reason is ValidationError.RecoveryCodeCharacter)
            assertEquals(ErrorCode.VALIDATION, reason!!.code)
        }
    }

    @Test
    fun validatorsNeverThrowOnHostileInput() {
        val hostile = listOf("", " ", "\u0000", "\uD800", "\uDFFF\uD800", "a".repeat(10_000), "‮".repeat(50))
        for (raw in hostile) {
            Validation.displayName(raw)
            Validation.roomName(raw)
            Validation.recoveryCode(raw)
            Validation.challengeSpec(ChallengeSpec(raw, 24, 6), catalog)
        }
    }
}
