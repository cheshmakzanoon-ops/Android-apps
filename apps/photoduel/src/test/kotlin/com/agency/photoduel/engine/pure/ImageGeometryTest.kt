package com.agency.photoduel.engine.pure

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGeometryTest {

    // ---- fit -------------------------------------------------------------------------------------

    @Test
    fun fullVariantShrinksLandscapeAndPortraitToA1600LongEdge() {
        assertEquals(PixelSize(1600, 1200), ImageGeometry.fitFull(4000, 3000))
        assertEquals(PixelSize(1200, 1600), ImageGeometry.fitFull(3000, 4000))
        assertEquals(PixelSize(1600, 1200), ImageGeometry.fitFull(4032, 3024))
        assertEquals(PixelSize(1600, 900), ImageGeometry.fitFull(3840, 2160))
        assertEquals(PixelSize(1600, 1600), ImageGeometry.fitFull(5000, 5000))
    }

    @Test
    fun thumbnailShrinksToA480LongEdge() {
        assertEquals(PixelSize(480, 360), ImageGeometry.fitThumbnail(4000, 3000))
        assertEquals(PixelSize(360, 480), ImageGeometry.fitThumbnail(3000, 4000))
        assertEquals(PixelSize(480, 270), ImageGeometry.fitThumbnail(1920, 1080))
        assertEquals(PixelSize(480, 480), ImageGeometry.fitThumbnail(1600, 1600))
    }

    @Test
    fun neverUpscales() {
        assertEquals(PixelSize(1000, 800), ImageGeometry.fitFull(1000, 800))
        assertEquals(PixelSize(1600, 1200), ImageGeometry.fitFull(1600, 1200))
        assertEquals(PixelSize(1200, 1600), ImageGeometry.fitFull(1200, 1600))
        assertEquals(PixelSize(300, 200), ImageGeometry.fitThumbnail(300, 200))
        assertEquals(PixelSize(480, 360), ImageGeometry.fitThumbnail(480, 360))
        assertEquals(PixelSize(200, 200), ImageGeometry.fitFull(200, 200))
    }

    @Test
    fun longEdgeIsExactlyTheLimitWhenShrinking() {
        assertEquals(1600, ImageGeometry.fitFull(1601, 1000).longEdge)
        assertEquals(PixelSize(1600, 999), ImageGeometry.fitFull(1601, 1000)) // 1000 * 1600 / 1601 = 999.37
        assertEquals(480, ImageGeometry.fitThumbnail(481, 481).longEdge)
        assertEquals(PixelSize(480, 480), ImageGeometry.fitThumbnail(481, 481))
    }

    @Test
    fun extremeAspectRatiosKeepAtLeastOnePixel() {
        assertEquals(PixelSize(1600, 8), ImageGeometry.fitFull(20_000, 100))
        assertEquals(PixelSize(1600, 1), ImageGeometry.fitFull(100_000, 10))
        assertEquals(PixelSize(1, 1600), ImageGeometry.fitFull(10, 100_000))
        assertEquals(PixelSize(1600, 1), ImageGeometry.fitFull(40_000, 1))
    }

    @Test
    fun roundingKeepsTheAspectRatioWithinOnePixelForManySizes() {
        val random = Random(1234)
        repeat(20_000) {
            val w = 1 + random.nextInt(12_000)
            val h = 1 + random.nextInt(12_000)
            for (limit in intArrayOf(ImageGeometry.FULL_MAX_LONG_EDGE, ImageGeometry.THUMBNAIL_MAX_LONG_EDGE)) {
                val out = ImageGeometry.fit(w, h, limit)
                assertTrue("never larger than the input: ${w}x$h -> $out", out.width <= w && out.height <= h)
                assertTrue("long edge within limit: ${w}x$h -> $out", out.longEdge <= limit)
                assertTrue("at least one pixel", out.width >= 1 && out.height >= 1)
                if (maxOf(w, h) > limit) {
                    assertEquals("long edge is the limit: ${w}x$h", limit, out.longEdge)
                    // Rounding may turn a nearly square image into an exactly square one, never flip it.
                    if (w > h) assertTrue("landscape stays landscape or square: ${w}x$h -> $out", out.width >= out.height)
                    if (w < h) assertTrue("portrait stays portrait or square: ${w}x$h -> $out", out.width <= out.height)
                    val exactWidth = w.toDouble() * limit / maxOf(w, h)
                    val exactHeight = h.toDouble() * limit / maxOf(w, h)
                    assertTrue("width within a pixel: ${w}x$h -> $out", Math.abs(out.width - exactWidth) <= 1.0)
                    assertTrue("height within a pixel: ${w}x$h -> $out", Math.abs(out.height - exactHeight) <= 1.0)
                    // The rounding is half a pixel at most on the edge that is not the long edge.
                    val exactShort = minOf(w, h).toDouble() * limit / maxOf(w, h)
                    assertTrue(Math.abs(out.shortEdge - exactShort) <= 0.5 + 1e-9 || out.shortEdge == 1)
                } else {
                    assertEquals(PixelSize(w, h), out)
                }
            }
        }
    }

    @Test
    fun sizesNearIntMaxDoNotOverflow() {
        val out = ImageGeometry.fit(Int.MAX_VALUE, Int.MAX_VALUE - 1, 1600)
        assertEquals(1600, out.width)
        assertEquals(1600, out.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fitRejectsZeroWidth() {
        ImageGeometry.fit(0, 100, 1600)
    }

    // ---- orientation -----------------------------------------------------------------------------

    @Test
    fun orientationValuesOneToEightMapToTheDocumentedTransforms() {
        val expected = mapOf(
            1 to OrientationTransform(0, flipHorizontal = false, flipVertical = false),
            2 to OrientationTransform(0, flipHorizontal = true, flipVertical = false),
            3 to OrientationTransform(180, flipHorizontal = false, flipVertical = false),
            4 to OrientationTransform(0, flipHorizontal = false, flipVertical = true),
            5 to OrientationTransform(270, flipHorizontal = true, flipVertical = false),
            6 to OrientationTransform(90, flipHorizontal = false, flipVertical = false),
            7 to OrientationTransform(90, flipHorizontal = true, flipVertical = false),
            8 to OrientationTransform(270, flipHorizontal = false, flipVertical = false),
        )
        for ((value, transform) in expected) {
            assertEquals("orientation $value", transform, ImageGeometry.transformFor(value))
        }
        assertTrue(ImageGeometry.transformFor(1).isIdentity)
        for (value in 2..8) assertFalse("orientation $value", ImageGeometry.transformFor(value).isIdentity)
    }

    @Test
    fun undefinedOrientationValuesAreTreatedAsNormal() {
        for (value in intArrayOf(0, 9, 10, 255, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertTrue("value $value", ImageGeometry.transformFor(value).isIdentity)
            assertFalse(ImageGeometry.isDefinedOrientation(value))
        }
        for (value in 1..8) assertTrue(ImageGeometry.isDefinedOrientation(value))
    }

    @Test
    fun everyPixelLandsWhereTheExifSpecificationPutsIt() {
        // Stored image 5 wide, 3 high. For each orientation, the independent oracle is the table of
        // "where is the 0th row and the 0th column" from the EXIF specification, written out per pixel
        // as (column c, row r) -> upright position, with W and H the size of the upright image.
        val storedW = 5
        val storedH = 3
        val oracle: Map<Int, (Int, Int, Int, Int) -> PixelPoint> = mapOf(
            1 to { c, r, _, _ -> PixelPoint(c, r) },
            2 to { c, r, w, _ -> PixelPoint(w - 1 - c, r) },
            3 to { c, r, w, h -> PixelPoint(w - 1 - c, h - 1 - r) },
            4 to { c, r, _, h -> PixelPoint(c, h - 1 - r) },
            5 to { c, r, _, _ -> PixelPoint(r, c) },
            6 to { c, r, w, _ -> PixelPoint(w - 1 - r, c) },
            7 to { c, r, w, h -> PixelPoint(w - 1 - r, h - 1 - c) },
            8 to { c, r, _, h -> PixelPoint(r, h - 1 - c) },
        )
        for (orientation in 1..8) {
            val transform = ImageGeometry.transformFor(orientation)
            val upright = transform.orientedSize(storedW, storedH)
            val seen = HashSet<PixelPoint>()
            for (r in 0 until storedH) {
                for (c in 0 until storedW) {
                    val actual = transform.mapPixel(c, r, storedW, storedH)
                    val wanted = oracle.getValue(orientation)(c, r, upright.width, upright.height)
                    assertEquals("orientation $orientation pixel ($c,$r)", wanted, actual)
                    assertTrue(actual.x in 0 until upright.width && actual.y in 0 until upright.height)
                    seen += actual
                }
            }
            assertEquals("orientation $orientation must be a bijection", storedW * storedH, seen.size)
        }
    }

    @Test
    fun orientationsFiveToEightSwapWidthAndHeight() {
        for (orientation in 1..4) {
            assertEquals(PixelSize(4000, 3000), ImageGeometry.orientedSize(4000, 3000, orientation))
        }
        for (orientation in 5..8) {
            assertEquals(PixelSize(3000, 4000), ImageGeometry.orientedSize(4000, 3000, orientation))
            assertTrue(ImageGeometry.transformFor(orientation).swapsAxes == (orientation == 5 || orientation == 6 || orientation == 7 || orientation == 8))
        }
        assertEquals(PixelSize(4000, 3000), ImageGeometry.orientedSize(4000, 3000, 0))
    }

    @Test
    fun outputSizesAreComputedFromTheUprightImage() {
        val rotated = ImageGeometry.outputSizes(4000, 3000, exifOrientation = 6)
        assertEquals(PixelSize(1200, 1600), rotated.full)
        assertEquals(PixelSize(360, 480), rotated.thumbnail)

        val normal = ImageGeometry.outputSizes(4000, 3000, exifOrientation = 1)
        assertEquals(PixelSize(1600, 1200), normal.full)
        assertEquals(PixelSize(480, 360), normal.thumbnail)

        // Smaller than both limits: nothing is upscaled, only the orientation swap applies.
        val small = ImageGeometry.outputSizes(400, 300, exifOrientation = 8)
        assertEquals(PixelSize(300, 400), small.full)
        assertEquals(PixelSize(300, 400), small.thumbnail)
    }

    @Test
    fun thumbnailNeverExceedsFullAndKeepsTheAspectWithinOnePixel() {
        val random = Random(99)
        repeat(5_000) {
            val w = 200 + random.nextInt(9_000)
            val h = 200 + random.nextInt(9_000)
            val orientation = 1 + random.nextInt(8)
            val sizes = ImageGeometry.outputSizes(w, h, orientation)
            assertTrue(
                "thumbnail larger than full for ${w}x$h/$orientation: $sizes",
                sizes.thumbnail.width <= sizes.full.width && sizes.thumbnail.height <= sizes.full.height,
            )
            if (sizes.full.width != sizes.full.height && sizes.thumbnail.width != sizes.thumbnail.height) {
                assertEquals("both variants share the long edge axis", sizes.full.width > sizes.full.height, sizes.thumbnail.width > sizes.thumbnail.height)
            }
            // The thumbnail's short edge, compared with the full's short edge scaled to the thumbnail's long edge.
            val expectedShort = sizes.full.shortEdge.toDouble() * sizes.thumbnail.longEdge / sizes.full.longEdge
            assertTrue(
                "aspect drift above one pixel for ${w}x$h/$orientation: $sizes",
                Math.abs(sizes.thumbnail.shortEdge - expectedShort) <= 1.0,
            )
        }
    }

    // ---- decode sampling -------------------------------------------------------------------------

    @Test
    fun sampleSizeKeepsAtLeastTheTargetLongEdge() {
        assertEquals(1, ImageGeometry.chooseSampleSize(1600, 1200))
        assertEquals(1, ImageGeometry.chooseSampleSize(3199, 2399))
        assertEquals(2, ImageGeometry.chooseSampleSize(3200, 2400))
        assertEquals(2, ImageGeometry.chooseSampleSize(4000, 3000))
        assertEquals(4, ImageGeometry.chooseSampleSize(8000, 6000))
        assertEquals(1, ImageGeometry.chooseSampleSize(800, 600))
        assertEquals(8, ImageGeometry.chooseSampleSize(20_000, 2_000))
    }

    @Test
    fun sampleSizeIsAPowerOfTwoWithinBudgetAndAsSmallAsPossible() {
        val random = Random(5)
        repeat(5_000) {
            val w = 1 + random.nextInt(40_000)
            val h = 1 + random.nextInt(40_000)
            val budget = 100_000L + random.nextInt(20_000_000)
            val target = ImageGeometry.FULL_MAX_LONG_EDGE
            val s = ImageGeometry.chooseSampleSize(w, h, target, budget)
            assertTrue("power of two: $s", s >= 1 && (s and (s - 1)) == 0)
            assertTrue("budget: ${w}x$h sample=$s budget=$budget", ImageGeometry.decodedPixels(w, h, s) <= budget)

            // Independent oracle: the quality rule alone, then the smallest larger power of two that fits.
            var quality = 1
            while (maxOf(w, h) / (quality * 2) >= target) quality *= 2
            assertTrue("never sharper than the quality rule: $s < $quality", s >= quality)
            if (s > quality) {
                assertTrue(
                    "sample $s is larger than needed for ${w}x$h at budget $budget",
                    ImageGeometry.decodedPixels(w, h, s / 2) > budget,
                )
            }
        }
    }

    @Test
    fun aTightMemoryBudgetForcesAStrongerSampleThanQualityAlone() {
        // 4000x3000: quality alone gives 2 (2000x1500 = 3 MP). A 1 MP budget needs 4 (1000x750).
        assertEquals(2, ImageGeometry.chooseSampleSize(4000, 3000, 1600, 12_000_000))
        assertEquals(4, ImageGeometry.chooseSampleSize(4000, 3000, 1600, 1_000_000))
        // A 40 MP square under the default budget: quality allows 2 (3162 px, 10 MP), which fits 12 MP.
        assertEquals(2, ImageGeometry.chooseSampleSize(6324, 6324))
    }

    @Test
    fun decodedPixelsRoundsUpLikeDecoders() {
        assertEquals(2L * 2L, ImageGeometry.decodedPixels(3, 3, 2))
        assertEquals(1000L * 750L, ImageGeometry.decodedPixels(4000, 3000, 4))
    }

    // ---- scaling steps ---------------------------------------------------------------------------

    @Test
    fun scaleStepsHalveUntilTheTarget() {
        assertEquals(
            listOf(PixelSize(800, 534), PixelSize(480, 320)),
            ImageGeometry.scaleSteps(PixelSize(1600, 1067), PixelSize(480, 320)),
        )
        assertEquals(emptyList<PixelSize>(), ImageGeometry.scaleSteps(PixelSize(480, 320), PixelSize(480, 320)))
        assertEquals(listOf(PixelSize(800, 600)), ImageGeometry.scaleSteps(PixelSize(1600, 1200), PixelSize(800, 600)))
        assertEquals(listOf(PixelSize(1000, 750)), ImageGeometry.scaleSteps(PixelSize(1600, 1200), PixelSize(1000, 750)))
    }

    @Test
    fun noScaleStepShrinksAnAxisByMoreThanTwoAndTheLastIsTheTarget() {
        val random = Random(77)
        repeat(3_000) {
            val from = PixelSize(1 + random.nextInt(8_000), 1 + random.nextInt(8_000))
            val to = ImageGeometry.fit(from.width, from.height, 1 + random.nextInt(1_600))
            val steps = ImageGeometry.scaleSteps(from, to)
            var previous = from
            for (step in steps) {
                assertTrue("${previous} -> $step", step.width <= previous.width && step.height <= previous.height)
                assertTrue("step too large: $previous -> $step", step.width * 2 >= previous.width && step.height * 2 >= previous.height)
                previous = step
            }
            assertEquals(to, previous)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun scaleStepsRefusesToUpscale() {
        ImageGeometry.scaleSteps(PixelSize(100, 100), PixelSize(200, 100))
    }
}
