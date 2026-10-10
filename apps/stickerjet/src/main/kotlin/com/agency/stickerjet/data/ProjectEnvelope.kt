package com.agency.stickerjet.data

import com.agency.stickerjet.domain.StickerProject
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

/** Upgrades an envelope from version N to N + 1. Receives and returns the whole envelope object. */
internal typealias EnvelopeMigration = (JsonObject) -> JsonObject

/** Outcome of decoding one stored document. */
internal sealed interface EnvelopeResult {
    data class Ok(val project: StickerProject) : EnvelopeResult

    /** Not JSON, not an envelope, or a project that does not match the schema. */
    data object Corrupt : EnvelopeResult

    /** Written by a newer app version. It must stay on disk and must not be overwritten by this version. */
    data class NewerSchema(val version: Int) : EnvelopeResult
}

/**
 * Versioned wrapper around every stored project: `{"schemaVersion":1,"project":{...}}`.
 *
 * Migrations are keyed by the version they upgrade from. A document is upgraded one step at a time to
 * [CURRENT_VERSION] before its project is decoded, so each step only needs to know one schema. Unknown
 * keys are ignored by the Json configuration, which lets a version add fields without a migration.
 */
object ProjectEnvelope {
    internal const val CURRENT_VERSION: Int = 1
    internal const val KEY_VERSION: String = "schemaVersion"
    internal const val KEY_PROJECT: String = "project"

    /** Empty while version 1 is the only schema. Add `1 to { ... }` here when version 2 ships. */
    internal val MIGRATIONS: Map<Int, EnvelopeMigration> = emptyMap()

    /**
     * The Json configuration every project document is written and read with. Share one instance
     * per app, or create it with this function, so the composition root and the tests agree.
     */
    fun createJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Serializes [project] inside the envelope at [CURRENT_VERSION]. */
    internal fun encode(json: Json, project: StickerProject): String =
        buildJsonObject {
            put(KEY_VERSION, JsonPrimitive(CURRENT_VERSION))
            put(KEY_PROJECT, json.encodeToJsonElement(StickerProject.serializer(), project))
        }.toString()

    /**
     * Decodes [text], upgrading it through [migrations] up to [currentVersion]. A document whose
     * version is above [currentVersion] is reported as [EnvelopeResult.NewerSchema] and not decoded.
     */
    internal fun decode(
        json: Json,
        text: String,
        currentVersion: Int = CURRENT_VERSION,
        migrations: Map<Int, EnvelopeMigration> = MIGRATIONS,
    ): EnvelopeResult {
        val envelope = parseObject(json, text) ?: return EnvelopeResult.Corrupt
        val version = (envelope[KEY_VERSION] as? JsonPrimitive)?.intOrNull
        if (version == null || version < 1) return EnvelopeResult.Corrupt
        if (version > currentVersion) return EnvelopeResult.NewerSchema(version)

        var upgraded = envelope
        for (from in version until currentVersion) {
            val migrate = migrations[from] ?: return EnvelopeResult.Corrupt
            upgraded = JsonObject(migrate(upgraded) + (KEY_VERSION to JsonPrimitive(from + 1)))
        }
        val projectElement = upgraded[KEY_PROJECT] ?: return EnvelopeResult.Corrupt
        return try {
            EnvelopeResult.Ok(json.decodeFromJsonElement(StickerProject.serializer(), projectElement))
        } catch (e: SerializationException) {
            EnvelopeResult.Corrupt
        } catch (e: IllegalArgumentException) {
            EnvelopeResult.Corrupt
        }
    }

    private fun parseObject(json: Json, text: String): JsonObject? =
        try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
