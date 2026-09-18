package com.skypie0102.githubbckp.data.local

import androidx.room.TypeConverter
import com.skypie0102.githubbckp.backup.MirrorStatus

class Converters {
    @TypeConverter
    fun mirrorStatusToString(value: MirrorStatus): String = value.name

    @TypeConverter
    fun stringToMirrorStatus(value: String): MirrorStatus =
        runCatching { MirrorStatus.valueOf(value) }.getOrDefault(MirrorStatus.IDLE)
}
