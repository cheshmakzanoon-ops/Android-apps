package com.agency.stickerjet.fakes

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.ArgbFrame
import com.agency.stickerjet.domain.SubjectSegmenter
import java.util.concurrent.atomic.AtomicInteger

/**
 * [SubjectSegmenter] with a fixed behaviour, chosen by a factory:
 * - [fixed]: always returns the same mask. The mask must match the size of every frame it is given.
 * - [noSubject]: always returns null, the "no confident person" case.
 * - [failing]: always throws the given cause.
 *
 * [segmentCalls] counts invocations, including failing ones, so tests can check how often a pipeline
 * segments. The count is thread-safe.
 */
class FakeSubjectSegmenter private constructor(
    private val behaviour: (ArgbFrame) -> AlphaMask?,
) : SubjectSegmenter {

    private val calls = AtomicInteger(0)

    /** Number of times [segment] has been called. */
    val segmentCalls: Int get() = calls.get()

    override suspend fun segment(frame: ArgbFrame): AlphaMask? {
        calls.incrementAndGet()
        return behaviour(frame)
    }

    companion object {
        fun fixed(mask: AlphaMask): FakeSubjectSegmenter = FakeSubjectSegmenter { frame ->
            require(mask.width == frame.width && mask.height == frame.height) {
                "Fixed mask is ${mask.width}x${mask.height} but the frame is ${frame.width}x${frame.height}"
            }
            mask
        }

        fun noSubject(): FakeSubjectSegmenter = FakeSubjectSegmenter { null }

        fun failing(cause: Throwable = IllegalStateException("Fake segmentation failure")): FakeSubjectSegmenter =
            FakeSubjectSegmenter { throw cause }
    }
}
