package com.agency.stickerjet.domain

/**
 * Product limits shared by every layer. Changing a value is a product decision recorded in
 * docs/decisions.md; tests in this module pin the values so an accidental edit fails the build.
 */
object StickerLimits {
    /** Output sticker is a square canvas of this many pixels per side. */
    const val CANVAS_PX: Int = 512

    /** Frame rate of exported animations. */
    const val FPS: Int = 12

    /** Shortest and longest exportable sticker animation, in milliseconds. */
    const val MIN_STICKER_MS: Long = 500L
    const val MAX_STICKER_MS: Long = 3_000L

    /** Sticker length selected when a clip is first imported. */
    const val DEFAULT_STICKER_MS: Long = 2_000L

    /** Longest source clip accepted by import, and the largest source file accepted. */
    const val MAX_SOURCE_MS: Long = 30_000L
    const val MAX_SOURCE_BYTES: Long = 150L * 1024L * 1024L

    /** Maximum size of one exported animated WebP file. */
    const val MAX_EXPORT_BYTES: Long = 500L * 1024L

    /** Maximum caption length, after trimming whitespace. */
    const val MAX_CAPTION_CHARS: Int = 40
}
