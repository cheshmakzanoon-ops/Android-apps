package com.agency.stickerjet.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopyWithLimitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun bytes(count: Int): ByteArray = ByteArray(count) { (it * 31 % 256).toByte() }

    @Test
    fun inputOfExactlyTheLimitIsCopied() {
        val data = bytes(200_000)
        val dest = tmp.root.resolve("exact.mp4")

        val outcome = copyWithLimit(data.inputStream(), dest, maxBytes = 200_000L)

        assertEquals(CopyOutcome.Copied(200_000L), outcome)
        assertArrayEquals(data, dest.readBytes())
    }

    @Test
    fun oneByteOverTheLimitIsTooLargeAndTheFileIsDeleted() {
        val dest = tmp.root.resolve("over.mp4")

        val outcome = copyWithLimit(bytes(200_001).inputStream(), dest, maxBytes = 200_000L)

        assertEquals(CopyOutcome.TooLarge, outcome)
        assertFalse("partial file must not remain", dest.exists())
    }

    @Test
    fun oversizeInputIsNotReadPastTheLimitPlusOneByte() {
        val source = CountingStream(bytes(10 * 1024 * 1024))
        val dest = tmp.root.resolve("early.mp4")

        val outcome = copyWithLimit(source, dest, maxBytes = 100L)

        assertEquals(CopyOutcome.TooLarge, outcome)
        assertEquals("reader must stop one byte past the limit", 101L, source.consumed)
        assertFalse(dest.exists())
    }

    @Test
    fun emptyInputCopiesZeroBytes() {
        val dest = tmp.root.resolve("empty.mp4")

        assertEquals(CopyOutcome.Copied(0L), copyWithLimit(ByteArray(0).inputStream(), dest, maxBytes = 10L))
        assertTrue(dest.isFile)
        assertEquals(0L, dest.length())
    }

    @Test
    fun zeroLimitRejectsAnyByteButAcceptsNothing() {
        val dest = tmp.root.resolve("zero.mp4")

        assertEquals(CopyOutcome.TooLarge, copyWithLimit(bytes(1).inputStream(), dest, maxBytes = 0L))
        assertFalse(dest.exists())
        assertEquals(CopyOutcome.Copied(0L), copyWithLimit(ByteArray(0).inputStream(), dest, maxBytes = 0L))
    }

    @Test
    fun ioErrorMidCopyDeletesThePartialFileAndRethrows() {
        val dest = tmp.root.resolve("broken.mp4")
        val input = FlakyStream(bytes(1_000), failAt = 500, error = IOException("disk read failed"))

        val error = assertThrows(IOException::class.java) {
            copyWithLimit(input, dest, maxBytes = 10_000L)
        }

        assertEquals("disk read failed", error.message)
        assertFalse("partial file must be removed", dest.exists())
    }

    @Test
    fun negativeLimitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            copyWithLimit(ByteArray(1).inputStream(), tmp.root.resolve("neg.mp4"), maxBytes = -1L)
        }
    }

    @Test
    fun copyIntoMissingFolderFailsWithoutCreatingIt() {
        val dest = File(tmp.root, "missing/dir/x.mp4")

        assertThrows(IOException::class.java) {
            copyWithLimit(ByteArray(4).inputStream(), dest, maxBytes = 10L)
        }
        assertFalse(dest.exists())
    }
}
