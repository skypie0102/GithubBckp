package com.skypie0102.githubbckp.data.local

import androidx.room.TypeConverter
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType

class Converters {
    @TypeConverter fun backupTypeToString(value: BackupType): String = value.name
    @TypeConverter fun stringToBackupType(value: String): BackupType = BackupType.valueOf(value)
    @TypeConverter fun backupStatusToString(value: BackupStatus): String = value.name
    @TypeConverter fun stringToBackupStatus(value: String): BackupStatus = BackupStatus.valueOf(value)
}
