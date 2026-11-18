package fr.astragames.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

// Each bulk tag insert binds one batch of game IDs and one batch of tag IDs.
private const val TAG_PAIR_BATCH_SIZE = 450

@Dao
interface AstraDao {
    @Query("SELECT * FROM games WHERE hidden = 0 ORDER BY title COLLATE NOCASE")
    fun observeGames(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    fun observeGame(id: String): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun getGame(id: String): GameEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM games WHERE id = :id)")
    suspend fun gameExists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM games WHERE id IN (:ids)")
    suspend fun countGamesByIds(ids: List<String>): Int

    @Query("SELECT id FROM games")
    suspend fun getGameIds(): List<String>

    @Query("SELECT * FROM games")
    suspend fun getAllGames(): List<GameEntity>

    @Query("UPDATE game_sources SET gamesCount = (SELECT COUNT(*) FROM games WHERE games.sourceId = game_sources.id AND missing = 0)")
    suspend fun refreshSourceGameCounts()

    @Query("SELECT * FROM games WHERE sourceId = :sourceId")
    suspend fun getGamesForSource(sourceId: String): List<GameEntity>

    @Query("SELECT * FROM games WHERE fingerprint = :fingerprint")
    suspend fun getGamesByFingerprint(fingerprint: String): List<GameEntity>

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

    @Query("SELECT id FROM games WHERE sourceId = :sourceId AND missing = 0")
    suspend fun getPresentGameIds(sourceId: String): List<String>

    @Query("UPDATE games SET missing = 1 WHERE sourceId = :sourceId AND id IN (:ids)")
    suspend fun markSourceGamesMissing(sourceId: String, ids: List<String>)

    @Query("UPDATE games SET missing = 0 WHERE id = :id")
    suspend fun markGameFound(id: String)

    @Query("UPDATE games SET missing = 0 WHERE sourceId = :sourceId AND id IN (:ids)")
    suspend fun markSourceGamesFound(sourceId: String, ids: List<String>)

    @Transaction
    suspend fun reconcileSourceGames(sourceId: String, foundIds: List<String>) {
        val present = getPresentGameIds(sourceId).toHashSet()
        val found = foundIds.toHashSet()
        (present - found).chunked(900).forEach { markSourceGamesMissing(sourceId, it) }
        (found - present).chunked(900).forEach { markSourceGamesFound(sourceId, it) }
    }

    @Query("SELECT COUNT(*) FROM games WHERE sourceId = :sourceId AND missing = 1")
    suspend fun countMissing(sourceId: String): Int

    @Query("UPDATE games SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: String): Int

    @Query("UPDATE games SET lastPlayedAt = :at, playCount = playCount + 1, missing = 0 WHERE id = :id")
    suspend fun recordLaunch(id: String, at: Long): Int

    @Query("UPDATE games SET coverUri = :coverUri WHERE id = :id")
    suspend fun setCover(id: String, coverUri: String?): Int

    @Query("UPDATE games SET coverUri = :coverUri WHERE id = :id AND (coverUri IS NULL OR TRIM(coverUri) = '')")
    suspend fun setCoverIfMissing(id: String, coverUri: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM games WHERE coverUri IN (:uris) OR bannerUri IN (:uris) OR iconUri IN (:uris))")
    suspend fun isArtworkReferenced(uris: List<String>): Boolean

    @Transaction
    suspend fun mergeMetadata(id: String, values: fr.astragames.app.core.metadata.MetadataValues, replaceExisting: Boolean = false): Boolean {
        val current = getGame(id) ?: return false
        val updated = values.merge(current, replaceExisting)
        if (updated == current) return false
        upsertGame(updated)
        return true
    }

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

    @Query("UPDATE games SET ryuugamesUrl = :url WHERE id = :id")
    suspend fun setRyuugamesUrl(id: String, url: String?)

    @Query("""UPDATE games SET title = :title, originalTitle = :originalTitle, developer = :developer, version = :version,
        productCode = :productCode, language = :language, description = :description, f95Url = :f95Url,
        ryuugamesUrl = :ryuugamesUrl WHERE id = :id""")
    suspend fun updateGameFieldsRaw(
        id: String,
        title: String,
        originalTitle: String?,
        developer: String?,
        version: String?,
        productCode: String?,
        language: String?,
        description: String?,
        f95Url: String?,
        ryuugamesUrl: String?
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
        f95Url: String?,
        ryuugamesUrl: String? = null
    ) {
        updateGameFieldsRaw(id, title, originalTitle, developer, version, productCode, language, description, f95Url, ryuugamesUrl)
        val game = checkNotNull(getGame(id)) { "Jeu introuvable." }
        deleteSearch(id)
        insertSearchRaw(game.toSearchEntity())
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

    @Query("SELECT treeUri FROM game_sources")
    suspend fun getSourceTreeUris(): List<String>

    @Query("SELECT * FROM game_sources WHERE id = :id LIMIT 1")
    suspend fun getSource(id: String): GameSourceEntity?

    @Query("SELECT * FROM game_sources WHERE enabled = 1")
    suspend fun getEnabledSources(): List<GameSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: GameSourceEntity)

    @Query("UPDATE game_sources SET lastScanStatus = 'RUNNING', lastError = NULL WHERE id = :sourceId")
    suspend fun startSourceScan(sourceId: String)

    @Query("""UPDATE game_sources SET lastScanAt = :finishedAt, lastScanStatus = :status, lastError = :error,
        gamesCount = (SELECT COUNT(*) FROM games WHERE games.sourceId = :sourceId AND missing = 0)
        WHERE id = :sourceId""")
    suspend fun finishSourceScan(sourceId: String, finishedAt: Long, status: String, error: String?)

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
            gameIds.chunked(900).forEach { chunk ->
                deleteGameTagRefsForGames(chunk)
                deleteSearchForGames(chunk)
                deletePlaySessionsForGames(chunk)
                deleteMetadataForGames(chunk)
                deleteCoverCandidatesForGames(chunk)
                deleteLaunchProfilesForGames(chunk)
            }
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

    @Query("SELECT EXISTS(SELECT 1 FROM tags WHERE id = :id)")
    suspend fun tagExists(id: String): Boolean

    @Query("SELECT COUNT(*) FROM tags WHERE id IN (:ids)")
    suspend fun countTagsByIds(ids: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<TagEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTag(tag: TagEntity)

    @Query("DELETE FROM game_tags WHERE tagId IN (:tagIds)")
    suspend fun deleteGameTagRefs(tagIds: List<String>)

    @Query("DELETE FROM tags WHERE id IN (:tagIds)")
    suspend fun deleteTagsRaw(tagIds: List<String>)

    @Query("INSERT OR IGNORE INTO game_tags (gameId, tagId) SELECT gameId, :keepId FROM game_tags WHERE tagId = :removedId")
    suspend fun copyTagReferences(keepId: String, removedId: String)

    @Query("UPDATE collection_rules SET value = :keepId WHERE field = 'TAG' AND value = :removedId")
    suspend fun retargetTagRules(keepId: String, removedId: String)

    @Transaction
    suspend fun mergeTags(keepId: String, removedId: String) {
        if (keepId == removedId) return
        require(tagExists(keepId)) { "Le tag à conserver est introuvable." }
        if (!tagExists(removedId)) return
        copyTagReferences(keepId, removedId)
        retargetTagRules(keepId, removedId)
        deleteGameTagRefs(listOf(removedId))
        deleteTagsRaw(listOf(removedId))
    }

    @Transaction
    suspend fun deleteTags(tagIds: List<String>) {
        if (tagIds.isEmpty()) return
        tagIds.chunked(900).forEach {
            deleteGameTagRefs(it)
            deleteTagsRaw(it)
        }
    }

    @Query("UPDATE tags SET groupName = :categoryName WHERE id IN (:tagIds)")
    suspend fun moveTagsToCategory(tagIds: List<String>, categoryName: String?): Int

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

    @Query("""
        INSERT OR IGNORE INTO game_tags (gameId, tagId)
        SELECT games.id, tags.id FROM games CROSS JOIN tags
        WHERE games.id IN (:gameIds) AND tags.id IN (:tagIds)
    """)
    suspend fun insertGameTagPairs(gameIds: List<String>, tagIds: List<String>)

    @Transaction
    suspend fun addTagsToGames(gameIds: Set<String>, tagIds: Set<String>) {
        if (gameIds.isEmpty()) return
        val gameBatches = gameIds.chunked(TAG_PAIR_BATCH_SIZE)
        val tagBatches = tagIds.chunked(TAG_PAIR_BATCH_SIZE)
        for (ids in gameBatches) {
            check(countGamesByIds(ids) == ids.size) { "Un jeu sélectionné a été retiré de la bibliothèque." }
        }
        for (ids in tagBatches) {
            check(countTagsByIds(ids) == ids.size) { "Un tag sélectionné a été supprimé. Vérifiez votre sélection." }
        }
        for (games in gameBatches) {
            for (tags in tagBatches) insertGameTagPairs(games, tags)
        }
    }

    @Query("DELETE FROM game_tags WHERE gameId = :gameId AND tagId IN (:tagIds)")
    suspend fun removeGameTags(gameId: String, tagIds: List<String>)

    @Transaction
    suspend fun replaceGameTags(gameId: String, tagIds: Set<String>) {
        clearGameTags(gameId)
        addTagsToGames(setOf(gameId), tagIds)
    }

    @Query("SELECT * FROM tag_categories ORDER BY sortOrder, name COLLATE NOCASE, id")
    fun observeTagCategories(): Flow<List<TagCategoryEntity>>

    @Query("SELECT * FROM tag_categories ORDER BY sortOrder, name COLLATE NOCASE, id")
    suspend fun getTagCategories(): List<TagCategoryEntity>

    @Query("SELECT * FROM tag_categories WHERE id = :id")
    suspend fun getTagCategory(id: String): TagCategoryEntity?

    @Query("UPDATE tag_categories SET sortOrder = :order WHERE id = :id")
    suspend fun setTagCategoryOrder(id: String, order: Int)

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

    @Query("SELECT * FROM library_folders WHERE id = :id")
    suspend fun getFolder(id: String): LibraryFolderEntity?

    @Query("UPDATE library_folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String): Int

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

    @Query("SELECT MIN(startedAt) FROM play_sessions WHERE endedAt IS NULL")
    suspend fun firstOpenSessionStart(): Long?

    @Query("SELECT * FROM play_sessions WHERE startedAt >= :firstStart ORDER BY startedAt DESC, rowid DESC")
    suspend fun sessionsSince(firstStart: Long): List<PlaySessionEntity>

    @Query("UPDATE play_sessions SET endedAt = :endedAt, durationMs = :durationMs WHERE id = :id AND endedAt IS NULL")
    suspend fun finishPlaySession(id: String, endedAt: Long, durationMs: Long)

    @Query("SELECT * FROM play_sessions WHERE endedAt IS NOT NULL ORDER BY startedAt DESC, rowid DESC LIMIT :limit")
    suspend fun getPlayHistory(limit: Int = 200): List<PlaySessionEntity>

    @Query("SELECT play_sessions.* FROM play_sessions JOIN games ON play_sessions.gameId = games.id WHERE play_sessions.endedAt IS NOT NULL AND games.hidden = 0 ORDER BY play_sessions.startedAt DESC, play_sessions.rowid DESC LIMIT :limit")
    fun observePlayHistory(limit: Int = 200): Flow<List<PlaySessionEntity>>

    @Query("DELETE FROM play_sessions WHERE id = :id AND endedAt IS NOT NULL")
    suspend fun deletePlaySession(id: String)

    @Query("DELETE FROM play_sessions WHERE endedAt IS NOT NULL AND endedAt < :before")
    suspend fun deletePlaySessionsBefore(before: Long)

    @Query("DELETE FROM play_sessions WHERE endedAt IS NOT NULL")
    suspend fun clearEndedPlaySessions()

    @Query("UPDATE play_sessions SET gameId = :primaryId WHERE gameId = :secondaryId")
    suspend fun movePlaySessions(primaryId: String, secondaryId: String)

    @Query("""
        SELECT gameId,
               SUM(MAX(0, COALESCE(durationMs, MIN(endedAt - startedAt, $MAX_SESSION_DURATION_MS)))) AS totalDurationMs,
               MAX(startedAt) AS lastSessionAt
        FROM play_sessions WHERE endedAt IS NOT NULL GROUP BY gameId
    """)
    fun observePlayStats(): Flow<List<GamePlayStat>>

    @Query("SELECT * FROM collections WHERE builtinKey IS NULL ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id AND builtinKey IS NULL")
    suspend fun getCustomCollection(id: String): CollectionEntity?

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
    suspend fun setGamesFavorite(ids: List<String>, favorite: Boolean): Int

    @Query("UPDATE games SET libraryFolderId = :folderId WHERE id IN (:ids)")
    suspend fun setGamesFolder(ids: List<String>, folderId: String?): Int

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

    @Query("SELECT * FROM launch_profiles")
    fun observeLaunchProfiles(): Flow<List<LaunchProfileEntity>>

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

    @Transaction
    suspend fun ignoreAndRemoveGames(ignored: DeletedGameEntity, gameIds: List<String>) {
        upsertDeletedGame(ignored)
        gameIds.forEach { deleteGameCompletely(it) }
    }

    @Query("SELECT * FROM deleted_games")
    suspend fun getIgnoredFolders(): List<DeletedGameEntity>

    @Query("DELETE FROM deleted_games WHERE id = :id")
    suspend fun restoreDeletedGame(id: String)

    @Query("SELECT * FROM ignored_duplicate_groups")
    fun observeIgnoredDuplicateGroups(): Flow<List<IgnoredDuplicateGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ignoreDuplicateGroup(group: IgnoredDuplicateGroupEntity)

    @Query("DELETE FROM ignored_duplicate_groups WHERE groupKey = :groupKey")
    suspend fun unignoreDuplicateGroup(groupKey: String)

    @Query("DELETE FROM ignored_duplicate_groups")
    suspend fun resetIgnoredDuplicateGroups()
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

    @Query("SELECT EXISTS(SELECT 1 FROM mod_installations WHERE gameId = :gameId)")
    suspend fun hasInstallationsForGame(gameId: String): Boolean

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
