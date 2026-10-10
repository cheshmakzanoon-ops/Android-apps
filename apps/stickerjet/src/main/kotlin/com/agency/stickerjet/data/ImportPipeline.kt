package com.agency.stickerjet.data

import com.agency.stickerjet.domain.ImportDecision
import com.agency.stickerjet.domain.ImportPolicy
import com.agency.stickerjet.domain.ImportRejection
import com.agency.stickerjet.domain.ProjectRepository
import com.agency.stickerjet.domain.SourceVideo
import com.agency.stickerjet.domain.StickerLimits
import com.agency.stickerjet.domain.StickerProjects
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the provider reports about a clip before it is copied. [sizeBytes] is null when unknown.
 * [displayName] is the picked file's name, or an empty string when it could not be read.
 */
internal data class ClipInfo(val sizeBytes: Long?, val displayName: String)

/**
 * Where a picked clip comes from. Production wraps a content URI. Tests supply in-memory streams.
 * Lost access surfaces as [FileNotFoundException] or [SecurityException].
 */
internal interface ClipSource {
    fun describe(): ClipInfo

    fun openStream(): InputStream
}

/**
 * The import steps with no Android types: size check, storage check, capped copy, probe, policy,
 * thumbnail and save. [ImportService] supplies the Android pieces, and tests supply fakes.
 */
internal class ImportPipeline(
    private val files: ProjectFiles,
    private val probe: MediaProbe,
    private val storage: StorageGuard,
    private val repository: ProjectRepository,
    private val clock: () -> Long,
    private val maxSourceBytes: Long = StickerLimits.MAX_SOURCE_BYTES,
) {

    /**
     * Runs one import into the new project [projectId]. Every outcome other than Imported leaves no
     * project folder behind. So does any exception, including cancellation.
     */
    suspend fun run(projectId: String, clip: ClipSource): ImportOutcome = withContext(Dispatchers.IO) {
        val dir = files.dir(projectId)
        require(!files.documentFile(projectId).exists()) { "A project with this id already exists" }
        files.inFlight.add(projectId)
        try {
            dir.deleteRecursively() // remnants of an earlier attempt with the same id
            val outcome = execute(projectId, clip)
            if (outcome !is ImportOutcome.Imported) dir.deleteRecursively()
            outcome
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        } finally {
            files.inFlight.remove(projectId)
        }
    }

    private suspend fun execute(projectId: String, clip: ClipSource): ImportOutcome {
        val info = clip.describe()

        val declared = info.sizeBytes
        if (declared != null && declared > maxSourceBytes) return ImportOutcome.Rejected(ImportRejection.TOO_LARGE)
        if (!storage.hasRoomFor(declared ?: maxSourceBytes)) return ImportOutcome.Failed(ImportFailure.LOW_STORAGE)

        val source = files.sourceFile(projectId)
        val copy = try {
            clip.openStream().use { input -> copyWithLimit(input, source, maxSourceBytes) }
        } catch (e: FileNotFoundException) {
            return ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED)
        } catch (e: SecurityException) {
            return ImportOutcome.Failed(ImportFailure.ACCESS_REVOKED)
        } catch (e: IOException) {
            return ImportOutcome.Failed(failureFor(e))
        }
        val copied = when (copy) {
            is CopyOutcome.Copied -> copy
            CopyOutcome.TooLarge -> return ImportOutcome.Rejected(ImportRejection.TOO_LARGE)
        }

        val probed = try {
            probe.probe(source)
        } catch (e: RuntimeException) {
            return ImportOutcome.Rejected(ImportRejection.UNREADABLE)
        }
        val accepted = when (val decision = ImportPolicy.evaluate(probed)) {
            is ImportDecision.Accepted -> decision
            is ImportDecision.Rejected -> return ImportOutcome.Rejected(decision.reason)
        }

        writeThumbnailBestEffort(source, files.thumbnailFile(projectId))

        val sourceVideo = SourceVideo(
            fileName = ProjectFiles.SOURCE_NAME,
            durationMs = accepted.durationMs,
            width = accepted.orientedWidth,
            height = accepted.orientedHeight,
            rotationDegrees = accepted.rotationDegrees,
            sizeBytes = copied.bytes,
        )
        val project = StickerProjects.create(projectId, sourceVideo, info.displayName, clock())
        return try {
            repository.save(project)
            ImportOutcome.Imported(project)
        } catch (e: IOException) {
            ImportOutcome.Failed(failureFor(e))
        }
    }

    /** A missing or broken thumbnail never fails the import: the project simply has no preview. */
    private fun writeThumbnailBestEffort(source: File, target: File) {
        val temp = File(target.absoluteFile.parentFile, target.name + TEMP_SUFFIX)
        try {
            if (probe.writeThumbnail(source, temp, ThumbnailSize.MAX_SIDE_PX)) replaceFile(temp, target)
        } catch (e: IOException) {
            // Not fatal. The project keeps no preview.
        } catch (e: RuntimeException) {
            // Not fatal. A decoder failure must not undo a good import.
        } finally {
            temp.delete()
        }
    }

    private fun failureFor(error: IOException): ImportFailure =
        if (isOutOfSpace(error)) ImportFailure.LOW_STORAGE else ImportFailure.IO_ERROR

    /** Recognizes ENOSPC in the message or in any cause, because Android reports it as text. */
    private fun isOutOfSpace(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            val message = cause.message ?: return@any false
            message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
        }
}
