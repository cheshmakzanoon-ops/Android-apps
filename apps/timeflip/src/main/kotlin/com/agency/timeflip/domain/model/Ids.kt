package com.agency.timeflip.domain.model

/** Stable identifier of a saved transformation project. */
@JvmInline
value class ProjectId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProjectId must not be blank" }
    }
}

/** Identifier of a photograph copied into app-private storage. Never a content URI. */
@JvmInline
value class PhotoId(val value: String) {
    init {
        require(value.isNotBlank()) { "PhotoId must not be blank" }
    }
}

/**
 * Opaque reference supplied by the platform (for example a Photo Picker content URI string).
 * Only the data layer interprets it; the domain and engine never read it directly.
 */
@JvmInline
value class PhotoSourceRef(val value: String)
