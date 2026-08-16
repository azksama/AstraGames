package fr.astragames.app.data.scanner

import fr.astragames.app.core.model.NormalizedTitle

object GameTitleNormalizer {
    private val developerRegex = Regex("^\\s*\\[([^]]+)]\\s*")
    private val productRegex = Regex("\\((R[JEN]\\d{6,}|[A-Z]{2,5}-?\\d{3,})\\)\\s*$", RegexOption.IGNORE_CASE)
    private val versionRegex = Regex("\\s+v(?:er(?:sion)?\\.?\\s*)?(\\d+(?:\\.\\d+){0,3}[a-z]?)\\s*$", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): NormalizedTitle {
        var title = raw.trim().replace('_', ' ').replace(Regex("\\s+"), " ")
        val developer = developerRegex.find(title)?.groupValues?.get(1)?.trim()
        title = title.replace(developerRegex, "")
        val productCode = productRegex.find(title)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT)
        title = title.replace(productRegex, "").trim()
        val version = versionRegex.find(title)?.groupValues?.get(1)
        title = title.replace(versionRegex, "").trim().trim('-', '–', '—')
        return NormalizedTitle(title.ifBlank { raw.trim() }, developer, version, productCode)
    }
}
