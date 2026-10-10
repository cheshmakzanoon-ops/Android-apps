package com.agency.timeflip.data.db

import com.agency.timeflip.domain.error.ErrorCategory
import com.agency.timeflip.domain.error.TimeFlipException
import com.agency.timeflip.domain.model.AlignmentSource
import com.agency.timeflip.domain.model.AlignmentTransform
import com.agency.timeflip.domain.model.Easing
import com.agency.timeflip.domain.model.ExportProfile
import com.agency.timeflip.domain.model.ExportedVideo
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pure JVM tests: the mapping layer has no Android dependency. */
class EntityMappersTest {

    private fun photo(id: String, slot: PhotoSlot) = SourcePhoto(
        id = PhotoId(id),
        slot = slot,
        size = PixelSize(1200, 1600),
        byteSize = 4_194_304L,
        importedAtEpochMs = 42L,
    )

    private fun project(confidence: Double? = null) = TimeFlipProject(
        id = ProjectId("p1"),
        name = "Kitchen",
        pair = ImagePair(photo("b", PhotoSlot.BEFORE), photo("a", PhotoSlot.AFTER)),
        alignment = AlignmentTransform(
            scale = 0.9876543210987654,
            rotationDegrees = 44.99999999999999,
            offsetX = -0.3333333333333333,
            offsetY = 0.7071067811865476,
        ),
        alignmentSource = AlignmentSource.AUTOMATIC,
        alignmentConfidence = confidence,
        transition = TransitionDefinition(
            kind = TransitionKind.VERTICAL_REVEAL,
            parameters = TransitionParameters(durationMs = 2999, easing = Easing.LINEAR, reversed = false, wipeAngleDegrees = -0.5),
        ),
        exportProfile = ExportProfile(preset = OutputPreset.LANDSCAPE_1080, fps = 24),
        createdAtEpochMs = 100L,
        updatedAtEpochMs = 200L,
    )

    private fun reload(project: TimeFlipProject): TimeFlipProject {
        val photos = project.toPhotoEntities()
        return project.toProjectEntity().toDomain(photos)
    }

    @Test
    fun projectRoundTripsExactly() {
        val original = project(confidence = 0.8765432109876543)
        assertEquals(original, reload(original))
    }

    @Test
    fun nullConfidenceStaysNull() {
        val original = project(confidence = null)
        assertNull(reload(original).alignmentConfidence)
    }

    @Test
    fun exportRoundTripsExactly() {
        val video = ExportedVideo(
            projectId = ProjectId("p1"),
            fileName = "p1-1.mp4",
            sizeBytes = 2_300_000_000L,
            durationMs = 2800,
            width = 1080,
            height = 1920,
            fps = 30,
            createdAtEpochMs = 5L,
        )
        assertEquals(video, video.toExportEntity().toDomain())
    }

    @Test
    fun unknownEnumNameFailsWithInvalidProject() {
        val entity = project().toProjectEntity().copy(easing = "EASE_SIDEWAYS")
        val error = assertThrows(TimeFlipException::class.java) { entity.toDomain(project().toPhotoEntities()) }
        assertEquals(ErrorCategory.INVALID_PROJECT, error.error.category)
    }

    @Test
    fun rowThatBreaksDomainRulesFailsWithInvalidProject() {
        val entity = project().toProjectEntity().copy(fps = 7)
        val error = assertThrows(TimeFlipException::class.java) { entity.toDomain(project().toPhotoEntities()) }
        assertEquals(ErrorCategory.INVALID_PROJECT, error.error.category)
    }

    @Test
    fun missingPhotoRowFailsWithInvalidProject() {
        val onlyAfter = project().toPhotoEntities().filter { it.slot == PhotoSlot.AFTER.name }
        val error = assertThrows(TimeFlipException::class.java) { project().toProjectEntity().toDomain(onlyAfter) }
        assertEquals(ErrorCategory.INVALID_PROJECT, error.error.category)
    }
}
