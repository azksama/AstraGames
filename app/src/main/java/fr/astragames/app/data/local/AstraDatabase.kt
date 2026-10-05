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
        DeletedGameEntity::class, IgnoredDuplicateGroupEntity::class, AuditEventEntity::class,
        GameSaveLocationEntity::class, SaveBackupEntity::class, ModEntity::class,
        ModInstallationEntity::class, ModInstalledFileEntity::class
    ],
    version = 9,
    exportSchema = true
)
abstract class AstraDatabase : RoomDatabase() {
    abstract fun dao(): AstraDao

    companion object {
        fun create(context: Context): AstraDatabase = Room.databaseBuilder(
            context.applicationContext,
            AstraDatabase::class.java,
            "astra_games.db"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9).build()

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE games ADD COLUMN ryuugamesUrl TEXT") }
        }

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN matchMode TEXT NOT NULL DEFAULT 'ALL'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ignored_duplicate_groups (`groupKey` TEXT NOT NULL, `ignoredAt` INTEGER NOT NULL, PRIMARY KEY(`groupKey`))"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN f95Url TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS audit_events (id TEXT NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL, timestamp INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_timestamp ON audit_events (timestamp)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS game_save_locations (id TEXT NOT NULL, gameId TEXT NOT NULL, uri TEXT NOT NULL, type TEXT NOT NULL, displayName TEXT NOT NULL, autoDetected INTEGER NOT NULL, enabled INTEGER NOT NULL, addedAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_game_save_locations_gameId ON game_save_locations (gameId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS save_backups (id TEXT NOT NULL, gameId TEXT NOT NULL, sourceUri TEXT NOT NULL, sourceName TEXT NOT NULL, backupUri TEXT NOT NULL, createdAt INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_save_backups_gameId ON save_backups (gameId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_save_backups_sourceUri ON save_backups (sourceUri)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mods (id TEXT NOT NULL, modId TEXT NOT NULL, name TEXT NOT NULL, version TEXT, author TEXT, description TEXT, engine TEXT NOT NULL, folderUri TEXT NOT NULL, hasManifest INTEGER NOT NULL, installMode TEXT NOT NULL, target TEXT, filesRoot TEXT NOT NULL, lastSeenAt INTEGER NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mods_modId_engine ON mods (modId, engine)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mods_folderUri ON mods (folderUri)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mod_installations (id TEXT NOT NULL, modId TEXT NOT NULL, gameId TEXT NOT NULL, installedAt INTEGER NOT NULL, modVersion TEXT, installMode TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mod_installations_modId ON mod_installations (modId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mod_installations_gameId ON mod_installations (gameId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mod_installed_files (id TEXT NOT NULL, installationId TEXT NOT NULL, relativePath TEXT NOT NULL, action TEXT NOT NULL, originalHash TEXT, installedHash TEXT, backupUri TEXT, PRIMARY KEY(id))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_mod_installed_files_installationId ON mod_installed_files (installationId)")
            }
        }
    }
}
