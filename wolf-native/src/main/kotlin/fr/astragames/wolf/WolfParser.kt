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
        val reference = loadGame().maps.firstOrNull { it.id == id }
            ?: throw WolfFormatException("Unknown map ID $id")
        return WolfFormats.parseMap(source.read(reference.file), reference.file)
    }

    private fun database(name: String, budget: WolfParseBudget): WolfDatabase = WolfFormats.parseDatabase(
        source.read("Data/BasicData/$name.project"), source.read("Data/BasicData/$name.dat"), name, budget)

    companion object {
        /** Resource names are stored relative to Data in unprotected Wolf data. */
        fun dataPath(path: String): String {
            if (path.isBlank()) return ""
            val normalized = path.replace('\\', '/').removePrefix("./")
            if (normalized.startsWith('/') || Regex("^[a-zA-Z]:").containsMatchIn(normalized) ||
                normalized.split('/').any { it == ".." } || normalized.indexOf('\u0000') >= 0)
                throw WolfFormatException("Wolf resource path escapes the game directory")
            return if (normalized.startsWith("Data/", ignoreCase = true)) normalized else "Data/$normalized"
        }
    }
}
