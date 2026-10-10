package com.agency.stickerjet.ui.robot

import android.graphics.Bitmap
import com.agency.stickerjet.domain.ExportRecord
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.SourceVideo
import com.agency.stickerjet.domain.StickerProject
import com.agency.stickerjet.domain.StickerProjects
import com.agency.stickerjet.ui.ports.ClipImportPort
import com.agency.stickerjet.ui.ports.ExportPort
import com.agency.stickerjet.ui.ports.ExportResult
import com.agency.stickerjet.ui.ports.ImportResult
import com.agency.stickerjet.ui.ports.PreviewPort
import com.agency.stickerjet.ui.ports.ProjectFilesPort
import com.agency.stickerjet.ui.ports.RenderedPreview
import com.agency.stickerjet.ui.ports.StoragePort
import java.io.File

/** Project files under a root directory, the same layout as the app's private storage. */
class RobotFiles(private val root: File) : ProjectFilesPort {
    override fun sourceFile(projectId: String): File = File(File(root, projectId), "source.mp4")
    override fun thumbnailFile(projectId: String): File = File(File(root, projectId), "thumbnail.jpg")
    override fun exportsDir(projectId: String): File = File(File(root, projectId), "exports")
}

/** Creates a project for a picked clip. Writes a placeholder source so the editor sees the file. */
class RobotImporter(
    private val files: ProjectFilesPort,
    private val repository: ProjectRepository,
    private val nowMs: () -> Long,
) : ClipImportPort {
    override suspend fun import(contentUri: String, projectId: String): ImportResult {
        files.sourceFile(projectId).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(SOURCE_BYTES))
        }
        val project = StickerProjects.create(
            id = projectId,
            source = SourceVideo(
                fileName = "source.mp4",
                durationMs = SOURCE_MS,
                width = 480,
                height = 360,
                rotationDegrees = 0,
                sizeBytes = SOURCE_BYTES.toLong(),
            ),
            displayName = "robot clip.mp4",
            nowMs = nowMs(),
        )
        repository.save(project)
        return ImportResult.Imported(project)
    }

    private companion object {
        const val SOURCE_MS = 10_000L
        const val SOURCE_BYTES = 64
    }
}

/** Writes a small export file and records it on the project, as the data layer does. */
class RobotExporter(
    private val files: ProjectFilesPort,
    private val repository: ProjectRepository,
) : ExportPort {
    override suspend fun export(project: StickerProject, onProgress: (Float) -> Unit): ExportResult {
        onProgress(0.5f)
        val dir = files.exportsDir(project.id).apply { mkdirs() }
        val name = "robot-export.webp"
        val file = File(dir, name).apply { writeBytes(ByteArray(EXPORT_BYTES)) }
        val record = ExportRecord(
            fileName = name,
            createdAtMs = 1L,
            sizeBytes = EXPORT_BYTES.toLong(),
            frameCount = FRAMES,
            durationMs = project.trim.durationMs,
        )
        repository.save(project.copy(exports = project.exports + record))
        onProgress(1f)
        return ExportResult.Success(file, record, subjectFrames = 0, totalFrames = FRAMES)
    }

    private companion object {
        const val EXPORT_BYTES = 128
        const val FRAMES = 24
    }
}

/** Renders a short solid-colour loop, so the Preview tab has real bitmaps to show. */
class RobotPreview : PreviewPort {
    override suspend fun render(project: StickerProject, canvasPx: Int): RenderedPreview {
        val frames = List(FRAMES) {
            Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888).apply { eraseColor(TEAL) }
        }
        return RenderedPreview(
            frames = frames,
            durationsMs = List(FRAMES) { 500 },
            subjectFrames = 0,
            totalFrames = FRAMES,
        )
    }

    private companion object {
        const val FRAMES = 4
        const val TEAL: Int = 0xFF2BB3A3.toInt()
    }
}

/** Storage with room to spare, so the robot never hits the low-storage path. */
object RobotStorage : StoragePort {
    override fun hasRoomFor(bytes: Long): Boolean = true
}
