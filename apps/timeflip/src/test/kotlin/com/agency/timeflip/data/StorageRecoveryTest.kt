package com.agency.timeflip.data

import com.agency.timeflip.data.db.TimeFlipDatabase
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.ProjectId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StorageRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: TimeFlipDatabase
    private lateinit var rootDir: File
    private lateinit var cacheDir: File
    private lateinit var repo: RoomProjectRepository
    private lateinit var recovery: StorageRecovery

    /** Older than the grace period, so recovery is allowed to remove it. */
    private val oldMs get() = System.currentTimeMillis() - 2 * StorageLayout.IN_FLIGHT_GRACE_MS

    @Before
    fun setUp() {
        rootDir = tmp.newFolder("timeflip")
        cacheDir = tmp.newFolder("cache")
        db = TestFixtures.inMemoryDatabase()
        repo = RoomProjectRepository(db, rootDir, cacheDir)
        recovery = StorageRecovery(rootDir, cacheDir, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun missingPhotoCopyIsReportedAndProjectRowIsKept() = runBlocking {
        repo.saveProject(TestFixtures.project("p1"))
        TestFixtures.writePhotoCopy(rootDir, "p1-before")

        val report = recovery.recover()

        assertEquals(listOf(PhotoSlot.AFTER), report.missingPhotos.map { it.slot })
        assertEquals(ProjectId("p1"), report.missingPhotos.single().projectId)
        assertNotNull(repo.getProject(ProjectId("p1")))
    }

    @Test
    fun interruptedTempFilesAreRemovedAndInFlightOnesKept() = runBlocking {
        val staleImport = File(rootDir, "photos/import-stale.tmp").apply { parentFile!!.mkdirs(); writeText("x") }
        val staleExport = File(rootDir, "exports/timeflip-stale.mp4.tmp").apply { parentFile!!.mkdirs(); writeText("x") }
        val freshImport = File(rootDir, "photos/import-fresh.tmp").apply { writeText("x") }
        staleImport.setLastModified(oldMs)
        staleExport.setLastModified(oldMs)

        val report = recovery.recover()

        assertEquals(2, report.tempFilesRemoved)
        assertFalse(staleImport.exists())
        assertFalse(staleExport.exists())
        assertTrue(freshImport.exists())
    }

    @Test
    fun unreferencedCopiesAreRemovedAndReferencedOnesKept() = runBlocking {
        repo.saveProject(TestFixtures.project("p1"))
        TestFixtures.writePhotoCopy(rootDir, "p1-before")
        TestFixtures.writePhotoCopy(rootDir, "p1-after")
        val orphan = File(rootDir, "photos/orphan-copy.jpg").apply { writeText("x") }
        orphan.setLastModified(oldMs)
        File(rootDir, "photos/p1-before.jpg").setLastModified(oldMs)

        val report = recovery.recover()

        assertEquals(1, report.unreferencedFilesRemoved)
        assertFalse(orphan.exists())
        assertTrue(File(rootDir, "photos/p1-before.jpg").exists())
        assertTrue(File(rootDir, "photos/p1-after.jpg").exists())
    }

    @Test
    fun staleAndOrphanThumbnailsAreRemoved() = runBlocking {
        repo.saveProject(TestFixtures.project("p1"))
        TestFixtures.writePhotoCopy(rootDir, "p1-before")
        val thumbs = File(cacheDir, "thumbs").apply { mkdirs() }
        val fresh = File(thumbs, "p1-before-512.jpg").apply { writeText("x") }
        val expired = File(thumbs, "p1-before-256.jpg").apply { writeText("x") }
        val sourceGone = File(thumbs, "ghost-512.jpg").apply { writeText("x") }
        val expiredAge = System.currentTimeMillis() - StorageRecovery.THUMBNAIL_MAX_AGE_MS - 60_000L
        expired.setLastModified(expiredAge)

        val report = recovery.recover()

        assertEquals(2, report.staleThumbnailsRemoved)
        assertTrue(fresh.exists())
        assertFalse(expired.exists())
        assertFalse(sourceGone.exists())
    }

    @Test
    fun recoveryIsIdempotent() = runBlocking {
        repo.saveProject(TestFixtures.project("p1"))
        val first = recovery.recover()
        val second = recovery.recover()

        assertEquals(first.missingPhotos.size, second.missingPhotos.size)
        assertEquals(0, second.tempFilesRemoved)
        assertEquals(0, second.unreferencedFilesRemoved)
        assertEquals(0, second.staleThumbnailsRemoved)
        assertEquals(2, db.photoDao().all().size)
    }
}
