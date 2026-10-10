package com.agency.stickerjet.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Writer and inspector tests on real encoder output from the fixtures. */
class WebpContainerTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/webp/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }

    private val lossyA = resource("lossy_alpha_64.webp")
    private val lossyB = resource("lossy_alpha_64_b.webp")
    private val lossless = resource("lossless_alpha_64.webp")

    private fun u32(bytes: ByteArray, at: Int): Int = WebpBytes.u32le(bytes, at).toInt()

    @Test
    fun animatedFileRoundTripsThroughInspector() {
        val bytes = AnimatedWebpWriter(64).write(
            listOf(WebpFrame(lossyA, 100), WebpFrame(lossyB, 200), WebpFrame(lossless, 300)),
            loopCount = 5,
        )
        val info = WebpInspector.inspect(bytes)
        assertEquals(64, info.canvasWidth)
        assertEquals(64, info.canvasHeight)
        assertEquals(3, info.frameCount)
        assertEquals(listOf(100, 200, 300), info.frameDurationsMs)
        assertEquals(5, info.loopCount)
        assertTrue(info.hasAlphaChunks, "lossy frames carry ALPH")
        assertTrue(info.isAnimated)
    }

    @Test
    fun riffSizeAndVp8xFlagsAreExact() {
        val bytes = AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 100), WebpFrame(lossyB, 100)))
        assertEquals(bytes.size - 8, u32(bytes, 4), "RIFF size must equal file length minus 8")
        assertEquals("VP8X", WebpBytes.fourcc(bytes, 12))
        assertEquals(10, u32(bytes, 16))
        assertEquals(0x12, WebpBytes.u8(bytes, 20), "animation (0x02) and alpha (0x10) flags")
    }

    @Test
    fun animChunkIsTransparentWithLoopCount() {
        val bytes = AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 100), WebpFrame(lossyB, 100)), loopCount = 258)
        assertEquals("ANIM", WebpBytes.fourcc(bytes, 30))
        assertEquals(6, u32(bytes, 34))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), bytes.copyOfRange(38, 42), "background BGRA")
        assertEquals(258, WebpBytes.u16le(bytes, 42))
    }

    @Test
    fun anmfChunksHaveExactSizesAndNoBlendNoDispose() {
        val bytes = AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 120), WebpFrame(lossless, 80)))
        val top = walkChunks(bytes, 12, bytes.size)
        val anmfs = top.filter { it.fourcc == "ANMF" }
        assertEquals(2, anmfs.size)
        for ((index, anmf) in anmfs.withIndex()) {
            val p = anmf.payloadAt
            assertEquals(0, WebpBytes.u24le(bytes, p), "X/2 is zero")
            assertEquals(0, WebpBytes.u24le(bytes, p + 3), "Y/2 is zero")
            assertEquals(63, WebpBytes.u24le(bytes, p + 6), "width-1 is canvas-1")
            assertEquals(63, WebpBytes.u24le(bytes, p + 9), "height-1 is canvas-1")
            assertEquals(if (index == 0) 120 else 80, WebpBytes.u24le(bytes, p + 12))
            assertEquals(0x02, WebpBytes.u8(bytes, p + 15), "no-blend, dispose none")
            val subs = walkChunks(bytes, p + 16, p + anmf.payloadSize)
            val subBytes = subs.sumOf { it.endAt - it.headerAt }
            assertEquals(anmf.payloadSize - 16, subBytes, "sub-chunks fill the ANMF payload exactly")
        }
    }

    @Test
    fun imageChunksAreCopiedVerbatim() {
        val frame = StaticWebp.parse(lossyA)
        val bytes = AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 100), WebpFrame(lossyB, 100)))
        assertTrue(indexOf(bytes, frame.imageChunks) > 0, "ALPH + VP8 bytes must appear unchanged")
    }

    @Test
    fun singleFrameIsStillImageNotAnimation() {
        val bytes = AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 100)))
        val info = WebpInspector.inspect(bytes)
        assertEquals(1, info.frameCount)
        assertFalse(info.isAnimated, "one frame must never be labelled animated")
        assertEquals(listOf(0), info.frameDurationsMs)
        assertEquals(0, WebpBytes.u8(bytes, 20) and 0x02, "animation flag clear")
        assertTrue(walkChunks(bytes, 12, bytes.size).none { it.fourcc == "ANMF" || it.fourcc == "ANIM" })
        assertEquals(bytes.size - 8, u32(bytes, 4))
    }

    @Test
    fun losslessOnlyAnimationHasNoAlphaChunks() {
        val bytes = AnimatedWebpWriter(64).write(listOf(WebpFrame(lossless, 50), WebpFrame(lossless, 50)))
        val info = WebpInspector.inspect(bytes)
        assertTrue(info.isAnimated)
        assertFalse(info.hasAlphaChunks)
    }

    @Test
    fun rejectsFramesOfTheWrongSize() {
        val lossless48 = resource("lossless_alpha_48.webp")
        assertFailsWith<IllegalArgumentException> {
            AnimatedWebpWriter(64).write(listOf(WebpFrame(lossless48, 100)))
        }
    }

    @Test
    fun rejectsAlreadyAnimatedFrames() {
        val animated = resource("pil_animated_3f.webp")
        assertFailsWith<IllegalArgumentException> {
            AnimatedWebpWriter(64).write(listOf(WebpFrame(animated, 100)))
        }
    }

    @Test
    fun rejectsEmptyInputAndBadTiming() {
        assertFailsWith<IllegalArgumentException> { AnimatedWebpWriter(64).write(emptyList()) }
        assertFailsWith<IllegalArgumentException> { WebpFrame(lossyA, 0) }
        assertFailsWith<IllegalArgumentException> { WebpFrame(lossyA, WebpFrame.MAX_DURATION_MS + 1) }
        assertFailsWith<IllegalArgumentException> { AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 1)), loopCount = 70_000) }
    }

    @Test
    fun inspectorRejectsNonWebpInput() {
        assertFailsWith<IllegalArgumentException> { WebpInspector.inspect(ByteArray(40)) }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (k in needle.indices) if (haystack[start + k] != needle[k]) continue@outer
            return start
        }
        return -1
    }
}
