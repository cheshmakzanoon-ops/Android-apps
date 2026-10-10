package com.agency.timeflip.domain.port

import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PhotoSourceRef
import com.agency.timeflip.domain.model.SourcePhoto

/**
 * Imports photographs into app-private storage. The original picker reference is only used once,
 * during import, so revoked or deleted originals cannot break a saved project.
 */
interface PhotoStore {
    /**
     * Copies the photograph, applies EXIF orientation, validates the format and reports its
     * oriented size. Throws [com.agency.timeflip.domain.error.TimeFlipException] with
     * UNSUPPORTED_FORMAT, DECODE_FAILED, PERMISSION_DENIED or STORAGE_FULL.
     */
    suspend fun importPhoto(source: PhotoSourceRef, slot: PhotoSlot, nowEpochMs: Long): SourcePhoto

    /** Absolute path of the private, oriented copy, or null when the copy is missing. */
    suspend fun originalPath(id: PhotoId): String?

    /** Absolute path of a cached thumbnail whose longest edge is at most [maxEdge], generated on demand. */
    suspend fun thumbnailPath(id: PhotoId, maxEdge: Int): String?

    suspend fun delete(id: PhotoId)
}
