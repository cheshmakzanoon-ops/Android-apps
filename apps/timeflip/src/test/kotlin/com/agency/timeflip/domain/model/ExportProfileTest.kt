package com.agency.timeflip.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportProfileTest {

    @Test
    fun presetFramesMatchOrientation() {
        assertEquals(PixelSize(720, 1280), OutputPreset.PORTRAIT_720.frame)
        assertEquals(PixelSize(1080, 1080), OutputPreset.SQUARE_1080.frame)
        assertEquals(PixelSize(1920, 1080), OutputPreset.LANDSCAPE_1080.frame)
        assertTrue(OutputPreset.PORTRAIT_1080.frame.height > OutputPreset.PORTRAIT_1080.frame.width)
    }

    @Test
    fun fallbackStepsDownOnlyWhereAHalfResolutionExists() {
        assertEquals(OutputPreset.PORTRAIT_720, OutputPreset.PORTRAIT_1080.fallback())
        assertNull(OutputPreset.PORTRAIT_720.fallback())
        assertNull(OutputPreset.LANDSCAPE_1080.fallback())
    }

    @Test
    fun bitrateIsClampedToSafeRange() {
        assertEquals(ExportProfile.MIN_BITRATE_BPS, ExportProfile(OutputPreset.PORTRAIT_720).bitrateBps)
        // 1080×1920 at 60 fps is 12_441_600 bps: inside the range, so the cap is not reached.
        assertEquals(12_441_600, ExportProfile(OutputPreset.PORTRAIT_1080, fps = 60).bitrateBps)
        val mid = ExportProfile(OutputPreset.PORTRAIT_1080, fps = 30).bitrateBps
        assertTrue(mid in ExportProfile.MIN_BITRATE_BPS..ExportProfile.MAX_BITRATE_BPS)
    }

    @Test
    fun unsupportedFrameRateIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ExportProfile(fps = 15) }
    }

    @Test
    fun requestRequiresMatchingTimelineAndTransition() {
        val pair = ImagePair(
            before = SourcePhoto(PhotoId("b"), PhotoSlot.BEFORE, PixelSize(100, 100), 1L, 0L),
            after = SourcePhoto(PhotoId("a"), PhotoSlot.AFTER, PixelSize(100, 100), 1L, 0L),
        )
        val transition = TransitionDefinition(TransitionKind.CROSSFADE, TransitionParameters(durationMs = 1200))
        assertThrows(IllegalArgumentException::class.java) {
            ExportRequest(
                projectId = ProjectId("p"),
                projectName = "p",
                pair = pair,
                alignment = AlignmentTransform.IDENTITY,
                transition = transition,
                timeline = TimelinePlan(transitionMs = 900),
                profile = ExportProfile(),
            )
        }
    }
}
