package com.agency.stickerjet.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StickerProjectsTest {

    private fun source(fileName: String = "My clip.mp4", durationMs: Long = 10_000L) =
        SourceVideo(fileName = fileName, durationMs = durationMs, width = 640, height = 480, rotationDegrees = 0, sizeBytes = 1_000L)

    private fun project(durationMs: Long = 10_000L) =
        StickerProjects.create("p1", source(durationMs = durationMs), displayName = "My clip.mp4", nowMs = 100L)

    private fun titleOf(displayName: String) = StickerProjects.create("p", source(), displayName, 1L).title

    @Test
    fun createSetsDefaultsFromTheSourceAndTheClock() {
        val created = StickerProjects.create("p1", source(), displayName = "My clip.mp4", nowMs = 100L)
        assertEquals("p1", created.id)
        assertEquals("My clip", created.title)
        assertEquals(TrimRange(0, 2_000), created.trim)
        assertEquals(Transform(), created.transform)
        assertEquals(Caption(), created.caption)
        assertEquals(BackgroundMode.ORIGINAL, created.background)
        assertTrue(created.maskStrokes.isEmpty())
        assertTrue(created.exports.isEmpty())
        assertEquals(100L, created.createdAtMs)
        assertEquals(100L, created.updatedAtMs)
    }

    @Test
    fun createFromAShortSourceGivesTheFullSourceAsTrim() {
        assertEquals(TrimRange(0, 300), StickerProjects.create("p1", source(durationMs = 300L), "short.mp4", 1L).trim)
    }

    @Test
    fun titleIsTheDisplayNameWithoutItsExtension() {
        assertEquals("My clip.final", titleOf("My clip.final.mp4"))
        assertEquals("a.b.c", titleOf("a.b.c.mov"))
        assertEquals("No extension", titleOf("No extension"))
    }

    @Test
    fun titleComesFromTheDisplayNameNotTheStoredFileName() {
        val created = StickerProjects.create("p", source(fileName = "source.mp4"), displayName = "Birthday party.mp4", nowMs = 1L)
        assertEquals("Birthday party", created.title)
        assertEquals("source.mp4", created.source.fileName)
    }

    @Test
    fun titleFallsBackToStickerWhenTheNameHasNoUsableStem() {
        for (name in listOf("", ".mp4", "   .mp4", "\t.mov")) {
            assertEquals("Sticker", titleOf(name), "display name '$name'")
        }
    }

    @Test
    fun titleKeepsUnicodeAndCollapsesWhitespace() {
        assertEquals("Vidéo 🎬 final", titleOf("Vidéo \t 🎬   final.mp4"))
        assertEquals("日本語", titleOf("日本語.mp4"))
    }

    @Test
    fun normalizeTitleHandlesEmptyAndBlankInput() {
        assertEquals("Sticker", StickerProjects.normalizeTitle(""))
        assertEquals("Sticker", StickerProjects.normalizeTitle(" \n\t "))
        assertEquals("A B", StickerProjects.normalizeTitle("  A   B  "))
    }

    @Test
    fun createRejectsABlankId() {
        assertFailsWith<IllegalArgumentException> { StickerProjects.create(" ", source(), "clip.mp4", 1L) }
    }

    @Test
    fun withTrimClampsTheWindowIntoTheSource() {
        val edited = project().withTrim(TrimRange(9_000, 9_100), nowMs = 5L)
        assertEquals(TrimRange(9_000, 9_500), edited.trim)
        assertEquals(5L, edited.updatedAtMs)
    }

    @Test
    fun withTrimOfAValidWindowKeepsItExactly() {
        assertEquals(TrimRange(1_000, 3_500), project().withTrim(TrimRange(1_000, 3_500), 2L).trim)
    }

    @Test
    fun editsLeaveTheOriginalProjectUnchanged() {
        val original = project()
        original.withTrim(TrimRange(1_000, 3_000), 2L).withTitle("Other", 3L)
        assertEquals(TrimRange(0, 2_000), original.trim)
        assertEquals("My clip", original.title)
        assertEquals(100L, original.updatedAtMs)
    }

    @Test
    fun withTransformNormalizesQuarterTurns() {
        val turns = mapOf(-1 to 3, 4 to 0, 5 to 1, -4 to 0, 2 to 2)
        for ((input, expected) in turns) {
            val edited = project().withTransform(Transform(scale = 1.5f, quarterTurns = input), 2L)
            assertEquals(expected, edited.transform.quarterTurns, "quarterTurns $input")
            assertEquals(1.5f, edited.transform.scale)
        }
    }

    @Test
    fun withTransformRejectsNonPositiveOrNonFiniteValues() {
        val bad = listOf(
            Transform(scale = 0f),
            Transform(scale = -1f),
            Transform(scale = Float.NaN),
            Transform(scale = Float.POSITIVE_INFINITY),
            Transform(offsetX = Float.NaN),
            Transform(offsetY = Float.NEGATIVE_INFINITY),
        )
        for (transform in bad) {
            assertFailsWith<IllegalArgumentException>("transform $transform") { project().withTransform(transform, 2L) }
        }
    }

    @Test
    fun withCaptionNormalizesTheTextAndKeepsStyle() {
        val edited = project().withCaption(Caption(text = "  hi    there ", position = CaptionPosition.TOP, argb = 0xFF00FF00L), 2L)
        assertEquals("hi there", edited.caption.text)
        assertEquals(CaptionPosition.TOP, edited.caption.position)
        assertEquals(0xFF00FF00L, edited.caption.argb)
    }

    @Test
    fun withCaptionAcceptsTheFullArgbRangeOnly() {
        assertEquals(0xFFFFFFFFL, project().withCaption(Caption(argb = 0xFFFFFFFFL), 2L).caption.argb)
        assertFailsWith<IllegalArgumentException> { project().withCaption(Caption(argb = -1L), 2L) }
        assertFailsWith<IllegalArgumentException> { project().withCaption(Caption(argb = 0x1_0000_0000L), 2L) }
    }

    @Test
    fun withCaptionCapsLongText() {
        val edited = project().withCaption(Caption(text = "x".repeat(60)), 2L)
        assertEquals("x".repeat(StickerLimits.MAX_CAPTION_CHARS), edited.caption.text)
    }

    @Test
    fun withBackgroundKeepsTheMaskStrokes() {
        val stroke = MaskStroke(StrokeMode.ERASE, listOf(NormalizedPoint(0.5f, 0.5f)), radius = 0.1f)
        val withStroke = project().withStroke(stroke, 2L).withBackground(BackgroundMode.REMOVE_BACKGROUND, 3L)
        assertEquals(BackgroundMode.REMOVE_BACKGROUND, withStroke.background)
        assertEquals(listOf(stroke), withStroke.maskStrokes)
    }

    @Test
    fun withStrokeAppendsInOrder() {
        val first = MaskStroke(StrokeMode.RESTORE, listOf(NormalizedPoint(0f, 0f), NormalizedPoint(1f, 1f)), 0.05f)
        val second = MaskStroke(StrokeMode.ERASE, listOf(NormalizedPoint(0.2f, 0.8f)), 0.2f)
        assertEquals(listOf(first, second), project().withStroke(first, 2L).withStroke(second, 3L).maskStrokes)
    }

    @Test
    fun withStrokeRejectsInvalidStrokes() {
        val valid = NormalizedPoint(0.5f, 0.5f)
        val bad = listOf(
            MaskStroke(StrokeMode.RESTORE, emptyList(), 0.1f),
            MaskStroke(StrokeMode.RESTORE, listOf(NormalizedPoint(1.5f, 0.5f)), 0.1f),
            MaskStroke(StrokeMode.RESTORE, listOf(NormalizedPoint(-0.1f, 0.5f)), 0.1f),
            MaskStroke(StrokeMode.RESTORE, listOf(NormalizedPoint(Float.NaN, 0.5f)), 0.1f),
            MaskStroke(StrokeMode.RESTORE, listOf(valid), 0f),
            MaskStroke(StrokeMode.RESTORE, listOf(valid), Float.NaN),
        )
        for (stroke in bad) {
            assertFailsWith<IllegalArgumentException>("stroke $stroke") { project().withStroke(stroke, 2L) }
        }
    }

    @Test
    fun withoutStrokesClearsStrokesAndKeepsEveryOtherFieldAndTheTimestamp() {
        val stroke = MaskStroke(StrokeMode.ERASE, listOf(NormalizedPoint(0.5f, 0.5f)), radius = 0.1f)
        val exported = ExportRecord(
            fileName = "stickerjet-clip-20261010-123456.webp",
            createdAtMs = 5L,
            sizeBytes = 1_000L,
            frameCount = 24,
            durationMs = 2_000L,
        )
        val original = project()
            .withTitle("Kept title", nowMs = 2L)
            .withTrim(TrimRange(1_000, 3_000), nowMs = 3L)
            .withTransform(Transform(scale = 1.25f, offsetX = 0.1f, offsetY = -0.1f, quarterTurns = 1), nowMs = 4L)
            .withCaption(Caption(text = "hi", position = CaptionPosition.TOP, argb = 0xFF00FF00L), nowMs = 5L)
            .withBackground(BackgroundMode.REMOVE_BACKGROUND, nowMs = 6L)
            .withStroke(stroke, nowMs = 7L)
            .withStroke(stroke.copy(mode = StrokeMode.RESTORE), nowMs = 8L)
            .copy(exports = listOf(exported))

        val cleared = original.withoutStrokes(nowMs = 9L)

        assertTrue(cleared.maskStrokes.isEmpty())
        assertEquals(9L, cleared.updatedAtMs)
        assertEquals(original.copy(maskStrokes = emptyList(), updatedAtMs = 9L), cleared)
        // The original project is a value and must not change.
        assertEquals(2, original.maskStrokes.size)
        assertEquals(8L, original.updatedAtMs)
    }

    @Test
    fun withTitleNormalizesAndFallsBackWhenEmpty() {
        assertEquals("My sticker", project().withTitle("  My   sticker ", 2L).title)
        assertEquals("Sticker", project().withTitle("   ", 2L).title)
        assertEquals("Ça va 🎉", project().withTitle("Ça  va\t🎉", 2L).title)
    }
}
