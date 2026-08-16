package fr.astragames.app.core.search

object SearchParser {
    fun terms(query: String): List<String> = query
        .lowercase(java.util.Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}_-]+"))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    fun toFtsQuery(query: String): String = terms(query)
        .joinToString(" AND ") { "${it.replace("\"", "\"\"") }*" }
        .ifBlank { "*" }
}
