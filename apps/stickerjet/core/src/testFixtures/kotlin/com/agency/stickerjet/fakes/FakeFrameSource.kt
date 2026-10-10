package com.agency.stickerjet.fakes

import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.FrameSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min

/**
 * [FrameSource] that renders synthetic frames without any media: a red-to-green gradient with a white
 * square that moves left to right once every [PERIOD_MS]. The same inputs always give the same pixels,
 * so tests can compare frames.
 *
 * The project id is ignored, so every project gets the same synthetic clip. Frames are rendered one at
 * a time as the flow is collected, and none are kept, which mirrors the real streaming contract. The
 * longest side is scaled down to `maxSidePx` with the aspect ratio kept, the same as the real source.
 */
class FakeFrameSource(
    private val sourceWidth: Int = 480,
    private val sourceHeight: Int = 360,
) : FrameSource {

    init {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source dimensions must be positive" }
    }

    override fun frames(projectId: String, timestampsMs: List<Long>, maxSidePx: Int): Flow<ArgbFrame> {
        require(maxSidePx > 0) { "maxSidePx must be positive" }
        val longest = max(sourceWidth, sourceHeight)
        val width = if (longest <= maxSidePx) sourceWidth else scaled(sourceWidth, maxSidePx, longest)
        val height = if (longest <= maxSidePx) sourceHeight else scaled(sourceHeight, maxSidePx, longest)
        return flow {
            for (timestampMs in timestampsMs) {
                emit(render(width, height, timestampMs))
            }
        }
    }

    private fun scaled(length: Int, maxSidePx: Int, longest: Int): Int =
        max(1, (length.toLong() * maxSidePx / longest).toInt())

    private fun render(width: Int, height: Int, timestampMs: Long): ArgbFrame {
        val side = max(1, min(width, height) / 4)
        val phase = Math.floorMod(timestampMs, PERIOD_MS)
        val squareX = (phase * (width - side) / PERIOD_MS).toInt()
        val squareY = (height - side) / 2
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val green = y * 255 / max(1, height - 1)
            for (x in 0 until width) {
                val red = x * 255 / max(1, width - 1)
                val inSquare = x >= squareX && x < squareX + side && y >= squareY && y < squareY + side
                pixels[y * width + x] = if (inSquare) OPAQUE_WHITE else OPAQUE_BLUE_BASE or (red shl 16) or (green shl 8)
            }
        }
        return ArgbFrame(width = width, height = height, pixels = pixels, timestampMs = timestampMs)
    }

    private companion object {
        const val PERIOD_MS = 2_000L
        const val OPAQUE_WHITE: Int = 0xFFFFFFFF.toInt()
        const val OPAQUE_BLUE_BASE: Int = 0xFF000080.toInt()
    }
}
