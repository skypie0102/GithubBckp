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

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backups ADD COLUMN origin TEXT")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backups ADD COLUMN scheduledRunId TEXT")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_backups_scheduledRunId ON backups(scheduledRunId)")
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE backups ADD COLUMN lastReverifiedAtEpochMs INTEGER")
            db.execSQL("ALTER TABLE backups ADD COLUMN lastReverificationStatus TEXT")
            db.execSQL("ALTER TABLE backups ADD COLUMN lastReverificationMessage TEXT")
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS mirrors (
                    repositoryId INTEGER NOT NULL,
                    archiveUri TEXT,
                    archiveSizeBytes INTEGER,
                    archiveSha256 TEXT,
                    formatVersion INTEGER NOT NULL DEFAULT 1,
                    lastCheckedAtEpochMs INTEGER,
                    lastSuccessfulSyncAtEpochMs INTEGER,
                    lastChangedAtEpochMs INTEGER,
                    lastAttemptAtEpochMs INTEGER,
                    lastAttemptStatus TEXT,
                    lastSourceHead TEXT,
                    lastRefsDigest TEXT,
                    lastError TEXT,
                    lastWarning TEXT,
                    PRIMARY KEY(repositoryId)
                )
                """.trimIndent(),
            )
        }
    }
}
