package com.agency.stickerjet.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRotationTest {

    @Test
    fun missingOrUnparsableRotationIsZero() {
        assertEquals(0, MediaFrameSource.normalizeRotation(null))
        assertEquals(0, MediaFrameSource.normalizeRotation("abc"))
    }

    @Test
    fun quarterTurnsAreKeptClockwise() {
        assertEquals(90, MediaFrameSource.normalizeRotation("90"))
        assertEquals(180, MediaFrameSource.normalizeRotation("180"))
        assertEquals(270, MediaFrameSource.normalizeRotation("270"))
    }

    @Test
    fun negativeAndLargeValuesWrapIntoOneTurn() {
        assertEquals(270, MediaFrameSource.normalizeRotation("-90"))
        assertEquals(90, MediaFrameSource.normalizeRotation("450"))
        assertEquals(0, MediaFrameSource.normalizeRotation("360"))
    }

    @Test
    fun offQuarterValuesRoundToTheNearestQuarterTurn() {
        assertEquals(90, MediaFrameSource.normalizeRotation("80"))
        assertEquals(90, MediaFrameSource.normalizeRotation("100"))
        assertEquals(180, MediaFrameSource.normalizeRotation("140"))
    }
}
