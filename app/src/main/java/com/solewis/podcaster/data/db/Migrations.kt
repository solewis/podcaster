package com.solewis.podcaster.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the `queue` table. The SQL here is copied verbatim from the Room-generated
 * `app/schemas/.../2.json` (via `./gradlew :app:kspDebugKotlin`, which regenerates it from
 * [QueueEntity][com.solewis.podcaster.data.db.entity.QueueEntity] alone) rather than hand-typed,
 * so it's guaranteed to match what Room's schema validation expects on open - a mismatch here
 * would throw for every subscriber on their very first launch after an update.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `queue` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `episodeId` TEXT NOT NULL,
                `position` INTEGER NOT NULL,
                `addedAt` INTEGER NOT NULL,
                FOREIGN KEY(`episodeId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_queue_episodeId` ON `queue` (`episodeId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_queue_position` ON `queue` (`position`)")
    }
}

/** Adds the short, precomputed description preview column - see
 * [com.solewis.podcaster.data.db.entity.EpisodeEntity.descriptionPreview]. A plain `ADD COLUMN`
 * rather than a table rebuild: SQLite supports adding a nullable column with no default data
 * migration needed, and every existing row simply reads back null until the next feed refresh
 * fills it in. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `descriptionPreview` TEXT DEFAULT NULL")
    }
}
