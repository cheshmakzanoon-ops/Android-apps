package com.agency.stickerjet.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryCleanerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: ProjectFiles
    private lateinit var repository: FileProjectRepository
    private lateinit var cleaner: LibraryCleaner

    @Before
    fun setUp() {
        files = projectFilesIn(tmp.root)
        repository = repositoryFor(files)
        cleaner = LibraryCleaner(files, repository)
    }

    private fun writeFile(path: File): File = path.apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(8))
    }

    @Test
    fun removeOrphansRemovesOnlyFoldersWithoutAUsableDocument() = runBlocking {
        repository.save(sampleProject(id = "ok"))
        writeFile(File(files.dir("orphan"), "source.mp4"))
        writeFile(File(files.dir("broken"), "project.json")).writeText("{not json")
        writeFile(File(files.dir("future"), "project.json")).writeText("""{"schemaVersion":2,"project":{}}""")
        writeFile(File(files.dir("busy"), "source.mp4"))
        files.inFlight.add("busy")

        val removed = cleaner.removeOrphans()

        assertEquals(2, removed)
        assertTrue(files.dir("ok").isDirectory)
        assertTrue("newer-version project must be kept", files.dir("future").isDirectory)
        assertTrue("in-flight import must be kept", files.dir("busy").isDirectory)
        assertFalse(files.dir("orphan").exists())
        assertFalse(files.dir("broken").exists())
    }

    @Test
    fun clearTemporaryFilesEmptiesTheFolderButKeepsIt() = runBlocking {
        val temp = files.tempDir()
        writeFile(File(temp, "export-partial.webp"))
        writeFile(File(temp, "nested/inner.bin"))

        cleaner.clearTemporaryFiles()

        assertTrue(temp.isDirectory)
        assertEquals(0, temp.listFiles().orEmpty().size)
    }

    @Test
    fun clearThumbnailsRemovesOnlyThumbnails() = runBlocking {
        repository.save(sampleProject(id = "keep"))
        writeFile(files.sourceFile("keep"))
        writeFile(files.thumbnailFile("keep"))

        cleaner.clearThumbnails()

        assertFalse(files.thumbnailFile("keep").exists())
        assertTrue(files.sourceFile("keep").isFile)
        assertEquals(listOf("keep"), repository.list().map { it.id })
    }

    @Test
    fun deleteEverythingRemovesProjectsLeftoversAndTemporaryFiles() = runBlocking {
        repository.save(sampleProject(id = "one"))
        repository.save(sampleProject(id = "two"))
        writeFile(File(files.dir("corrupt"), "project.json")).writeText("{")
        writeFile(File(files.tempDir(), "scratch.bin"))

        cleaner.deleteEverything()

        assertTrue(repository.list().isEmpty())
        assertEquals(0, files.root.listFiles().orEmpty().size)
        assertEquals(0, files.tempDir().listFiles().orEmpty().size)
    }

    @Test
    fun deleteEverythingKeepsTheFolderOfAnImportThatIsRunning() = runBlocking {
        writeFile(File(files.dir("running"), "source.mp4"))
        files.inFlight.add("running")

        cleaner.deleteEverything()

        assertTrue(files.dir("running").isDirectory)
    }
}
