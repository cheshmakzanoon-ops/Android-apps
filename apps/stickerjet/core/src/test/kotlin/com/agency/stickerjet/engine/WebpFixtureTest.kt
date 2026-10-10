package com.agency.stickerjet.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Cross-checks [WebpInspector] against Pillow. `expected.json` is written by tools/make_webp_fixtures.py,
 * which decodes each fixture with PIL and records what PIL sees. The inspector must agree on the same bytes.
 */
class WebpFixtureTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/webp/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }

    private val fixtures = Json.parseToJsonElement(String(resource("expected.json"), Charsets.UTF_8))
        .jsonObject.getValue("fixtures").jsonArray.map { it.jsonObject }

    @Test
    fun inspectorAgreesWithPillOnEveryFixture() {
        assertTrue(fixtures.size >= 6, "expected.json should list every fixture")
        for (fixture in fixtures) {
            val name = fixture.getValue("file").jsonPrimitive.content
            val info = WebpInspector.inspect(resource(name))
            assertEquals(fixture.intField("width"), info.canvasWidth, "$name canvas width")
            assertEquals(fixture.intField("height"), info.canvasHeight, "$name canvas height")
            assertEquals(fixture.intField("n_frames"), info.frameCount, "$name frame count")
            assertEquals(fixture.getValue("is_animated").jsonPrimitive.boolean, info.isAnimated, "$name animated")
            assertEquals(fixture.getValue("alph_present").jsonPrimitive.boolean, info.hasAlphaChunks, "$name ALPH")
            if (info.isAnimated) {
                val pilDurations = fixture.getValue("durations_ms").jsonArray.map { it.jsonPrimitive.int }
                assertEquals(pilDurations, info.frameDurationsMs, "$name durations")
                assertEquals(fixture.intField("loop"), info.loopCount, "$name loop count")
            }
        }
    }

    @Test
    fun pillAnimatedFixturesAreReportedAnimatedWithTheirTiming() {
        val three = WebpInspector.inspect(resource("pil_animated_3f.webp"))
        assertEquals(64, three.canvasWidth)
        assertEquals(64, three.canvasHeight)
        assertEquals(listOf(100, 200, 300), three.frameDurationsMs)
        assertEquals(0, three.loopCount)
        assertTrue(three.isAnimated)
        assertTrue(three.hasAlphaChunks)

        val two = WebpInspector.inspect(resource("pil_animated_lossless_2f.webp"))
        assertEquals(listOf(40, 60), two.frameDurationsMs)
        assertEquals(3, two.loopCount)
        assertFalse(two.hasAlphaChunks, "VP8L frames carry alpha without ALPH chunks")
    }

    @Test
    fun stillFixturesAreNeverAnimated() {
        val still = WebpInspector.inspect(resource("lossy_alpha_odd_33x17.webp"))
        assertEquals(33, still.canvasWidth)
        assertEquals(17, still.canvasHeight)
        assertEquals(1, still.frameCount)
        assertFalse(still.isAnimated)
        assertTrue(still.hasAlphaChunks)
    }

    @Test
    fun truncatedAnimationIsRejected() {
        val bytes = resource("pil_animated_3f.webp")
        assertFailsWith<IllegalArgumentException> { WebpInspector.inspect(bytes.copyOf(bytes.size - 10)) }
    }

    private fun kotlinx.serialization.json.JsonObject.intField(key: String): Int =
        getValue(key).jsonPrimitive.int
}
