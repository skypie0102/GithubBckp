package com.skypie0102.githubbckp.data.local

import androidx.room.TypeConverter
import com.skypie0102.githubbckp.backup.BackupOrigin
import com.skypie0102.githubbckp.backup.BackupReverificationStatus
import com.skypie0102.githubbckp.backup.BackupStatus
import com.skypie0102.githubbckp.backup.BackupType
import com.skypie0102.githubbckp.storage.StorageDestination

class Converters {
    @TypeConverter fun backupTypeToString(value: BackupType): String = value.name
    @TypeConverter fun stringToBackupType(value: String): BackupType = BackupType.valueOf(value)
    @TypeConverter fun backupStatusToString(value: BackupStatus): String = value.name
    @TypeConverter fun stringToBackupStatus(value: String): BackupStatus = BackupStatus.valueOf(value)
    @TypeConverter fun backupOriginToString(value: BackupOrigin?): String? = value?.name
    @TypeConverter fun stringToBackupOrigin(value: String?): BackupOrigin? = value?.let(BackupOrigin::valueOf)
    @TypeConverter fun storageDestinationToString(value: StorageDestination?): String? = value?.name
    @TypeConverter fun stringToStorageDestination(value: String?): StorageDestination? =
        value?.let(StorageDestination::valueOf)
    @TypeConverter fun backupReverificationStatusToString(value: BackupReverificationStatus?): String? = value?.name
    @TypeConverter fun stringToBackupReverificationStatus(value: String?): BackupReverificationStatus? =
        value?.let(BackupReverificationStatus::valueOf)
}
