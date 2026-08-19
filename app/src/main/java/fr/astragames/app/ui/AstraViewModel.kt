package fr.astragames.app.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.webkit.CookieManager
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
import fr.astragames.app.core.metadata.F95Session
import fr.astragames.app.core.metadata.F95ZoneMetadata
import fr.astragames.app.core.search.TagMatcher
import fr.astragames.app.core.search.DuplicateDetector
import fr.astragames.app.core.search.DuplicateDetector.DuplicateGroup
import fr.astragames.app.core.collections.SmartCollectionEvaluator
import fr.astragames.app.data.local.AuditEventEntity
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
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.CoverBlurMode
import fr.astragames.app.settings.SearchEngine
import fr.astragames.app.worker.GameUpdatesWorker
import kotlinx.coroutines.flow.Flow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private const val VNDB_REQUEST_INTERVAL_MS = 1_550L

data class LibraryFilters(
    val query: String = "",
    val sourceId: String? = null,
    val folderId: String? = null,
    val collectionId: String? = null,
    val systemFolderId: String? = null,
    val engine: GameEngine? = null,
    val tagIds: Set<String> = emptySet(),
    val excludedTagIds: Set<String> = emptySet(),
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

enum class LibrarySort { TITLE, RECENTLY_ADDED, LAST_PLAYED, MOST_PLAYED, NEVER_PLAYED, UPDATE_AVAILABLE }

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
    val settingsLoaded: Boolean = false,
    val filters: LibraryFilters = LibraryFilters(),
    val scanning: Boolean = false,
    val scanProgress: ScanProgressState = ScanProgressState(),
    val metadataRefresh: MetadataRefreshState = MetadataRefreshState(),
    val joiPlayInstalled: Boolean = false,
    val setupQueue: List<GameEntity> = emptyList()
)

data class MetadataRefreshState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0
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
    val browserUrl: String? = null,
    val searchEngine: SearchEngine = SearchEngine.YANDEX
)

data class CropRequest(val gameId: String, val source: Uri)

data class F95ImportState(
    val gameId: String? = null,
    val loading: Boolean = false,
    val metadata: F95ZoneMetadata? = null,
    val error: String? = null,
    val browserUrl: String? = null,
    val searchEngine: SearchEngine = SearchEngine.YANDEX
)

data class GameUpdateInfo(
    val game: GameEntity,
    val currentVersion: String?,
    val latestVersion: String
)

data class TagMergeSuggestion(
    val first: TagEntity,
    val second: TagEntity,
    val similarity: Float
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AstraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AstraApplication
    private val repository = app.container.repository
    private val settingsRepository = app.container.settings
    private val saveManager = app.container.saveManager
    private val modsManager = app.container.modsManager
    private val dao = app.container.dao
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
    val notificationPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pickSaveFolderRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val pickModsRootRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pickModZipRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val biometricUnlockRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val locked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = locked
    private val playHistory = dao.observePlayHistory(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableSaves = MutableStateFlow<List<fr.astragames.app.data.saves.GameSave>>(emptyList())
    val gameSaves: StateFlow<List<fr.astragames.app.data.saves.GameSave>> = mutableSaves
    private val mutableSaveEntries = MutableStateFlow<List<fr.astragames.app.data.saves.SaveEntry>>(emptyList())
    val saveEntries: StateFlow<List<fr.astragames.app.data.saves.SaveEntry>> = mutableSaveEntries
    private val mutableMods = MutableStateFlow<List<fr.astragames.app.data.mods.ModCatalogItem>>(emptyList())
    val modsCatalog: StateFlow<List<fr.astragames.app.data.mods.ModCatalogItem>> = mutableMods
    private val pendingSaveFolderGameId = MutableStateFlow<String?>(null)
    private var backgrounded = false
    private var ignoreNextRelock = false
    private val runtimeManager = JoiPlayRuntimeManager()
    private val metadataRefresh = MutableStateFlow(MetadataRefreshState())
    private val metadataMutex = Mutex()
    private val scanMutex = Mutex()
    private var scanJob: Job? = null
    private var metadataJob: Job? = null
    private val mutableGameUpdates = MutableStateFlow<List<GameUpdateInfo>>(emptyList())
    val gameUpdates: StateFlow<List<GameUpdateInfo>> = mutableGameUpdates
    private val mutableUpdatesChecking = MutableStateFlow(false)
    val updatesChecking: StateFlow<Boolean> = mutableUpdatesChecking
    private val mutableLatestVersions = MutableStateFlow<Map<String, String>>(emptyMap())
    val latestGameVersions: StateFlow<Map<String, String>> = mutableLatestVersions
    val updateBadgeCount: StateFlow<Int> = mutableGameUpdates.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    private val mutableTagMerges = MutableStateFlow<List<TagMergeSuggestion>>(emptyList())
    val tagMerges: StateFlow<List<TagMergeSuggestion>> = mutableTagMerges
    private val ignoredTagMerges = MutableStateFlow<Set<String>>(emptySet())

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

    private data class BackgroundState(
        val scanning: Boolean,
        val progress: ScanProgressState,
        val metadata: MetadataRefreshState
    )
    private val backgroundState = combine(scanning, scanProgress, metadataRefresh) { active, progress, metadata ->
        BackgroundState(active, progress, metadata)
    }

    private data class RuntimeState(
        val runtimes: List<JoiPlayRuntimeInfo> = emptyList(),
        val joiPlayInstalled: Boolean = false
    )

    private val runtimeRefresh = MutableStateFlow(0)
    private val runtimeState = combine(
        repository.games
            .map { games -> games.mapNotNull { runCatching { GameEngine.valueOf(it.engine) }.getOrNull() }.toSet() }
            .distinctUntilChanged(),
        settingsRepository.settings.map { it.joiPlayCatalogJson }.distinctUntilChanged(),
        runtimeRefresh
    ) { libraryEngines, catalogJson, _ ->
        withContext(Dispatchers.IO) {
            RuntimeState(
                runtimes = runtimeManager.inspect(
                    application,
                    libraryEngines,
                    app.container.joiPlayCatalog.parse(catalogJson)
                ),
                joiPlayInstalled = app.container.launcher.isInstalled(application)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RuntimeState())

    @Suppress("UNCHECKED_CAST")
    private val baseUiState: StateFlow<AstraUiState> = combine(
        coreData, settingsRepository.settings, filters, backgroundState, setupQueueIds, mutableLatestVersions
    ) { values ->
        val data = values[0] as CoreData
        val settings = values[1] as AstraSettings
        val filter = values[2] as LibraryFilters
        val background = values[3] as BackgroundState
        val setupIds = values[4] as List<String>
        val latestVersions = values[5] as Map<String, String>
        val refsByGame = data.refs.groupBy { it.gameId }.mapValues { (_, refs) -> refs.map { it.tagId }.toSet() }
        val playStatsByGame = data.playStats.associateBy { it.gameId }
        val selectedFolderIds = filter.folderId?.let { rootId -> descendantFolderIds(rootId, data.folders) }
        val selectedCollectionGameIds = filter.collectionId?.let { collectionId ->
            val collection = data.collections.firstOrNull { it.id == collectionId } ?: return@let emptySet()
            val rules = data.collectionRules.filter { it.collectionId == collectionId }
            data.games.filter { game ->
                SmartCollectionEvaluator.matches(collection, rules, game, data.refs, data.folders, playStatsByGame[game.id])
            }.mapTo(mutableSetOf()) { it.id }
        }
        val systemFolders = buildSystemFolderFilters(data.games, data.sources)
        val selectedSystemGameIds = filter.systemFolderId
            ?.let { id -> systemFolders.firstOrNull { it.id == id }?.gameIds }
            .orEmpty()
        val filtered = data.candidates.filter { game ->
            val gameTags = refsByGame[game.id].orEmpty()
            val tagsMatch = TagMatcher.matches(filter.tagIds, gameTags, filter.tagMode)
            val excludedTagsMatch = gameTags.none { it in filter.excludedTagIds }
            (filter.sourceId == null || game.sourceId == filter.sourceId) &&
                (selectedFolderIds == null || game.libraryFolderId in selectedFolderIds) &&
                (selectedCollectionGameIds == null || game.id in selectedCollectionGameIds) &&
                (filter.systemFolderId == null || game.id in selectedSystemGameIds) &&
                (filter.engine == null || game.engine == filter.engine.name) &&
                (!filter.favoritesOnly || game.favorite) &&
                (!filter.missingOnly || game.missing) && tagsMatch && excludedTagsMatch
        }.let { games ->
            when (filter.sort) {
                LibrarySort.TITLE -> games.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
                LibrarySort.RECENTLY_ADDED -> games.sortedByDescending { it.dateAdded }
                LibrarySort.LAST_PLAYED -> games.sortedByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }
                LibrarySort.MOST_PLAYED -> games.sortedByDescending { it.playCount }
                LibrarySort.NEVER_PLAYED -> games.sortedBy { it.playCount > 0 }
                LibrarySort.UPDATE_AVAILABLE -> games.sortedByDescending { game ->
                    val latest = latestVersions[game.id]
                    if (latest != null && latest != game.version) 1 else 0
                }
            }
        }
        val customGames = data.collections.associate { collection ->
            val rules = data.collectionRules.filter { it.collectionId == collection.id }
            collection.id to data.games.filter { game ->
                SmartCollectionEvaluator.matches(collection, rules, game, data.refs, data.folders, playStatsByGame[game.id])
            }
        }
        val duplicateGroups = DuplicateDetector.groups(data.games).filterNot { it.key in data.ignoredDuplicateKeys }
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
            settings = settings,
            settingsLoaded = true,
            filters = filter,
            scanning = background.scanning,
            scanProgress = background.progress,
            metadataRefresh = background.metadata,
            setupQueue = setupIds.mapNotNull { id -> data.games.firstOrNull { it.id == id } }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AstraUiState())

    val uiState: StateFlow<AstraUiState> = combine(baseUiState, runtimeState) { state, runtime ->
        state.copy(runtimes = runtime.runtimes, joiPlayInstalled = runtime.joiPlayInstalled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AstraUiState())

    init {
        viewModelScope.launch {
            repository.recoverInterruptedScans()
            val settings = settingsRepository.settings.first()
            val latestParsed = parseLatestVersions(settings.f95LatestVersions)
            mutableLatestVersions.value = latestParsed
            val libraryGames = repository.games.first()
            mutableGameUpdates.value = libraryGames.mapNotNull { game ->
                val latest = latestParsed[game.id] ?: return@mapNotNull null
                if (latest != null && game.version != null && latest != game.version)
                    GameUpdateInfo(game, game.version, latest)
                else null
            }
            GameUpdatesWorker.schedule(app, settings.updateCheckInterval)
            GameUpdatesWorker.schedule(app, settings.updateCheckInterval)
            settings.f95SessionXfUser?.let { xfUser ->
                settings.f95SessionXfSession?.let { xfSession ->
                    app.container.f95Zone.setSession(F95Session(settings.f95SessionUser, xfUser, xfSession))
                }
            }
            if (settings.scanOnLaunch) scanAll(silent = true)
            if (settings.lockBiometricEnabled || settings.lockPinEnabled) locked.value = true
        }
    }

    fun updateQuery(value: String) { filters.value = filters.value.copy(query = value) }
    fun filterSource(id: String?) { filters.value = filters.value.copy(sourceId = id, systemFolderId = null) }
    fun filterFolder(id: String?) { filters.value = filters.value.copy(folderId = id, collectionId = null) }
    fun filterCollection(id: String?) { filters.value = filters.value.copy(collectionId = id, folderId = null) }
    fun filterSystemFolder(id: String?) { filters.value = filters.value.copy(systemFolderId = id, sourceId = null) }
    fun setSort(sort: LibrarySort) { filters.value = filters.value.copy(sort = sort) }
    fun filterEngine(engine: GameEngine?) { filters.value = filters.value.copy(engine = engine) }
    fun toggleFavoriteFilter() { filters.value = filters.value.copy(favoritesOnly = !filters.value.favoritesOnly) }
    fun toggleMissingFilter() { filters.value = filters.value.copy(missingOnly = !filters.value.missingOnly) }
    fun toggleTagFilter(id: String) {
        val next = filters.value.tagIds.toMutableSet().apply { if (!add(id)) remove(id) }
        filters.value = filters.value.copy(tagIds = next)
    }
    fun cycleTagFilter(id: String) {
        val filter = filters.value
        filters.value = when {
            id in filter.tagIds -> filter.copy(tagIds = filter.tagIds - id, excludedTagIds = filter.excludedTagIds + id)
            id in filter.excludedTagIds -> filter.copy(excludedTagIds = filter.excludedTagIds - id)
            else -> filter.copy(tagIds = filter.tagIds + id)
        }
    }
    fun setTagFilters(ids: Set<String>) { filters.value = filters.value.copy(tagIds = ids) }
    fun setExcludedTagFilters(ids: Set<String>) { filters.value = filters.value.copy(excludedTagIds = ids) }
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
                // Keep only the first folder below the selected system source.
                // Deeper folders remain included in that parent and are not listed separately.
                val parentPath = relative.substringBeforeLast('/', missingDelimiterValue = "")
                val parentFolder = parentPath.substringBefore('/', missingDelimiterValue = parentPath)
                val key = "${source.id}|$parentFolder"
                val decoded = Uri.decode(parentFolder)
                val label = if (decoded.isBlank()) source.displayName else "${source.displayName} / $decoded"
                folders.getOrPut(key) {
                    MutableFolder(source.id, label, if (parentFolder.isBlank()) 0 else 1)
                }.games += game.id
            }
        }
        return folders.map { (id, folder) ->
            SystemFolderFilter(id, folder.sourceId, folder.label, folder.depth, folder.games)
        }.sortedWith(compareBy<SystemFolderFilter> { sources.indexOfFirst { source -> source.id == it.sourceId } }.thenBy { it.label.lowercase(java.util.Locale.ROOT) })
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
    fun scanSource(id: String) { scanJob?.cancel(); scanJob = viewModelScope.launch {
        if (!scanMutex.tryLock()) return@launch
        scanning.value = true
        scanProgress.value = ScanProgressState(active = true, phase = "Préparation du scan")
        try {
            val before = repository.getGameIds()
            val report = repository.scanSource(id, ::updateScanProgress)
            enqueueNewGames(before)
            mutableScanReports.value = listOf(report)
            events.emit(UiEvent.Message("${report.found} jeux trouvés, ${report.added} ajoutés"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            events.emit(UiEvent.Message(error.message ?: "Le scan a échoué"))
        } finally {
            scanning.value = false
            scanProgress.value = ScanProgressState()
            scanMutex.unlock()
        }
    }
    }

    fun scanAll(silent: Boolean = false) { scanJob?.cancel(); scanJob = viewModelScope.launch {
        if (!scanMutex.tryLock()) return@launch
        scanning.value = true
        scanProgress.value = ScanProgressState(active = true, phase = "Préparation du scan")
        try {
            val before = repository.getGameIds()
            val reports = repository.scanAll(::updateScanProgress)
            enqueueNewGames(before)
            if (!silent) {
                mutableScanReports.value = reports
                events.emit(UiEvent.Message("${reports.sumOf { it.found }} jeux détectés"))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!silent) events.emit(UiEvent.Message(error.message ?: "Le scan a échoué"))
        } finally {
            scanning.value = false
            scanProgress.value = ScanProgressState()
            scanMutex.unlock()
        }
    }
    }

    fun showLatestScanReport(sourceId: String) = viewModelScope.launch {
        val report = repository.latestScanReport(sourceId)
        if (report == null) events.emit(UiEvent.Message("Aucun rapport disponible pour cette source"))
        else mutableScanReports.value = listOf(report)
    }

    fun dismissScanReports() { mutableScanReports.value = emptyList() }

    fun importTags(uri: Uri) = viewModelScope.launch {
        runCatching { repository.importTags(uri) }
            .onSuccess { events.emit(UiEvent.Message("$it tags importés")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Import des tags impossible")) }
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
        val searchEngine = settingsRepository.settings.first().searchEngine
        mutableCoverSearch.value = CoverSearchState(
            gameId = gameId, loading = true, configured = app.container.covers.configured,
            browserUrl = app.container.covers.searchUrl(game, searchEngine),
            searchEngine = searchEngine
        )
        runCatching { app.container.covers.search(game, searchEngine) }
            .onSuccess { results ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    loading = false,
                    results = results,
                    error = if (results.isEmpty()) "Aucune image trouvée." else null
                )
            }
            .onFailure { error ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    loading = false, error = error.message ?: "La recherche d’images a échoué."
                )
            }
    }

    fun prepareCoverPicker(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        val searchEngine = settingsRepository.settings.first().searchEngine
        mutableCoverSearch.value = CoverSearchState(
            gameId = gameId,
            loading = true,
            configured = app.container.covers.configured,
            browserUrl = app.container.covers.searchUrl(game, searchEngine),
            searchEngine = searchEngine
        )
        runCatching { app.container.covers.search(game, searchEngine) }
            .onSuccess { results ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    loading = false,
                    results = results,
                    error = if (results.isEmpty()) "Aucune image trouvée automatiquement." else null
                )
            }
            .onFailure { error ->
                mutableCoverSearch.value = mutableCoverSearch.value.copy(
                    loading = false,
                    error = error.message ?: "La recherche d’images a échoué."
                )
            }
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
            repository.applyAutomaticMetadata(
                gameId = gameId,
                originalTitle = null,
                developer = null,
                description = null,
                f95Url = metadata.sourceUrl,
                version = metadata.version,
                language = metadata.language
            )
            mutableF95Import.value = mutableF95Import.value.copy(loading = false, metadata = metadata, error = null)
        }.onFailure { error ->
            mutableF95Import.value = mutableF95Import.value.copy(loading = false, error = error.message ?: "Import F95Zone impossible.")
        }
    }
    fun prepareF95Search(gameId: String, title: String) = viewModelScope.launch {
        val searchEngine = settingsRepository.settings.first().searchEngine
        mutableF95Import.value = mutableF95Import.value.copy(
            gameId = gameId,
            browserUrl = app.container.f95Zone.searchUrl(title, searchEngine),
            searchEngine = searchEngine
        )
    }
    fun applyF95Tags(gameId: String, selectedTags: Set<String>) = viewModelScope.launch {
        val imported = repository.importF95Tags(gameId, selectedTags)
        events.emit(UiEvent.Message("$imported tag(s) F95Zone associé(s) au jeu"))
        mutableF95Import.value = F95ImportState()
    }
    fun chooseF95Cover(gameId: String, candidate: CoverCandidate) = chooseRemoteCover(gameId, candidate)
    fun clearF95Import() { mutableF95Import.value = F95ImportState() }

    fun connectF95Session(cookies: String, username: String?) = viewModelScope.launch {
        val xfUser = cookieValue(cookies, "xf_user")
        val xfSession = cookieValue(cookies, "xf_session")
        if (xfUser == null || xfSession == null) {
            events.emit(UiEvent.Message("Aucune session F95Zone détectée. Connectez-vous d’abord sur le site."))
            return@launch
        }
        settingsRepository.setF95Session(username, xfUser, xfSession)
        app.container.f95Zone.setSession(F95Session(username, xfUser, xfSession))
        events.emit(UiEvent.Message("Session F95Zone enregistrée"))
    }

    fun disconnectF95() = viewModelScope.launch {
        settingsRepository.clearF95Session()
        app.container.f95Zone.setSession(null)
        CookieManager.getInstance().setCookie("https://f95zone.to", "xf_user=; Max-Age=0")
        CookieManager.getInstance().setCookie("https://f95zone.to", "xf_session=; Max-Age=0")
        CookieManager.getInstance().flush()
        events.emit(UiEvent.Message("Session F95Zone supprimée"))
    }

    private fun cookieValue(cookies: String, name: String): String? =
        cookies.split(';').map(String::trim).firstOrNull { it.startsWith("${name}=") }
            ?.substringAfter('=')?.takeIf(String::isNotBlank)
    fun completeGameSetup(gameId: String) {
        setupQueueIds.value = setupQueueIds.value.filterNot { it == gameId }
    }
    fun auditEvents(): Flow<List<AuditEventEntity>> = repository.auditEvents

    fun dismissGameSetup() {
        setupQueueIds.value = emptyList()
    }

    private suspend fun enqueueNewGames(before: Set<String>) {
        val added = repository.getGameIds() - before
        if (added.isNotEmpty()) {
            setupQueueIds.value = (setupQueueIds.value + added).distinct()
            viewModelScope.launch { enrichGames(added.toList(), visibleProgress = false) }
        }
    }

    fun refreshIncompleteMetadata() {
        if (metadataRefresh.value.running || metadataMutex.isLocked) return
        metadataJob?.cancel()
        val ids = uiState.value.games.filter {
            it.coverUri == null || it.description.isNullOrBlank() || it.developer.isNullOrBlank()
        }.map { it.id }
        metadataJob = viewModelScope.launch {
            if (ids.isEmpty()) {
                events.emit(UiEvent.Message("Toutes les métadonnées sont déjà présentes"))
                return@launch
            }
            val enriched = enrichGames(ids, visibleProgress = true)
            events.emit(
                UiEvent.Message(
                    if (enriched == 0) "Aucune métadonnée trouvée — vérifiez votre connexion"
                    else "Actualisation terminée pour ${ids.size} jeu(x)"
                )
            )
        }
    }

    private suspend fun enrichGames(ids: List<String>, visibleProgress: Boolean): Int = metadataMutex.withLock {
        if (visibleProgress) metadataRefresh.value = MetadataRefreshState(running = true, total = ids.size)
        var enriched = 0
        ids.forEachIndexed { index, id ->
            if (enrichGame(id)) enriched++
            if (visibleProgress) metadataRefresh.value = MetadataRefreshState(true, index + 1, ids.size)
            if (index < ids.lastIndex) delay(VNDB_REQUEST_INTERVAL_MS)
        }
        if (visibleProgress) metadataRefresh.value = MetadataRefreshState()
        enriched
    }

    private suspend fun enrichGame(gameId: String): Boolean {
        val game = repository.getGame(gameId) ?: return false
        val searchEngine = settingsRepository.settings.first().searchEngine
        val vndb = try {
            app.container.vndb.search(game.title)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("AstraMetadata", "VNDB introuvable pour « ${game.title} »", error)
            null
        }
        val f95Url = game.f95Url ?: vndb?.f95Url ?: try {
            app.container.f95Zone.findThread(game.title, searchEngine)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("AstraMetadata", "F95 introuvable pour « ${game.title} »", error)
            null
        }
        repository.applyAutomaticMetadata(
            gameId = game.id,
            originalTitle = vndb?.originalTitle,
            developer = vndb?.developers?.joinToString(" • "),
            description = vndb?.description,
            f95Url = f95Url
        )
        if (game.coverUri == null && vndb?.coverUrl != null) {
            val candidate = CoverCandidate(
                imageUrl = vndb.coverUrl,
                thumbnailUrl = vndb.coverUrl,
                source = "vndb.org",
                contextUrl = "https://vndb.org/${vndb.id}",
                matchedTitle = vndb.title,
                confidence = 1f
            )
            val downloaded = try {
                app.container.covers.download(candidate)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // La jaquette reste manquante ; l'enrichissement continue.
                null
            }
            if (downloaded != null) repository.setCover(game.id, downloaded)
        }
        return vndb != null || f95Url != null
    }
    fun completeOnboarding() = viewModelScope.launch { settingsRepository.completeOnboarding() }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { settingsRepository.setLanguage(language) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setTheme(mode) }
    fun setDynamicColor(value: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(value) }
    fun setScanOnLaunch(value: Boolean) = viewModelScope.launch { settingsRepository.setScanOnLaunch(value) }
    fun setViewMode(mode: LibraryViewMode) = viewModelScope.launch { settingsRepository.setViewMode(mode) }
    fun setCoverSize(size: CoverSize) = viewModelScope.launch { settingsRepository.setCoverSize(size) }
    fun setGridColumns(columns: Int) = viewModelScope.launch { settingsRepository.setGridColumns(columns) }
    fun setSearchEngine(engine: SearchEngine) = viewModelScope.launch { settingsRepository.setSearchEngine(engine) }
    fun setCoverBlurMode(mode: CoverBlurMode) = viewModelScope.launch { settingsRepository.setCoverBlurMode(mode) }
    fun setOpenSearchInExternalBrowser(value: Boolean) = viewModelScope.launch {
        settingsRepository.setOpenSearchInExternalBrowser(value)
    }
    fun refreshRuntimes() {
        runtimeRefresh.value += 1
    }

    fun cancelSyncs() {
        scanJob?.cancel()
        metadataJob?.cancel()
        scanning.value = false
        scanProgress.value = ScanProgressState()
        metadataRefresh.value = MetadataRefreshState()
        events.tryEmit(UiEvent.Message("Synchronisation arrêtée"))
    }

    fun checkGameUpdates() = viewModelScope.launch {
        if (mutableUpdatesChecking.value) return@launch
        val candidates = repository.games.first().filter { !it.f95Url.isNullOrBlank() }
        if (candidates.isEmpty()) {
            events.emit(UiEvent.Message("Aucun jeu lié à un thread F95Zone"))
            return@launch
        }
        mutableUpdatesChecking.value = true
        mutableGameUpdates.value = emptyList()
        val updates = mutableListOf<GameUpdateInfo>()
        val latestVersionByGame = mutableMapOf<String, String>()
        candidates.forEachIndexed { index, game ->
            val url = game.f95Url ?: return@forEachIndexed
            val latest = try {
                app.container.f95Zone.fetchVersion(url)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("AstraMetadata", "Version introuvable pour « ${game.title} »", error)
                null
            }
            if (latest != null) latestVersionByGame[game.id] = latest
            if (latest != null && game.version != null && latest != game.version) {
                updates += GameUpdateInfo(game, game.version, latest)
            }
            mutableGameUpdates.value = updates.toList()
            if (index < candidates.lastIndex) delay(VNDB_REQUEST_INTERVAL_MS)
        }
        mutableLatestVersions.value = latestVersionByGame
        settingsRepository.setF95LatestVersions(
            latestVersionByGame.entries.joinToString("|") { "${it.key}:${it.value}" }
        )
        mutableUpdatesChecking.value = false
        events.emit(
            UiEvent.Message(
                if (updates.isEmpty()) "Aucune mise à jour disponible"
                else "${updates.size} mise(s) à jour trouvée(s)"
            )
        )
    }

    fun refreshTagMerges() = viewModelScope.launch {
        var tags = repository.tags.first()
        var merged = true
        while (merged) {
            val match = withContext(Dispatchers.Default) { findPerfectTagMatch(tags) }
            if (match == null) {
                merged = false
            } else {
                repository.mergeTags(match.first.id, match.second.id)
                tags = repository.tags.first()
            }
        }
        val ignored = ignoredTagMerges.value
        mutableTagMerges.value = withContext(Dispatchers.Default) { findTagMergeSuggestions(tags) }
            .filterNot { mergeKey(it.first.id, it.second.id) in ignored }
    }

    fun ignoreTagMerge(first: TagEntity, second: TagEntity) = viewModelScope.launch {
        ignoredTagMerges.value = ignoredTagMerges.value + mergeKey(first.id, second.id)
        mutableTagMerges.value = mutableTagMerges.value.filterNot { mergeKey(it.first.id, it.second.id) == mergeKey(first.id, second.id) }
    }

    private fun mergeKey(firstId: String, secondId: String): String =
        listOf(firstId, secondId).sorted().joinToString("|")

    private fun parseLatestVersions(raw: String): Map<String, String> =
        raw.split("|").filter(String::isNotBlank).mapNotNull { entry ->
            val key = entry.substringBefore(":", "")
            val value = entry.substringAfter(":", "")
            if (key.isBlank() || value.isBlank()) null else key to value
        }.toMap()

    private fun findPerfectTagMatch(tags: List<TagEntity>): Pair<TagEntity, TagEntity>? {
        for (i in tags.indices) {
            for (j in i + 1 until tags.size) {
                if (tagSimilarity(tags[i].name, tags[j].name) >= 1f) return tags[i] to tags[j]
            }
        }
        return null
    }

    private fun findTagMergeSuggestions(tags: List<TagEntity>): List<TagMergeSuggestion> {
        val suggestions = mutableListOf<TagMergeSuggestion>()
        for (i in tags.indices) {
            for (j in i + 1 until tags.size) {
                val left = tags[i].name.trim().lowercase(java.util.Locale.ROOT)
                val right = tags[j].name.trim().lowercase(java.util.Locale.ROOT)
                if (left.isEmpty() || right.isEmpty()) continue
                val maxLength = maxOf(left.length, right.length)
                val lengthDiff = if (left.length > right.length) left.length - right.length else right.length - left.length
                if (lengthDiff.toFloat() / maxLength > .4f) continue
                val similarity = 1f - levenshtein(left, right).toFloat() / maxLength
                if (similarity >= .6f && similarity < 1f) {
                    suggestions += TagMergeSuggestion(tags[i], tags[j], similarity)
                }
            }
        }
        return suggestions
    }

    fun acknowledgeGameUpdate(gameId: String) = viewModelScope.launch {
        val update = mutableGameUpdates.value.firstOrNull { it.game.id == gameId } ?: return@launch
        mutableGameUpdates.value = mutableGameUpdates.value.filterNot { it.game.id == gameId }
        val latestMap = mutableLatestVersions.value - gameId
        mutableLatestVersions.value = latestMap
        settingsRepository.setF95LatestVersions(
            latestMap.entries.joinToString("|") { "${it.key}:${it.value}" }
        )
        val notified = settingsRepository.settings.first().f95NotifiedUpdates
        val key = "${gameId}:${update.latestVersion}"
        settingsRepository.setF95NotifiedUpdates(
            (notified.split("|").filter(String::isNotBlank) + key).distinct().joinToString("|")
        )
    }

    fun confirmGameUpdate(gameId: String, version: String) = viewModelScope.launch {
        repository.setGameVersion(gameId, version)
        acknowledgeGameUpdate(gameId)
    }

    fun setUpdateCheckInterval(interval: String) = viewModelScope.launch {
        settingsRepository.setUpdateCheckInterval(interval)
        GameUpdatesWorker.schedule(getApplication(), interval)
    }

    fun lockAppIfNeeded() {
        val settings = uiState.value.settings
        locked.value = settings.lockBiometricEnabled || settings.lockPinEnabled
    }

    fun unlockApp() { locked.value = false }

    fun requestBiometricUnlock() { biometricUnlockRequests.tryEmit(Unit) }

    fun verifyLockPin(pin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = settingsRepository.verifyPin(pin)
        if (ok) locked.value = false
        onResult(ok)
        if (!ok) events.emit(UiEvent.Message("Code incorrect"))
    }

    fun setBiometricLock(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBiometricLock(enabled)
        if (enabled) locked.value = true
    }

    fun setPinLock(pin: String?) = viewModelScope.launch {
        settingsRepository.setPinLock(pin)
        locked.value = pin != null || uiState.value.settings.lockBiometricEnabled
        events.emit(UiEvent.Message(if (pin == null) "Code desactive" else "Code enregistre"))
    }

    fun setHistoryEnabled(value: Boolean) = viewModelScope.launch { settingsRepository.setHistoryEnabled(value) }

    fun playHistory() = playHistory

    fun deletePlaySession(id: String) = viewModelScope.launch { dao.deletePlaySession(id) }

    fun clearPlayHistory() = viewModelScope.launch {
        dao.clearEndedPlaySessions()
        events.emit(UiEvent.Message("Historique efface"))
    }

    fun observeSaveLocations(gameId: String) = saveManager.observeLocations(gameId)

    fun observeSaveBackups(gameId: String) = saveManager.observeBackups(gameId)

    fun detectSaveLocations(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        runCatching { saveManager.detectLocations(game) }
            .onSuccess { events.emit(UiEvent.Message(it.size.toString() + " emplacement(s) de sauvegarde")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Detection impossible")) }
    }

    fun requestSaveFolder(gameId: String) {
        pendingSaveFolderGameId.value = gameId
        ignoreNextRelock = true
        pickSaveFolderRequests.tryEmit(gameId)
    }

    fun addSaveLocation(uri: Uri) = viewModelScope.launch {
        val gameId = pendingSaveFolderGameId.value ?: return@launch
        val game = repository.getGame(gameId) ?: return@launch
        runCatching { saveManager.addLocation(game, uri, uri.lastPathSegment.orEmpty()) }
            .onSuccess { events.emit(UiEvent.Message("Dossier de sauvegarde ajoute")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Ajout impossible")) }
    }

    fun removeSaveLocation(id: String) = viewModelScope.launch { saveManager.removeLocation(id) }

    fun loadSaves(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        runCatching { saveManager.listSaves(game) }
            .onSuccess { mutableSaves.value = it }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Lecture des sauvegardes impossible")) }
    }

    fun loadSaveEntries(gameId: String, save: fr.astragames.app.data.saves.GameSave) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        runCatching { saveManager.readSave(game, save).second }
            .onSuccess { mutableSaveEntries.value = it }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Lecture de la sauvegarde impossible")) }
    }

    fun applySaveEdits(gameId: String, save: fr.astragames.app.data.saves.GameSave, edits: List<fr.astragames.app.data.saves.SaveEdit>) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        runCatching { saveManager.writeSave(game, save, edits) }
            .onSuccess {
                events.emit(UiEvent.Message("Sauvegarde enregistree avec backup"))
                loadSaveEntries(gameId, save)
            }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Ecriture impossible")) }
    }

    fun restoreSaveBackup(backup: fr.astragames.app.data.local.SaveBackupEntity) = viewModelScope.launch {
        runCatching { saveManager.restoreBackup(backup) }
            .onSuccess { events.emit(UiEvent.Message("Backup restaure")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Restauration impossible")) }
    }

    fun deleteSaveBackup(backup: fr.astragames.app.data.local.SaveBackupEntity) = viewModelScope.launch {
        runCatching { saveManager.deleteBackup(backup) }
            .onSuccess { events.emit(UiEvent.Message("Backup supprime")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Suppression impossible")) }
    }

    fun setSaveEditorFavorites(value: String) = viewModelScope.launch { settingsRepository.setSaveEditorFavorites(value) }

    fun requestModsRoot() {
        ignoreNextRelock = true
        pickModsRootRequests.tryEmit(Unit)
    }

    fun setModsRoot(uri: Uri) = viewModelScope.launch {
        settingsRepository.setModsRoot(uri.toString())
        runCatching { modsManager.scanRepository(uri.toString()) }
            .onSuccess { events.emit(UiEvent.Message(it.toString() + " mod(s) detecte(s)")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Scan des mods impossible")) }
    }

    fun scanMods() = viewModelScope.launch {
        val root = settingsRepository.settings.first().modsRootUri
        if (root == null) {
            events.emit(UiEvent.Message("Choisissez d abord le dossier Astra/Mods"))
            return@launch
        }
        runCatching { modsManager.scanRepository(root) }
            .onSuccess { events.emit(UiEvent.Message(it.toString() + " mod(s) detecte(s)")) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Scan des mods impossible")) }
    }

    fun loadMods(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        val root = settingsRepository.settings.first().modsRootUri
        if (root != null) runCatching { modsManager.scanRepository(root) }
        runCatching { modsManager.catalogFor(game) }
            .onSuccess { mutableMods.value = it }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Catalogue de mods illisible")) }
    }

    fun requestModZipImport() {
        ignoreNextRelock = true
        pickModZipRequests.tryEmit(Unit)
    }

    fun importModZip(uri: Uri, engine: String?, replaceExisting: Boolean) = viewModelScope.launch {
        val root = settingsRepository.settings.first().modsRootUri
        if (root == null) {
            events.emit(UiEvent.Message("Choisissez d abord le dossier Astra/Mods"))
            return@launch
        }
        runCatching { modsManager.importZip(uri, root, engine, replaceExisting) }
            .onSuccess { events.emit(UiEvent.Message("Mod importe : " + it.name)) }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Import impossible")) }
    }

    fun installMod(gameId: String, modId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        val mod = dao.getMods().firstOrNull { it.id == modId } ?: return@launch
        runCatching { modsManager.install(game, mod) }
            .onSuccess {
                events.emit(UiEvent.Message("Mod installe"))
                loadMods(gameId)
            }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Installation impossible")) }
    }

    fun uninstallMod(gameId: String, installationId: String, force: Boolean = false) = viewModelScope.launch {
        val installation = dao.observeInstallationsForGame(gameId).first().firstOrNull { it.id == installationId } ?: return@launch
        runCatching { modsManager.uninstall(installation, force) }
            .onSuccess { warnings ->
                if (warnings.isNotEmpty()) events.emit(UiEvent.Message("Des fichiers ont change depuis l installation"))
                else {
                    events.emit(UiEvent.Message("Mod desinstalle"))
                    loadMods(gameId)
                }
            }
            .onFailure { events.emit(UiEvent.Message(it.message ?: "Desinstallation impossible")) }
    }

    fun openGameFolder(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        openFolderRequests.emit(Uri.parse(game.documentUri))
    }

    fun rescanGame(gameId: String) = viewModelScope.launch {
        val game = repository.getGame(gameId) ?: return@launch
        repository.verifyGamePresence(gameId)
        scanSource(game.sourceId)
        events.emit(UiEvent.Message("Rescan du jeu lance"))
    }

    fun mergeTagPair(keep: TagEntity, removed: TagEntity) = viewModelScope.launch {
        repository.mergeTags(keep.id, removed.id)
        refreshTagMerges()
    }

    private fun tagSimilarity(first: String, second: String): Float {
        val left = first.trim().lowercase(java.util.Locale.ROOT)
        val right = second.trim().lowercase(java.util.Locale.ROOT)
        if (left == right) return 1f
        val distance = levenshtein(left, right)
        return 1f - distance.toFloat() / maxOf(left.length, right.length, 1)
    }

    private fun levenshtein(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        left.forEachIndexed { leftIndex, leftChar ->
            val current = IntArray(right.length + 1)
            current[0] = leftIndex + 1
            right.forEachIndexed { rightIndex, rightChar ->
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + if (leftChar == rightChar) 0 else 1
                )
            }
            previous = current
        }
        return previous.last()
    }
    fun requestNotificationPermission() { notificationPermissionRequests.tryEmit(Unit) }
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
                if (settingsRepository.settings.first().historyEnabled) repository.startPlaySession(id)
            }
            is LaunchResult.Failure -> events.emit(UiEvent.Message(result.message))
        }
    }

    fun finishActivePlaySession() = viewModelScope.launch { repository.finishActivePlaySession() }

    fun onAppResumed() {
        finishActivePlaySession()
        if (backgrounded && !ignoreNextRelock) {
            val settings = uiState.value.settings
            if (settings.lockBiometricEnabled || settings.lockPinEnabled) locked.value = true
        }
        ignoreNextRelock = false
        backgrounded = false
    }

    fun onAppBackgrounded() { backgrounded = true }

    private fun descendantFolderIds(rootId: String, folders: List<LibraryFolderEntity>): Set<String> {
        val result = mutableSetOf(rootId)
        var changed: Boolean
        do {
            changed = result.addAll(folders.filter { it.parentId in result }.map { it.id })
        } while (changed)
        return result
    }
}
