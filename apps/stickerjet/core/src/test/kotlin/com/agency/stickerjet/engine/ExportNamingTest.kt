package com.agency.stickerjet.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExportNamingTest {

    @Test
    fun namesUseAsciiSlugAndUtcStamp() {
        // 1 Jan 1970 00:00:00 UTC is epoch zero.
        assertEquals("stickerjet-my-cat-19700101-000000.webp", ExportNaming.fileName("My Cat!", 0L, emptySet()))
    }

    @Test
    fun emptyOrNonAsciiTitlesFallBackToSticker() {
        assertEquals("stickerjet-sticker-19700101-000000.webp", ExportNaming.fileName("   ", 0L, emptySet()))
        assertEquals("stickerjet-sticker-19700101-000000.webp", ExportNaming.fileName("猫", 0L, emptySet()))
    }

    @Test
    fun collisionsGetNumericSuffixes() {
        val first = "stickerjet-cat-19700101-000000.webp"
        assertEquals("stickerjet-cat-19700101-000000-2.webp", ExportNaming.fileName("cat", 0L, setOf(first)))
        assertEquals(
            "stickerjet-cat-19700101-000000-3.webp",
            ExportNaming.fileName("cat", 0L, setOf(first, "stickerjet-cat-19700101-000000-2.webp")),
        )
    }

    @Test
    fun slugIsCappedAndNeverEndsWithAHyphen() {
        val slug = ExportNaming.slug("a very long title that goes on and on and on forever")
        assertTrue(slug.length <= 32, "slug is $slug")
        assertTrue(!slug.endsWith("-"))
    }
}
