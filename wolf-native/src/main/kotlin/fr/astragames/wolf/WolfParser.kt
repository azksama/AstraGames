package fr.astragames.wolf

/** Loads engine metadata only. Images/audio and maps are read on demand. */
class WolfParser(private val source: WolfAssetSource, private val progress: (String) -> Unit = {}) {
    private var game: WolfGameData? = null

    fun loadGame(): WolfGameData {
        game?.let { return it }
        // The same budget spans all nine metadata resources. Maps remain lazy
        // and use their own per-load budget so revisiting a map does not consume
        // an ever-growing quota.
        val budget = WolfParseBudget()
        progress("Lecture des réglages Wolf")
        val config = WolfFormats.parseGame(source.read("Data/BasicData/Game.dat"), "Game.dat", budget)
        progress("Lecture des événements communs")
        val common = WolfFormats.parseCommonEvents(source.read("Data/BasicData/CommonEvent.dat"), "CommonEvent.dat", budget)
        progress("Lecture des bases de données")
        val databases = linkedMapOf(
            WolfDatabaseKind.USER to database("DataBase", budget),
            WolfDatabaseKind.MUTABLE to database("CDataBase", budget),
            WolfDatabaseKind.SYSTEM to database("SysDatabase", budget),
        )
        progress("Lecture des tuiles")
        val tilesets = WolfFormats.parseTilesets(source.read("Data/BasicData/TileSetData.dat"), "TileSetData.dat", budget)
        val system = databases.getValue(WolfDatabaseKind.SYSTEM)
        val mapType = system.types.getOrNull(0)
            ?: throw WolfFormatException("SysDatabase: missing map registry (type 0)")
        val maps = mapType.rows.map { row ->
            val file = mapType.string(row.id, 0)
                ?: throw WolfFormatException("SysDatabase: map ${row.id} has no map-file field")
            WolfMapReference(row.id, row.name, dataPath(file))
        }
        val positions = system.types.getOrNull(7)
            ?: throw WolfFormatException("SysDatabase: missing starting-position table (type 7)")
        fun position(field: Int): Int = positions.number(0, field)
            ?: throw WolfFormatException("SysDatabase: missing starting-position field $field")
        val start = WolfStartPosition(position(0), position(1), position(2))
        if (maps.none { it.id == start.mapId && it.file.isNotBlank() })
            throw WolfFormatException("SysDatabase: starting map ${start.mapId} is absent")
        return WolfGameData(config, common, databases, tilesets, maps, start).also { game = it }
    }

    fun loadMap(id: Int): WolfMap {
        val data = loadGame()
        val reference = data.maps.firstOrNull { it.id == id }
            ?: throw WolfFormatException("Unknown map ID $id")
        // WOLFX aliases take precedence even when the clear map exists. The
        // metadata/map reader is not the individual-resource media decoder.
        val encrypted = when {
            reference.file.endsWith(".wolfx", ignoreCase = true) -> reference.file
            source.exists("${reference.file}.wolfx") -> "${reference.file}.wolfx"
            else -> null
        }
        if (encrypted != null) throw WolfFormatException("Carte WOLFX non prise en charge par le lecteur natif : $encrypted")
        // Original W2 0044dcf0 / W3 009f8860 apply SysDB0[field3]:
        // 1 horizontal, 2 vertical, 3 both; every other value clears both.
        val loop = data.databases.getValue(WolfDatabaseKind.SYSTEM).types[0].number(id, 3) ?: 0
        return WolfFormats.parseMap(source.read(reference.file), reference.file).copy(
            loopX = loop == 1 || loop == 3,
            loopY = loop == 2 || loop == 3,
        )
    }

    private fun database(name: String, budget: WolfParseBudget): WolfDatabase = WolfFormats.parseDatabase(
        source.read("Data/BasicData/$name.project"), source.read("Data/BasicData/$name.dat"), name, budget)

    companion object {
        const val DATA_ROOT_PREFIX = "game-root:"
        /** Game-root path, with Windows separators, without permitting a parent traversal. */
        fun normalizePath(path: String): String {
            val normalized = path.replace('\\', '/')
            if (normalized.startsWith('/') || ':' in normalized || normalized.indexOf('\u0000') >= 0)
                throw WolfFormatException("Wolf resource path escapes the game directory")
            val parts = ArrayDeque<String>()
            for (part in normalized.split('/')) when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw WolfFormatException("Wolf resource path escapes the game directory") else parts.removeLast()
                else -> parts.addLast(part)
            }
            return parts.joinToString("/")
        }
        /** Resource names are stored relative to Data in unprotected Wolf data. */
        fun dataPath(path: String): String {
            if (path.isBlank()) return ""
            val windows = path.replace('\\', '/')
            if (windows.startsWith('/') || ':' in windows || '\u0000' in windows)
                throw WolfFormatException("Wolf resource path escapes the game directory")
            return normalizePath(if (windows.startsWith("Data/", ignoreCase = true)) windows else "Data/$windows")
        }
        /** Explicitly distinguishes Data-relative resource names from resources in Save or another game folder. */
        fun imageAssetPath(path: String): String {
            if (path.startsWith(DATA_ROOT_PREFIX)) return DATA_ROOT_PREFIX + rootAssetPath(path)
            val root = dataPath(path)
            return if (root.isBlank() || root.startsWith("Data/", true)) root else DATA_ROOT_PREFIX + root
        }
        fun rootAssetPath(path: String): String = normalizePath(path.removePrefix(DATA_ROOT_PREFIX))
    }
}
