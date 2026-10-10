package com.agency.stickerjet.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrimPolicyTest {

    @Test
    fun defaultTrimIsTheFirstDefaultLengthOfALongSource() {
        assertEquals(TrimRange(0, 2_000), TrimPolicy.defaultTrim(30_000))
    }

    @Test
    fun defaultTrimOfAShortSourceIsTheWholeSource() {
        assertEquals(TrimRange(0, 1_200), TrimPolicy.defaultTrim(1_200))
    }

    @Test
    fun defaultTrimAtExactlyTheMinimumIsTheWholeSource() {
        assertEquals(TrimRange(0, 500), TrimPolicy.defaultTrim(500))
    }

    @Test
    fun sourceShorterThanTheMinimumYieldsTheFullSourceAndIsInvalid() {
        val trim = TrimPolicy.defaultTrim(499)
        assertEquals(TrimRange(0, 499), trim)
        assertFalse(TrimPolicy.isValid(trim, 499))
    }

    @Test
    fun emptyOrNegativeSourceYieldsAnEmptyRange() {
        assertEquals(TrimRange(0, 0), TrimPolicy.defaultTrim(0))
        assertEquals(TrimRange(0, 0), TrimPolicy.defaultTrim(-5))
    }

    @Test
    fun clampKeepsAValidWindowUnchanged() {
        val trim = TrimRange(4_000, 6_500)
        assertEquals(trim, TrimPolicy.clamp(trim, 10_000))
    }

    @Test
    fun clampRaisesATooShortWindowToTheMinimumLength() {
        assertEquals(TrimRange(1_000, 1_500), TrimPolicy.clamp(TrimRange(1_000, 1_100), 10_000))
    }

    @Test
    fun clampLowersATooLongWindowToTheMaximumLength() {
        assertEquals(TrimRange(1_000, 4_000), TrimPolicy.clamp(TrimRange(1_000, 9_000), 10_000))
    }

    @Test
    fun clampSlidesTheWindowBackSoItEndsAtTheSourceEnd() {
        assertEquals(TrimRange(8_900, 10_000), TrimPolicy.clamp(TrimRange(9_800, 10_900), 10_000))
    }

    @Test
    fun clampMovesANegativeStartToZero() {
        assertEquals(TrimRange(0, 1_000), TrimPolicy.clamp(TrimRange(-5_000, -4_000), 10_000))
    }

    @Test
    fun clampOfAnInvertedRangeGivesTheMinimumLength() {
        assertEquals(TrimRange(2_500, 3_000), TrimPolicy.clamp(TrimRange(2_500, 2_000), 10_000))
    }

    @Test
    fun clampOfAnOverflowingRangeDoesNotWrap() {
        assertEquals(TrimRange(0, 3_000), TrimPolicy.clamp(TrimRange(Long.MIN_VALUE, Long.MAX_VALUE), 30_000))
        assertEquals(TrimRange(0, 500), TrimPolicy.clamp(TrimRange(Long.MIN_VALUE, Long.MAX_VALUE), 500))
    }

    @Test
    fun clampOfAShortSourceReturnsTheFullSource() {
        assertEquals(TrimRange(0, 499), TrimPolicy.clamp(TrimRange(100, 200), 499))
    }

    @Test
    fun isValidAcceptsTheExactBoundaries() {
        assertTrue(TrimPolicy.isValid(TrimRange(0, 500), 500)) // exactly MIN, whole source
        assertTrue(TrimPolicy.isValid(TrimRange(0, 3_000), 30_000)) // exactly MAX
        assertTrue(TrimPolicy.isValid(TrimRange(27_000, 30_000), 30_000)) // ends exactly at the source end
    }

    @Test
    fun isValidRejectsOneMillisecondOutsideTheBoundaries() {
        assertFalse(TrimPolicy.isValid(TrimRange(0, 499), 30_000)) // below MIN
        assertFalse(TrimPolicy.isValid(TrimRange(0, 3_001), 30_000)) // above MAX
        assertFalse(TrimPolicy.isValid(TrimRange(27_001, 30_001), 30_000)) // runs past the source
        assertFalse(TrimPolicy.isValid(TrimRange(-1, 1_000), 30_000)) // starts before the source
    }

    @Test
    fun isValidRejectsEmptyAndInvertedAndOverflowingRanges() {
        assertFalse(TrimPolicy.isValid(TrimRange(1_000, 1_000), 30_000))
        assertFalse(TrimPolicy.isValid(TrimRange(2_000, 1_000), 30_000))
        assertFalse(TrimPolicy.isValid(TrimRange(5, Long.MIN_VALUE), 30_000))
        assertFalse(TrimPolicy.isValid(TrimRange(Long.MIN_VALUE, 1_000), 30_000))
    }

    @Test
    fun defaultTrimIsValidForEverySourceThatCanBeImported() {
        for (sourceMs in StickerLimits.MIN_STICKER_MS..StickerLimits.MAX_SOURCE_MS) {
            val trim = TrimPolicy.defaultTrim(sourceMs)
            assertTrue(TrimPolicy.isValid(trim, sourceMs), "default trim invalid for source $sourceMs ms")
        }
    }

    @Test
    fun clampIsIdempotentAndAlwaysValidForRandomInputs() {
        val random = Random(SEED)
        repeat(RUNS) {
            val sourceMs = random.nextLong(0L, 40_000L)
            val startMs = random.nextLong(-2_000L, 45_000L)
            val trim = TrimRange(startMs, startMs + random.nextLong(-1_000L, 6_000L))
            val once = TrimPolicy.clamp(trim, sourceMs)
            assertEquals(once, TrimPolicy.clamp(once, sourceMs), "not idempotent for $trim in $sourceMs")
            if (sourceMs >= StickerLimits.MIN_STICKER_MS) {
                assertTrue(TrimPolicy.isValid(once, sourceMs), "clamp($trim, $sourceMs) = $once is invalid")
            }
        }
    }

    private companion object {
        const val SEED = 20_261_010
        const val RUNS = 5_000
    }
}
