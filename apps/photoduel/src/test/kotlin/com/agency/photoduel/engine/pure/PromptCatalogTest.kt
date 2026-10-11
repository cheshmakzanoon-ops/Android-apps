package com.agency.photoduel.engine.pure

import com.agency.photoduel.domain.model.PromptCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCatalogTest {

    // ---- the real catalogue file -------------------------------------------------------------------

    private fun findCatalogueFile(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "assets/source/prompts/prompts.v1.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("assets/source/prompts/prompts.v1.json not found above ${System.getProperty("user.dir")}")
    }

    private fun realCatalogue(): PromptCatalog =
        PromptCatalogParser.parseOrThrow(findCatalogueFile().readText(Charsets.UTF_8))

    @Test
    fun theCatalogueFileIsValidAndHasThirtyPrompts() {
        val result = PromptCatalogParser.parse(findCatalogueFile().readText(Charsets.UTF_8))
        assertTrue("catalogue invalid: $result", result is PromptCatalogParseResult.Valid)
        val catalog = (result as PromptCatalogParseResult.Valid).catalog
        assertEquals("1.0.0", catalog.version)
        assertEquals(30, catalog.prompts.size)
    }

    @Test
    fun idsAreUniqueStableAndSequential() {
        val ids = realCatalogue().prompts.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals((1..30).map { "p-%03d".format(it) }, ids)
    }

    @Test
    fun titlesAndBriefsRespectTheExactSizeLimits() {
        for (prompt in realCatalogue().prompts) {
            val titleLength = prompt.title.codePointCount(0, prompt.title.length)
            val briefLength = prompt.brief.codePointCount(0, prompt.brief.length)
            assertTrue("${prompt.id} title has $titleLength characters", titleLength in 1..40)
            assertTrue("${prompt.id} brief has $briefLength characters", briefLength in 1..140)
            assertEquals("${prompt.id} title has stray blanks", prompt.title.trim(), prompt.title)
            assertEquals("${prompt.id} brief has stray blanks", prompt.brief.trim(), prompt.brief)
        }
        assertEquals(40, PromptCatalogParser.MAX_TITLE_LENGTH)
        assertEquals(140, PromptCatalogParser.MAX_BRIEF_LENGTH)
    }

    @Test
    fun titlesAreUniqueAndTextIsPlain() {
        val prompts = realCatalogue().prompts
        assertEquals(prompts.size, prompts.map { it.title.lowercase() }.toSet().size)
        for (p in prompts) {
            val text = p.title + " " + p.brief
            assertFalse("${p.id} contains markup or a link", text.contains('<') || text.contains('>') || text.contains("http", ignoreCase = true))
            assertFalse("${p.id} has digits in the title", Regex("\\d").containsMatchIn(p.title))
        }
    }

    @Test
    fun theFileUsesExactlyTheDocumentedKeys() {
        val root = Json.parseToJsonElement(findCatalogueFile().readText(Charsets.UTF_8)).jsonObject
        assertEquals(setOf("catalogVersion", "prompts"), root.keys)
        val prompts: JsonArray = root.getValue("prompts").jsonArray
        assertEquals(30, prompts.size)
        for (entry in prompts) {
            assertEquals(setOf("id", "title", "brief"), (entry as JsonObject).keys)
        }
    }

    // ---- parser behaviour --------------------------------------------------------------------------

    private fun catalogue(vararg prompts: String, version: String = "1.0.0"): String =
        """{"catalogVersion":"$version","prompts":[${prompts.joinToString(",")}]}"""

    private fun prompt(id: String = "p-001", title: String = "A title", brief: String = "A brief."): String =
        """{"id":"$id","title":"$title","brief":"$brief"}"""

    private fun invalid(json: String): List<String> {
        val result = PromptCatalogParser.parse(json)
        assertTrue("expected Invalid for $json but got $result", result is PromptCatalogParseResult.Invalid)
        return (result as PromptCatalogParseResult.Invalid).problems
    }

    @Test
    fun acceptsAMinimalValidCatalogue() {
        val result = PromptCatalogParser.parse(catalogue(prompt()))
        assertTrue(result is PromptCatalogParseResult.Valid)
        val catalog = (result as PromptCatalogParseResult.Valid).catalog
        assertEquals("1.0.0", catalog.version)
        assertEquals("p-001", catalog.prompts.single().id)
        assertEquals("A title", catalog.prompts.single().title)
        assertEquals("A brief.", catalog.prompts.single().brief)
    }

    @Test
    fun acceptsTextExactlyAtTheLimits() {
        val title = "T".repeat(40)
        val brief = "B".repeat(140)
        val result = PromptCatalogParser.parse(catalogue(prompt(title = title, brief = brief)))
        assertTrue("$result", result is PromptCatalogParseResult.Valid)
    }

    @Test
    fun rejectsTextOneCharacterOverTheLimits() {
        assertTrue(invalid(catalogue(prompt(title = "T".repeat(41)))).single().contains("title"))
        assertTrue(invalid(catalogue(prompt(brief = "B".repeat(141)))).single().contains("brief"))
    }

    @Test
    fun countsCodePointsNotUtf16Units() {
        val emojiTitle = "📷".repeat(40) // 40 code points, 80 UTF-16 units
        assertTrue(PromptCatalogParser.parse(catalogue(prompt(title = emojiTitle))) is PromptCatalogParseResult.Valid)
        val tooMany = "📷".repeat(41)
        assertTrue(invalid(catalogue(prompt(title = tooMany))).single().contains("title"))
    }

    @Test
    fun rejectsDuplicateIds() {
        val problems = invalid(catalogue(prompt("p-001", "One", "First."), prompt("p-001", "Two", "Second.")))
        assertTrue(problems.toString(), problems.any { it.contains("duplicate id") && it.contains("p-001") })
    }

    @Test
    fun rejectsDuplicateTitlesIgnoringCase() {
        val problems = invalid(catalogue(prompt("p-001", "Same", "First."), prompt("p-002", "SAME", "Second.")))
        assertTrue(problems.toString(), problems.any { it.contains("duplicate title") })
    }

    @Test
    fun rejectsEmptyAndBlankTitlesAndBriefs() {
        assertTrue(invalid(catalogue(prompt(title = ""))).any { it.contains("title is empty") })
        assertTrue(invalid(catalogue(prompt(title = "   "))).any { it.contains("title is empty") })
        assertTrue(invalid(catalogue(prompt(brief = ""))).any { it.contains("brief is empty") })
        assertTrue(invalid(catalogue(prompt(brief = "  "))).any { it.contains("brief is empty") })
    }

    @Test
    fun rejectsAnEmptyList() {
        val problems = invalid(catalogue())
        assertTrue(problems.single().contains("no prompts"))
    }

    @Test
    fun rejectsBadIdsAndVersions() {
        assertTrue(invalid(catalogue(prompt(id = "x-001"))).any { it.contains("id must look like") })
        assertTrue(invalid(catalogue(prompt(id = "p-1"))).any { it.contains("id must look like") })
        assertTrue(invalid(catalogue(prompt(id = ""))).any { it.contains("id must look like") })
        assertTrue(invalid(catalogue(prompt(), version = "1.0")).any { it.contains("catalogVersion") })
        assertTrue(invalid(catalogue(prompt(), version = "v1")).any { it.contains("catalogVersion") })
        // Longer numbers are allowed so the catalogue can grow past p-999.
        assertTrue(PromptCatalogParser.parse(catalogue(prompt(id = "p-1000"))) is PromptCatalogParseResult.Valid)
    }

    @Test
    fun rejectsLeadingBlanksAndControlCharacters() {
        assertTrue(invalid(catalogue(prompt(title = " Padded"))).any { it.contains("leading or trailing") })
        assertTrue(invalid(catalogue(prompt(brief = "Padded. "))).any { it.contains("leading or trailing") })
        assertTrue(invalid(catalogue(prompt(brief = "Line one.\\nLine two."))).any { it.contains("control character") })
        assertTrue(invalid(catalogue(prompt(title = "Tab\\there"))).any { it.contains("control character") })
    }

    @Test
    fun rejectsUnknownKeysAtEveryLevel() {
        val topLevel = """{"catalogVersion":"1.0.0","prompts":[${prompt()}],"extra":1}"""
        assertTrue(invalid(topLevel).single().startsWith("Malformed"))
        val inPrompt = """{"catalogVersion":"1.0.0","prompts":[{"id":"p-001","title":"T","brief":"B","tags":["x"]}]}"""
        assertTrue(invalid(inPrompt).single().startsWith("Malformed"))
    }

    @Test
    fun rejectsMissingFieldsWrongTypesAndBrokenJson() {
        assertTrue(invalid("""{"catalogVersion":"1.0.0","prompts":[{"id":"p-001","title":"T"}]}""").single().startsWith("Malformed"))
        assertTrue(invalid("""{"prompts":[]}""").single().startsWith("Malformed"))
        assertTrue(invalid("""{"catalogVersion":"1.0.0"}""").single().startsWith("Malformed"))
        assertTrue(invalid("""{"catalogVersion":1,"prompts":[]}""").single().startsWith("Malformed"))
        assertTrue(invalid("""{"catalogVersion":"1.0.0","prompts":[{"id":"p-001","title":null,"brief":"B"}]}""").single().startsWith("Malformed"))
        assertTrue(invalid("not json at all").single().startsWith("Malformed"))
        assertTrue(invalid("").single().startsWith("Malformed"))
        assertTrue(invalid("""{"catalogVersion":"1.0.0","prompts":[""").single().startsWith("Malformed"))
        assertTrue(invalid("[]").single().startsWith("Malformed"))
    }

    @Test
    fun reportsEveryProblemNotJustTheFirst() {
        val json = catalogue(
            prompt("p-001", "", "ok"),
            prompt("p-001", "Fine", "B".repeat(150)),
        )
        val problems = invalid(json)
        assertTrue(problems.toString(), problems.size >= 3)
    }

    @Test
    fun parseOrThrowThrowsWithAllProblems() {
        try {
            PromptCatalogParser.parseOrThrow(catalogue())
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("no prompts"))
        }
    }
}
