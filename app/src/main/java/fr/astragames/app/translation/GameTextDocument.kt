package fr.astragames.app.translation

/** Format-specific readers expose only text slots, never executable game code. */
internal interface GameTextDocument {
    val entries: List<RpgTextDocument.Entry>
    fun translatedEntries(dictionary: Map<String, String>): ByteArray
}

internal object GameTextDocuments {
    val engines = setOf("RPG_MAKER_MV", "RPG_MAKER_MZ", "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE", "WOLF_RPG", "RENPY")
    private val rgssExtensions = mapOf("RPG_MAKER_XP" to ".rxdata", "RPG_MAKER_VX" to ".rvdata", "RPG_MAKER_VX_ACE" to ".rvdata2")
    fun safePath(name: String): Boolean = name.length in 1..512 && name.split('/').let { parts ->
        parts.size <= 8 && parts.all { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c < ' ' || c in "\\:" } }
    }
    fun accepts(engine: String, name: String): Boolean {
        if (!safePath(name)) return false
        return when (engine) {
            "RPG_MAKER_MV", "RPG_MAKER_MZ" -> RpgTextDocument.accepts(name)
            "WOLF_RPG" -> name == "BasicData/CommonEvent.dat" || name.startsWith("MapData/") && name.count { it == '/' } == 1 && name.endsWith(".mps", true)
            "RENPY" -> name.endsWith(".rpy", true) && name.split('/').none { it.startsWith('.') || it in setOf("saves", "cache", "tl") }
            else -> rgssExtensions[engine]?.let { ext -> name.endsWith(ext) && RpgTextDocument.accepts(name.removeSuffix(ext) + ".json") } == true
        }
    }
    fun read(engine: String, name: String, bytes: ByteArray): GameTextDocument {
        require(accepts(engine, name)) { "Fichier de texte non pris en charge : $name" }
        return when (engine) {
            "RPG_MAKER_MV", "RPG_MAKER_MZ" -> RpgTextDocument(name, bytes)
            "WOLF_RPG" -> WolfTextDocument(name, bytes)
            "RENPY" -> RenPyTextDocument(name, bytes)
            else -> RgssTextDocument(name, bytes)
        }
    }
}
