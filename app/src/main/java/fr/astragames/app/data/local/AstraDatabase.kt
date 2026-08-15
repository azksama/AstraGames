package fr.astragames.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GameEntity::class, GameSearchEntity::class, GameSourceEntity::class,
        SourceExclusionEntity::class, TagEntity::class, TagCategoryEntity::class, GameTagCrossRef::class,
        LibraryFolderEntity::class, PlaySessionEntity::class, MetadataEntity::class,
        CoverCandidateEntity::class, CollectionEntity::class, CollectionRuleEntity::class,
        ScanHistoryEntity::class, ScanReportItemEntity::class, LaunchProfileEntity::class,
        DeletedGameEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AstraDatabase : RoomDatabase() {
    abstract fun dao(): AstraDao

    companion object {
        fun create(context: Context): AstraDatabase = Room.databaseBuilder(
            context.applicationContext,
            AstraDatabase::class.java,
            "astra_games.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).fallbackToDestructiveMigration(false).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tag_categories (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `normalizedName` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_tag_categories_normalizedName ON tag_categories (`normalizedName`)")
                db.execSQL(
                    "INSERT OR IGNORE INTO tag_categories (id, name, normalizedName, sortOrder) SELECT lower(hex(randomblob(16))), groupName, lower(groupName), rowid FROM tags WHERE groupName IS NOT NULL GROUP BY groupName"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_history ADD COLUMN gamesUpdated INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN gamesUnchanged INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN visitedFolders INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_history ADD COLUMN ignoredFolders INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS scan_report_items (`id` TEXT NOT NULL, `scanId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `gameId` TEXT, `path` TEXT NOT NULL, `title` TEXT, `status` TEXT NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_report_items_scanId ON scan_report_items (`scanId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_report_items_sourceId ON scan_report_items (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_report_items_gameId ON scan_report_items (`gameId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS launch_profiles (`gameId` TEXT NOT NULL, `launcherType` TEXT NOT NULL, `engineOverride` TEXT, `executableName` TEXT, `physicalPath` TEXT, `customAction` TEXT, `packageName` TEXT, `arguments` TEXT NOT NULL, PRIMARY KEY(`gameId`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS deleted_games (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `documentUri` TEXT NOT NULL, `physicalPath` TEXT, `fingerprint` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deleted_games_fingerprint ON deleted_games (`fingerprint`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_deleted_games_documentUri ON deleted_games (`documentUri`)")
            }
        }
    }
}
