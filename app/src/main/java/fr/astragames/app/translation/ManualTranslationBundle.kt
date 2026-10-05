package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject

internal data class TranslationSnapshot(val name: String, val hash: String, val entries: List<RpgTextDocument.Entry>) {
    val texts: List<String> get() = entries.map { it.text }
}

/** Names used as logical identifiers must also match actors stored in pre-existing saves. */
internal fun protectedActorNameGroups(documents: List<TranslationSnapshot>): Set<String> = documents
    .flatMap { it.entries }.filter { it.identityReference }.mapNotNull { it.identityGroup }.toSet()

internal fun localTranslationReplacements(documents: List<TranslationSnapshot>, dictionary: Map<String, String>): Map<String, Map<String, String>> {
    val protectedGroups = protectedActorNameGroups(documents)
    return documents.associate { document -> document.name to document.entries.associate { entry ->
        entry.path to if (entry.identityGroup in protectedGroups) entry.text else dictionary.getValue(entry.text)
    } }
}

/** Portable, data-only exchange. Import is bound to this library game and every source file. */
internal object ManualTranslationBundle {
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_ENTRIES = 200_000
    private const val FORMAT = "astra-rpgm-translation"
    private const val IDENTITY_REASON = "This actor name is used by a game condition and may exist in player saves. Keep its source unchanged."
    data class Imported(val source: String, val target: String, val replacements: Map<String, Map<String, String>>, val preserved: Int)

    fun export(gameId: String, title: String, engine: String, source: String, target: String, documents: List<TranslationSnapshot>): ByteArray {
        languages(source, target)
        require(documents.sumOf { it.entries.size } in 1..MAX_ENTRIES) { "Aucun texte à exporter, ou trop de textes." }
        val protectedGroups = protectedActorNameGroups(documents)
        val entries = JSONArray()
        documents.forEach { document -> document.entries.forEach { entry ->
            val masked = ProtectedText.mask(entry.text)
            entries.put(JSONObject().put("id", id(document.name, entry))
                .put("file", document.name).put("path", entry.path)
                .put("source", masked.source).put("protectedTokens", tokens(masked)).put("translation", "")
                .apply {
                    entry.identityGroup?.let { put("identityGroup", it) }
                    if (entry.identityGroup in protectedGroups) put("preserveOriginal", true).put("readOnlyReason", IDENTITY_REASON)
                })
        } }
        val value = JSONObject().put("format", FORMAT).put("version", 1)
            .put("gameId", gameKey(gameId, engine)).put("gameTitle", title).put("engine", engine)
            .put("sourceLanguage", source).put("targetLanguage", target).put("sourceHash", sourceHash(documents))
            .put("instructions", "Translate every entry.source into targetLanguage and place the result in entry.translation. " +
                "Only edit translation. Keep every entry, id, file, path, source, protectedTokens and all metadata unchanged. " +
                "Copy each ⟦ASTRA_…⟧ marker exactly once in the same order. Do not add line breaks or RPG Maker commands. " +
                "Entries with the same identityGroup must have exactly the same translation, including unchanged actor names and name comparisons. " +
                "If preserveOriginal is true, copy source unchanged into translation or leave translation empty. These names are logical identifiers. " +
                "Empty translation means keep the original text. Return only the complete UTF-8 JSON file, without Markdown.")
            .put("files", JSONArray(documents.map { JSONObject().put("name", it.name).put("sha256", it.hash) }))
            .put("entries", entries)
        return value.toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) { "Export trop volumineux (64 Mo maximum)." } }
    }

    fun import(bytes: ByteArray, gameId: String, engine: String, documents: List<TranslationSnapshot>): Imported {
        val value = TranslationJson.parse(bytes, MAX_BYTES) as? JSONObject ?: error("Objet JSON de traduction attendu.")
        require(string(value, "format") == FORMAT && value.opt("version") == 1) { "Format ou version d’export inconnu." }
        require(string(value, "gameId") == gameKey(gameId, engine) && string(value, "engine") == engine) { "Ce fichier de traduction appartient à un autre jeu." }
        require(string(value, "sourceHash") == sourceHash(documents)) { "Le jeu a changé depuis l’export. Exportez à nouveau ses textes." }
        val source = string(value, "sourceLanguage"); val target = string(value, "targetLanguage")
        languages(source, target)
        val exportedFiles = value.getJSONArray("files")
        require(exportedFiles.length() == documents.size) { "Liste des fichiers incomplète." }
        val expectedFiles = documents.associate { it.name to it.hash }
        val seenFiles = mutableSetOf<String>()
        for (index in 0 until exportedFiles.length()) {
            val file = exportedFiles.getJSONObject(index)
            val name = string(file, "name")
            require(seenFiles.add(name) && expectedFiles[name] == string(file, "sha256")) { "Fichier source inconnu, dupliqué ou modifié : $name" }
        }
        val expected = documents.flatMap { document -> document.entries.map { entry -> id(document.name, entry) to (document.name to entry) } }.toMap()
        val protectedGroups = protectedActorNameGroups(documents)
        require(expected.size in 1..MAX_ENTRIES) { "Nombre de textes invalide." }
        val entries = value.getJSONArray("entries")
        require(entries.length() == expected.size) { "Liste des textes incomplète ou issue d’une ancienne extraction. Exportez à nouveau et conservez toutes les entrées." }
        val seen = mutableSetOf<String>()
        val replacements = mutableMapOf<String, MutableMap<String, String>>()
        val identityTranslations = mutableMapOf<String, Pair<String, String>>()
        var preserved = 0
        for (index in 0 until entries.length()) {
            val row = entries.getJSONObject(index)
            val id = string(row, "id")
            require(seen.add(id)) { "Identifiant de texte dupliqué : $id" }
            val (file, entry) = expected[id] ?: error("Identifiant de texte inconnu : $id")
            val masked = ProtectedText.mask(entry.text)
            require(string(row, "file") == file && string(row, "path") == entry.path && string(row, "source") == masked.source) {
                "Texte source ou emplacement modifié : $file${entry.path}"
            }
            // Older v1 bundles omitted this advisory field; coherence always uses verified game data.
            require(!row.has("identityGroup") || row.opt("identityGroup") == entry.identityGroup) { "Groupe de noms modifié : $file${entry.path}" }
            val preserveOriginal = entry.identityGroup in protectedGroups
            require(!row.has("preserveOriginal") || row.opt("preserveOriginal") == preserveOriginal) { "Protection du nom modifiée : $file${entry.path}" }
            require(!row.has("readOnlyReason") || row.opt("readOnlyReason") == if (preserveOriginal) IDENTITY_REASON else null) { "Motif de protection modifié : $file${entry.path}" }
            val savedTokens = row.getJSONArray("protectedTokens")
            require(savedTokens.length() == masked.tokens.size) { "Liste des commandes modifiée : $file${entry.path}" }
            for (token in 0 until savedTokens.length()) {
                val saved = savedTokens.getJSONObject(token)
                require(string(saved, "marker") == masked.markers[token] && string(saved, "value") == masked.tokens[token]) { "Commande protégée modifiée : $file${entry.path}" }
            }
            val translation = string(row, "translation")
            val maximum = maxOf(entry.text.length, 4096, minOf(1_048_576, entry.text.length * 8))
            // Marker expansion is transport overhead, not translated text. Existing long sources can round-trip.
            require(translation.length.toLong() <= maximum.toLong() + masked.markers.sumOf { it.length.toLong() }) { "Traduction trop longue : $file${entry.path}" }
            val replacement = if (translation.isBlank()) entry.text else try {
                ProtectedText.unmask(entry.text, translation, masked)
            } catch (failure: IllegalArgumentException) {
                throw IllegalArgumentException("$file${entry.path} : ${failure.message}", failure)
            }
            require(replacement.length <= maximum) { "Traduction trop longue : $file${entry.path}" }
            require(!preserveOriginal || replacement == entry.text) { "Nom utilisé par une condition du jeu : conservez le texte original dans $file${entry.path}." }
            entry.identityGroup?.let { group ->
                val previous = identityTranslations.putIfAbsent(group, replacement to "$file${entry.path}")
                require(previous == null || previous.first == replacement) {
                    "Nom d’acteur incohérent entre ${previous?.second} et $file${entry.path}. Traduisez toutes les entrées du même identityGroup de façon identique."
                }
            }
            if (translation.isBlank() && !preserveOriginal) preserved++
            replacements.getOrPut(file) { mutableMapOf() }[entry.path] = replacement
        }
        return Imported(source, target, replacements, preserved)
    }

    private fun tokens(masked: ProtectedText.Masked) = JSONArray(masked.tokens.mapIndexed { index, token ->
        JSONObject().put("marker", masked.markers[index]).put("value", token)
    })
    private fun string(value: JSONObject, key: String): String = value.opt(key) as? String ?: error("Champ texte attendu : $key")
    private fun gameKey(gameId: String, engine: String) = textHash("$engine\u0000$gameId".toByteArray(Charsets.UTF_8))
    private fun id(file: String, entry: RpgTextDocument.Entry) = textHash("$file\u0000${entry.path}\u0000${entry.text}".toByteArray(Charsets.UTF_8))
    private fun sourceHash(documents: List<TranslationSnapshot>) = textHash(documents.sortedBy { it.name }
        .joinToString("\n") { "${it.name}:${it.hash}" }.toByteArray(Charsets.UTF_8))
    private fun languages(source: String, target: String) {
        val language = Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
        require(source.length <= 32 && target.length <= 32 && source.matches(language) && target.matches(language) && source != target) { "Choisissez deux langues différentes." }
    }
}
