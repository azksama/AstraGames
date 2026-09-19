package fr.astragames.app.core.search

object SearchParser {
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    fun terms(query: String): List<String> = query
        .lowercase(java.util.Locale.ROOT)
        .split(separators)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    fun toFtsQuery(query: String): String = terms(query)
        // Whitespace is an AND in both standard and enhanced FTS3/4 syntax.
        .joinToString(" ") { "$it*" }
        .ifBlank { "*" }
}
