package com.solewis.podcaster.testing

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.solewis.podcaster.data.db.PodcasterDatabase

/**
 * A fresh in-memory database per test. Isolation is the whole point: the one existing device test
 * that reads the app's real shared database is also the one test that has actually failed for
 * reasons unrelated to a code change.
 */
fun inMemoryDatabase(): PodcasterDatabase =
    Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PodcasterDatabase::class.java
    ).build()
