package fr.astragames.app.core.model

enum class GameEngine {
    RPG_MAKER_MV, RPG_MAKER_MZ, RPG_MAKER_VX_ACE, RPG_MAKER_VX, RPG_MAKER_XP,
    RPG_MAKER_2000, RPG_MAKER_2003, RENPY, HTML5, TYRANO, CONSTRUCT, TWINE,
    ELECTRON, WOLF_RPG, UNKNOWN
}

enum class GameLauncherType { JOIPLAY, EXTERNAL, MTOOL, NONE }
enum class ScanStatus { NEVER, RUNNING, SUCCESS, PARTIAL, FAILED }
enum class ScanItemState { NEW, UNCHANGED, UPDATED, MOVED, MISSING, DUPLICATE }
enum class ScanReportItemStatus { ADDED, UPDATED, UNCHANGED, MOVED, MISSING, IGNORED, ERROR }
enum class CompatibilitySeverity { OK, WARNING, ERROR }
enum class TagMatchMode { ALL, ANY, EXCLUDE }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LibraryViewMode { GRID, LIST }
enum class CoverSize { SMALL, MEDIUM, LARGE }

data class DetectionResult(
    val engine: GameEngine,
    val confidence: Float,
    val evidence: List<String>,
    val executableName: String?
)

data class NormalizedTitle(
    val title: String,
    val developer: String? = null,
    val version: String? = null,
    val productCode: String? = null
)

data class ScanReport(
    val reportId: String,
    val sourceId: String,
    val sourceName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val visitedFolders: Int,
    val found: Int,
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    val moved: Int,
    val missing: Int,
    val ignored: Int,
    val errors: List<String>,
    val items: List<ScanReportItem> = emptyList()
)

data class ScanReportItem(
    val path: String,
    val title: String?,
    val status: ScanReportItemStatus,
    val reason: String,
    val gameId: String? = null
)

data class CompatibilityCheck(
    val label: String,
    val detail: String,
    val severity: CompatibilitySeverity
)

data class GameCompatibilityReport(
    val gameId: String,
    val canLaunch: Boolean,
    val summary: String,
    val checks: List<CompatibilityCheck>
)

sealed interface LaunchResult {
    data object Success : LaunchResult
    data class Failure(val message: String) : LaunchResult
}
