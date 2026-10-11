package fr.astragames.wolf

/** Wolf's explicit hidden-code operation, separate from ordinary string assignment/formatting. */
internal object WolfStringMacros {
    private val supported = setOf("GET_COMMONEVENT_ID_FROM_NAME", "GET_COMMONEVENT_NAME_FROM_ID",
        "GET_MAPEVENT_ID_FROM_NAME", "GET_MAPEVENT_NAME_FROM_ID", "GET_TITLE", "GET_TITLE_PLUS",
        "GET_MS_VOICE_LIST", "GET_MS_VOICE_PLAYING", "GET_FILE_EXIST", "GET_SOUNDTIME",
        "GET_MAPEV_FILENAME", "GET_FILENAME_FROM_PICTURE", "GET_STRING_BYTE_COUNT", "GET_STRING_WIDTH_COUNT")

    private fun pathName(name: String) = name.replace(Regex("^(FIND_PATH_DIR[48])_SX=")) { "${it.groupValues[1]}/SX=" }
    private val databaseItem = Regex("GET_ISNUMBER_([CUS])DB_ITEM/TYPE=(.*?)/ITEM=(.*)")
    private val lineCut = Regex("CUT_LINE_STRING_(.*?)_(.*)")

    data class SpeechRequest(val text: String, val speed: Int, val volume: Int, val voice: String?, val absolute: Boolean)

    fun problem(input: String): String? {
        val name = input.substringAfter("<<", "").substringBefore(">>")
        if (!input.startsWith("<<")) return null // The engine returns its defined missing-delimiter error.
        if (!input.contains(">>")) return null
        if (name in supported) return if (name == "GET_STRING_BYTE_COUNT" && !WolfTextExpansion.hasReference(input.substringAfter(">>")))
            runCatching { WolfStringMeasurements.byteCount(input.substringAfter(">>")) }.exceptionOrNull()?.message else null
        val shape = WolfTextCodes.staticExpansion(name)
        if (databaseItem.matches(shape) || lineCut.matches(shape)) return null
        if (name.substringBefore('/') == "MS_VOICE_PLAY")
            return runCatching { speechRequest(input) }.exceptionOrNull()?.message
        if (pathName(name).substringBefore('/') in setOf("FIND_PATH_DIR4", "FIND_PATH_DIR8"))
            return runCatching { pathRequest("<<${pathName(shape)}>>") }.exceptionOrNull()?.message
        return "Code de chaîne spécial non porté : $name"
    }

    /** REA W3 a33f38..a3421a and the author's hidden-code documentation. */
    fun speechRequest(input: String): SpeechRequest {
        require(input.startsWith("<<") && input.contains(">>")) { "Délimiteurs de voix absents" }
        val end = input.indexOf(">>")
        val parts = input.substring(2, end).split('/')
        require(parts[0] == "MS_VOICE_PLAY") { "Code de voix inconnu" }
        val values = linkedMapOf<String, String>(); var absolute = false
        parts.drop(1).filter { it.isNotEmpty() }.forEach { option ->
            if (option == "ABS") absolute = true
            else {
                require('=' in option && option.substringBefore('=') in setOf("SPEED", "VOL", "VOICE")) { "Option de voix non portée : $option" }
                values.putIfAbsent(option.substringBefore('='), option.substringAfter('='))
            }
        }
        return SpeechRequest(input.substring(end + 2), (values["SPEED"]?.let(WolfVariables::numericPrefix) ?: 0).coerceIn(-10, 10),
            maxOf(0, values["VOL"]?.let(WolfVariables::numericPrefix) ?: 100), values["VOICE"]?.takeIf { it.isNotBlank() }, absolute)
    }

    /** REA W3 a2e2d0 and the official hidden-code contract: duplicate names select the highest ID. */
    fun evaluate(input: String, game: WolfGameData, map: WolfMap, convertSpecial: Boolean = true,
        actorGraphic: ((Int) -> String)? = null, pictureFile: ((Int) -> String)? = null,
        path: ((WolfPathfinding.Request) -> String)? = null): String {
        if (!input.startsWith("<<")) return "<<Error>> Missing \"<<\""
        val end = input.indexOf(">>")
        if (end < 0) return "<<Error>> Missing \">>\""
        val name = input.substring(2, end)
        val argument = input.substring(end + 2)
        if (pathName(name).substringBefore('/') in setOf("FIND_PATH_DIR4", "FIND_PATH_DIR8"))
            return (path ?: error("Hôte de recherche de chemin absent"))(pathRequest(input))
        lineCut.matchEntire(name)?.let { match ->
            return WolfStringMeasurements.cutLines(argument, WolfVariables.numericPrefix(match.groupValues[1]), WolfVariables.numericPrefix(match.groupValues[2]))
        }
        databaseItem.matchEntire(name)?.let { match ->
            val kind = when (match.groupValues[1]) { "C" -> WolfDatabaseKind.MUTABLE; "U" -> WolfDatabaseKind.USER; else -> WolfDatabaseKind.SYSTEM }
            val database = game.databases[kind] ?: error("Base de données absente : $kind")
            val typeId = WolfVariables.numericPrefix(match.groupValues[2])
            val fieldId = WolfVariables.numericPrefix(match.groupValues[3])
            val type = database.types.getOrNull(typeId)
                ?: return "-1 <<Error>> Type Overflow: max=${database.types.size}"
            val field = type.fields.getOrNull(fieldId)
                ?: return "-1 <<Error>> Item Overflow: max=${type.fields.size}"
            return if (field.valueType == WolfValueType.NUMBER) "1" else "0"
        }
        return when (name) {
            "GET_COMMONEVENT_ID_FROM_NAME" -> (game.commonEvents.filter { it.name.isNotEmpty() && it.name == argument }.maxOfOrNull { it.id } ?: -1).toString()
            "GET_COMMONEVENT_NAME_FROM_ID" -> game.commonEvents.firstOrNull { it.id == WolfVariables.numericPrefix(argument) }?.name ?: "<<NotFound>>"
            "GET_MAPEVENT_ID_FROM_NAME" -> (map.events.filter { it.name.isNotEmpty() && it.name == argument }.maxOfOrNull { it.id } ?: -1).toString()
            "GET_MAPEVENT_NAME_FROM_ID" -> map.events.firstOrNull { it.id == WolfVariables.numericPrefix(argument) }?.name ?: "<<NotFound>>"
            "GET_TITLE" -> game.config.title
            "GET_TITLE_PLUS" -> game.config.subtitle
            "GET_STRING_BYTE_COUNT" -> WolfStringMeasurements.byteCount(argument, convertSpecial).toString()
            "GET_STRING_WIDTH_COUNT" -> WolfStringMeasurements.widthCount(argument, convertSpecial).toString()
            "GET_MAPEV_FILENAME" -> (actorGraphic ?: error("État graphique des personnages absent"))(WolfVariables.numericPrefix(argument))
            "GET_FILENAME_FROM_PICTURE" -> (pictureFile ?: error("État des pictures absent"))(WolfVariables.numericPrefix(argument))
            else -> error("Code de chaîne spécial non porté : $name")
        }
    }

    fun pathRequest(input: String): WolfPathfinding.Request {
        require(input.startsWith("<<") && input.contains(">>")) { "Délimiteurs de recherche absents" }
        val parts = pathName(input.substring(2, input.indexOf(">>"))).split('/')
        val directions = when (parts[0]) { "FIND_PATH_DIR4" -> 4; "FIND_PATH_DIR8" -> 8
            else -> error("Directions de recherche non portées") }
        val values = linkedMapOf<String, String>()
        val options = hashSetOf<String>()
        parts.drop(1).filter { it.isNotEmpty() }.forEach { part ->
            if ('=' in part) {
                val key = part.substringBefore('=')
                require(key in setOf("SX", "SY", "EX", "EY", "LEN", "EXE", "MOVE_EV")) { "Paramètre de recherche non porté : $key" }
                values.putIfAbsent(key, part.substringAfter('='))
            } else {
                require(part in setOf("v2", "POS", "STEP", "TIME")) { "Option de recherche non portée : $part" }
                options.add(part)
            }
        }
        fun number(key: String, default: Int? = null) = values[key]?.let(WolfVariables::numericPrefix)
            ?: default ?: error("Paramètre de recherche absent : $key")
        return WolfPathfinding.Request(directions, WolfPathfinding.Point(number("SX"), number("SY")),
            WolfPathfinding.Point(number("EX"), number("EY")), version2 = "v2" in options,
            positions = "POS" in options, steps = "STEP" in options, time = "TIME" in options,
            length = number("LEN", 0), executions = number("EXE", 10_000), movingEvent = number("MOVE_EV", -999))
    }
}
