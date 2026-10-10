package com.agency.stickerjet.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptionPolicyTest {

    private val max = StickerLimits.MAX_CAPTION_CHARS

    @Test
    fun trimsAndCollapsesWhitespace() {
        assertEquals("hello world", CaptionPolicy.normalize("  hello    world  "))
        assertEquals("a b c", CaptionPolicy.normalize("a\tb\n\nc"))
    }

    @Test
    fun nonBreakingAndOtherUnicodeSpacesCollapseLikeAsciiSpaces() {
        assertEquals("a b", CaptionPolicy.normalize("a  b"))
        assertEquals("a b", CaptionPolicy.normalize("a 　b"))
    }

    @Test
    fun blankInputGivesAnEmptyCaption() {
        assertEquals("", CaptionPolicy.normalize(""))
        assertEquals("", CaptionPolicy.normalize(" \t\n "))
    }

    @Test
    fun textAtExactlyTheLimitIsKept() {
        val text = "x".repeat(max)
        assertEquals(text, CaptionPolicy.normalize(text))
    }

    @Test
    fun textOneCharacterOverTheLimitIsCut() {
        assertEquals("x".repeat(max), CaptionPolicy.normalize("x".repeat(max + 1)))
    }

    @Test
    fun whitespaceIsRemovedBeforeCountingSoPaddingDoesNotShortenTheText() {
        assertEquals("x".repeat(max), CaptionPolicy.normalize("   " + "x".repeat(max) + "   "))
    }

    @Test
    fun truncationRemovesASpaceThatBecomesTrailing() {
        // 39 letters, a space, then more text: the cut lands right after the space.
        assertEquals("a".repeat(39), CaptionPolicy.normalize("a".repeat(39) + " bcdef"))
    }

    @Test
    fun truncationCountsCodePointsAndNeverSplitsASurrogatePair() {
        val grinningFace = "😀"
        val result = CaptionPolicy.normalize(grinningFace.repeat(max + 10))
        assertEquals(grinningFace.repeat(max), result)
        assertEquals(max, result.codePointCount(0, result.length))
        assertEquals(max * 2, result.length)
    }

    @Test
    fun mixedScriptsAreKeptAsWritten() {
        assertEquals("héllo wörld 日本語", CaptionPolicy.normalize("  héllo   wörld 日本語  "))
    }

    @Test
    fun normalizeIsIdempotent() {
        val once = CaptionPolicy.normalize("  one   two\t" + "x".repeat(60))
        assertEquals(once, CaptionPolicy.normalize(once))
        assertTrue(once.codePointCount(0, once.length) <= max)
    }
}
