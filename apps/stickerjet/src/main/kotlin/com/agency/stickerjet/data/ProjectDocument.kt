package com.agency.stickerjet.data

import com.agency.stickerjet.domain.StickerProject
import java.io.IOException
import kotlinx.serialization.json.Json

/** What is on disk for one project id. */
internal sealed interface DocumentState {
    data class Readable(val project: StickerProject) : DocumentState

    /** No project document: an interrupted import, or a folder left behind by a crash or a partial delete. */
    data object Missing : DocumentState

    /** Present but unusable: not JSON, wrong shape, too large, or an id that does not match its folder. */
    data object Corrupt : DocumentState

    /** Written by a newer app version. */
    data class NewerSchema(val version: Int) : DocumentState

    /** The file exists but could not be read. Maintenance must not treat this as garbage. */
    data object IoError : DocumentState
}

/** Largest document accepted. Real projects are a few kilobytes, so anything bigger is damage. */
private const val MAX_DOCUMENT_BYTES: Long = 8L * 1024L * 1024L

/** Reads and classifies the document of project [id]. Never throws for a damaged document. */
internal fun readDocument(files: ProjectFiles, json: Json, id: String): DocumentState {
    val file = files.documentFile(id)
    if (!file.isFile) return DocumentState.Missing
    if (file.length() > MAX_DOCUMENT_BYTES) return DocumentState.Corrupt
    val text = try {
        file.readText(Charsets.UTF_8)
    } catch (e: IOException) {
        return DocumentState.IoError
    }
    return when (val result = ProjectEnvelope.decode(json, text)) {
        is EnvelopeResult.Ok ->
            if (result.project.id == id) DocumentState.Readable(result.project) else DocumentState.Corrupt
        EnvelopeResult.Corrupt -> DocumentState.Corrupt
        is EnvelopeResult.NewerSchema -> DocumentState.NewerSchema(result.version)
    }
}
