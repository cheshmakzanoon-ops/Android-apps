package com.agency.photoduel.ui.state

import com.agency.photoduel.domain.rules.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputsTest {

    @Test
    fun displayNameIsTrimmedAndChecked() {
        assertEquals(FieldCheck("Ada", null), Inputs.displayName("  Ada  "))
        assertTrue(Inputs.displayName("José_2.0").isValid)
        assertEquals(NameProblem.EMPTY, Inputs.displayName("   ").problem)
        assertEquals(NameProblem.TOO_LONG, Inputs.displayName("x".repeat(Inputs.DISPLAY_NAME_MAX + 1)).problem)
        assertTrue(Inputs.displayName("x".repeat(Inputs.DISPLAY_NAME_MAX)).isValid)
        assertEquals(NameProblem.ILLEGAL_CHARACTER, Inputs.displayName("Ada 😀").problem)
        assertEquals(NameProblem.ILLEGAL_CHARACTER, Inputs.displayName("Ada!").problem)
        assertFalse(Inputs.displayName("").isValid)
    }

    @Test
    fun roomNameAllowsEmojiButNotControlCharacters() {
        assertEquals("Sunday walkers", Inputs.roomName(" Sunday walkers ").clean)
        assertTrue(Inputs.roomName("Walkers 📷").isValid)
        assertEquals(NameProblem.EMPTY, Inputs.roomName("").problem)
        assertEquals(NameProblem.TOO_LONG, Inputs.roomName("r".repeat(Inputs.ROOM_NAME_MAX + 1)).problem)
        assertEquals(NameProblem.ILLEGAL_CHARACTER, Inputs.roomName("a‮b").problem)
    }

    @Test
    fun limitsMatchTheDomainRules() {
        assertEquals(Validation.DISPLAY_NAME_MAX_LENGTH, Inputs.DISPLAY_NAME_MAX)
        assertEquals(Validation.ROOM_NAME_MAX_LENGTH, Inputs.ROOM_NAME_MAX)
        assertEquals(280, Inputs.REPORT_DETAILS_MAX)
    }

    @Test
    fun recoveryCodeIsCanonicalised() {
        assertEquals("ABCD-EFGH-JKMN-PQRS", Inputs.recoveryCode("abcd efgh".replace(" ", "") + "-jkmn-pqrs"))
        assertEquals("ABCD-EFGH-JKMN-PQRS", Inputs.recoveryCode("  ABCDEFGHJKMNPQRS "))
        // Crockford reading: I and L are 1, O is 0.
        assertEquals("1100-0000-0000-0000", Inputs.recoveryCode("IL0O000000000000"))
        assertNull(Inputs.recoveryCode("ABCD-EFGH-JKMN-PQR"))
        assertNull(Inputs.recoveryCode("ABCD-EFGH-JKMN-PQRU"))
        assertNull(Inputs.recoveryCode(""))
    }

    @Test
    fun deleteNeedsTheExactWord() {
        assertTrue(Inputs.isDeleteConfirmed("DELETE"))
        assertFalse(Inputs.isDeleteConfirmed("delete"))
        assertFalse(Inputs.isDeleteConfirmed(" DELETE"))
        assertFalse(Inputs.isDeleteConfirmed(""))
    }

    @Test
    fun reportDetailsAreTrimmedOrDropped() {
        assertNull(Inputs.reportDetails("   "))
        assertEquals("blurry", Inputs.reportDetails("  blurry \n"))
    }

    @Test
    fun invitationTokensAreReadFromLinksAndBareText() {
        val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd"
        assertEquals(token, InvitationInput.tokenFrom("photoduel://invite/$token"))
        assertEquals(token, InvitationInput.tokenFrom("  PHOTODUEL://INVITE/$token/  "))
        assertEquals(token, InvitationInput.tokenFrom("photoduel://invite/$token?utm=x#frag"))
        assertEquals(token, InvitationInput.tokenFrom(token))
    }

    @Test
    fun textThatIsNotAnInvitationGivesNoToken() {
        assertNull(InvitationInput.tokenFrom(""))
        assertNull(InvitationInput.tokenFrom("hello"))
        assertNull(InvitationInput.tokenFrom("https://example.invalid/invite/abcdefghijklmnopqrstuvwxyz"))
        assertNull(InvitationInput.tokenFrom("photoduel://invite/short"))
        assertNull(InvitationInput.tokenFrom("photoduel://invite/abc def ghi jkl mno pqr stu"))
        assertNull(InvitationInput.tokenFrom("x".repeat(3000)))
    }

    @Test
    fun invitationUrlUsesTheCustomScheme() {
        assertEquals("photoduel://invite/abc", InvitationInput.urlFor("abc"))
    }

    @Test
    fun durationChoicesEqualTheAllowedValues() {
        assertEquals(Validation.SUBMISSION_HOURS, ChallengeDurations.SUBMISSION_HOURS.toSet())
        assertEquals(Validation.VOTING_HOURS, ChallengeDurations.VOTING_HOURS.toSet())
        assertTrue(ChallengeDurations.DEFAULT_SUBMISSION_HOURS in ChallengeDurations.SUBMISSION_HOURS)
        assertTrue(ChallengeDurations.DEFAULT_VOTING_HOURS in ChallengeDurations.VOTING_HOURS)
        assertEquals(ChallengeDurations.SUBMISSION_HOURS.sorted(), ChallengeDurations.SUBMISSION_HOURS)
        assertEquals(ChallengeDurations.VOTING_HOURS.sorted(), ChallengeDurations.VOTING_HOURS)
    }
}
