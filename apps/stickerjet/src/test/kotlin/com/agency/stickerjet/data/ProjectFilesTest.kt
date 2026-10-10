package com.agency.stickerjet.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun filesOfAProjectSitSideBySideInItsFolder() {
        val files = ProjectFiles(tmp.root.resolve("projects"), tmp.root.resolve("temp"))
        val dir = files.dir("abc")

        assertEquals(File(dir, "source.mp4"), files.sourceFile("abc"))
        assertEquals(File(dir, "thumbnail.jpg"), files.thumbnailFile("abc"))
        assertEquals(File(dir, "exports"), files.exportsDir("abc"))
        assertEquals(File(dir, "project.json"), files.documentFile("abc"))
        assertEquals(tmp.root.resolve("projects"), dir.parentFile)
    }

    @Test
    fun tempDirIsCreatedOnFirstUse() {
        val files = ProjectFiles(tmp.root.resolve("projects"), tmp.root.resolve("cache/stickerjet"))

        assertEquals(tmp.root.resolve("cache/stickerjet"), files.tempDir())
        assertTrue(files.tempDir().isDirectory)
    }

    @Test
    fun defaultTempFolderIsASiblingOfTheProjectRoot() {
        val files = ProjectFiles(tmp.root.resolve("filesDir/projects"))

        assertEquals(File(tmp.root.resolve("filesDir"), ProjectFiles.DEFAULT_TEMP_FOLDER), files.tempRoot)
    }

    @Test
    fun validIdsAreAccepted() {
        assertTrue(ProjectFiles.isValidId("a".repeat(64)))
        assertTrue(ProjectFiles.isValidId("abc-DEF_123"))
    }

    @Test
    fun unsafeIdsAreRejected() {
        val files = ProjectFiles(tmp.root.resolve("projects"), tmp.root.resolve("temp"))
        val unsafe = listOf("", "../x", "a/b", "a b", "a\\b", "a".repeat(65), "x\u0000y")

        for (id in unsafe) {
            assertFalse("id should be unsafe: $id", ProjectFiles.isValidId(id))
            assertThrows(IllegalArgumentException::class.java) { files.dir(id) }
        }
    }
}
