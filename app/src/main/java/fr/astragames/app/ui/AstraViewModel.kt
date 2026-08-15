package fr.astragames.app.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.model.CoverSize
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.GameCompatibilityReport
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.core.model.LibraryViewMode
import fr.astragames.app.core.model.TagMatchMode
import fr.astragames.app.core.model.ThemeMode
import fr.astragames.app.core.model.ScanReport
import fr.astragames.app.core.metadata.CoverCandidate
import fr.astragames.app.core.metadata.F95ZoneMetadata
import fr.astragames.app.core.search.TagMatcher
import fr.astragames.app.core.search.DuplicateDetector
import fr.astragames.app.core.search.DuplicateDetector.DuplicateGroup
import fr.astragames.app.core.collections.SmartCollectionEvaluator
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GamePlayStat
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.GameTagCrossRef
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.data.repository.GameEdits
import fr.astragames.app.data.repository.CollectionRuleDraft
import fr.astragames.app.data.repository.DuplicateMergePreview
import fr.astragames.app.data.repository.SaveConflictStrategy
import fr.astragames.app.data.scanner.ScanProgressUpdate
import fr.astragames.app.launcher.CompatibilityDiagnostic
import fr.astragames.app.launcher.JoiPlayRuntimeInfo
import fr.astragames.app.launcher.JoiPlayRuntimeManager
import fr.astragames.app.settings.AstraSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryFilters(
    val query: String = "",
    val sourceId: String? = null,
    val folderId: String? = null,
    val systemFolderId: String? = null,
    val engine: GameEngine? = null,
    val tagIds: Set<String> = emptySet(),
    val tagMode: TagMatchMode = TagMatchMode.ALL,
    val favoritesOnly: Boolean = false,
    val missingOnly: Boolean = false,
    val sort: LibrarySort = LibrarySort.TITLE
)

data class SystemFolderFilter(
    val id: String,
    val sourceId: String,
    val label: String,
    val depth: Int,
    val gameIds: Set<String>
)

enum class LibrarySort { TITLE, RECENTLY_ADDED, LAST_PLAYED, MOST_PLAYED }

data class AstraUiState(
    val games: List<GameEntity> = emptyList(),
    val filteredGames: List<GameEntity> = emptyList(),
    val sources: List<GameSourceEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val tagCategories: List<TagCategoryEntity> = emptyList(),
    val folders: List<LibraryFolderEntity> = emptyList(),
    val systemFolders: List<SystemFolderFilter> = emptyList(),
    val deletedGames: List<DeletedGameEntity> = emptyList(),
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val collectionRules: List<CollectionRuleEntity> = emptyList(),
    val customCollectionGames: Map<String, List<GameEntity>> = emptyMap(),
    val playStats: Map<String, GamePlayStat> = emptyMap(),
    val runtimes: List<JoiPlayRuntimeInfo> = emptyList(),
    val settings: AstraSettings = AstraSettings(),
    val filters: LibraryFilters = LibraryFilters(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgressState = ScanProgressState(),
    val joiPlayInstalled: Boolean = false,
    val setupQueue: List<GameEntity> = emptyList()
)

data class ScanProgressState(
    val active: Boolean = false,
    val sourceName: String = "",
    val phase: String = "",
    val currentPath: String = "",
    val depth: Int = 0,
    val visitedFolders: Int = 0,
    val foundGames: Int = 0
)

data class CoverSearchState(
    val gameId: String? = null,
    val loading: Boolean = false,
    val downloading: Boolean = false,
    val results: List<CoverCandidate> = emptyList(),
    val error: String? = null,
    val configured: Boolean = false,
    val browserUrl: String? = null
)

data class CropRequest(val gameId: String, val source: Uri)

data class F95ImportState(
    val gameId: String? = null,
    val loading: Boolean = false,
    val metadata: F95ZoneMetadata? = null,
    val error: String? = null,
    val browserUrl: String? = null
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AstraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AstraApplication
    private val repository = app.container.repository
    private val settingsRepository = app.container.settings
    private val filters = MutableStateFlow(LibraryFilters())
    private val scanning = MutableStateFlow(false)
    private val scanProgress = MutableStateFlow(ScanProgressState())
    private val setupQueueIds = MutableStateFlow<List<String>>(emptyList())
    private val mutableScanReports = MutableStateFlow<List<ScanReport>>(emptyList())
    val scanReports: StateFlow<List<ScanReport>> = mutableScanReports
    private val mutableCompatibility = MutableStateFlow<Map<String, GameCompatibilityReport>>(emptyMap())
    val compatibility: StateFlow<Map<String, GameCompatibilityReport>> = mutableCompatibility
    private val compatibilityDiagnostic = CompatibilityDiagnostic(app.container.launcher)
    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    private val mutableCoverSearch = MutableStateFlow(
        CoverSearchState(configured = app.container.covers.configured)
    )
    val coverSearch: StateFlow<CoverSearchState> = mutableCoverSearch
    val cropRequests = MutableSharedFlow<CropRequest>(extraBufferCapacity = 1)
    private val mutableF95Import = MutableStateFlow(F95ImportState())
    val f95Import: StateFlow<F95ImportState> = mutableF95Import
    private val mutableDuplicatePreview = MutableStateFlow<DuplicateMergePreview?>(null)
    val duplicatePreview: StateFlow<DuplicateMergePreview?> = mutableDuplicatePreview
    val openFolderRequests = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    private val runtimeManager = JoiPlayRuntimeManager()

    private data class CoreData(
        val games: List<GameEntity>,
        val candidates: List<GameEntity>,
        val sources: List<GameSourceEntity>,
        val tags: List<TagEntity>,
        val tagCategories: List<TagCategoryEntity>,
        val folders: List<LibraryFolderEntity>,
        val deletedGames: List<DeletedGameEntity>,
        val collections: List<CollectionEntity>,
        val collectionRules: List<CollectionRuleEntity>,
        val refs: List<GameTagCrossRef>,
        val playStats: List<GamePlayStat>,
        val ignoredDuplicateKeys: Set<String>
    )

    private val candidates = filters.map { it.query }.distinctUntilChanged().flatMapLatest { query ->
        if (query.isBlank()) repository.games else repository.search(query)
    }

    private val gameData = combine(repository.games, candidates) { all, filtered -> all to filtered }

    private val tagData = combine(repository.tags, repository.tagCategories) { tags, categories -> tags to categories }

    private data class OrganizationData(
        val folders: List<LibraryFolderEntity>, val deleted: List<DeletedGameEntity>,
        val collections: List<CollectionEntity>, val rules: List<CollectionRuleEntity>
    )
    private data class ActivityData(
        val refs: List<GameTagCrossRef>, val stats: List<GamePlayStat>, val ignored: Set<String>
    )

    private val organizationData = combine(
        repository.folders, repository.deletedGames, repository.collections, repository.collectionRules
    ) { folders, deleted, collections, rules -> OrganizationData(folders, deleted, collections, rules) }
    private val activityData = combine(
        repository.gameTagRefs(), repository.playStats, repository.ignoredDuplicateGroups
    ) { refs, stats, ignored -> ActivityData(refs, stats, ignored.map { it.groupKey }.toSet()) }

    private val coreData = combine(
        gameData, repository.sources, tagData, organizationData, activityData
    ) { games, sources, tags, organization, activity ->
        CoreData(
            games.first, games.second, sources, tags.first, tags.second,
            organization.folders, organization.deleted, organization.collections, organization.rules,
            activity.refs, activity.stats, activity.ignored
        )
    }

    private val scanState = combine(scanning, scanProgress) { active, progress -> active to progress }

    val uiState: StateFlow<AstraUiState> = combine(
        coreData, settingsRepository.settings, filters, scanState, setupQueueIds
    ) { data, settings, filter, scan, setupIds ->
        val refsByGame = data.refs.groupBy { it.gameId }.mapValues { (_, refs) -> refs.map { it.tagId }.toSet() }
        val selectedFolderIds = filter.folderId?.let { rootId -> descendantFolderIds(rootId, data.folders) }
        val systemFolders = buildSystemFolderFilters(data.games, data.sources)
        val selectedSystemGameIds = filter.systemFolderId
            ?.let { id -> systemFolders.firstOrNull { it.id == id }?.gameIds }
            .orEmpty()
        val filtered = data.candidates.filter { game ->
            val gameTags = refsByGame[game.id].orEmpty()
            val tagsMatch = TagMatcher.matches(filter.tagIds, gameTags, filter.tagMode)
            (filter.sourceId == null || game.sourceId == filter.sourceId) &&
                (selectedFolderIds == null || game.libraryFolderId in selectedFolderIds) &&
                (filter.systemFolderId == null || game.id in selectedSystemGameIds) &&
                (filter.engine == null || game.engine == filter.engine.name) &&
                (!filter.favoritesOnly || game.favorite) &&
                (!filter.missingOnly || game.missing) && tagsMatch
        }.let { games ->
            when (filter.sort) {
                LibrarySort.TITLE -> games.sortedBy { it.title.lowercase() }
                LibrarySort.RECENTLY_ADDED -> games.sortedByDescending { it.dateAdded }
                LibrarySort.LAST_PLAYED -> games.sortedByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }
                LibrarySort.MOST_PLAYED -> games.sortedByDescending { it.playCount }
            }
        }
        val playStatsByGame = data.playStats.associateBy { it.gameId }
        val customGames = data.collections.associate { collection ->
            val rules = data.collectionRules.filter { it.collectionId == collection.id }
            collection.id to data.games.filter { game ->
                SmartCollectionEvaluator.matches(collection, rules, game, data.refs, data.folders, playStatsByGame[game.id])
            }
        }
        val duplicateGroups = DuplicateDetector.groups(data.games).filterNot { it.key in data.ignoredDuplicateKeys }
        val libraryEngines = data.games.mapNotNull { runCatching { GameEngine.valueOf(it.engine) }.getOrNull() }.toSet()
        AstraUiState(
            games = data.games,
            filteredGames = filtered,
            sources = data.sources,
            tags = data.tags,
            tagCategories = data.tagCategories,
            folders = data.folders,
            systemFolders = systemFolders,
            deletedGames = data.deletedGames,
            duplicateGroups = duplicateGroups,
            collections = data.collections,
            collectionRules = data.collectionRules,
            customCollectionGames = customGames,
            playStats = playStatsByGame,
            runtimes = runtimeManager.inspect(application, libraryEngines),
            settings = settings,
            filters = filter,
            scanning = scan.first,
            scanProgress = scan.second,
            joiPlayInstalled = app.container.launcher.isInstalled(application),
            setupQueue = setupIds.mapNotNull { id -> data.games.firstOrNull { it.id == id } }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AstraUiState())

    init {
        viewModelScope.launch {
            repository.recoverInterruptedScans()
            if (settingsRepository.settings.first().scanOnLaunch) scanAll(silent = true)
        }
    }

    fun updateQuery(value: String) { filters.value = filters.value.copy(query = value) }
    fun filterSource(id: String?) { filters.value = filters.value.copy(sourceId = id, systemFolderId = null) }
    fun filterFolder(id: String?) { filters.value = filters.value.copy(folderId = id) }
    fun filterSystemFolder(id: String?) { filters.value = filters.value.copy(systemFolderId = id, sourceId = null) }
    fun setSort(sort: LibrarySort) { filters.value = filters.value.copy(sort = sort) }
    fun filterEngine(engine: GameEngine?) { filters.value = filters.value.copy(engine = engine) }
    fun toggleFavoriteFilter() { filters.value = filters.value.copy(favoritesOnly = !filters.value.favoritesOnly) }
    fun toggleMissingFilter() { filters.value = filters.value.copy(missingOnly = !filters.value.missingOnly) }
    fun toggleTagFilter(id: String) {
        val next = filters.value.tagIds.toMutableSet().apply { if (!add(id)) remove(id) }
        filters.value = filters.value.copy(tagIds = next)
    }
    fun setTagFilters(ids: Set<String>) { filters.value = filters.value.copy(tagIds = ids) }
    fun setTagMode(mode: TagMatchMode) { filters.value = filters.value.copy(tagMode = mode) }
    fun searchByTag(id: String) {
        filters.value = LibraryFilters(query = "", tagIds = setOf(id), tagMode = TagMatchMode.ALL)
    }
    fun clearFilters() { filters.value = LibraryFilters(query = filters.value.query) }

    private fun buildSystemFolderFilters(
        games: List<GameEntity>,
        sources: List<GameSourceEntity>
    ): List<SystemFolderFilter> {
        data class MutableFolder(
            val sourceId: String,
            val label: String,
            val depth: Int,
            val games: MutableSet<String> = linkedSetOf()
        )

        val folders = linkedMapOf<String, MutableFolder>()
        sources.forEach { source ->
            val treeId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(source.treeUri)) }.getOrNull()
                ?: return@forEach
            games.asSequence().filter { it.sourceId == source.id }.forEach { game ->
                val documentId = runCatching { DocumentsContract.getDocumentId(Uri.parse(game.documentUri)) }.getOrNull()
                    ?: return@forEach
                val relative = when {
                    documentId == treeId -> ""
                    documentId.startsWith("$treeId/") -> documentId.removePrefix("$treeId/")
                    else -> return@forEach
                }
                // The last segment is the game itself. The filter lists its real parent
                // hierarchy, which keeps large libraries usable instead of showing one
                // menu entry per game.
                val parentPath = relative.substringBeforeLast('/', missingDelimiterValue = "")
                val paths = buildList {
                    add("")
                    var current = ""
                    parentPath.split('/').filter(String::isNotBlank).forEach { segment ->
                        current = listOf(current, segment).filter(String::isNotBlank).joinToString("/")
                        add(current)
                    }
                }
                paths.forEach { path ->
                    val key = "${source.id}|$path"
                    val decoded = path.split('/').filter(String::isNotBlank).joinToString(" / ") { Uri.decode(it) }
                    val label = if (decoded.isBlank()) source.displayName else "${source.displayName} / $decoded"
                    folders.getOrPut(key) { MutableFolder(source.id, label, path.count { it == '/' } + if (path.isBlank()) 0 else 1) }.games += game.id
                }
            }
        }
        return folders.map { (id, folder) ->
            SystemFolderFilter(id, folder.sourceId, folder.label, folder.depth, folder.games)
        }.sortedWith(compareBy<SystemFolderFilter> { sources.indexOfFirst { source -> source.id == it.sourceId } }.thenBy { it.label.lowercase() })
    }

    fun addSource(uri: Uri) = viewModelScope.launch {
        runCatching { repository.addSource(uri) }
            .onSuccess { events.emit(UiEvent.Message("Source ajoutée")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Impossible d'ajouter la source")) }
    }

    fun toggleSource(id: String) = viewModelScope.launch { repository.toggleSource(id) }
    fun toggleSourceRecursive(id: String) = viewModelScope.launch { repository.toggleSourceRecursive(id) }
    fun removeSource(id: String) = viewModelScope.launch {
        repository.removeSource(id, removeGames = true)
        events.emit(UiEvent.Message("Source retirée. Les fichiers du dossier sont conservés."))
    }
    fun scanSource(id: String) = viewModelScope.launch {
        val before = repository.getGameIds()
        scanning.value = true
        scanProgress.value = ScanProgressState(active = true, phase = "Préparation du scan")
        val report = repository.scanSource(id, ::updateScanProgress)
        scanning.value = false
        scanProgress.value = ScanProgressState()
        enqueueNewGames(before)
        mutableScanReports.value = listOf(report)
        events.emit(UiEvent.Message("${report.found} jeux trouvés, ${report.added} ajoutés"))
    }

    fun scanAll(silent: Boolean = false) = viewModelScope.launch {
        if (scanning.value) return@launch
        val before = repository.getGameIds()
        scanning.value = true
        scanProgress.value = ScanProgressState(active = true, phase = "Préparation du scan")
        val reports = runCatching { repository.scanAll(::updateScanProgress) }.getOrElse {
            if (!silent) events.emit(UiEvent.Message(it.message ?: "Le scan a échoué"))
            emptyList()
        }
        scanning.value = false
        scanProgress.value = ScanProgressState()
        enqueueNewGames(before)
        if (!silent) {
            mutableScanReports.value = reports
            events.emit(UiEvent.Message("${reports.sumOf { it.found }} jeux détectés"))
        }
    }

    fun showLatestScanReport(sourceId: String) = viewModelScope.launch {
        val report = repository.latestScanReport(sourceId)
        if (report == null) events.emit(UiEvent.Message("Aucun rapport disponible pour cette source"))
        else mutableScanReports.value = listOf(report)
    }

    fun dismissScanReports() { mutableScanReports.value = emptyList() }

    fun importTags(uri: Uri) = viewModelScope.launch {
        val count = runCatching { repository.importTags(uri) }.getOrElse { 0 }
        events.emit(UiEvent.Message("$count tags importés"))
    }

    fun toggleFavorite(id: String) = viewModelScope.launch { repository.toggleFavorite(id) }
    fun createFolder(name: String, parentId: String? = null) = viewModelScope.launch { repository.createFolder(name, parentId) }
    fun renameFolder(folder: LibraryFolderEntity, name: String) = viewModelScope.launch { repository.renameFolder(folder, name) }
    fun deleteFolder(folder: LibraryFolderEntity) = viewModelScope.launch { repository.deleteFolder(folder) }
    fun setGameFolder(id: String, folderId: String?) = viewModelScope.launch {
        repository.setGameFolder(id, folderId)
        events.emit(UiEvent.Message("Dossier du jeu mis à jour"))
    }
    fun setGamesFolder(ids: Set<String>, folderId: String?) = viewModelScope.launch {
        repository.setGamesFolder(ids, folderId)
        events.emit(UiEvent.Message("${ids.size} jeu(x) classé(s)"))
    }
    fun setGamesFavorite(ids: Set<String>, favorite: Boolean = true) = viewModelScope.launch {
        repository.setGamesFavorite(ids, favorite)
        events.emit(UiEvent.Message("${ids.size} jeu(x) mis à jour"))
    }
    fun addTagsToGames(ids: Set<String>, tagIds: Set<String>) = viewModelScope.launch {
        repository.addTagsToGames(ids, tagIds)
        events.emit(UiEvent.Message("Tags ajoutés à ${ids.size} jeu(x)"))
    }
    fun deleteGames(ids: Set<String>) = viewModelScope.launch {
        ids.forEach { repository.deleteGame(it, deleteAssociatedFiles = false) }
        events.emit(UiEvent.Message("${ids.size} jeu(x) retiré(s) de la bibliothèque"))
    }
    fun saveCollection(id: String?, name: String, matchMode: String, rules: List<CollectionRuleDraft>) = viewModelScope.launch {
        runCatching { repository.createOrUpdateCollection(id, name, matchMode, rules) }
            .onSuccess { events.emit(UiEvent.Message("Collection intelligente enregistrée")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Collection invalide")) }
    }
    fun deleteCollection(id: String) = viewModelScope.launch {
        repository.deleteCollection(id)
        events.emit(UiEvent.Message("Collection supprimée"))
    }
    fun ignoreDuplicateGroup(key: String) = viewModelScope.launch {
        repository.ignoreDuplicateGroup(key)
        events.emit(UiEvent.Message("Ce groupe de doublons sera désormais ignoré"))
    }
    fun previewDuplicateMerge(primaryId: String, secondaryId: String) = viewModelScope.launch {
        runCatching { repository.previewDuplicateMerge(primaryId, secondaryId) }
            .onSuccess { mutableDuplicatePreview.value = it }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Comparaison impossible")) }
    }
    fun clearDuplicatePreview() { mutableDuplicatePreview.value = null }
    fun mergeDuplicate(
        primaryId: String, secondaryId: String, migrateSaves: Boolean,
        strategy: SaveConflictStrategy, deleteSecondaryFiles: Boolean, onDone: () -> Unit
    ) = viewModelScope.launch {
        runCatching { repository.mergeDuplicate(primaryId, secondaryId, migrateSaves, strategy, deleteSecondaryFiles) }
            .onSuccess { result ->
                mutableDuplicatePreview.value = null
                onDone()
                events.emit(UiEvent.Message("Doublon fusionné • ${result.savesConsidered} sauvegarde(s) analysée(s)"))
            }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Fusion impossible")) }
    }
    fun deleteGame(id: String, deleteFiles: Boolean, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        runCatching { repository.deleteGame(id, deleteFiles) }
            .onSuccess {
                onDeleted()
                events.emit(UiEvent.Message(if (deleteFiles) "Jeu et fichiers supprimés" else "Jeu retiré de la bibliothèque"))
            }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Suppression impossible")) }
    }
    fun restoreDeletedGame(id: String) = viewModelScope.launch {
        repository.restoreDeletedGame(id)
        events.emit(UiEvent.Message("Jeu réautorisé. Relancez le scan de sa source."))
    }
    fun openGameSaveFolder(id: String) = viewModelScope.launch {
        val uri = repository.findSaveFolderUri(id)
        if (uri == null) events.emit(UiEvent.Message("Aucun dossier de sauvegarde détecté"))
        else openFolderRequests.emit(uri)
    }
    fun game(id: String) = repository.game(id)
    fun gameTags(id: String) = repository.gameTags(id)
    fun launchProfile(id: String) = repository.launchProfile(id)
    fun verifyGamePresence(id: String) = viewModelScope.launch { repository.verifyGamePresence(id) }
    fun toggleGameTag(gameId: String, tagId: String, selected: Boolean) = viewModelScope.launch {
        repository.toggleGameTag(gameId, tagId, selected)
    }
    fun setGameTags(gameId: String, tagIds: Set<String>) = viewModelScope.launch {
        repository.setGameTags(gameId, tagIds)
        events.emit(UiEvent.Message("Tags enregistrés"))
    }
    fun importTextTags(gameId: String, text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        val count = repository.importTextTags(gameId, text)
        events.emit(UiEvent.Message("$count tag(s) associé(s) au jeu"))
    }
    fun createTag(name: String, categoryName: String?) = viewModelScope.launch {
        if (repository.createTag(name, categoryName)) events.emit(UiEvent.Message("Tag créé"))
    }
    fun editTag(tag: TagEntity, name: String, categoryName: String?) = viewModelScope.launch {
        repository.editTag(tag, name, categoryName)
        events.emit(UiEvent.Message("Tag modifié"))
    }
    fun deleteTags(ids: Set<String>) = viewModelScope.launch {
        repository.deleteTags(ids)
        events.emit(UiEvent.Message("${ids.size} tag(s) supprimé(s)"))
    }
    fun moveTagsToCategory(ids: Set<String>, category: String?) = viewModelScope.launch {
        repository.moveTagsToCategory(ids, category)
        events.emit(UiEvent.Message("Catégorie appliquée"))
    }
    fun createTagCategory(name: String) = viewModelScope.launch { repository.createTagCategory(name) }
    fun renameTagCategory(category: TagCategoryEntity, name: String) = viewModelScope.launch {
        repository.renameTagCategory(category, name)
    }
    fun deleteTagCategory(category: TagCategoryEntity) = viewModelScope.launch {
        repository.deleteTagCategory(category)
    }
    fun moveTagCategory(id: String, direction: Int) = viewModelScope.launch {
        repository.moveTagCategory(id, direction)
    }
    fun updateGame(gameId: String, edits: GameEdits, textTags: String = "") = viewModelScope.launch {
        repository.updateGame(gameId, edits)
        if (textTags.isNotBlank()) repository.importTextTags(gameId, textTags)
        events.emit(UiEvent.Message("Jeu mis à jour"))
    }
    fun configureScannedGame(gameId: String, edits: GameEdits, selectedTags: Set<String>, textTags: String) = viewModelScope.launch {
        repository.updateGame(gameId, edits)
        repository.setGameTags(gameId, selectedTags)
        if (textTags.isNotBlank()) repository.importTextTags(gameId, textTags)
        completeGameSetup(gameId)
        events.emit(UiEvent.Message("Jeu configuré"))
    }
    fun setCover(gameId: String, uri: Uri) = viewModelScope.launch {
        runCatching { repository.setCover(gameId, uri) }
            .onSuccess {
                mutableCoverSearch.value = CoverSearchState(configured = app.container.covers.configured)
                events.emit(UiEvent.Message("Jaquette enregistrée"))
            }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Impossible d'enregistrer la jaquette")) }
    }
    fun removeCover(gameId: String) = viewModelScope.launch {
        repository.removeCover(gameId)
        events.emit(UiEvent.Message("Jaquette supprimée"))
    }

    fun searchCovers(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        mutableCoverSearch.value = CoverSearchState(
            gameId = gameId, loading = true, configured = app.container.covers.configured,
            browserUrl = app.container.covers.searchUrl(game)
        )
        runCatching { app.container.covers.search(game) }
            .onSuccess { results ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    loading = false,
                    results = results,
                    error = if (results.isEmpty()) "Aucune image trouvée." else null
                )
            }
            .onFailure { error ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    loading = false, error = error.message ?: "La recherche Google a échoué."
                )
            }
    }

    fun prepareCoverPicker(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        mutableCoverSearch.value = CoverSearchState(
            gameId = gameId,
            configured = app.container.covers.configured,
            browserUrl = app.container.covers.searchUrl(game)
        )
    }

    fun chooseRemoteCover(gameId: String, candidate: CoverCandidate) = viewModelScope.launch {
        mutableCoverSearch.value = mutableCoverSearch.value.copy(downloading = true, error = null)
        runCatching { app.container.covers.download(candidate) }
            .onSuccess { uri ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(downloading = false)
                cropRequests.emit(CropRequest(gameId, uri))
            }
            .onFailure { error ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    downloading = false, error = error.message ?: "Impossible de télécharger cette image."
                )
            }
    }

    fun clearCoverSearch() {
        mutableCoverSearch.value = CoverSearchState(configured = app.container.covers.configured)
    }
    fun fetchF95Metadata(gameId: String, url: String) = viewModelScope.launch {
        mutableF95Import.value = mutableF95Import.value.copy(gameId = gameId, loading = true, metadata = null, error = null)
        val result = runCatching { app.container.f95Zone.fetch(url) }
        result.onSuccess { metadata ->
            repository.setF95Url(gameId, metadata.sourceUrl)
            mutableF95Import.value = mutableF95Import.value.copy(loading = false, metadata = metadata, error = null)
        }.onFailure { error ->
            mutableF95Import.value = mutableF95Import.value.copy(loading = false, error = error.message ?: "Import F95Zone impossible.")
        }
    }
    fun prepareF95Search(gameId: String, title: String) {
        mutableF95Import.value = mutableF95Import.value.copy(
            gameId = gameId,
            browserUrl = app.container.f95Zone.googleSearchUrl(title)
        )
    }
    fun applyF95Tags(gameId: String, selectedTags: Set<String>) = viewModelScope.launch {
        val imported = repository.importF95Tags(gameId, selectedTags)
        events.emit(UiEvent.Message("$imported tag(s) F95Zone associé(s) au jeu"))
        mutableF95Import.value = F95ImportState()
    }
    fun chooseF95Cover(gameId: String, candidate: CoverCandidate) = chooseRemoteCover(gameId, candidate)
    fun clearF95Import() { mutableF95Import.value = F95ImportState() }
    fun completeGameSetup(gameId: String) {
        setupQueueIds.value = setupQueueIds.value.filterNot { it == gameId }
    }
    fun dismissGameSetup() {
        setupQueueIds.value = emptyList()
    }

    private suspend fun enqueueNewGames(before: Set<String>) {
        val added = repository.getGameIds() - before
        if (added.isNotEmpty()) setupQueueIds.value = (setupQueueIds.value + added).distinct()
    }
    fun completeOnboarding() = viewModelScope.launch { settingsRepository.completeOnboarding() }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setTheme(mode) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(value) }
    fun setScanOnLaunch(value: Boolean) = viewModelScope.launch { settingsRepository.setScanOnLaunch(value) }
    fun setViewMode(mode: LibraryViewMode) = viewModelScope.launch { settingsRepository.setViewMode(mode) }
    fun setCoverSize(size: CoverSize) = viewModelScope.launch { settingsRepository.setCoverSize(size) }
    fun setGridColumns(columns: Int) = viewModelScope.launch { settingsRepository.setGridColumns(columns) }
    fun configureBackupFolder(uri: Uri) = viewModelScope.launch {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsRepository.setBackupFolder(uri.toString())
            repository.createBackup(uri)
        }.onSuccess { events.emit(UiEvent.Message("Sauvegarde créée : $it")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Sauvegarde impossible")) }
    }
    fun createBackup() = viewModelScope.launch {
        val uri = settingsRepository.settings.first().backupFolderUri?.let(Uri::parse)
        if (uri == null) events.emit(UiEvent.Message("Choisissez d’abord un dossier de sauvegarde"))
        else runCatching { repository.createBackup(uri) }
            .onSuccess { events.emit(UiEvent.Message("Sauvegarde créée : $it")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Sauvegarde impossible")) }
    }
    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        runCatching { repository.restoreBackup(uri) }
            .onSuccess { events.emit(UiEvent.Message("Sauvegarde restaurée")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Restauration impossible")) }
    }

    fun diagnoseGame(id: String) = viewModelScope.launch {
        val game = repository.getGame(id) ?: return@launch
        val profile = repository.getLaunchProfile(id)
        val report = compatibilityDiagnostic.inspect(getApplication(), game, profile)
        mutableCompatibility.value = mutableCompatibility.value + (id to report)
    }

    fun saveLaunchProfile(profile: LaunchProfileEntity) = viewModelScope.launch {
        repository.saveLaunchProfile(profile)
        diagnoseGame(profile.gameId)
        events.emit(UiEvent.Message("Profil de lancement enregistré"))
    }

    fun resetLaunchProfile(gameId: String) = viewModelScope.launch {
        repository.resetLaunchProfile(gameId)
        diagnoseGame(gameId)
        events.emit(UiEvent.Message("Détection automatique restaurée"))
    }

    fun testLaunchProfile(profile: LaunchProfileEntity) = viewModelScope.launch {
        repository.saveLaunchProfile(profile)
        val game = repository.getGame(profile.gameId) ?: return@launch
        val diagnostic = compatibilityDiagnostic.inspect(getApplication(), game, profile)
        mutableCompatibility.value = mutableCompatibility.value + (game.id to diagnostic)
        if (!diagnostic.canLaunch) {
            events.emit(UiEvent.Message(diagnostic.summary))
            return@launch
        }
        when (val result = app.container.launcher.launch(getApplication(), game, profile)) {
            LaunchResult.Success -> events.emit(UiEvent.Message("Test envoyé au lanceur configuré"))
            is LaunchResult.Failure -> events.emit(UiEvent.Message(result.message))
        }
    }

    private fun updateScanProgress(update: ScanProgressUpdate) {
        scanProgress.value = ScanProgressState(
            active = true,
            sourceName = update.sourceName,
            phase = update.phase,
            currentPath = update.currentPath,
            depth = update.depth,
            visitedFolders = update.visitedFolders,
            foundGames = update.foundGames
        )
    }

    fun launchGame(id: String) = viewModelScope.launch {
        val game = repository.getGame(id) ?: return@launch
        val profile = repository.getLaunchProfile(id)
        val diagnostic = compatibilityDiagnostic.inspect(getApplication(), game, profile)
        mutableCompatibility.value = mutableCompatibility.value + (id to diagnostic)
        if (!diagnostic.canLaunch) {
            events.emit(UiEvent.Message(diagnostic.summary))
            return@launch
        }
        when (val result = app.container.launcher.launch(getApplication(), game, profile)) {
            LaunchResult.Success -> {
                repository.recordLaunch(id)
                repository.startPlaySession(id)
            }
            is LaunchResult.Failure -> events.emit(UiEvent.Message(result.message))
        }
    }

    fun finishActivePlaySession() = viewModelScope.launch { repository.finishActivePlaySession() }

    private fun descendantFolderIds(rootId: String, folders: List<LibraryFolderEntity>): Set<String> {
        val result = mutableSetOf(rootId)
        var changed: Boolean
        do {
            changed = result.addAll(folders.filter { it.parentId in result }.map { it.id })
        } while (changed)
        return result
    }
}
