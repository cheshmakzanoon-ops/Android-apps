package com.agency.photoduel.data.remote

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink

internal val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()
internal val JPEG_MEDIA_TYPE: MediaType = "image/jpeg".toMediaType()

/**
 * Marks [delegate] as one-shot so OkHttp never transmits it a second time on its own (no silent replay
 * after a `408`, a `503` with `Retry-After: 0`, or a redirect). Only GET is retried, and only by
 * [PhotoDuelApiClient] itself.
 */
internal class OneShotRequestBody(private val delegate: RequestBody) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true
    override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
}

/** An empty body without a content type, as OkHttp sends for a bare DELETE, but one-shot. */
internal fun emptyOneShotBody(): RequestBody = OneShotRequestBody(EmptyBody)

private object EmptyBody : RequestBody() {
    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = 0L
    override fun writeTo(sink: BufferedSink) = Unit
}

/**
 * Wraps an upload body and reports the fraction of bytes handed to the connection, from 0 to 1.
 *
 * The wrapped body is first rendered into memory (an upload is at most a few megabytes) and then written
 * in chunks of [chunkBytes], each flushed to the socket, so progress follows the network rather than
 * jumping straight to the end. Reported values strictly increase: 0 when writing starts, then at least
 * every [minStep] of progress, and exactly 1 once the last byte was written. A value is never reported
 * twice and never goes down, even if OkHttp were to call [writeTo] again. The callback runs on the OkHttp
 * thread that writes the body; an exception thrown by it is swallowed so it cannot crash that thread.
 */
internal class CountingRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit,
    private val minStep: Float = 0.01f,
    private val chunkBytes: Long = 16L * 1024,
) : RequestBody() {
    @Volatile
    private var lastReported = -1f

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val content = Buffer()
        delegate.writeTo(content)
        val total = content.size
        report(0f, force = true)
        var written = 0L
        while (!content.exhausted()) {
            val count = minOf(chunkBytes, content.size)
            sink.write(content, count)
            sink.flush()
            written += count
            report((written.toDouble() / total).toFloat().coerceIn(0f, 1f), force = written == total)
        }
        report(1f, force = true)
    }

    private fun report(fraction: Float, force: Boolean) {
        val last = lastReported
        if (fraction <= last) return
        if (!force && fraction - last < minStep) return
        lastReported = fraction
        try {
            onProgress(fraction)
        } catch (e: RuntimeException) {
            // A faulty progress listener must not abort the upload or crash the OkHttp thread.
        }
    }
}
