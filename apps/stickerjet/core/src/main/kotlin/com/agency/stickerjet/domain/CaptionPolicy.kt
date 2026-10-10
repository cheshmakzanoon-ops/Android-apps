package com.agency.stickerjet.domain

/** Text rules for the caption drawn over the animation. */
object CaptionPolicy {

    /**
     * Returns the caption as it will be drawn:
     * - leading and trailing whitespace removed,
     * - every run of whitespace (including non-breaking spaces) replaced by one ASCII space,
     * - at most [StickerLimits.MAX_CAPTION_CHARS] Unicode code points.
     *
     * Truncation counts code points, so it never splits a surrogate pair. Whitespace exposed at the new
     * end by truncation is removed too, so the result never ends with a space.
     */
    fun normalize(text: String): String {
        val collapsed = collapseWhitespace(text)
        if (collapsed.codePointCount(0, collapsed.length) <= StickerLimits.MAX_CAPTION_CHARS) return collapsed
        val end = collapsed.offsetByCodePoints(0, StickerLimits.MAX_CAPTION_CHARS)
        return collapsed.substring(0, end).trimEnd()
    }
}

/**
 * Trims [text] and replaces each run of whitespace with one ASCII space. Used by captions and titles,
 * so both follow the same whitespace rule.
 */
internal fun collapseWhitespace(text: String): String {
    val out = StringBuilder(text.length)
    var pendingSpace = false
    for (ch in text) {
        if (ch.isWhitespace()) {
            pendingSpace = true
        } else {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(ch)
        }
    }
    return out.toString()
}
