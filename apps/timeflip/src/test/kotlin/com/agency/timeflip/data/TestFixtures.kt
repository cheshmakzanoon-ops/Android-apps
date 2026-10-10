package com.agency.timeflip.data

import androidx.room.Room
import com.agency.timeflip.data.db.TimeFlipDatabase
import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.ExportProfile
import com.agency.timeflip.domain.model.ImagePair
import com.agency.timeflip.domain.model.OutputPreset
import com.agency.timeflip.domain.model.PhotoId
import com.agency.timeflip.domain.model.PhotoSlot
import com.agency.timeflip.domain.model.PixelSize
import com.agency.timeflip.domain.model.ProjectId
import com.agency.timeflip.domain.model.SourcePhoto
import com.agency.timeflip.domain.model.TimeFlipProject
import com.agency.timeflip.domain.model.TransitionDefinition
import com.agency.timeflip.domain.model.TransitionKind
import com.agency.timeflip.domain.model.TransitionParameters
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.robolectric.RuntimeEnvironment

/** Shared builders for data-layer tests. Values are chosen to expose lossy storage (non-terminating doubles). */
internal object TestFixtures {

    fun context() = RuntimeEnvironment.getApplication()

    fun photo(
        id: String,
        slot: PhotoSlot,
        width: Int = 1200,
        height: Int = 1600,
        byteSize: Long = 1000L,
    ) = SourcePhoto(
        id = PhotoId(id),
        slot = slot,
        size = PixelSize(width, height),
        byteSize = byteSize,
        importedAtEpochMs = 10L,
    )

    fun project(
        id: String,
        name: String = "Project $id",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
        photoPrefix: String = id,
    ): TimeFlipProject = TimeFlipProject(
        id = ProjectId(id),
        name = name,
        pair = ImagePair(
            before = photo("$photoPrefix-before", PhotoSlot.BEFORE),
            after = photo("$photoPrefix-after", PhotoSlot.AFTER),
        ),
        alignment = AlignmentTransform(
            scale = 1.2345678901234567,
            rotationDegrees = -12.375,
            offsetX = 0.0123456789012345,
            offsetY = -0.5,
        ),
        alignmentSource = AlignmentSource.MANUAL,
        alignmentConfidence = null,
        transition = TransitionDefinition(
            kind = TransitionKind.DIRECTIONAL_WIPE,
            parameters = TransitionParameters(
                durationMs = 1750,
                easing = Easing.EASE_OUT_CUBIC,
                reversed = true,
                wipeAngleDegrees = 33.333333333333336,
            ),
        ),
        exportProfile = ExportProfile(preset = OutputPreset.SQUARE_1080, fps = 60),
        createdAtEpochMs = createdAt,
        updatedAtEpochMs = updatedAt,
    )

    /** In-memory database with the same configuration as the app (migrations, foreign keys). */
    fun inMemoryDatabase(): TimeFlipDatabase =
        TimeFlipDatabase.configure(Room.inMemoryDatabaseBuilder(context(), TimeFlipDatabase::class.java))
            .allowMainThreadQueries()
            .build()

    /** On-disk database under a unique name, so a test can close it and reopen it. */
    fun fileDatabase(name: String): TimeFlipDatabase =
        TimeFlipDatabase.configure(Room.databaseBuilder(context(), TimeFlipDatabase::class.java, name))
            .allowMainThreadQueries()
            .build()

    fun uniqueDatabaseName(): String = "test-${UUID.randomUUID()}.db"

    /** Writes bytes as a photo copy, as the store would have written it. */
    fun writePhotoCopy(rootDir: java.io.File, id: String, content: String = "copy-$id") {
        val photos = java.io.File(rootDir, StorageLayout.PHOTOS_DIR)
        photos.mkdirs()
        java.io.File(photos, id + StorageLayout.PHOTO_EXTENSION).writeText(content)
    }

    fun assertInvalidProject(block: suspend () -> Unit) {
        val failure = try {
            runBlocking { block() }
            null
        } catch (error: TimeFlipException) {
            error
        }
        assertNotNull("expected a TimeFlipException", failure)
        assertEquals(ErrorCategory.INVALID_PROJECT, failure!!.error.category)
    }
}
