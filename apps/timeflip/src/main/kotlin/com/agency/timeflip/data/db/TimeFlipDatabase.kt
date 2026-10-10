package com.agency.timeflip.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Library database, schema version 1. Room writes the schema JSON to apps/timeflip/schemas
 * (configured through the KSP room.schemaLocation argument). Do not hand-write it.
 */
@Database(
    entities = [ProjectEntity::class, PhotoEntity::class, ExportEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TimeFlipDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao

    abstract fun photoDao(): PhotoDao

    abstract fun exportDao(): ExportDao

    companion object {
        const val FILE_NAME = "timeflip.db"

        /** Opens the on-disk database in app-private storage. */
        fun create(context: Context): TimeFlipDatabase =
            configure(Room.databaseBuilder(context.applicationContext, TimeFlipDatabase::class.java, FILE_NAME)).build()

        /** Shared configuration for the on-disk and in-memory builders. Tests use this too. */
        internal fun configure(builder: RoomDatabase.Builder<TimeFlipDatabase>): RoomDatabase.Builder<TimeFlipDatabase> =
            builder
                .addMigrations(*MIGRATIONS.toTypedArray())
                .addCallback(ForeignKeysEnabledCallback)
    }
}

/** Cascade deletes depend on foreign key enforcement, which SQLite leaves off unless asked. */
private object ForeignKeysEnabledCallback : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}
