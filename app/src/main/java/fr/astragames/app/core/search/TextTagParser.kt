package fr.astragames.app.core.search

/** Accepte `tag un, tag deux` ainsi que `[tag un] [tag deux]`. */
fun parseTextTagList(raw: String): List<String> {
    val bracketPattern = Regex("\\[([^]\\r\\n]+)]")
    val bracketed = bracketPattern.findAll(raw).map { it.groupValues[1] }.toList()
    val remaining = bracketPattern.replace(raw, " ")
    val separated = remaining.split(',', ';', '\n', '\r')
    return (bracketed + separated)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }
}
