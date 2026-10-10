package com.agency.stickerjet.data

import com.agency.stickerjet.data.StorageGuard.Companion.SAFETY_MARGIN_BYTES
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roomIsAcceptedUpToTheMarginPlusTheRequestedBytes() {
        val guard = StorageGuard(tmp.root, usableBytes = { SAFETY_MARGIN_BYTES + 100L })

        assertTrue(guard.hasRoomFor(100L))
        assertFalse(guard.hasRoomFor(101L))
    }

    @Test
    fun zeroBytesStillNeedTheSafetyMargin() {
        assertTrue(StorageGuard(tmp.root, usableBytes = { SAFETY_MARGIN_BYTES }).hasRoomFor(0L))
        assertFalse(StorageGuard(tmp.root, usableBytes = { SAFETY_MARGIN_BYTES - 1 }).hasRoomFor(0L))
    }

    @Test
    fun safetyMarginIsAtLeastSixtyFourMebibytes() {
        assertTrue(SAFETY_MARGIN_BYTES >= 64L * 1024L * 1024L)
    }

    @Test
    fun measuresTheNearestExistingFolderWhenRootIsMissing() {
        val missing = File(tmp.root, "a/b/c")
        var measured: File? = null
        val guard = StorageGuard(missing, usableBytes = { measured = it; Long.MAX_VALUE / 2 })

        guard.hasRoomFor(1L)

        assertEquals(tmp.root.canonicalFile, measured?.canonicalFile)
    }

    @Test
    fun hugeRequestIsRefusedWithoutOverflow() {
        val guard = StorageGuard(tmp.root, usableBytes = { Long.MAX_VALUE })

        assertFalse(guard.hasRoomFor(Long.MAX_VALUE))
    }

    @Test
    fun negativeByteCountIsRejected() {
        val guard = StorageGuard(tmp.root, usableBytes = { Long.MAX_VALUE })

        assertThrows(IllegalArgumentException::class.java) { guard.hasRoomFor(-1L) }
    }
}
