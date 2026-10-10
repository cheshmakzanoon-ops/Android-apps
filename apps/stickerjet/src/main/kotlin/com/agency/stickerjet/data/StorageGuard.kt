package com.agency.stickerjet.data

import android.os.StatFs
import java.io.File

/**
 * Decides whether a copy of a given size fits. Free space is read from the file system that holds
 * [root]. A fixed safety margin stays free after the copy, so the app and the system keep working.
 */
class StorageGuard internal constructor(
    private val root: File,
    private val usableBytes: (File) -> Long,
) {

    /** Reads the free space with StatFs on the file system that holds [root]. */
    constructor(root: File) : this(root, ::usableBytesOf)

    /** True when [bytes] plus [SAFETY_MARGIN_BYTES] are free. Throws for a negative [bytes]. */
    fun hasRoomFor(bytes: Long): Boolean {
        require(bytes >= 0) { "Byte count must not be negative" }
        if (bytes > Long.MAX_VALUE - SAFETY_MARGIN_BYTES) return false
        return usableBytes(existingAncestor()) >= SAFETY_MARGIN_BYTES + bytes
    }

    /** The nearest folder that exists, so StatFs never gets a missing path. */
    private fun existingAncestor(): File =
        generateSequence(root.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: root

    companion object {
        /** Free space that always stays available, on top of the bytes requested. */
        const val SAFETY_MARGIN_BYTES: Long = 64L * 1024L * 1024L
    }
}

private fun usableBytesOf(directory: File): Long = StatFs(directory.path).availableBytes
