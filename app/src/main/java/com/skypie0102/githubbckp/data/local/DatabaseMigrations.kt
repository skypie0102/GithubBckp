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

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backups ADD COLUMN warningMessage TEXT")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backups ADD COLUMN repositoryOwnerAtBackup TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN repositoryNameAtBackup TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN repositoryDefaultBranchAtBackup TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN repositoryPrivateAtBackup INTEGER")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE repositories ADD COLUMN isAvailable INTEGER NOT NULL DEFAULT 1")
        }
    }
}
