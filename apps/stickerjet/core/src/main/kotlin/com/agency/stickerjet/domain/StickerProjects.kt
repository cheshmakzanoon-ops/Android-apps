package com.agency.stickerjet.domain

/** Factory and title rules for [StickerProject]. */
object StickerProjects {

    /** Title used when no usable name is left. */
    const val FALLBACK_TITLE: String = "Sticker"

    /**
     * A new project with the default trim from [TrimPolicy.defaultTrim], default transform, caption and
     * background, no strokes or exports, and both timestamps set to [nowMs].
     *
     * The title is [displayName] without its directory and extension, normalized by [normalizeTitle].
     * [displayName] is the name the user picked (OpenableColumns.DISPLAY_NAME), not [SourceVideo.fileName],
     * which names the stored copy and is always the same for every import.
     */
    fun create(id: String, source: SourceVideo, displayName: String, nowMs: Long): StickerProject {
        require(id.isNotBlank()) { "Project id must not be blank" }
        return StickerProject(
            id = id,
            title = normalizeTitle(stemOf(displayName)),
            source = source,
            trim = TrimPolicy.defaultTrim(source.durationMs),
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
    }

    /**
     * Collapses whitespace in [raw] and trims it. Returns [FALLBACK_TITLE] when nothing is left.
     * Unicode is kept as written, and titles are not length-capped.
     */
    fun normalizeTitle(raw: String): String = collapseWhitespace(raw).ifEmpty { FALLBACK_TITLE }

    /** `"clips/My clip.final.mp4"` gives `"My clip.final"`. A name that starts with a dot gives an empty stem. */
    private fun stemOf(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        return name.substringBeforeLast('.', missingDelimiterValue = name)
    }
}
