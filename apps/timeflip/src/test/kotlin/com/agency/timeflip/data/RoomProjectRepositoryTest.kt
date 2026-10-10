package com.agency.timeflip.data

import com.agency.timeflip.data.db.PhotoEntity
import com.agency.timeflip.data.db.TimeFlipDatabase
import com.agency.timeflip.domain.model.ExportedVideo
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.rules.ProjectRules
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: TimeFlipDatabase
    private lateinit var rootDir: File
    private lateinit var cacheDir: File
    private lateinit var repo: RoomProjectRepository

    @Before
    fun setUp() {
        rootDir = tmp.newFolder("timeflip")
        cacheDir = tmp.newFolder("cache")
        db = TestFixtures.inMemoryDatabase()
        repo = RoomProjectRepository(db, rootDir, cacheDir)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedProjectReopensWithExactValuesAndNullConfidence() = runBlocking {
        val name = TestFixtures.uniqueDatabaseName()
        val original = TestFixtures.project("p1")
        val first = TestFixtures.fileDatabase(name)
        RoomProjectRepository(first, rootDir, cacheDir).saveProject(original)
        first.close()

        val reopened = TestFixtures.fileDatabase(name)
        try {
            val loaded = RoomProjectRepository(reopened, rootDir, cacheDir).getProject(ProjectId("p1"))
            assertNotNull(loaded)
            assertEquals(original, loaded)
            assertEquals(original.alignment, loaded!!.alignment)
            assertEquals(original.transition.parameters, loaded.transition.parameters)
            assertNull(loaded.alignmentConfidence)
        } finally {
            reopened.close()
            TestFixtures.context().deleteDatabase(name)
        }
    }

    @Test
    fun savingAnExistingProjectKeepsItsPhotosAndExports() = runBlocking {
        val project = TestFixtures.project("p1")
        repo.saveProject(project)
        repo.recordExport(export("p1", "p1-a.mp4", createdAt = 5_000L))

        repo.saveProject(project.copy(name = "Renamed", updatedAtEpochMs = 2_000L))

        assertEquals("Renamed", repo.getProject(ProjectId("p1"))!!.name)
        assertEquals(1, repo.listExports(ProjectId("p1")).size)
        assertEquals(2, db.photoDao().forProject("p1").size)
    }

    @Test
    fun swappingAndReplacingPhotosKeepsExactlyTwoRows() = runBlocking {
        val project = TestFixtures.project("p1")
        repo.saveProject(project)

        val swapped = ProjectRules.swapPhotos(project, nowEpochMs = 2_000L)
        repo.saveProject(swapped)
        assertEquals(project.pair.after.id, repo.getProject(ProjectId("p1"))!!.pair.before.id)

        val replacement = TestFixtures.photo("p1-new", PhotoSlot.AFTER)
        val replaced = ProjectRules.replacePhoto(swapped, replacement, nowEpochMs = 3_000L)
        repo.saveProject(replaced)

        val loaded = repo.getProject(ProjectId("p1"))!!
        assertEquals(replacement.id, loaded.pair.after.id)
        assertEquals(2, db.photoDao().forProject("p1").size)
    }

    @Test
    fun libraryIsOrderedByUpdatedAtWithExportCounts() = runBlocking {
        repo.saveProject(TestFixtures.project("p1", updatedAt = 1_000L))
        repo.saveProject(TestFixtures.project("p2", updatedAt = 3_000L))
        repo.saveProject(TestFixtures.project("p3", updatedAt = 2_000L))
        repo.recordExport(export("p2", "p2-a.mp4", createdAt = 4_000L))
        repo.recordExport(export("p2", "p2-b.mp4", createdAt = 4_100L))
        repo.recordExport(export("p3", "p3-a.mp4", createdAt = 4_200L))

        val library = repo.observeProjects().first()

        assertEquals(listOf("p2", "p3", "p1"), library.map { it.id.value })
        assertEquals(listOf(2, 1, 0), library.map { it.exportCount })
        assertEquals(listOf("p2-after", "p3-after", "p1-after"), library.map { it.thumbnailPhotoId?.value })

        repo.saveProject(TestFixtures.project("p1", updatedAt = 5_000L))
        assertEquals("p1", repo.observeProjects().first().first().id.value)
    }

    @Test
    fun deletingAProjectCascadesRowsAndOnlyItsOwnFiles() = runBlocking {
        repo.saveProject(TestFixtures.project("p1"))
        repo.saveProject(TestFixtures.project("p2"))
        repo.recordExport(export("p1", "p1-a.mp4", createdAt = 4_000L))
        repo.recordExport(export("p2", "p2-a.mp4", createdAt = 4_000L))
        TestFixtures.writePhotoCopy(rootDir, "p1-before")
        TestFixtures.writePhotoCopy(rootDir, "p1-after")
        TestFixtures.writePhotoCopy(rootDir, "p2-before")
        TestFixtures.writePhotoCopy(rootDir, "p2-after")
        writeFile(File(cacheDir, "thumbs/p1-before-512.jpg"))
        writeFile(File(cacheDir, "thumbs/p2-before-512.jpg"))
        writeFile(File(rootDir, "exports/p1-a.mp4"))
        writeFile(File(rootDir, "exports/p2-a.mp4"))

        repo.deleteProject(ProjectId("p1"))

        assertNull(repo.getProject(ProjectId("p1")))
        assertTrue(repo.listExports(ProjectId("p1")).isEmpty())
        assertEquals(listOf("p2", "p2"), db.photoDao().all().map { it.projectId })
        assertEquals(listOf("p2-a.mp4"), db.exportDao().allFileNames())

        assertFalse(File(rootDir, "photos/p1-before.jpg").exists())
        assertFalse(File(rootDir, "photos/p1-after.jpg").exists())
        assertFalse(File(cacheDir, "thumbs/p1-before-512.jpg").exists())
        assertFalse(File(rootDir, "exports/p1-a.mp4").exists())
        assertTrue(File(rootDir, "photos/p2-before.jpg").exists())
        assertTrue(File(rootDir, "photos/p2-after.jpg").exists())
        assertTrue(File(cacheDir, "thumbs/p2-before-512.jpg").exists())
        assertTrue(File(rootDir, "exports/p2-a.mp4").exists())
    }

    @Test
    fun duplicateCreatesIndependentRowsAndPhotoCopies() = runBlocking {
        val original = TestFixtures.project("p1")
        repo.saveProject(original)
        repo.recordExport(export("p1", "p1-a.mp4", createdAt = 4_000L))
        TestFixtures.writePhotoCopy(rootDir, "p1-before", content = "BEFORE-BYTES")
        TestFixtures.writePhotoCopy(rootDir, "p1-after", content = "AFTER-BYTES")

        val newId = repo.duplicateProject(ProjectId("p1"), ProjectId("p1-copy"), nowEpochMs = 9_000L)

        assertEquals(ProjectId("p1-copy"), newId)
        val copy = repo.getProject(newId)!!
        assertEquals("Project p1 copy", copy.name)
        assertEquals(9_000L, copy.createdAtEpochMs)
        assertEquals(9_000L, copy.updatedAtEpochMs)
        assertEquals(original.alignment, copy.alignment)
        assertEquals(original.transition, copy.transition)
        assertTrue(repo.listExports(newId).isEmpty())

        assertFalse(copy.pair.before.id == original.pair.before.id)
        val copiedBefore = File(rootDir, "photos/${copy.pair.before.id.value}.jpg")
        assertEquals("BEFORE-BYTES", copiedBefore.readText())
        assertEquals("AFTER-BYTES", File(rootDir, "photos/${copy.pair.after.id.value}.jpg").readText())

        repo.deleteProject(ProjectId("p1"))
        assertEquals("BEFORE-BYTES", copiedBefore.readText())
        assertNotNull(repo.getProject(newId))
    }

    @Test
    fun missingPhotoRowIsReportedAsInvalidProject() {
        runBlocking { repo.saveProject(TestFixtures.project("p1")) }
        runBlocking {
            db.photoDao().deleteForProject("p1")
            db.photoDao().insertAll(
                listOf(
                    PhotoEntity(
                        id = "p1-after",
                        projectId = "p1",
                        slot = PhotoSlot.AFTER.name,
                        width = 1200,
                        height = 1600,
                        byteSize = 1000L,
                        importedAtEpochMs = 10L,
                    ),
                ),
            )
        }
        TestFixtures.assertInvalidProject { repo.getProject(ProjectId("p1")) }
    }

    @Test
    fun unknownStoredEnumNameIsReportedAsInvalidProject() {
        runBlocking { repo.saveProject(TestFixtures.project("p1")) }
        db.openHelper.writableDatabase.execSQL("UPDATE projects SET easing = 'BOGUS' WHERE id = ?", arrayOf<Any>("p1"))
        TestFixtures.assertInvalidProject { repo.getProject(ProjectId("p1")) }
    }

    @Test
    fun exportForUnknownProjectIsReportedAsInvalidProject() {
        TestFixtures.assertInvalidProject { repo.recordExport(export("missing", "missing.mp4", createdAt = 1L)) }
    }

    @Test
    fun deleteAllDataRemovesRowsAndEveryFileDirectory() = runBlocking {
        repo.saveProject(TestFixtures.project("p1"))
        repo.recordExport(export("p1", "p1-a.mp4", createdAt = 4_000L))
        TestFixtures.writePhotoCopy(rootDir, "p1-before")
        writeFile(File(cacheDir, "thumbs/p1-before-512.jpg"))
        writeFile(File(rootDir, "exports/p1-a.mp4"))

        repo.deleteAllData()

        assertTrue(repo.observeProjects().first().isEmpty())
        assertNull(repo.getProject(ProjectId("p1")))
        assertTrue(db.photoDao().all().isEmpty())
        assertTrue(db.exportDao().allFileNames().isEmpty())
        assertFalse(rootDir.exists())
        assertFalse(File(cacheDir, "thumbs").exists())
    }

    private fun export(projectId: String, fileName: String, createdAt: Long) = ExportedVideo(
        projectId = ProjectId(projectId),
        fileName = fileName,
        sizeBytes = 100L,
        durationMs = 2_800,
        width = 1080,
        height = 1920,
        fps = 30,
        createdAtEpochMs = createdAt,
    )

    private fun writeFile(file: File) {
        file.parentFile?.mkdirs()
        file.writeText("data")
    }
}
