package com.agency.photoduel.engine.pure

import com.agency.photoduel.domain.model.Prompt
import com.agency.photoduel.domain.model.PromptCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** One prompt as stored in `assets/source/prompts/prompts.v1.json`. */
@Serializable
data class PromptEntry(val id: String, val title: String, val brief: String)

/** The whole catalogue file. Unknown keys are rejected so a typo cannot silently drop a field. */
@Serializable
data class PromptCatalogFile(val catalogVersion: String, val prompts: List<PromptEntry>)

sealed interface PromptCatalogParseResult {
    data class Valid(val catalog: PromptCatalog) : PromptCatalogParseResult

    /** [problems] are human-readable and contain no prompt text beyond ids. */
    data class Invalid(val problems: List<String>) : PromptCatalogParseResult
}

/**
 * Parses and validates the curated prompt catalogue. The same file is read by the backend, which
 * serves it at `GET /v1/prompts`, and may be bundled with the app, so this validation is strict.
 *
 * Rules: valid JSON with exactly the keys `catalogVersion` and `prompts` (and `id`, `title`,
 * `brief` per prompt); a semantic version; at least one prompt; ids of the form `p-` plus three or
 * more digits, unique; titles of 1 to [MAX_TITLE_LENGTH] characters; briefs of 1 to
 * [MAX_BRIEF_LENGTH] characters; no leading or trailing blanks and no control characters; unique
 * titles. Length is counted in Unicode code points.
 */
object PromptCatalogParser {
    const val MAX_TITLE_LENGTH: Int = 40
    const val MAX_BRIEF_LENGTH: Int = 140

    private val ID_PATTERN = Regex("^p-[0-9]{3,}$")
    private val VERSION_PATTERN = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parse(json: String): PromptCatalogParseResult {
        val file = try {
            strictJson.decodeFromString(PromptCatalogFile.serializer(), json)
        } catch (e: SerializationException) {
            return PromptCatalogParseResult.Invalid(listOf("Malformed catalogue JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"))
        } catch (e: IllegalArgumentException) {
            return PromptCatalogParseResult.Invalid(listOf("Malformed catalogue JSON: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"))
        }
        val problems = validate(file)
        if (problems.isNotEmpty()) return PromptCatalogParseResult.Invalid(problems)
        val catalog = PromptCatalog(
            version = file.catalogVersion,
            prompts = file.prompts.map { Prompt(id = it.id, title = it.title, brief = it.brief) },
        )
        return PromptCatalogParseResult.Valid(catalog)
    }

    /** Parses [json] or throws [IllegalArgumentException] listing every problem. */
    fun parseOrThrow(json: String): PromptCatalog = when (val result = parse(json)) {
        is PromptCatalogParseResult.Valid -> result.catalog
        is PromptCatalogParseResult.Invalid -> throw IllegalArgumentException(result.problems.joinToString("; "))
    }

    fun validate(file: PromptCatalogFile): List<String> {
        val problems = ArrayList<String>()
        if (!VERSION_PATTERN.matches(file.catalogVersion)) {
            problems += "catalogVersion must look like 1.0.0"
        }
        if (file.prompts.isEmpty()) {
            problems += "The catalogue has no prompts"
            return problems
        }
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        for ((index, prompt) in file.prompts.withIndex()) {
            val label = if (prompt.id.isNotEmpty()) prompt.id else "entry #${index + 1}"
            if (!ID_PATTERN.matches(prompt.id)) problems += "$label: id must look like p-001"
            if (!seenIds.add(prompt.id)) problems += "$label: duplicate id"
            checkText(label, "title", prompt.title, MAX_TITLE_LENGTH, problems)
            checkText(label, "brief", prompt.brief, MAX_BRIEF_LENGTH, problems)
            if (prompt.title.isNotBlank() && !seenTitles.add(prompt.title.lowercase())) {
                problems += "$label: duplicate title"
            }
        }
        return problems
    }

    private fun checkText(label: String, field: String, value: String, maxLength: Int, problems: MutableList<String>) {
        if (value.isBlank()) {
            problems += "$label: $field is empty"
            return
        }
        val length = value.codePointCount(0, value.length)
        if (length > maxLength) problems += "$label: $field has $length characters, the limit is $maxLength"
        if (value != value.trim()) problems += "$label: $field has leading or trailing blanks"
        if (value.any { it.isISOControl() }) problems += "$label: $field contains a control character"
    }
}
