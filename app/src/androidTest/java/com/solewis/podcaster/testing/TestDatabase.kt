package com.solewis.podcaster.testing

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.solewis.podcaster.data.db.PodcasterDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * In-memory, and deliberately allowing main-thread queries: the app's own startup path reads the
 * database from Compose composition, and blocking there for a few microseconds against an in-memory
 * database is preferable to reshaping production code to suit the test harness.
 */
fun inMemoryTestDatabase(context: Context): PodcasterDatabase =
    Room.inMemoryDatabaseBuilder(context, PodcasterDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/**
 * Stops everything [scope] started and *waits for it to have stopped* before closing [db].
 *
 * The obvious teardown - `scope.cancel()` then `db.close()` - does not do that, and the difference
 * is a flake that took roughly every other full on-device run. `cancel` only asks; a coroutine
 * already inside a Room query is not interruptible, because the query runs on Room's own executor
 * and `step()` is a blocking JNI call. So the database could be closed with a read still in
 * flight, and that read then threw:
 *
 *     java.lang.IllegalStateException: Cannot perform this operation because the connection pool
 *     has been closed.
 *
 * Two things made it hard to recognise. The throw happens on a Room background thread inside a
 * coroutine nobody is awaiting, so it reaches the default uncaught handler and the instrumentation
 * runner attributes it to whichever test happens to be starting - always a *different* test from
 * the one that leaked, and one that fails in ~20ms without running a line of its own body. And it
 * needs the leaking test to be followed by another, so running the accused class by itself passes,
 * which reads as "flaky infrastructure" rather than as a specific unfinished read.
 *
 * `cancelAndJoin` waits for the in-flight query to finish before returning, so by the time the
 * database closes there is nothing left that could touch it.
 */
fun cancelAndClose(scope: CoroutineScope, db: RoomDatabase) {
    runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    db.close()
}
