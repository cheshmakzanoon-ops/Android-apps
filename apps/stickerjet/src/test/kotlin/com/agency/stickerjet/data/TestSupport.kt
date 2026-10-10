package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ProbeResult
import com.agency.stickerjet.domain.SourceVideo
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.TrimRange
import java.io.File
import java.io.InputStream
import kotlinx.serialization.json.Json

/** Test doubles and builders shared by the data tests. Nothing here touches Android. */

internal const val TEST_NOW_MS: Long = 1_700_000_000_000L

/** Fake probe that reports fixed facts, with the real size of the file it is given. */
internal class FakeMediaProbe(
    var facts: ProbeResult = okFacts(),
    var thumbnailWritten: Boolean = true,
    var thumbnailFailure: RuntimeException? = null,
    var probeFailure: RuntimeException? = null,
) : MediaProbe {

    val thumbnailSizes = mutableListOf<Int>()

    override fun probe(file: File): ProbeResult {
        probeFailure?.let { throw it }
        return facts.copy(sizeBytes = file.length())
    }

    override fun writeThumbnail(source: File, target: File, maxSidePx: Int): Boolean {
        thumbnailSizes += maxSidePx
        thumbnailFailure?.let { throw it }
        if (!thumbnailWritten) return false
        target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        return true
    }

    companion object {
        fun okFacts(rotation: Int = 0): ProbeResult = ProbeResult(
            durationMs = 5_000L,
            width = 1080,
            height = 1920,
            rotationDegrees = rotation,
            hasVideoTrack = true,
            sizeBytes = 0L,
        )
    }
}

/** Clip whose provider info and stream are set by the test. Counts how often the stream was opened. */
internal class FakeClip(
    private val info: ClipInfo = ClipInfo(sizeBytes = null, displayName = "My clip.final.mp4"),
    private val open: () -> InputStream = { ByteArray(0).inputStream() },
) : ClipSource {

    var opened = 0
        private set

    override fun describe(): ClipInfo = info

    override fun openStream(): InputStream {
        opened++
        return open()
    }
}

/** Stream that yields [data] up to [failAt] bytes, then throws [error] on the next read. */
internal class FlakyStream(
    private val data: ByteArray,
    private val failAt: Int,
    private val error: Throwable,
) : InputStream() {

    private var position = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= failAt) throw error
        if (position >= data.size) return -1
        val count = minOf(len, failAt - position, data.size - position)
        System.arraycopy(data, position, b, off, count)
        position += count
        return count
    }
}

/** Stream that counts the bytes its reader has consumed. */
internal class CountingStream(private val data: ByteArray) : InputStream() {
    var consumed = 0L
        private set
    private var position = 0

    override fun read(): Int {
        if (position >= data.size) return -1
        consumed++
        return data[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(len, data.size - position)
        System.arraycopy(data, position, b, off, count)
        position += count
        consumed += count
        return count
    }
}

internal fun testJson(): Json = ProjectEnvelope.createJson()

internal fun sampleProject(
    id: String = "p1",
    title: String = "Clip",
    updatedAtMs: Long = TEST_NOW_MS,
): StickerProject = StickerProject(
    id = id,
    title = title,
    source = SourceVideo(
        fileName = ProjectFiles.SOURCE_NAME,
        durationMs = 5_000L,
        width = 1080,
        height = 1920,
        rotationDegrees = 0,
        sizeBytes = 4_096L,
    ),
    trim = TrimRange(startMs = 0L, endMs = 2_000L),
    createdAtMs = TEST_NOW_MS,
    updatedAtMs = updatedAtMs,
)

/** Project files under [root] and a temporary folder under [temp], both created. */
internal fun projectFilesIn(root: File): ProjectFiles {
    val projects = File(root, "projects").apply { mkdirs() }
    val temp = File(root, "temp").apply { mkdirs() }
    return ProjectFiles(projects, temp)
}

internal fun repositoryFor(files: ProjectFiles): FileProjectRepository =
    FileProjectRepository(files, testJson()) { TEST_NOW_MS }
