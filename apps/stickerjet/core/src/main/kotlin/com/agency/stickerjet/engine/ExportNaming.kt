package com.agency.stickerjet.engine

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * File names for exported stickers: `stickerjet-<ascii-slug>-<yyyyMMdd-HHmmss>.webp`, with the timestamp in
 * UTC. A name already in [existing] gets `-2`, `-3`, and so on. This mirrors the domain `StickerFileNamer`
 * contract; once that object lands, callers should switch to it and this file can be removed.
 */
object ExportNaming {
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    fun fileName(title: String, createdAtMs: Long, existing: Set<String>): String {
        val base = "stickerjet-${slug(title)}-${STAMP.format(Instant.ofEpochMilli(createdAtMs))}"
        var candidate = "$base.webp"
        var suffix = 2
        while (candidate in existing) {
            candidate = "$base-$suffix.webp"
            suffix++
        }
        return candidate
    }

    /** Lowercase ASCII letters and digits joined by single hyphens, at most 32 characters, else "sticker". */
    internal fun slug(title: String): String {
        val words = title.lowercase(Locale.ROOT)
            .map { if (it in 'a'..'z' || it in '0'..'9') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
        val joined = words.joinToString("-").take(MAX_SLUG).trimEnd('-')
        return joined.ifEmpty { "sticker" }
    }

    private const val MAX_SLUG = 32
}
