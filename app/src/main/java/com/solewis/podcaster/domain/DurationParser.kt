package com.solewis.podcaster.domain

/**
 * Parses the `<itunes:duration>` value from a podcast RSS feed into milliseconds.
 *
 * Real feeds are inconsistent about this field. Verified directly against two real feeds before
 * writing this (see the project plan): one show's 2952 episodes are uniformly `H:MM:SS`, while
 * another's 501 episodes mix `H:MM:SS` and `M:SS`, and 54 of them have no duration at all. Other
 * feeds in the wild are known to emit bare seconds (optionally with a decimal fraction). This
 * parser accepts all of those and returns `null` - never throws - for anything else, so a bad
 * feed degrades to "duration unknown" instead of crashing a refresh.
 */
object DurationParser {

    private val MAX_DURATION_MILLIS = 24L * 60 * 60 * 1000 // 24 hours

    /**
     * @return duration in milliseconds, or `null` if [raw] is missing, blank, unparseable, or
     * outside the sane range of (0, 24h].
     */
    fun parseToMillis(raw: String?): Long? {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) return null

        val seconds = if (':' in trimmed) {
            parseColonSeparated(trimmed)
        } else {
            trimmed.toDoubleOrNull()
        } ?: return null

        if (seconds < 0) return null

        val millis = (seconds * 1000).toLong()
        return millis.takeIf { it in 1..MAX_DURATION_MILLIS }
    }

    /** Handles `H:MM:SS`, `M:SS`, and their single-digit variants (e.g. `1:23:45`, `9:05`). */
    private fun parseColonSeparated(value: String): Double? {
        val parts = value.split(':')
        if (parts.size !in 2..3) return null

        val components = parts.map { it.toIntOrNull() }
        if (components.any { it == null || it < 0 }) return null

        return when (components.size) {
            2 -> components[0]!! * 60.0 + components[1]!!
            3 -> components[0]!! * 3600.0 + components[1]!! * 60.0 + components[2]!!
            else -> null
        }
    }
}
