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

/** e.g. position=171,000ms, duration=11,940,000ms -> "3h 15m left". Null when duration is unknown. */
fun formatRemaining(positionMillis: Long, durationMillis: Long?): String? {
    if (durationMillis == null) return null
    return formatDuration((durationMillis - positionMillis).coerceAtLeast(0))?.let { "$it left" }
}

/**
 * Clock-style formatting for a live playback position/duration, e.g. 78,000ms -> "1:18",
 * 8,300,000ms -> "2:18:20". Distinct from [formatDuration]: that reads better for browsing a list
 * ("3h 53m"), this is for knowing exactly where you are while scrubbing/listening. Returns null
 * when the value is unknown, same convention as [formatDuration].
 */
fun formatTimer(millis: Long?): String? {
    if (millis == null || millis < 0) return null
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Storage sizes, e.g. 54,000,000 -> "51 MB". Binary units (1024) because that is what the system's
 * own storage settings report, and a download that reads as a different size in two places on the
 * same phone looks like a bug. Whole numbers above a megabyte - nobody choosing what to delete
 * cares about the decimal.
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024 * 1024))
}
