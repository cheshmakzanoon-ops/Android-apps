package com.agency.photoduel.data.remote

import com.agency.photoduel.domain.model.ErrorCode
import com.agency.photoduel.domain.model.PreparedPhoto
import java.util.Collections
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/api-contract.md section 7: `PUT /v1/challenges/{challengeId}/submission` as multipart. */
internal class SubmissionUploadTest : RemoteTestBase() {

    private val full = ByteArray(400_000) { (it % 251).toByte() }
    private val thumbnail = ByteArray(30_000) { (it % 13 + 100).toByte() }
    private val photo = PreparedPhoto(fullJpeg = full, thumbnailJpeg = thumbnail, width = 1600, height = 1067)

    @Test
    fun submitPhotoSendsTheFullAndThumbnailPartsAsJpegFiles() = blocking {
        enqueueJson(201, Fixtures.SUBMISSION_MINE)

        val submission = service.submitPhoto("c1", photo) { }.success()

        assertEquals("s1", submission.id)
        assertTrue(submission.isMine)
        val request = takeRequest()
        request.assertRoute("PUT", "/v1/challenges/c1/submission")
        request.assertAuthenticated()
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readByteString()
        assertTrue(body.indexOf("""Content-Disposition: form-data; name="full"; filename="full.jpg"""".encodeUtf8()) >= 0)
        assertTrue(
            body.indexOf("""Content-Disposition: form-data; name="thumbnail"; filename="thumbnail.jpg"""".encodeUtf8()) >= 0,
        )
        assertEquals(2, countOf(body, "Content-Type: image/jpeg".encodeUtf8()))
        assertTrue(body.indexOf(full.toByteString()) >= 0)
        assertTrue(body.indexOf(thumbnail.toByteString()) >= 0)
    }

    @Test
    fun replacingASubmissionAnswers200AndIsSuccess() = blocking {
        enqueueJson(200, Fixtures.SUBMISSION_MINE)

        assertEquals("s1", service.submitPhoto("c1", photo) { }.success().id)
    }

    @Test
    fun progressStartsAtZeroIncreasesStrictlyAndEndsAtOne() = blocking {
        enqueueJson(201, Fixtures.SUBMISSION_MINE)
        val reported: MutableList<Float> = Collections.synchronizedList(mutableListOf())

        service.submitPhoto("c1", photo) { reported += it }.success()

        val values = reported.toList()
        assertTrue("Expected several progress values, got $values", values.size >= 5)
        assertEquals(0f, values.first())
        assertEquals(1f, values.last())
        values.zipWithNext().forEach { (a, b) -> assertTrue("Progress went from $a to $b", b > a) }
        assertTrue(values.all { it in 0f..1f })
    }

    @Test
    fun aThrowingProgressListenerDoesNotBreakTheUpload() = blocking {
        enqueueJson(201, Fixtures.SUBMISSION_MINE)

        val submission = service.submitPhoto("c1", photo) { throw IllegalStateException("listener bug") }.success()

        assertEquals("s1", submission.id)
    }

    @Test
    fun countingBodyNeverReportsAValueTwiceEvenIfWrittenAgain() {
        val reported = mutableListOf<Float>()
        val body = CountingRequestBody(
            ByteArray(10_000).toRequestBody(JPEG_MEDIA_TYPE),
            onProgress = { reported += it },
        )

        body.writeTo(Buffer())
        body.writeTo(Buffer())

        assertEquals(0f, reported.first())
        assertEquals(1f, reported.last())
        reported.zipWithNext().forEach { (a, b) -> assertTrue(b > a) }
        assertTrue(body.isOneShot())
    }

    @Test
    fun anUploadIsNotRetriedAfterANetworkFailure() = blocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        enqueueJson(201, Fixtures.SUBMISSION_MINE)

        val error = service.submitPhoto("c1", photo) { }.failure()

        assertEquals(ErrorCode.NETWORK, error.code)
        assertTrue(error.retryable)
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun tooLargeAndUnsupportedUploadsMapToTheirCodes() = blocking {
        enqueueError(413, "PAYLOAD_TOO_LARGE", "Upload over 3 MB")
        enqueueError(415, "UNSUPPORTED_MEDIA", "Not JPEG, PNG or WebP")
        enqueueError(410, "DEADLINE_PASSED", "The submission deadline has passed")

        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, service.submitPhoto("c1", photo) { }.failure().code)
        assertEquals(ErrorCode.UNSUPPORTED_MEDIA, service.submitPhoto("c1", photo) { }.failure().code)
        assertEquals(ErrorCode.DEADLINE_PASSED, service.submitPhoto("c1", photo) { }.failure().code)
    }

    private fun countOf(haystack: ByteString, needle: ByteString): Int {
        var count = 0
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            count++
            from = haystack.indexOf(needle, from + needle.size)
        }
        return count
    }
}
