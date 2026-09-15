package com.skypie0102.githubbckp.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backups ADD COLUMN storageProvider TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN remoteFileName TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN remoteSizeBytes INTEGER")
            db.execSQL("ALTER TABLE backups ADD COLUMN remoteChecksumMd5 TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN remoteDeletedAtEpochMs INTEGER")
        }
    }
}
