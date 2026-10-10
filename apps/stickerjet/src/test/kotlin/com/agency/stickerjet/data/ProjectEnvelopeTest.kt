package com.agency.stickerjet.data

import com.agency.stickerjet.domain.StickerProject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectEnvelopeTest {

    private val json = testJson()
    private val project = sampleProject(id = "env-1", title = "Envelope")

    private fun projectObject(): JsonObject =
        json.encodeToJsonElement(StickerProject.serializer(), project).jsonObject

    private fun envelopeText(version: Int, body: JsonObject): String =
        JsonObject(
            mapOf(
                ProjectEnvelope.KEY_VERSION to JsonPrimitive(version),
                ProjectEnvelope.KEY_PROJECT to body,
            ),
        ).toString()

    @Test
    fun encodeWritesCurrentVersionAndProject() {
        val root = json.parseToJsonElement(ProjectEnvelope.encode(json, project)).jsonObject

        assertEquals("1", root.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("env-1", root.getValue("project").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test
    fun encodeWritesDefaultValuesExplicitly() {
        val body = json.parseToJsonElement(ProjectEnvelope.encode(json, project)).jsonObject
            .getValue("project").jsonObject

        assertTrue("exports must be written even when empty", body.containsKey("exports"))
        assertTrue("caption must be written with its defaults", body.containsKey("caption"))
        assertTrue("transform must be written with its defaults", body.containsKey("transform"))
    }

    @Test
    fun decodeReturnsTheProjectItWrote() {
        val result = ProjectEnvelope.decode(json, ProjectEnvelope.encode(json, project))

        assertEquals(EnvelopeResult.Ok(project), result)
    }

    @Test
    fun unknownKeysAtAnyLevelAreIgnored() {
        val body = JsonObject(projectObject() + ("futureField" to JsonPrimitive(true)))
        val text = JsonObject(
            mapOf(
                ProjectEnvelope.KEY_VERSION to JsonPrimitive(1),
                ProjectEnvelope.KEY_PROJECT to body,
                "futureEnvelopeKey" to JsonPrimitive("ignored"),
            ),
        ).toString()

        assertEquals(EnvelopeResult.Ok(project), ProjectEnvelope.decode(json, text))
    }

    @Test
    fun newerVersionIsReportedAndNotDecoded() {
        assertEquals(EnvelopeResult.NewerSchema(2), ProjectEnvelope.decode(json, envelopeText(2, projectObject())))
    }

    @Test
    fun missingVersionIsCorrupt() {
        val text = JsonObject(mapOf(ProjectEnvelope.KEY_PROJECT to projectObject())).toString()

        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, text))
    }

    @Test
    fun zeroOrNegativeVersionIsCorrupt() {
        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, envelopeText(0, projectObject())))
        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, envelopeText(-3, projectObject())))
    }

    @Test
    fun bareProjectWithoutEnvelopeIsCorrupt() {
        // A project document written without the envelope is not one this version wrote.
        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, projectObject().toString()))
    }

    @Test
    fun notAnObjectOrNotJsonIsCorrupt() {
        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, "[1,2,3]"))
        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, "{not json"))
        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, ""))
    }

    @Test
    fun projectMissingARequiredFieldIsCorrupt() {
        val incomplete = JsonObject(projectObject().filterKeys { it != "trim" })

        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, envelopeText(1, incomplete)))
    }

    @Test
    fun projectKeyThatIsNotAnObjectIsCorrupt() {
        val text = """{"schemaVersion":1,"project":"nope"}"""

        assertEquals(EnvelopeResult.Corrupt, ProjectEnvelope.decode(json, text))
    }

    @Test
    fun legacyVersionIsUpgradedThroughMigrationsBeforeDecoding() {
        // Version 1 stored the title under "label". The 1 to 2 step renames it back to "title".
        val legacyBody = JsonObject(projectObject().mapKeys { if (it.key == "title") "label" else it.key })
        val legacy = envelopeText(1, legacyBody)
        val calls = mutableListOf<Int>()
        val renameLabel: EnvelopeMigration = { envelope ->
            calls += 1
            val body = envelope.getValue(ProjectEnvelope.KEY_PROJECT).jsonObject
            val renamed = JsonObject(body.mapKeys { if (it.key == "label") "title" else it.key })
            JsonObject(envelope + (ProjectEnvelope.KEY_PROJECT to renamed))
        }

        val result = ProjectEnvelope.decode(
            json,
            legacy,
            currentVersion = 2,
            migrations = mapOf(1 to renameLabel),
        )

        assertEquals(EnvelopeResult.Ok(project), result)
        assertEquals(listOf(1), calls)
    }

    @Test
    fun missingMigrationStepIsCorrupt() {
        val result = ProjectEnvelope.decode(
            json,
            envelopeText(1, projectObject()),
            currentVersion = 2,
            migrations = emptyMap(),
        )

        assertEquals(EnvelopeResult.Corrupt, result)
    }

    @Test
    fun multiStepChainRunsEveryStepInOrder() {
        val order = mutableListOf<Int>()
        val stepOne: EnvelopeMigration = { envelope ->
            order += 1
            envelope
        }
        val stepTwo: EnvelopeMigration = { envelope ->
            order += 2
            envelope
        }

        val result = ProjectEnvelope.decode(
            json,
            envelopeText(1, projectObject()),
            currentVersion = 3,
            migrations = mapOf(1 to stepOne, 2 to stepTwo),
        )

        assertEquals(EnvelopeResult.Ok(project), result)
        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun currentVersionDocumentNeedsNoMigration() {
        val calls = mutableListOf<Int>()
        val neverRun: EnvelopeMigration = { envelope ->
            calls += 1
            envelope
        }

        val result = ProjectEnvelope.decode(
            json,
            envelopeText(2, projectObject()),
            currentVersion = 2,
            migrations = mapOf(1 to neverRun),
        )

        assertEquals(EnvelopeResult.Ok(project), result)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun shippedMigrationTableIsEmptyWhileVersionOneIsCurrent() {
        assertEquals(1, ProjectEnvelope.CURRENT_VERSION)
        assertTrue(ProjectEnvelope.MIGRATIONS.isEmpty())
    }
}
