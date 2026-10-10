package fr.astragames.wolf

/** The host retains Android SAF permissions; the engine never copies a game tree. */
interface WolfAssetSource {
    fun read(path: String): ByteArray
    fun exists(path: String): Boolean
    fun list(path: String): List<String>
}

class WolfFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

enum class WolfEncoding { CP932, UTF8 }

data class WolfCommand(
    val opcode: Int,
    val arguments: IntArray,
    val indent: Int,
    val strings: List<String>,
    val route: WolfRoute? = null,
    val extension: ByteArray = byteArrayOf(),
    /** Offset in the expanded payload, for an actionable VM diagnostic. */
    val offset: Int = 0,
) {
    val code: Int get() = opcode
    val args: IntArray get() = arguments
    val depth: Int get() = indent
}

data class WolfRouteCommand(val opcode: Int, val arguments: IntArray) {
    val code: Int get() = opcode
    val args: IntArray get() = arguments
}

data class WolfRoute(
    val flags: Int,
    val commands: List<WolfRouteCommand>,
    val header: ByteArray = byteArrayOf(),
)

data class WolfPage(
    val index: Int,
    val graphicTile: Int,
    val graphicFile: String,
    val graphicDirection: Int,
    val graphicFrame: Int,
    val graphicOpacity: Int,
    val graphicBlend: Int,
    val conditions: ByteArray,
    val movement: ByteArray,
    val flags: Int,
    val route: WolfRoute,
    val commands: List<WolfCommand>,
    val featureCount: Int,
    val features: ByteArray,
    val pageTransfer: Int?,
    val offset: Int,
) {
    val trigger: Int get() = conditions[0].toInt() and 0xff
}

data class WolfEvent(val id: Int, val name: String, val x: Int, val y: Int, val pages: List<WolfPage>)

data class WolfMap(
    val name: String,
    val title: String,
    val wireVersion: Int,
    val encoding: WolfEncoding,
    val tilesetId: Int,
    val width: Int,
    val height: Int,
    val layerCount: Int,
    /** Layer-major order, then y * width + x. A missing tile matrix is empty. */
    val tiles: IntArray,
    val events: List<WolfEvent>,
    val layoutMarker: Int,
    val modernMetadata: Int?,
) {
    fun tile(x: Int, y: Int, layer: Int): Int =
        if (x in 0 until width && y in 0 until height && layer in 0 until layerCount && tiles.isNotEmpty())
            tiles[(layer * height + y) * width + x] else -1
}

data class WolfCommon(
    val index: Int,
    val id: Int,
    val name: String,
    val conditionWord: Int,
    val argumentSettings: ByteArray,
    val commands: List<WolfCommand>,
    val metadataString: String,
    val description: String,
    val argumentNames: List<String>,
    val argumentModes: ByteArray,
    val argumentStringOptions: List<List<String>>,
    val argumentNumericOptions: List<IntArray>,
    val argumentDefaults: IntArray,
    val color: Int,
    val localVariableNames: List<String>,
    val metadataExtra: String,
    val returnName: String?,
    val returnValue: Int?,
    val offset: Int,
)

data class WolfGameConfig(
    val title: String,
    val subtitle: String,
    val encoding: WolfEncoding,
    val tileSize: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val defaultHeroGraphic: String,
    val characterDirectionsImage: Int,
    val characterDirectionsMove: Int,
    val animationPatterns: Int,
    val font: String,
    val subFonts: List<String>,
    val byteSettings: ByteArray,
    val wordSettings: IntArray,
    val strings: List<String>,
    val footer: Int,
)

enum class WolfDatabaseKind { USER, MUTABLE, SYSTEM }
enum class WolfValueType { NUMBER, STRING }

data class WolfDatabaseField(
    val name: String,
    val valueType: WolfValueType,
    val index: Int,
    val editorType: Int,
    val metadata: String,
    val stringOptions: List<String>,
    val numericOptions: IntArray,
    val defaultValue: Int,
)

data class WolfDatabaseRow(val id: Int, val name: String, val numbers: IntArray, val strings: List<String>)

data class WolfDatabaseType(
    val id: Int,
    val name: String,
    val description: String,
    val fields: List<WolfDatabaseField>,
    val rows: List<WolfDatabaseRow>,
    val dataIdMethod: Int,
    val idString: String?,
) {
    fun number(row: Int, field: Int): Int? = fields.getOrNull(field)?.let {
        if (it.valueType == WolfValueType.NUMBER) rows.getOrNull(row)?.numbers?.getOrNull(it.index) else null
    }
    fun string(row: Int, field: Int): String? = fields.getOrNull(field)?.let {
        if (it.valueType == WolfValueType.STRING) rows.getOrNull(row)?.strings?.getOrNull(it.index) else null
    }
}

data class WolfDatabase(val name: String, val encoding: WolfEncoding, val version: Int, val types: List<WolfDatabaseType>)

data class WolfTileset(
    val id: Int,
    val name: String,
    val baseImage: String,
    val autoImages: List<String>,
    val tags: ByteArray,
    val passage: IntArray,
) {
    /** The file reserves one empty slot plus all version-specific autotiles. */
    fun passageIndex(tile: Int): Int = when {
        tile >= 100000 -> tile / 100000
        tile >= 32 -> tile - 32 + autoImages.size + 1
        else -> -1
    }
    fun passageForTile(tile: Int): Int = passageIndex(tile).let {
        if (it < 0) 0 else passage.getOrElse(it) { 0x0f }
    }
    fun tagForTile(tile: Int): Int = tags.getOrNull(passageIndex(tile))?.toInt()?.and(0xff) ?: 0
}

data class WolfMapReference(val id: Int, val name: String, val file: String)
data class WolfStartPosition(val mapId: Int, val x: Int, val y: Int)

data class WolfGameData(
    val config: WolfGameConfig,
    val commonEvents: List<WolfCommon>,
    val databases: Map<WolfDatabaseKind, WolfDatabase>,
    val tilesets: List<WolfTileset>,
    val maps: List<WolfMapReference>,
    val start: WolfStartPosition,
)
