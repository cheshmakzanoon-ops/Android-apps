package com.agency.timeflip.data.db

import com.agency.timeflip.data.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemaTest {

    @Test
    fun databaseIsSchemaVersionOne() {
        val db = TestFixtures.inMemoryDatabase()
        try {
            // Room writes the schema version into the database file as user_version.
            assertEquals(1, db.openHelper.readableDatabase.version)
        } finally {
            db.close()
        }
    }

    @Test
    fun noMigrationsExistUntilTheSchemaChanges() {
        assertTrue(MIGRATIONS.isEmpty())
    }
}
