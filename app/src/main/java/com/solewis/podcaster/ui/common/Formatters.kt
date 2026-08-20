package com.solewis.podcaster.ui.common

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

fun formatEpisodeDate(epochMillis: Long?): String? {
    if (epochMillis == null) return null
    return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateFormatter)
}

/** e.g. 5,025,000ms -> "1h 23m". Returns null when the duration is unknown - feeds frequently omit it. */
fun formatDuration(millis: Long?): String? {
    if (millis == null || millis <= 0) return null
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
