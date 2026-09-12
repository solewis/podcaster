package com.solewis.podcaster.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Verifies [MIGRATION_1_2] against the real schema-1 shape exported to `app/schemas` - the exact
 * database shape every existing install (including the developer's own live phone) upgrades
 * from. Deliberately uses its own db file name distinct from [PodcasterDatabase.NAME]: this test
 * runs under the app's own package, so re-using the real name would let it collide with an
 * actual installed database file on whatever device runs it.
 */
class PodcasterDatabaseMigrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PodcasterDatabase::class.java
    )

    @Test
    fun migrate1To2_addsQueueTableWithoutLosingExistingData() {
        helper.createDatabase(testDbName, 1).apply {
            execSQL(
                "INSERT INTO podcasts (id, feedUrl, title, subscribedAt) VALUES (1, 'https://example.com/feed.xml', 'Test Show', 1000)"
            )
            execSQL(
                """
                INSERT INTO episodes
                    (id, podcastId, stableKey, stableKeySource, title, enclosureUrl, feedPosition, firstSeenAt, positionMillis, isPlayed)
                VALUES
                    ('1:abc', 1, 'abc', 'hash', 'Ep 1', 'https://example.com/ep1.mp3', 0, 1000, 42000, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 2, true, MIGRATION_1_2)

        migrated.query("SELECT title FROM podcasts WHERE id = 1").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Test Show")
        }
        migrated.query("SELECT positionMillis FROM episodes WHERE id = '1:abc'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(42000L)
        }

        // The new table exists and enforces the same FK/index shape Room's own generated
        // schema expects - an insert against it exercising both proves the migration produced
        // a structurally correct table, not just one that happens to satisfy the row count.
        migrated.execSQL("INSERT INTO queue (episodeId, position, addedAt) VALUES ('1:abc', 0, 2000)")
        migrated.query("SELECT COUNT(*) FROM queue").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(1)
        }

        migrated.close()
    }

    @Test
    fun migrate2To3_addsDescriptionPreviewWithoutLosingExistingData() {
        helper.createDatabase(testDbName, 2).apply {
            execSQL(
                "INSERT INTO podcasts (id, feedUrl, title, subscribedAt) VALUES (1, 'https://example.com/feed.xml', 'Test Show', 1000)"
            )
            execSQL(
                """
                INSERT INTO episodes
                    (id, podcastId, stableKey, stableKeySource, title, descriptionHtml, enclosureUrl, feedPosition, firstSeenAt, positionMillis, isPlayed)
                VALUES
                    ('1:abc', 1, 'abc', 'hash', 'Ep 1', '<p>Show notes</p>', 'https://example.com/ep1.mp3', 0, 1000, 42000, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(testDbName, 3, true, MIGRATION_2_3)

        // Existing data survives, including the column the migration does not touch.
        migrated.query("SELECT descriptionHtml, positionMillis FROM episodes WHERE id = '1:abc'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("<p>Show notes</p>")
            assertThat(cursor.getLong(1)).isEqualTo(42000L)
        }
        // The new column exists and reads back null for a row that predates it - nothing
        // backfills old rows; the next feed refresh does that naturally.
        migrated.query("SELECT descriptionPreview FROM episodes WHERE id = '1:abc'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
        }

        migrated.close()
    }
}
