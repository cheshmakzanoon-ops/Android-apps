package com.agency.stickerjet.ui.robot

import com.agency.stickerjet.domain.AlphaMask
import com.agency.stickerjet.domain.MaskStroke
import com.agency.stickerjet.fakes.FakeFrameSource
import com.agency.stickerjet.fakes.FakeSubjectSegmenter
import com.agency.stickerjet.fakes.InMemoryProjectRepository
import com.agency.stickerjet.ui.ports.LibraryMaintenancePort
import com.agency.stickerjet.ui.ports.MaskRefinementPort
import com.agency.stickerjet.ui.ports.OnboardingPort
import com.agency.stickerjet.ui.ports.UiPorts
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * A complete set of ports for instrumented tests: domain fakes from core testFixtures, plus the robot
 * doubles for the platform-facing ports. [onboardingCompleted] chooses the first screen.
 */
fun robotPorts(root: File, onboardingCompleted: Boolean): UiPorts {
    val repository = InMemoryProjectRepository()
    val files = RobotFiles(root)
    return UiPorts(
        projects = repository,
        frames = FakeFrameSource(),
        segmenter = FakeSubjectSegmenter.noSubject(),
        files = files,
        importer = RobotImporter(files, repository) { System.currentTimeMillis() },
        exporter = RobotExporter(files, repository),
        preview = RobotPreview(),
        maintenance = NoopMaintenance,
        storage = RobotStorage,
        onboarding = FixedOnboarding(onboardingCompleted),
        masks = NoStrokeMasks,
    )
}

private object NoopMaintenance : LibraryMaintenancePort {
    override suspend fun clearTemporaryFiles() = Unit
    override suspend fun deleteEverything() = Unit
}

private class FixedOnboarding(completed: Boolean) : OnboardingPort {
    override val completed = flowOf(completed)
    override suspend fun markCompleted() = Unit
}

/** Refinement with no strokes applied: an absent base is fully opaque, as in the domain. */
private object NoStrokeMasks : MaskRefinementPort {
    override fun refine(base: AlphaMask?, width: Int, height: Int, strokes: List<MaskStroke>): AlphaMask =
        base ?: AlphaMask(width, height, ByteArray(width * height) { 0xFF.toByte() })
}
