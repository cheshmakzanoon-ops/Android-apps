package com.agency.stickerjet.ui.common

import java.util.Locale

/**
 * Number formatting for labels. Uses [Locale.ROOT] so the digits and decimal point are stable across
 * tests; the surrounding words come from string resources and are localised there.
 */
object StickerFormat {
    /** Milliseconds as seconds with one decimal place, for example 2000 becomes "2.0". */
    fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1f", ms / 1000.0)

    /** Bytes as whole kilobytes, rounded up so a file is never shown smaller than it is. */
    fun kilobytes(bytes: Long): String = ((bytes + 1023L) / 1024L).toString()

    /** Scale or zoom factor with one decimal place, for example 1.5 becomes "1.5". */
    fun factor(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
}
