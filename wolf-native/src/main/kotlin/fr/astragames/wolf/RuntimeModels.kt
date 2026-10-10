package fr.astragames.wolf

/** Logical inputs, independent of the Android, X11 and Windows key tables. */
enum class NativeKey { UP, DOWN, LEFT, RIGHT, ACCEPT, BACK, SHIFT, CONTROL, SPACE, Z, X, C, A, S, D, W, F1, F5, F12 }

data class NativeRect(val x: Float, val y: Float, val width: Float, val height: Float)
data class NativeCharacter(
    val id: Int,
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val direction: Int = 2,
    val pattern: Int = 1,
    val opacity: Int = 255,
    val onTop: Boolean = false,
    val tileId: Int = -1,
    val moving: Boolean = false,
)

data class NativeMapScene(
    val map: WolfMap,
    val tileset: WolfTileset,
    val characters: List<NativeCharacter>,
    val cameraX: Float,
    val cameraY: Float,
    val tileSize: Int,
    val characterDirections: Int = 4,
    val animationPatterns: Int = 3,
)

data class NativePicture(
    val id: Int,
    val path: String? = null,
    val text: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    /** Zero means the texture's intrinsic dimension; it never changes logical game resolution. */
    val width: Float = 0f,
    val height: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val opacity: Int = 255,
    val rotation: Float = 0f,
    val blend: Int = 0,
    val z: Int = id,
    val sourceRect: NativeRect? = null,
    val color: Int = -1,
    val anchor: Int = 0,
    val fontSize: Float = 16f,
    val fontColor: Int = -1,
    val screenRelative: Boolean = true,
)

data class NativeText(val text: String, val x: Float, val y: Float, val size: Float = 16f, val color: Int = -1)
data class NativeDialog(
    val text: String,
    val choices: List<String> = emptyList(),
    val selectedChoice: Int = 0,
    val waiting: Boolean = true,
    val fontSize: Float = 16f,
)

data class NativeFrame(
    val logicalWidth: Int,
    val logicalHeight: Int,
    val map: NativeMapScene?,
    val pictures: List<NativePicture>,
    val texts: List<NativeText>,
    val dialog: NativeDialog?,
    val tick: Long,
    val diagnostic: String? = null,
    val screenOpacity: Int = 255,
    val screenColor: Int = 0,
) { val height: Int get() = logicalHeight }

sealed interface NativeEffect {
    data class Audio(
        val channel: Int,
        val path: String? = null,
        val volume: Int = 100,
        val pitch: Int = 100,
        val loop: Boolean = false,
        val fadeTicks: Int = 0,
        val stop: Boolean = false,
    ) : NativeEffect
    data class Save(val slot: Int) : NativeEffect
    data class Load(val slot: Int) : NativeEffect
    data class Diagnostic(val message: String) : NativeEffect
    data object Quit : NativeEffect
}

data class WolfCapabilityIssue(
    val file: String,
    val eventId: Int,
    val opcode: Int,
    val offset: Int,
    val reason: String,
)

data class WolfCapabilities(val issues: List<WolfCapabilityIssue>, val scannedMapCount: Int = 0, val inspectedAllMaps: Boolean = false) {
    val supported: Boolean get() = issues.isEmpty()
    fun description(): String = issues.take(12).joinToString("\n") {
        "${it.file} / événement ${it.eventId} / commande ${it.opcode} @0x${it.offset.toString(16)} : ${it.reason}"
    }
}

class WolfRuntimeException(
    val mapId: Int,
    val eventId: Int,
    val commonId: Int,
    val opcode: Int,
    val offset: Int,
    reason: String,
) : IllegalStateException("Wolf natif : carte $mapId, événement $eventId, commun $commonId, commande $opcode @0x${offset.toString(16)} : $reason")
