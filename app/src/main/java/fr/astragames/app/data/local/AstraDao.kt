package fr.astragames.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AstraDao {
    @Query("SELECT * FROM games WHERE hidden = 0 ORDER BY title COLLATE NOCASE")
    fun observeGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    fun observeGame(id: String): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun getGame(id: String): GameEntity?

    @Query("SELECT id FROM games")
    suspend fun getGameIds(): List<String>

    @Query("SELECT * FROM games WHERE sourceId = :sourceId")
    suspend fun getGamesForSource(sourceId: String): List<GameEntity>

    @Query("SELECT * FROM games WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findGameByFingerprint(fingerprint: String): GameEntity?

    @Query("SELECT * FROM games WHERE documentUri = :documentUri LIMIT 1")
    suspend fun findGameByDocumentUri(documentUri: String): GameEntity?

    @Query("SELECT games.* FROM games JOIN game_search ON games.id = game_search.gameId WHERE game_search MATCH :ftsQuery AND games.hidden = 0 ORDER BY games.title COLLATE NOCASE")
    fun searchGames(ftsQuery: String): Flow<List<GameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGameRaw(game: GameEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchRaw(search: GameSearchEntity)

    @Transaction
    suspend fun upsertGame(game: GameEntity) {
        insertGameRaw(game)
        deleteSearch(game.id)
        insertSearchRaw(game.toSearchEntity())
    }

    @Query("DELETE FROM game_search WHERE gameId = :gameId")
    suspend fun deleteSearch(gameId: String)

    @Query("UPDATE games SET missing = 1 WHERE sourceId = :sourceId")
    suspend fun markSourceGamesMissing(sourceId: String)

    @Query("UPDATE games SET missing = 0 WHERE id = :id")
    suspend fun markGameFound(id: String)

    @Query("UPDATE games SET missing = 0 WHERE sourceId = :sourceId AND id IN (:ids)")
    suspend fun markSourceGamesFound(sourceId: String, ids: List<String>)

    @Transaction
    suspend fun reconcileSourceGames(sourceId: String, foundIds: List<String>) {
        markSourceGamesMissing(sourceId)
        foundIds.chunked(900).forEach { markSourceGamesFound(sourceId, it) }
    }

    @Query("SELECT COUNT(*) FROM games WHERE sourceId = :sourceId AND missing = 1")
    suspend fun countMissing(sourceId: String): Int

    @Query("UPDATE games SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: String)

    @Query("UPDATE games SET lastPlayedAt = :at, playCount = playCount + 1, missing = 0 WHERE id = :id")
    suspend fun recordLaunch(id: String, at: Long)

    @Query("UPDATE games SET coverUri = :coverUri WHERE id = :id")
    suspend fun setCover(id: String, coverUri: String?)

    @Query("UPDATE games SET version = :version WHERE id = :id")
    suspend fun setGameVersionRaw(id: String, version: String)

    @Transaction
    suspend fun setGameVersion(id: String, version: String) {
        setGameVersionRaw(id, version)
        getGame(id)?.let { game ->
            deleteSearch(id)
            insertSearchRaw(game.toSearchEntity())
        }
    }

    @Query("UPDATE games SET f95Url = :f95Url WHERE id = :id")
    suspend fun setF95Url(id: String, f95Url: String?)

    @Query("UPDATE games SET libraryFolderId = :folderId WHERE id = :id")
    suspend fun setGameFolder(id: String, folderId: String?)

    @Query("UPDATE games SET title = :title, originalTitle = :originalTitle, developer = :developer, version = :version, productCode = :productCode, language = :language, description = :description, f95Url = :f95Url WHERE id = :id")
    suspend fun updateGameFieldsRaw(
        id: String,
        title: String,
        originalTitle: String?,
        developer: String?,
        version: String?,
        productCode: String?,
        language: String?,
        description: String?,
        f95Url: String?
    )

    @Transaction
    suspend fun updateGameFields(
        id: String,
        title: String,
        originalTitle: String?,
        developer: String?,
        version: String?,
        productCode: String?,
        language: String?,
        description: String?,
        f95Url: String?
    ) {
        updateGameFieldsRaw(id, title, originalTitle, developer, version, productCode, language, description, f95Url)
        val updatedGame = getGame(id)
        if (updatedGame != null) upsertGame(updatedGame)
    }

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteGame(id: String)

    @Query("DELETE FROM play_sessions WHERE gameId = :gameId")
    suspend fun deletePlaySessions(gameId: String)

    @Query("DELETE FROM metadata WHERE gameId = :gameId")
    suspend fun deleteMetadata(gameId: String)

    @Query("DELETE FROM cover_candidates WHERE gameId = :gameId")
    suspend fun deleteCoverCandidates(gameId: String)

    @Transaction
    suspend fun deleteGameCompletely(gameId: String) {
        clearGameTags(gameId)
        deleteSearch(gameId)
        deletePlaySessions(gameId)
        deleteMetadata(gameId)
        deleteCoverCandidates(gameId)
        deleteLaunchProfile(gameId)
        deleteSaveLocationsForGame(gameId)
        deleteSaveBackupsForGame(gameId)
        deleteInstalledFilesForGame(gameId)
        deleteInstallationsForGame(gameId)
        deleteGame(gameId)
    }

    @Query("SELECT * FROM game_sources ORDER BY displayName COLLATE NOCASE")
    fun observeSources(): Flow<List<GameSourceEntity>>

    @Query("SELECT * FROM game_sources WHERE id = :id LIMIT 1")
    suspend fun getSource(id: String): GameSourceEntity?

    @Query("SELECT * FROM game_sources WHERE enabled = 1")
    suspend fun getEnabledSources(): List<GameSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: GameSourceEntity)

    @Query("UPDATE game_sources SET enabled = NOT enabled WHERE id = :id")
    suspend fun toggleSource(id: String)

    @Query("UPDATE game_sources SET recursive = NOT recursive WHERE id = :id")
    suspend fun toggleSourceRecursive(id: String)

    @Query("UPDATE game_sources SET lastScanStatus = 'PARTIAL', lastError = 'Scan interrompu' WHERE lastScanStatus = 'RUNNING'")
    suspend fun recoverInterruptedScans()

    @Query("DELETE FROM game_sources WHERE id = :id")
    suspend fun deleteSource(id: String)

    @Query("DELETE FROM games WHERE sourceId = :sourceId")
    suspend fun deleteGamesForSource(sourceId: String)

    @Query("SELECT id FROM games WHERE sourceId = :sourceId")
    suspend fun getGameIdsForSource(sourceId: String): List<String>

    @Query("DELETE FROM game_tags WHERE gameId IN (:gameIds)")
    suspend fun deleteGameTagRefsForGames(gameIds: List<String>)

    @Query("DELETE FROM game_search WHERE gameId IN (:gameIds)")
    suspend fun deleteSearchForGames(gameIds: List<String>)

    @Query("DELETE FROM play_sessions WHERE gameId IN (:gameIds)")
    suspend fun deletePlaySessionsForGames(gameIds: List<String>)

    @Query("DELETE FROM metadata WHERE gameId IN (:gameIds)")
    suspend fun deleteMetadataForGames(gameIds: List<String>)

    @Query("DELETE FROM cover_candidates WHERE gameId IN (:gameIds)")
    suspend fun deleteCoverCandidatesForGames(gameIds: List<String>)

    @Query("DELETE FROM launch_profiles WHERE gameId IN (:gameIds)")
    suspend fun deleteLaunchProfilesForGames(gameIds: List<String>)

    @Query("DELETE FROM scan_report_items WHERE sourceId = :sourceId")
    suspend fun deleteScanReportItemsForSource(sourceId: String)

    @Query("DELETE FROM scan_history WHERE sourceId = :sourceId")
    suspend fun deleteScanHistoryForSource(sourceId: String)

    @Query("DELETE FROM deleted_games WHERE sourceId = :sourceId")
    suspend fun deleteDeletedGamesForSource(sourceId: String)

    @Query("DELETE FROM source_exclusions WHERE sourceId = :sourceId")
    suspend fun deleteSourceExclusions(sourceId: String)

    @Transaction
    suspend fun deleteSourceAndGames(sourceId: String) {
        val gameIds = getGameIdsForSource(sourceId)
        if (gameIds.isNotEmpty()) {
            deleteGameTagRefsForGames(gameIds)
            deleteSearchForGames(gameIds)
            deletePlaySessionsForGames(gameIds)
            deleteMetadataForGames(gameIds)
            deleteCoverCandidatesForGames(gameIds)
            deleteLaunchProfilesForGames(gameIds)
            deleteGamesForSource(sourceId)
            gameIds.forEach { gameId ->
                deleteSaveLocationsForGame(gameId)
                deleteSaveBackupsForGame(gameId)
                deleteInstalledFilesForGame(gameId)
                deleteInstallationsForGame(gameId)
            }
        }
        deleteScanReportItemsForSource(sourceId)
        deleteScanHistoryForSource(sourceId)
        deleteDeletedGamesForSource(sourceId)
        deleteSourceExclusions(sourceId)
        deleteSource(sourceId)
    }

    @Query("SELECT * FROM source_exclusions WHERE enabled = 1 AND (sourceId IS NULL OR sourceId = :sourceId)")
    suspend fun getExclusions(sourceId: String): List<SourceExclusionEntity>

    @Query("SELECT * FROM tags ORDER BY groupName, name COLLATE NOCASE")
    fun observeTags(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY groupName, name COLLATE NOCASE")
    suspend fun getTags(): List<TagEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TagEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(tag: TagEntity)

    @Query("DELETE FROM game_tags WHERE tagId IN (:tagIds)")
    suspend fun deleteGameTagRefs(tagIds: List<String>)

    @Query("DELETE FROM tags WHERE id IN (:tagIds)")
    suspend fun deleteTagsRaw(tagIds: List<String>)

    @Query("UPDATE game_tags SET tagId = :keepId WHERE tagId = :removedId")
    suspend fun replaceTagReferences(keepId: String, removedId: String)

    @Transaction
    suspend fun deleteTags(tagIds: List<String>) {
        if (tagIds.isEmpty()) return
        deleteGameTagRefs(tagIds)
        deleteTagsRaw(tagIds)
    }

    @Query("UPDATE tags SET groupName = :categoryName WHERE id IN (:tagIds)")
    suspend fun moveTagsToCategory(tagIds: List<String>, categoryName: String?)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addGameTag(ref: GameTagCrossRef)

    @Query("DELETE FROM game_tags WHERE gameId = :gameId AND tagId = :tagId")
    suspend fun removeGameTag(gameId: String, tagId: String)

    @Query("SELECT tags.* FROM tags JOIN game_tags ON tags.id = game_tags.tagId WHERE game_tags.gameId = :gameId ORDER BY tags.name")
    fun observeTagsForGame(gameId: String): Flow<List<TagEntity>>

    @Query("SELECT tagId FROM game_tags WHERE gameId = :gameId")
    suspend fun getTagIdsForGame(gameId: String): List<String>

    @Query("SELECT * FROM game_tags")
    fun observeGameTagRefs(): Flow<List<GameTagCrossRef>>

    @Query("DELETE FROM game_tags WHERE gameId = :gameId")
    suspend fun clearGameTags(gameId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGameTags(refs: List<GameTagCrossRef>)

    @Transaction
    suspend fun replaceGameTags(gameId: String, tagIds: Set<String>) {
        clearGameTags(gameId)
        insertGameTags(tagIds.map { GameTagCrossRef(gameId, it) })
    }

    @Query("SELECT * FROM tag_categories ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeTagCategories(): Flow<List<TagCategoryEntity>>

    @Query("SELECT * FROM tag_categories ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getTagCategories(): List<TagCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTagCategory(category: TagCategoryEntity)

    @Query("UPDATE tags SET groupName = :newName WHERE groupName = :oldName")
    suspend fun renameTagCategoryReferences(oldName: String, newName: String)

    @Query("UPDATE tags SET groupName = NULL WHERE groupName = :name")
    suspend fun clearTagCategoryReferences(name: String)

    @Query("DELETE FROM tag_categories WHERE id = :id")
    suspend fun deleteTagCategoryRaw(id: String)

    @Query("SELECT * FROM library_folders ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_folders ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getFolders(): List<LibraryFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM library_folders WHERE id = :id")
    suspend fun deleteFolder(id: String)

    @Query("UPDATE library_folders SET parentId = :newParentId WHERE parentId = :folderId")
    suspend fun moveChildFolders(folderId: String, newParentId: String?)

    @Query("UPDATE games SET libraryFolderId = NULL WHERE libraryFolderId = :folderId")
    suspend fun clearGamesFromFolder(folderId: String)

    @Insert
    suspend fun insertPlaySession(session: PlaySessionEntity)

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActivePlaySession(): PlaySessionEntity?

    @Query("UPDATE play_sessions SET endedAt = :endedAt, durationMs = :durationMs WHERE id = :id")
    suspend fun finishPlaySession(id: String, endedAt: Long, durationMs: Long)

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getPlayHistory(limit: Int = 200): List<PlaySessionEntity>

    @Query("SELECT play_sessions.* FROM play_sessions JOIN games ON play_sessions.gameId = games.id WHERE play_sessions.endedAt IS NOT NULL AND games.hidden = 0 ORDER BY play_sessions.startedAt DESC LIMIT :limit")
    fun observePlayHistory(limit: Int = 200): Flow<List<PlaySessionEntity>>

    @Query("DELETE FROM play_sessions WHERE id = :id")
    suspend fun deletePlaySession(id: String)

    @Query("DELETE FROM play_sessions WHERE endedAt IS NOT NULL AND endedAt < :before")
    suspend fun deletePlaySessionsBefore(before: Long)

    @Query("DELETE FROM play_sessions WHERE endedAt IS NOT NULL")
    suspend fun clearEndedPlaySessions()

    @Query("UPDATE play_sessions SET gameId = :primaryId WHERE gameId = :secondaryId")
    suspend fun movePlaySessions(primaryId: String, secondaryId: String)

    @Query("SELECT gameId, COALESCE(SUM(durationMs), 0) AS totalDurationMs, MAX(startedAt) AS lastSessionAt FROM play_sessions WHERE endedAt IS NOT NULL GROUP BY gameId")
    fun observePlayStats(): Flow<List<GamePlayStat>>

    @Query("SELECT * FROM collections WHERE builtinKey IS NULL ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collection_rules ORDER BY rowid")
    fun observeCollectionRules(): Flow<List<CollectionRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollection(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectionRules(rules: List<CollectionRuleEntity>)

    @Query("DELETE FROM collection_rules WHERE collectionId = :collectionId")
    suspend fun deleteCollectionRules(collectionId: String)

    @Query("DELETE FROM collections WHERE id = :collectionId")
    suspend fun deleteCollectionRaw(collectionId: String)

    @Transaction
    suspend fun replaceCollection(collection: CollectionEntity, rules: List<CollectionRuleEntity>) {
        upsertCollection(collection)
        deleteCollectionRules(collection.id)
        upsertCollectionRules(rules)
    }

    @Transaction
    suspend fun deleteCollection(collectionId: String) {
        deleteCollectionRules(collectionId)
        deleteCollectionRaw(collectionId)
    }

    @Query("UPDATE games SET favorite = :favorite WHERE id IN (:ids)")
    suspend fun setGamesFavorite(ids: List<String>, favorite: Boolean)

    @Query("UPDATE games SET libraryFolderId = :folderId WHERE id IN (:ids)")
    suspend fun setGamesFolder(ids: List<String>, folderId: String?)

    @Query("UPDATE games SET title = :title, originalTitle = :originalTitle, aliases = :aliases, coverUri = :coverUri, bannerUri = :bannerUri, iconUri = :iconUri, description = :description, developer = :developer, version = :version, productCode = :productCode, language = :language, f95Url = :f95Url, releaseDate = :releaseDate, dateAdded = :dateAdded, lastPlayedAt = :lastPlayedAt, playCount = :playCount, favorite = :favorite, keywords = :keywords WHERE id = :id")
    suspend fun updateMergedGame(
        id: String, title: String, originalTitle: String?, aliases: String,
        coverUri: String?, bannerUri: String?, iconUri: String?, description: String?, developer: String?,
        version: String?, productCode: String?, language: String?, f95Url: String?, releaseDate: Long?, dateAdded: Long,
        lastPlayedAt: Long?, playCount: Int, favorite: Boolean, keywords: String
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanHistory(history: ScanHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScanReportItems(items: List<ScanReportItemEntity>)

    @Query("SELECT * FROM scan_history WHERE sourceId = :sourceId AND finishedAt IS NOT NULL ORDER BY finishedAt DESC LIMIT 1")
    suspend fun getLatestScanHistory(sourceId: String): ScanHistoryEntity?

    @Query("SELECT * FROM scan_report_items WHERE scanId = :scanId ORDER BY rowid")
    suspend fun getScanReportItems(scanId: String): List<ScanReportItemEntity>

    @Query("SELECT * FROM launch_profiles WHERE gameId = :gameId LIMIT 1")
    fun observeLaunchProfile(gameId: String): Flow<LaunchProfileEntity?>

    @Query("SELECT * FROM launch_profiles WHERE gameId = :gameId LIMIT 1")
    suspend fun getLaunchProfile(gameId: String): LaunchProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLaunchProfile(profile: LaunchProfileEntity)

    @Query("DELETE FROM launch_profiles WHERE gameId = :gameId")
    suspend fun deleteLaunchProfile(gameId: String)

    @Query("SELECT * FROM deleted_games ORDER BY deletedAt DESC")
    fun observeDeletedGames(): Flow<List<DeletedGameEntity>>

    @Query("SELECT * FROM deleted_games WHERE sourceId = :sourceId")
    suspend fun getDeletedGamesForSource(sourceId: String): List<DeletedGameEntity>

    @Query("SELECT * FROM deleted_games WHERE documentUri = :documentUri LIMIT 1")
    suspend fun findDeletedGameByDocumentUri(documentUri: String): DeletedGameEntity?

    @Query("SELECT * FROM deleted_games WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findDeletedGameByFingerprint(fingerprint: String): DeletedGameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDeletedGame(game: DeletedGameEntity)

    @Query("DELETE FROM deleted_games WHERE id = :id")
    suspend fun restoreDeletedGame(id: String)

    @Query("SELECT * FROM ignored_duplicate_groups")
    fun observeIgnoredDuplicateGroups(): Flow<List<IgnoredDuplicateGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignoreDuplicateGroup(group: IgnoredDuplicateGroupEntity)

    @Query("DELETE FROM ignored_duplicate_groups WHERE groupKey = :groupKey")
    suspend fun unignoreDuplicateGroup(groupKey: String)
    @Insert
    suspend fun insertAudit(event: AuditEventEntity)
    @Query("SELECT * FROM audit_events ORDER BY timestamp DESC")
    fun observeAuditEvents(): Flow<List<AuditEventEntity>>

    @Query("SELECT * FROM game_save_locations WHERE gameId = :gameId ORDER BY addedAt")
    fun observeSaveLocations(gameId: String): Flow<List<GameSaveLocationEntity>>

    @Query("SELECT * FROM game_save_locations WHERE gameId = :gameId AND enabled = 1")
    suspend fun getSaveLocations(gameId: String): List<GameSaveLocationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSaveLocation(location: GameSaveLocationEntity)

    @Query("DELETE FROM game_save_locations WHERE id = :id")
    suspend fun deleteSaveLocation(id: String)

    @Query("DELETE FROM game_save_locations WHERE gameId = :gameId")
    suspend fun deleteSaveLocationsForGame(gameId: String)

    @Query("SELECT * FROM save_backups WHERE gameId = :gameId ORDER BY createdAt DESC")
    fun observeSaveBackups(gameId: String): Flow<List<SaveBackupEntity>>

    @Query("SELECT * FROM save_backups WHERE sourceUri = :sourceUri ORDER BY createdAt DESC")
    suspend fun getSaveBackupsForSource(sourceUri: String): List<SaveBackupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSaveBackup(backup: SaveBackupEntity)

    @Query("DELETE FROM save_backups WHERE id = :id")
    suspend fun deleteSaveBackup(id: String)

    @Query("DELETE FROM save_backups WHERE gameId = :gameId")
    suspend fun deleteSaveBackupsForGame(gameId: String)

    @Query("SELECT * FROM mods ORDER BY engine, name COLLATE NOCASE")
    fun observeMods(): Flow<List<ModEntity>>

    @Query("SELECT * FROM mods")
    suspend fun getMods(): List<ModEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMod(mod: ModEntity)

    @Query("DELETE FROM mods WHERE id NOT IN (:keepIds)")
    suspend fun deleteModsNotIn(keepIds: List<String>)

    @Query("DELETE FROM mods WHERE id IN (:ids)")
    suspend fun deleteMods(ids: List<String>)

    @Query("SELECT * FROM mod_installations WHERE gameId = :gameId ORDER BY installedAt DESC")
    fun observeInstallationsForGame(gameId: String): Flow<List<ModInstallationEntity>>

    @Query("SELECT * FROM mod_installations WHERE modId = :modId")
    suspend fun getInstallationsForMod(modId: String): List<ModInstallationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstallation(installation: ModInstallationEntity)

    @Query("DELETE FROM mod_installations WHERE id = :id")
    suspend fun deleteInstallation(id: String)

    @Query("DELETE FROM mod_installations WHERE gameId = :gameId")
    suspend fun deleteInstallationsForGame(gameId: String)

    @Query("DELETE FROM mod_installed_files WHERE installationId IN (SELECT id FROM mod_installations WHERE gameId = :gameId)")
    suspend fun deleteInstalledFilesForGame(gameId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInstalledFiles(files: List<ModInstalledFileEntity>)

    @Query("SELECT * FROM mod_installed_files WHERE installationId = :installationId ORDER BY relativePath")
    suspend fun getInstalledFiles(installationId: String): List<ModInstalledFileEntity>

    @Query("DELETE FROM mod_installed_files WHERE installationId = :installationId")
    suspend fun deleteInstalledFiles(installationId: String)
}
