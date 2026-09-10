package com.solewis.podcaster.player

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rolling, on-device record of everything that moves the playback position.
 *
 * Exists for a bug that cannot be caught any other way: resume an episode, listen for about a
 * minute, and playback jumps back to where it started and replays that minute. It happens
 * occasionally, on a real phone, away from a computer - so logcat is no use (its ring buffer has
 * rotated long before the phone is next plugged in) and a debugger is no use either. What is needed
 * is for the phone to still know, hours later, what happened at 08:14.
 *
 * Every position change in this app comes from one of a handful of places, and each one records
 * itself here. Read back, the log says which: a `SEEK` discontinuity means something called
 * `seekTo`, and the preceding line says who; an `AUTO_TRANSITION` or a `setMediaItem` means the item
 * was replaced, and by whom; a `STATE_ENDED` a minute into an hour-long episode means the player
 * believed the stream had finished, which would explain the replay and point at the cache rather
 * than at anything in this file.
 *
 * Deliberately plain text and deliberately cheap: an append to an already-open file, a few dozen
 * bytes, only on events that are already rare (a seek, a state change - not the progress ticker).
 * Capped so it can be left on indefinitely; when the cap is hit the oldest half goes, which keeps
 * the recent past - the only part anyone reads - at the cost of the distant past.
 */
class PlaybackLog(private val file: File, private val maxBytes: Long = MAX_BYTES) {

    private val timestamps = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    /**
     * [detail] should carry the numbers, not prose. Positions in milliseconds so two lines can be
     * subtracted; a human-readable duration would have to be parsed back.
     */
    fun record(event: String, detail: String = "") {
        val line = buildString {
            append(timestamps.format(Date()))
            append(' ')
            append(event)
            if (detail.isNotEmpty()) {
                append(' ')
                append(detail)
            }
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line)
                if (file.length() > maxBytes) trim()
            }
        }
    }

    /** The whole log, newest last, for sharing. Empty rather than absent when nothing is recorded. */
    fun snapshot(): String = synchronized(lock) {
        runCatching { file.readText() }.getOrDefault("")
    }

    fun clear() {
        synchronized(lock) { runCatching { file.writeText("") } }
    }

    /**
     * Drops the oldest half.
     *
     * Halving rather than trimming to exactly the cap, so this runs once every many appends instead
     * of on every append once the file is full - the difference between an occasional rewrite and a
     * rewrite of the whole file on every seek.
     */
    private fun trim() {
        val kept = file.readText().let { it.substring(it.length / 2) }
        // From the first newline, so the log never begins with half a line.
        file.writeText(kept.substringAfter('\n', kept))
    }

    companion object {
        /**
         * About a week of ordinary listening. Small enough to paste into a message, large enough
         * that a bug noticed on Thursday is still described in full.
         */
        private const val MAX_BYTES = 192L * 1024

        @Volatile
        private var instance: PlaybackLog? = null

        /**
         * The process's one log. A second instance over the same file would append without seeing
         * the first's lock, which is how a log ends up with interleaved half-lines - and the
         * playback service and the UI both write to this one.
         */
        fun forApp(context: android.content.Context): PlaybackLog =
            instance ?: synchronized(this) {
                instance ?: PlaybackLog(
                    File(context.applicationContext.filesDir, "playback-log.txt")
                ).also { instance = it }
            }
    }
}
