package com.agency.stickerjet.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Suffix of the scratch file that is renamed over a finished file. */
internal const val TEMP_SUFFIX: String = ".tmp"

/**
 * Writes [text] to [target] so that a reader sees either the old content or the new content, never a
 * mix. The text goes to `<target>.tmp` in the same folder, is flushed to the disk, and then replaces
 * the target by a rename. A crash before the rename leaves the old document in place.
 */
internal fun writeTextAtomically(target: File, text: String) {
    val parent = requireNotNull(target.absoluteFile.parentFile) { "Target has no folder" }
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
        throw IOException("Cannot create the project folder")
    }
    val temp = File(parent, target.name + TEMP_SUFFIX)
    try {
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        replaceFile(temp, target)
    } finally {
        temp.delete()
    }
}

/** Renames [from] over [to]. Falls back to a plain replace on file systems without atomic rename. */
internal fun replaceFile(from: File, to: File) {
    try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
