package com.agency.stickerjet.domain

import com.agency.stickerjet.fakes.FakeFrameSource
import com.agency.stickerjet.fakes.FakeSubjectSegmenter
import com.agency.stickerjet.fakes.InMemoryProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Checks that the shared fakes in testFixtures behave as the consuming lanes will assume. */
class FixtureFakesTest {

    private fun project(id: String, title: String = "clip") = StickerProjects.create(
        id = id,
        source = SourceVideo(fileName = "source.mp4", durationMs = 10_000L, width = 640, height = 480, rotationDegrees = 0, sizeBytes = 1L),
        displayName = "$title.mp4",
        nowMs = 1L,
    )

    @Test
    fun repositorySavesLoadsReplacesAndDeletes() = runBlocking {
        val repository = InMemoryProjectRepository()
        assertNull(repository.load("a"))

        repository.save(project("a", "first"))
        repository.save(project("b"))
        repository.save(project("a", "second"))

        assertEquals("second", repository.load("a")?.title)
        assertEquals(listOf("a", "b"), repository.list().map { it.id })

        repository.delete("a")
        assertNull(repository.load("a"))
        assertEquals(listOf("b"), repository.list().map { it.id })
    }

    @Test
    fun repositoryAcceptsInitialProjects() = runBlocking {
        val repository = InMemoryProjectRepository(listOf(project("x"), project("y")))
        assertEquals(listOf("x", "y"), repository.list().map { it.id })
    }

    @Test
    fun repositoryStaysConsistentUnderConcurrentWrites() = runBlocking {
        val repository = InMemoryProjectRepository()
        coroutineScope {
            (0 until 200).map { i -> async(Dispatchers.Default) { repository.save(project("id-$i")) } }.awaitAll()
        }
        assertEquals(200, repository.list().size)
        assertEquals("id-0", repository.load("id-0")?.id)
        assertEquals("id-199", repository.load("id-199")?.id)
    }

    @Test
    fun frameSourceEmitsOneFramePerTimestampInOrder() = runBlocking {
        val frames = FakeFrameSource().frames("project-1", listOf(0L, 500L, 1_000L), maxSidePx = 512).toList()
        assertEquals(listOf(0L, 500L, 1_000L), frames.map { it.timestampMs })
        assertEquals(480, frames.first().width)
        assertEquals(360, frames.first().height)
    }

    @Test
    fun frameSourceScalesTheLongestSideWithTheAspectRatio() = runBlocking {
        val landscape = FakeFrameSource(480, 360).frames("project-1", listOf(0L), maxSidePx = 240).toList().single()
        assertEquals(240, landscape.width)
        assertEquals(180, landscape.height)

        val portrait = FakeFrameSource(360, 480).frames("project-1", listOf(0L), maxSidePx = 240).toList().single()
        assertEquals(180, portrait.width)
        assertEquals(240, portrait.height)
    }

    @Test
    fun frameSourceIsDeterministicAndMovesTheSquare() = runBlocking {
        val source = FakeFrameSource()
        val first = source.frames("project-1", listOf(0L, 1_000L), maxSidePx = 128).toList()
        val again = source.frames("project-1", listOf(0L, 1_000L), maxSidePx = 128).toList()
        assertContentEquals(first[0].pixels, again[0].pixels)
        assertNotEquals(first[0].pixels.toList(), first[1].pixels.toList())
    }

    @Test
    fun frameSourceRendersOpaquePixels() = runBlocking {
        val frame = FakeFrameSource().frames("project-1", listOf(0L), maxSidePx = 64).toList().single()
        assertTrue(frame.pixels.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun segmenterReturnsTheFixedMaskAndCountsCalls() = runBlocking {
        val frame = ArgbFrame(width = 2, height = 2, pixels = IntArray(4), timestampMs = 0L)
        val mask = AlphaMask(width = 2, height = 2, alpha = ByteArray(4) { 255.toByte() })
        val segmenter = FakeSubjectSegmenter.fixed(mask)
        assertSame(mask, segmenter.segment(frame))
        assertSame(mask, segmenter.segment(frame))
        assertEquals(2, segmenter.segmentCalls)
    }

    @Test
    fun segmenterFixedMaskMustMatchTheFrameSize() = runBlocking {
        val frame = ArgbFrame(width = 2, height = 2, pixels = IntArray(4), timestampMs = 0L)
        val segmenter = FakeSubjectSegmenter.fixed(AlphaMask(width = 3, height = 2, alpha = ByteArray(6)))
        assertFailsWith<IllegalArgumentException> { segmenter.segment(frame) }
    }

    @Test
    fun segmenterWithNoSubjectReturnsNull() = runBlocking {
        val frame = ArgbFrame(width = 1, height = 1, pixels = IntArray(1), timestampMs = 0L)
        val segmenter = FakeSubjectSegmenter.noSubject()
        assertNull(segmenter.segment(frame))
        assertEquals(1, segmenter.segmentCalls)
    }

    @Test
    fun segmenterThatFailsThrowsTheConfiguredCause() = runBlocking {
        val frame = ArgbFrame(width = 1, height = 1, pixels = IntArray(1), timestampMs = 0L)
        val cause = IllegalArgumentException("model unavailable")
        val segmenter = FakeSubjectSegmenter.failing(cause)
        val thrown = assertFailsWith<IllegalArgumentException> { segmenter.segment(frame) }
        assertSame(cause, thrown)
        assertEquals(1, segmenter.segmentCalls)
    }
}
