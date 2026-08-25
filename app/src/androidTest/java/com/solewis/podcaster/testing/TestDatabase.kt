package com.solewis.podcaster.testing

import android.content.Context
import androidx.room.Room
import com.solewis.podcaster.data.db.PodcasterDatabase

/**
 * In-memory, and deliberately allowing main-thread queries: the app's own startup path reads the
 * database from Compose composition, and blocking there for a few microseconds against an in-memory
 * database is preferable to reshaping production code to suit the test harness.
 */
fun inMemoryTestDatabase(context: Context): PodcasterDatabase =
    Room.inMemoryDatabaseBuilder(context, PodcasterDatabase::class.java)
        .allowMainThreadQueries()
        .build()
