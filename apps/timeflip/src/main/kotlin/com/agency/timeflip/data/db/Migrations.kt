package com.agency.timeflip.data.db

import androidx.room.migration.Migration

/**
 * Schema migrations, applied in order when the database version increases.
 *
 * Version 1 is the first schema, so there are no migrations yet and the list is empty.
 *
 * Policy for adding a migration (version N to N+1):
 * 1. Change the entities and set `version = N + 1` in [TimeFlipDatabase]. Keep the `exportSchema`
 *    JSON that KSP writes for version N+1 and commit it with the change.
 * 2. Add an object of type [Migration] (from, to) here that makes the database match the new
 *    schema with SQL only. Keep existing rows; never drop user data to make a migration easy.
 * 3. Append it to [MIGRATIONS]. Each migration runs exactly once, in order.
 * 4. Add a MigrationTestHelper test that opens version N from its committed schema JSON, migrates
 *    to N+1 and checks the rows that matter.
 * Destructive fallback migration is never enabled: if no migration exists, the app must fail
 * visibly instead of silently deleting a user's library.
 */
internal val MIGRATIONS: List<Migration> = emptyList()
