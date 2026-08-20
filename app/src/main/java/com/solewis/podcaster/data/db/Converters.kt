package com.solewis.podcaster.data.db

import androidx.room.TypeConverter
import com.solewis.podcaster.data.db.model.SortOrder

class Converters {
    @TypeConverter
    fun sortOrderToString(value: SortOrder): String = value.name

    @TypeConverter
    fun stringToSortOrder(value: String): SortOrder =
        runCatching { SortOrder.valueOf(value) }.getOrDefault(SortOrder.NEWEST_FIRST)
}
