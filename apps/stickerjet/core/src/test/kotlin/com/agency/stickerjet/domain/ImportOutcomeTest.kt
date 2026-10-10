package com.agency.stickerjet.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportOutcomeTest {

    /** No `else` branch: this compiles only while every ImportOutcome variant is handled. */
    private fun describe(outcome: ImportOutcome): String = when (outcome) {
        is ImportOutcome.Imported -> "imported:${outcome.project.id}"
        is ImportOutcome.Rejected -> "rejected:${outcome.reason.name}"
        is ImportOutcome.Failed -> when (outcome.cause) {
            ImportFailure.ACCESS_REVOKED -> "failed:access"
            ImportFailure.LOW_STORAGE -> "failed:storage"
            ImportFailure.IO_ERROR -> "failed:io"
        }
    }

    @Test
    fun sealedHierarchyIsExhaustiveInAWhenExpression() {
        val project = StickerProjects.create(
            id = "p",
            source = SourceVideo(fileName = "source.mp4", durationMs = 2_000L, width = 640, height = 480, rotationDegrees = 0, sizeBytes = 1L),
            displayName = "clip.mp4",
            nowMs = 1L,
        )
        assertEquals("imported:p", describe(ImportOutcome.Imported(project)))
        assertEquals("rejected:TOO_LONG", describe(ImportOutcome.Rejected(ImportRejection.TOO_LONG)))
        assertEquals("failed:storage", describe(ImportOutcome.Failed(ImportFailure.LOW_STORAGE)))
        assertEquals("failed:access", describe(ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED)))
        assertEquals("failed:io", describe(ImportOutcome.Failed(ImportFailure.IO_ERROR)))
    }
}
