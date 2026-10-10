package com.agency.stickerjet.domain

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** File names for exported stickers. */
object StickerFileNamer {

    private const val PREFIX = "stickerjet-"
    private const val EXTENSION = ".webp"
    private const val FALLBACK_SLUG = "sticker"

    /** Longest slug kept. Titles can come from long file names, and the whole name must stay well below 255 bytes. */
    private const val MAX_SLUG_CHARS = 48

    /** Explicit UTC, so the name never depends on the device time zone. The formatter is immutable and thread-safe. */
    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    /**
     * Returns `stickerjet-<slug>-<yyyyMMdd-HHmmss>.webp`, where the time is [createdAtMs] in UTC.
     *
     * If the name is already in [existing], the function appends `-2`, `-3`, and so on before `.webp`,
     * and returns the first free name. Comparison ignores case, so names still collide on case-insensitive
     * storage.
     */
    fun fileName(title: String, createdAtMs: Long, existing: Set<String>): String {
        val stem = PREFIX + slug(title) + "-" + STAMP.format(Instant.ofEpochMilli(createdAtMs))
        val taken = existing.mapTo(HashSet(existing.size)) { it.lowercase(Locale.ROOT) }
        var candidate = stem + EXTENSION
        var suffix = 2
        while (candidate.lowercase(Locale.ROOT) in taken) {
            candidate = "$stem-$suffix$EXTENSION"
            suffix++
        }
        return candidate
    }

    /**
     * ASCII slug of [title]: lowercase letters and digits separated by single hyphens.
     * Accents are removed (`Café` becomes `cafe`). Any other character, including non-Latin
     * letters, acts as a separator. Leading and trailing hyphens are dropped, and the result is
     * truncated to [MAX_SLUG_CHARS]. An empty result becomes `sticker`.
     */
    internal fun slug(title: String): String {
        val out = StringBuilder()
        var pendingHyphen = false
        for (ch in Normalizer.normalize(title, Normalizer.Form.NFKD)) {
            val isAsciiLetterOrDigit = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'
            if (isAsciiLetterOrDigit) {
                if (pendingHyphen && out.isNotEmpty()) out.append('-')
                pendingHyphen = false
                out.append(ch.lowercaseChar())
            } else if (ch.category != CharCategory.NON_SPACING_MARK) {
                // Combining accents from NFKD are dropped, so they do not split a word.
                pendingHyphen = true
            }
        }
        return out.toString().take(MAX_SLUG_CHARS).trimEnd('-').ifEmpty { FALLBACK_SLUG }
    }
}
