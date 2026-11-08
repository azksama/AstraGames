package fr.astragames.app.core.metadata

import fr.astragames.app.data.local.GameEntity

internal fun GameEntity.needsMetadataEnrichment(): Boolean =
    listOf(coverUri, description, developer, version, language).any { it.isNullOrBlank() }

internal fun matchesMetadataTitle(requested: String, found: String): Boolean {
    fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("\\[[^\\]]*]"), " ")
        .replace(Regex("(?i)\\b(?:v(?:ersion)?\\s*)?\\d+(?:[._-]\\d+)+\\b"), " ")
        .lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    val left = normalized(requested)
    return left.isNotBlank() && left == normalized(found)
}

/** Only explicitly selected fields may replace an existing value during an import. */
enum class MetadataField(val label: String) {
    ORIGINAL_TITLE("Titre original"), DEVELOPER("Développeur"), VERSION("Version"),
    LANGUAGE("Langue"), DESCRIPTION("Description");

    fun incoming(metadata: F95ZoneMetadata): String? = when (this) {
        ORIGINAL_TITLE -> metadata.originalTitle
        DEVELOPER -> metadata.developer
        VERSION -> metadata.version
        LANGUAGE -> metadata.language
        DESCRIPTION -> metadata.description
    }?.trim()?.takeIf(String::isNotBlank)

    fun current(game: GameEntity): String? = when (this) {
        ORIGINAL_TITLE -> game.originalTitle
        DEVELOPER -> game.developer
        VERSION -> game.version
        LANGUAGE -> game.language
        DESCRIPTION -> game.description
    }
}

data class MetadataImportSelection(
    val fields: Set<MetadataField>,
    val tags: Set<String>,
    val image: CoverCandidate?,
    val includeSourceLink: Boolean = true
)

/** Null means leave the field alone. Empty strings from providers are never imported. */
data class MetadataValues(
    val originalTitle: String? = null,
    val developer: String? = null,
    val version: String? = null,
    val language: String? = null,
    val description: String? = null,
    val f95Url: String? = null,
    val ryuugamesUrl: String? = null
) {
    fun merge(game: GameEntity, replaceExisting: Boolean): GameEntity {
        fun value(current: String?, incoming: String?): String? =
            if (replaceExisting || current.isNullOrBlank()) incoming?.trim()?.takeIf(String::isNotBlank) ?: current else current
        return game.copy(
            originalTitle = value(game.originalTitle, originalTitle), developer = value(game.developer, developer),
            version = value(game.version, version), language = value(game.language, language),
            description = value(game.description, description), f95Url = value(game.f95Url, f95Url),
            ryuugamesUrl = value(game.ryuugamesUrl, ryuugamesUrl)
        )
    }
}
