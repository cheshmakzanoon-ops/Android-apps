package com.agency.stickerjet.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class StickerFormatTest {

    @Test
    fun seconds_use_one_decimal_place_and_a_dot_regardless_of_default_locale() {
        assertEquals("2.0", StickerFormat.seconds(2_000))
        assertEquals("0.5", StickerFormat.seconds(500))
        assertEquals("3.0", StickerFormat.seconds(3_000))
    }

    @Test
    fun kilobytes_round_up_so_a_file_is_never_shown_smaller_than_it_is() {
        assertEquals("2", StickerFormat.kilobytes(2_048))
        assertEquals("3", StickerFormat.kilobytes(2_049))
        assertEquals("1", StickerFormat.kilobytes(1))
    }

    @Test
    fun factor_keeps_one_decimal_place() {
        assertEquals("1.5", StickerFormat.factor(1.5f))
        assertEquals("1.0", StickerFormat.factor(1f))
    }
}
