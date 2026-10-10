package com.agency.stickerjet.ui.editor

import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.TrimPolicy
import com.agency.stickerjet.domain.TrimRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Handle rules for the trim sliders. Every result must be a valid window inside the source. */
class TrimEditorTest {

    private val source = 10_000L

    @Test
    fun start_handle_moves_freely_inside_the_length_limits() {
        assertEquals(TrimRange(1_000, 2_000), TrimEditor.moveStart(TrimRange(0, 2_000), 1_000, source))
    }

    @Test
    fun start_handle_cannot_cross_the_minimum_length() {
        // Ending at 2000 with a 500 ms minimum: the latest start is 1500.
        assertEquals(TrimRange(1_500, 2_000), TrimEditor.moveStart(TrimRange(0, 2_000), 1_900, source))
    }

    @Test
    fun start_handle_cannot_stretch_past_the_maximum_length() {
        // Ending at 5000 with a 3000 ms maximum: the earliest start is 2000.
        assertEquals(TrimRange(2_000, 5_000), TrimEditor.moveStart(TrimRange(4_000, 5_000), 0, source))
    }

    @Test
    fun end_handle_respects_both_limits_and_the_source_end() {
        assertEquals(TrimRange(1_000, 4_000), TrimEditor.moveEnd(TrimRange(1_000, 2_000), 9_000, source))
        assertEquals(TrimRange(1_000, 1_500), TrimEditor.moveEnd(TrimRange(1_000, 2_000), 1_100, source))
    }

    @Test
    fun every_result_is_a_valid_window_for_a_grid_of_requests() {
        val bases = listOf(TrimRange(0, 500), TrimRange(0, 2_000), TrimRange(4_000, 7_000), TrimRange(9_000, 9_500))
        for (base in bases) {
            for (requested in -1_000L..11_000L step 137L) {
                val byStart = TrimEditor.moveStart(base, requested, source)
                val byEnd = TrimEditor.moveEnd(base, requested, source)
                assertTrue("start $requested from $base gave $byStart", TrimPolicy.isValid(byStart, source))
                assertTrue("end $requested from $base gave $byEnd", TrimPolicy.isValid(byEnd, source))
            }
        }
    }

    @Test
    fun slider_ranges_leave_room_for_a_minimum_length_window() {
        assertEquals(0f, TrimEditor.startRange(source).start, 0f)
        assertEquals((source - StickerLimits.MIN_STICKER_MS).toFloat(), TrimEditor.startRange(source).endInclusive, 0f)
        assertEquals(StickerLimits.MIN_STICKER_MS.toFloat(), TrimEditor.endRange(source).start, 0f)
        assertEquals(source.toFloat(), TrimEditor.endRange(source).endInclusive, 0f)
    }
}
