package com.agency.stickerjet.engine

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Writes Kotlin-muxed WebP files for independent checking with Pillow (tools/verify_animated_webp.py).
 * Runs only when STICKERJET_WEBP_SAMPLES_DIR is set, so ordinary test runs write nothing.
 */
class WebpVerificationSamplesTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/webp/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }

    @Test
    fun writeSamplesForPillverification() {
        val dir = System.getenv("STICKERJET_WEBP_SAMPLES_DIR") ?: return
        val target = File(dir).apply { mkdirs() }
        val lossyA = resource("lossy_alpha_64.webp")
        val lossyB = resource("lossy_alpha_64_b.webp")
        val lossless = resource("lossless_alpha_64.webp")

        File(target, "kotlin_animated_lossy_3f.webp").writeBytes(
            AnimatedWebpWriter(64).write(
                listOf(WebpFrame(lossyA, 83), WebpFrame(lossyB, 84), WebpFrame(lossyA, 83)),
                loopCount = 5,
            ),
        )
        File(target, "kotlin_animated_mixed_2f.webp").writeBytes(
            AnimatedWebpWriter(64).write(listOf(WebpFrame(lossless, 120), WebpFrame(lossyB, 80))),
        )
        File(target, "kotlin_still_1f.webp").writeBytes(
            AnimatedWebpWriter(64).write(listOf(WebpFrame(lossyA, 100))),
        )
        assertEquals(3, WebpInspector.inspect(File(target, "kotlin_animated_lossy_3f.webp").readBytes()).frameCount)
    }
}
