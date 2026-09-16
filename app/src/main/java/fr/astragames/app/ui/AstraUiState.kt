package fr.astragames.app.ui

import android.net.Uri
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.TagMatchMode
import fr.astragames.app.core.metadata.CoverCandidate
import fr.astragames.app.core.metadata.F95ZoneMetadata
import fr.astragames.app.core.search.DuplicateDetector.DuplicateGroup
import fr.astragames.app.data.local.DeletedGameEntity
import fr.astragames.app.data.local.CollectionEntity
import fr.astragames.app.data.local.CollectionRuleEntity
import fr.astragames.app.data.local.GamePlayStat
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.local.LibraryFolderEntity
import fr.astragames.app.data.local.TagEntity
import fr.astragames.app.data.local.TagCategoryEntity
import fr.astragames.app.launcher.JoiPlayRuntimeInfo
import fr.astragames.app.settings.AstraSettings
import fr.astragames.app.settings.SearchEngine
import kotlinx.coroutines.flow.first

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
