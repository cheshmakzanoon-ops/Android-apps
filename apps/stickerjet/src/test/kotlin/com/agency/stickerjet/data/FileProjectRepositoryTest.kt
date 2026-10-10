package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.domain.NormalizedPoint
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.StrokeMode
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var files: ProjectFiles
    private lateinit var repository: FileProjectRepository

    @Before
    fun setUp() {
        files = projectFilesIn(tmp.root)
        repository = repositoryFor(files)
    }

    @Test
    fun saveThenLoadReturnsAnEqualProject() = runBlocking {
        val project = sampleProject(id = "r1").copy(
            maskStrokes = listOf(MaskStroke(StrokeMode.RESTORE, listOf(NormalizedPoint(0.1f, 0.2f)), radius = 0.05f)),
            exports = listOf(ExportRecord("stickerjet-clip-20240101-000000.webp", 1L, 100L, 24, 2_000L)),
        )

        repository.save(project)

        assertEquals(project, repository.load("r1"))
        assertEquals(listOf(project), repository.list())
    }

    @Test
    fun documentOnDiskIsTheVersionedEnvelope() = runBlocking {
        repository.save(sampleProject(id = "r2"))

        val root = testJson().parseToJsonElement(files.documentFile("r2").readText()).jsonObject

        assertEquals("1", root.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("r2", root.getValue("project").jsonObject.getValue("id").jsonPrimitive.content)
        assertFalse(File(files.dir("r2"), "project.json.tmp").exists())
    }

    @Test
    fun listIsSortedByUpdatedAtMsDescending() = runBlocking {
        repository.save(sampleProject(id = "a", updatedAtMs = 10L))
        repository.save(sampleProject(id = "b", updatedAtMs = 30L))
        repository.save(sampleProject(id = "c", updatedAtMs = 20L))

        assertEquals(listOf("b", "c", "a"), repository.list().map { it.id })
    }

    @Test
    fun saveOverwritesThePreviousDocument() = runBlocking {
        repository.save(sampleProject(id = "r3", title = "First"))
        repository.save(sampleProject(id = "r3", title = "Second"))

        assertEquals("Second", repository.load("r3")?.title)
        assertEquals(1, repository.list().size)
    }

    @Test
    fun failedWriteKeepsThePreviousDocument() = runBlocking {
        repository.save(sampleProject(id = "r4", title = "Kept"))
        // Blocking the temporary name with a non-empty folder makes the next write fail before the rename.
        File(files.dir("r4"), "project.json.tmp").apply { mkdirs(); File(this, "blocker").writeText("x") }

        assertThrows(IOException::class.java) {
            runBlocking { repository.save(sampleProject(id = "r4", title = "Lost")) }
        }

        assertEquals("Kept", repository.load("r4")?.title)
    }

    @Test
    fun corruptDocumentIsSkippedAndCounted() = runBlocking {
        repository.save(sampleProject(id = "good"))
        File(files.dir("bad"), "project.json").apply { parentFile.mkdirs() }.writeText("{not json")

        val scan = repository.scan()

        assertEquals(listOf("good"), scan.projects.map { it.id })
        assertEquals(1, scan.skipped)
        assertNull(repository.load("bad"))
    }

    @Test
    fun documentWhoseIdDoesNotMatchItsFolderIsSkipped() = runBlocking {
        File(files.dir("wrong"), "project.json").apply { parentFile.mkdirs() }
            .writeText(ProjectEnvelope.encode(testJson(), sampleProject(id = "right")))

        val scan = repository.scan()

        assertTrue(scan.projects.isEmpty())
        assertEquals(1, scan.skipped)
        assertNull(repository.load("wrong"))
    }

    @Test
    fun newerSchemaDocumentIsSkippedAndNotRewritten() = runBlocking {
        val future = """{"schemaVersion":2,"project":{}}"""
        File(files.dir("future"), "project.json").apply { parentFile.mkdirs() }.writeText(future)

        val scan = repository.scan()

        assertEquals(1, scan.skipped)
        assertEquals(future, files.documentFile("future").readText())
    }

    @Test
    fun folderWithoutDocumentIsCountedAsSkipped() = runBlocking {
        files.dir("empty").mkdirs()

        assertEquals(1, repository.scan().skipped)
    }

    @Test
    fun foldersWithInvalidNamesAreIgnored() = runBlocking {
        File(files.root, "not valid!").mkdirs()

        assertEquals(0, repository.scan().skipped)
    }

    @Test
    fun loadWithInvalidIdReturnsNullWithoutThrowing() = runBlocking {
        assertNull(repository.load("../escape"))
    }

    @Test
    fun deleteRemovesTheWholeProjectFolder() = runBlocking {
        repository.save(sampleProject(id = "gone"))
        files.exportsDir("gone").mkdirs()
        File(files.exportsDir("gone"), "stickerjet-x.webp").writeBytes(ByteArray(10))
        files.thumbnailFile("gone").writeBytes(ByteArray(3))
        files.sourceFile("gone").writeBytes(ByteArray(20))

        repository.delete("gone")

        assertFalse(files.dir("gone").exists())
        assertNull(repository.load("gone"))
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun deleteOfUnknownIdIsANoOp() = runBlocking {
        repository.delete("never-existed")

        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun saveWithInvalidIdIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.save(sampleProject(id = "../outside")) }
        }
        assertFalse(File(tmp.root, "outside").exists())
    }
}
