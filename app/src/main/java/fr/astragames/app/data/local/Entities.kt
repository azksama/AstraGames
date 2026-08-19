package fr.astragames.app.data.local

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index

@Entity(
    tableName = "games",
    indices = [
        Index("sourceId"), Index("fingerprint"), Index("engine"), Index("libraryFolderId"),
        Index(value = ["documentUri"], unique = true)
    ]
)
data class GameEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val originalTitle: String? = null,
    val aliases: String = "",
    val documentUri: String,
    val physicalPath: String?,
    val executableName: String?,
    val engine: String,
    val launcher: String,
    val sourceId: String,
    val coverUri: String? = null,
    val bannerUri: String? = null,
    val iconUri: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val version: String? = null,
    val productCode: String? = null,
    val language: String? = null,
    val f95Url: String? = null,
    val releaseDate: Long? = null,
    val dateAdded: Long,
    val lastModified: Long,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    val favorite: Boolean = false,
    val hidden: Boolean = false,
    val missing: Boolean = false,
    val autoDetected: Boolean = true,
    val libraryFolderId: String? = null,
    val keywords: String = "",
    val fingerprint: String
)

@Fts4
@Entity(tableName = "game_search")
data class GameSearchEntity(
    val gameId: String,
    val title: String,
    val originalTitle: String,
    val aliases: String,
    val developer: String,
    val version: String,
    val productCode: String,
    val engine: String,
    val keywords: String,
    val description: String
)

@Entity(tableName = "game_sources", indices = [Index(value = ["treeUri"], unique = true)])
data class GameSourceEntity(
    @androidx.room.PrimaryKey val id: String,
    val displayName: String,
    val treeUri: String,
    val enabled: Boolean = true,
    val recursive: Boolean = true,
    val maxDepth: Int? = null,
    val autoScan: Boolean = true,
    val includeHiddenFolders: Boolean = false,
    val lastScanAt: Long? = null,
    val lastScanStatus: String = "NEVER",
    val gamesCount: Int = 0,
    val lastError: String? = null
)

@Entity(tableName = "source_exclusions", indices = [Index("sourceId")])
data class SourceExclusionEntity(
    @androidx.room.PrimaryKey val id: String,
    val sourceId: String?,
    val relativePath: String? = null,
    val folderNamePattern: String? = null,
    val enabled: Boolean = true
)

@Entity(tableName = "tags", indices = [Index(value = ["normalizedName"], unique = true)])
data class TagEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val groupName: String? = null,
    val description: String? = null
)

@Entity(tableName = "tag_categories", indices = [Index(value = ["normalizedName"], unique = true)])
data class TagCategoryEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val sortOrder: Int = 0
)

@Entity(tableName = "game_tags", primaryKeys = ["gameId", "tagId"], indices = [Index("tagId")])
data class GameTagCrossRef(val gameId: String, val tagId: String)

@Entity(tableName = "library_folders", indices = [Index("parentId")])
data class LibraryFolderEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val sortOrder: Int = 0
)

@Entity(tableName = "play_sessions", indices = [Index("gameId")])
data class PlaySessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val gameId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationMs: Long? = null
)

@Entity(tableName = "metadata", primaryKeys = ["gameId", "fieldName"], indices = [Index("gameId")])
data class MetadataEntity(
    val gameId: String,
    val fieldName: String,
    val value: String?,
    val source: String,
    val userLocked: Boolean
)

@Entity(tableName = "cover_candidates", indices = [Index("gameId")])
data class CoverCandidateEntity(
    @androidx.room.PrimaryKey val id: String,
    val gameId: String,
    val imageUrl: String,
    val source: String,
    val matchedTitle: String? = null,
    val confidence: Float
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val builtinKey: String? = null,
    val sortOrder: Int = 0,
    val matchMode: String = "ALL"
)

@Entity(tableName = "collection_rules", indices = [Index("collectionId")])
data class CollectionRuleEntity(
    @androidx.room.PrimaryKey val id: String,
    val collectionId: String,
    val field: String,
    val operator: String,
    val value: String
)

@Entity(tableName = "scan_history", indices = [Index("sourceId")])
data class ScanHistoryEntity(
    @androidx.room.PrimaryKey val id: String,
    val sourceId: String?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val gamesFound: Int = 0,
    val gamesAdded: Int = 0,
    val gamesUpdated: Int = 0,
    val gamesUnchanged: Int = 0,
    val gamesMoved: Int = 0,
    val gamesMissing: Int = 0,
    val visitedFolders: Int = 0,
    val ignoredFolders: Int = 0,
    val errors: Int = 0
)

@Entity(tableName = "scan_report_items", indices = [Index("scanId"), Index("sourceId"), Index("gameId")])
data class ScanReportItemEntity(
    @androidx.room.PrimaryKey val id: String,
    val scanId: String,
    val sourceId: String,
    val gameId: String? = null,
    val path: String,
    val title: String? = null,
    val status: String,
    val reason: String
)

@Entity(tableName = "launch_profiles")
data class LaunchProfileEntity(
    @androidx.room.PrimaryKey val gameId: String,
    val launcherType: String = "JOIPLAY",
    val engineOverride: String? = null,
    val executableName: String? = null,
    val physicalPath: String? = null,
    val customAction: String? = null,
    val packageName: String? = null,
    val arguments: String = ""
)

@Entity(
    tableName = "deleted_games",
    indices = [Index("fingerprint"), Index(value = ["documentUri"], unique = true)]
)
data class DeletedGameEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val documentUri: String,
    val physicalPath: String?,
    val fingerprint: String,
    val sourceId: String,
    val deletedAt: Long,
    val reason: String = "USER_DELETED"
)

@Entity(tableName = "ignored_duplicate_groups")
data class IgnoredDuplicateGroupEntity(
    @androidx.room.PrimaryKey val groupKey: String,
    val ignoredAt: Long
)

data class GamePlayStat(
    val gameId: String,
    val totalDurationMs: Long,
    val lastSessionAt: Long?
)

data class TagCount(val tag: TagEntity, val gameCount: Int)
data class FolderCount(val folder: LibraryFolderEntity, val gameCount: Int)

fun GameEntity.toSearchEntity() = GameSearchEntity(
    gameId = id,
    title = title,
    originalTitle = originalTitle.orEmpty(),
    aliases = aliases,
    developer = developer.orEmpty(),
    version = version.orEmpty(),
    productCode = productCode.orEmpty(),
    engine = engine.replace('_', ' '),
    keywords = keywords,
    description = description.orEmpty()
)

@Entity(tableName = "audit_events", indices = [Index("timestamp")])
data class AuditEventEntity(
    @androidx.room.PrimaryKey val id: String,
    val type: String,
    val detail: String,
    val timestamp: Long
)

/** Emplacement de dossier de sauvegardes connu pour un jeu (auto-détecté ou défini manuellement). */
@Entity(tableName = "game_save_locations", indices = [Index("gameId")])
data class GameSaveLocationEntity(
    @androidx.room.PrimaryKey val id: String,
    val gameId: String,
    val uri: String,
    val type: String,
    val displayName: String,
    val autoDetected: Boolean,
    val enabled: Boolean = true,
    val addedAt: Long
)

/** Backup automatique d'une sauvegarde de jeu, créé avant chaque écriture. */
@Entity(tableName = "save_backups", indices = [Index("gameId"), Index("sourceUri")])
data class SaveBackupEntity(
    @androidx.room.PrimaryKey val id: String,
    val gameId: String,
    val sourceUri: String,
    val sourceName: String,
    val backupUri: String,
    val createdAt: Long,
    val sizeBytes: Long
)

/** Mod détecté dans le dépôt local Astra/Mods/&lt;Engine&gt;/. */
@Entity(tableName = "mods", indices = [Index(value = ["modId", "engine"]), Index("folderUri")])
data class ModEntity(
    @androidx.room.PrimaryKey val id: String,
    val modId: String,
    val name: String,
    val version: String?,
    val author: String?,
    val description: String?,
    val engine: String,
    val folderUri: String,
    val hasManifest: Boolean,
    val installMode: String,
    val target: String?,
    val filesRoot: String,
    val lastSeenAt: Long
)

/** Installation d'un mod sur un jeu, nécessaire à la désinstallation contrôlée. */
@Entity(tableName = "mod_installations", indices = [Index("modId"), Index("gameId")])
data class ModInstallationEntity(
    @androidx.room.PrimaryKey val id: String,
    val modId: String,
    val gameId: String,
    val installedAt: Long,
    val modVersion: String?,
    val installMode: String,
    val status: String
)

/** Fichier individuel touché par une installation de mod. */
@Entity(tableName = "mod_installed_files", indices = [Index("installationId")])
data class ModInstalledFileEntity(
    @androidx.room.PrimaryKey val id: String,
    val installationId: String,
    val relativePath: String,
    val action: String,
    val originalHash: String?,
    val installedHash: String?,
    val backupUri: String?
)
