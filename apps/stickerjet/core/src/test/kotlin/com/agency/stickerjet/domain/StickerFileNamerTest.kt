package com.agency.stickerjet.domain

import java.util.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerFileNamerTest {

    /** 2026-10-10T12:34:56Z */
    private val createdAtMs = 1_791_635_696_000L

    private val base = "stickerjet-my-clip-20261010-123456"

    @Test
    fun namesAreSlugTimestampAndExtension() {
        assertEquals("$base.webp", StickerFileNamer.fileName("My Clip", createdAtMs, emptySet()))
    }

    @Test
    fun timestampIsUtcAtMidnight() {
        assertEquals(
            "stickerjet-clip-20261010-000000.webp",
            StickerFileNamer.fileName("clip", 1_791_590_400_000L, emptySet()),
        )
    }

    @Test
    fun timestampDoesNotDependOnTheDeviceTimeZone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati")) // UTC+14
            assertEquals("$base.webp", StickerFileNamer.fileName("My Clip", createdAtMs, emptySet()))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles")) // UTC-7 in October
            assertEquals("$base.webp", StickerFileNamer.fileName("My Clip", createdAtMs, emptySet()))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun emptyAndBlankTitlesUseTheStickerFallback() {
        val expected = "stickerjet-sticker-20261010-123456.webp"
        for (title in listOf("", "   ", "\t\n", "!!!", "---", "日本語", "🎉🎉")) {
            assertEquals(expected, StickerFileNamer.fileName(title, createdAtMs, emptySet()), "title '$title'")
        }
    }

    @Test
    fun unicodeTitlesAreAsciiSlugged() {
        assertEquals(
            "stickerjet-cafe-unicode-20261010-123456.webp",
            StickerFileNamer.fileName("Café Ünïcödé 🎉", createdAtMs, emptySet()),
        )
        assertEquals(
            "stickerjet-istanbul-20261010-123456.webp",
            StickerFileNamer.fileName("İstanbul", createdAtMs, emptySet()),
        )
    }

    @Test
    fun separatorsAndPunctuationCollapseToSingleHyphens() {
        assertEquals("stickerjet-hello-world-20261010-123456.webp", StickerFileNamer.fileName("--Hello,  World!!", createdAtMs, emptySet()))
    }

    @Test
    fun longTitlesAreTruncatedSoTheNameStaysShort() {
        val name = StickerFileNamer.fileName("a".repeat(200), createdAtMs, emptySet())
        assertEquals("stickerjet-${"a".repeat(48)}-20261010-123456.webp", name)
    }

    @Test
    fun duplicateNamesGetTheNextFreeSuffixBeforeTheExtension() {
        assertEquals("$base-2.webp", StickerFileNamer.fileName("My Clip", createdAtMs, setOf("$base.webp")))
        assertEquals("$base-3.webp", StickerFileNamer.fileName("My Clip", createdAtMs, setOf("$base.webp", "$base-2.webp")))
    }

    @Test
    fun aGapInTheSuffixesIsNotFilledAheadOfTheBaseName() {
        assertEquals("$base.webp", StickerFileNamer.fileName("My Clip", createdAtMs, setOf("$base-2.webp")))
    }

    @Test
    fun unrelatedExistingNamesDoNotChangeTheResult() {
        val existing = setOf("stickerjet-other-20261010-123456.webp", "notes.txt")
        assertEquals("$base.webp", StickerFileNamer.fileName("My Clip", createdAtMs, existing))
    }

    @Test
    fun collisionsIgnoreCase() {
        assertEquals("$base-2.webp", StickerFileNamer.fileName("My Clip", createdAtMs, setOf("STICKERJET-MY-CLIP-20261010-123456.WEBP")))
    }

    @Test
    fun everyGeneratedNameIsAsciiAndWellFormedForRandomTitles() {
        val shape = Regex("stickerjet-[a-z0-9]+(-[a-z0-9]+)*-\\d{8}-\\d{6}(-\\d+)?\\.webp")
        val pool = "abcXYZ 0129-_.,!?éüß日🎉\t"
        val random = Random(SEED)
        val existing = mutableSetOf<String>()
        repeat(RUNS) {
            val title = buildString { repeat(random.nextInt(0, 30)) { append(pool[random.nextInt(pool.length)]) } }
            val name = StickerFileNamer.fileName(title, createdAtMs + random.nextLong(0L, 86_400_000L), existing)
            assertTrue(shape.matches(name), "malformed name '$name' for title '$title'")
            assertTrue(existing.add(name.lowercase()), "name '$name' reused")
        }
    }

    private companion object {
        const val SEED = 20_261_010
        const val RUNS = 500
    }
}
