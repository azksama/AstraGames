package fr.astragames.app.translation

import android.util.AtomicFile
import fr.astragames.app.data.local.GameEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, persistent extraction cache. Opening a screen reads only its small summary. */
internal class TranslationAnalysisCache(root: File, game: GameEntity) {
    private val identity = "${game.id}\n${game.engine}\n${game.documentUri}"
    private val directory = File(root, textHash(identity.toByteArray(Charsets.UTF_8)))

    fun summary(): TranslationAnalysis? = read("summary.json", 4096)?.let { value ->
        runCatching {
            TranslationAnalysis(value.getInt("files"), value.getInt("texts"), value.getInt("characters"), false)
                .takeIf { it.files in 1..2000 && it.texts >= 0 && it.characters >= 0 }
        }.getOrNull()
    }

    fun snapshots(): Map<String, TranslationSnapshot> = runCatching {
        val array = read("texts.json", MAX_BYTES)?.getJSONArray("documents") ?: return emptyMap()
        require(array.length() in 1..2000)
        (0 until array.length()).map { index ->
            val document = array.getJSONObject(index)
            val name = document.getString("name")
            require(RpgTextDocument.accepts(name))
            val entries = document.getJSONArray("entries")
            TranslationSnapshot(name, document.getString("hash"), (0 until entries.length()).map { i ->
                val entry = entries.getJSONObject(i)
                RpgTextDocument.Entry(
                    entry.getString("path"), entry.getString("text"),
                    entry.optString("group").takeIf(String::isNotEmpty), entry.optBoolean("reference")
                )
            })
        }.also { require(it.map(TranslationSnapshot::name).distinct().size == it.size) }.associateBy { it.name }
    }.getOrDefault(emptyMap())

    fun save(documents: List<TranslationSnapshot>) {
        val texts = documents.flatMap { it.texts }.distinct()
        val payload = envelope().put("documents", JSONArray().apply {
            documents.forEach { snapshot ->
                put(JSONObject().put("name", snapshot.name).put("hash", snapshot.hash).put("entries", JSONArray().apply {
                    snapshot.entries.forEach { entry ->
                        put(JSONObject().put("path", entry.path).put("text", entry.text)
                            .put("group", entry.identityGroup.orEmpty()).put("reference", entry.identityReference))
                    }
                }))
            }
        })
        write("texts.json", payload, MAX_BYTES)
        // Publish only after the full extraction completed and its payload was committed.
        write("summary.json", envelope().put("files", documents.size).put("texts", texts.size)
            .put("characters", texts.sumOf { it.length }), 4096)
    }

    private fun envelope() = JSONObject().put("schema", 1).put("identity", identity)

    private fun read(name: String, limit: Int): JSONObject? = runCatching {
        val bytes = AtomicFile(File(directory, name)).openRead().use { readTranslationBytes(it, limit) }
        (TranslationJson.parse(bytes, limit) as JSONObject).takeIf {
            it.getInt("schema") == 1 && it.getString("identity") == identity
        }
    }.getOrNull()

    private fun write(name: String, value: JSONObject, limit: Int) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "Analyse trop volumineuse pour le cache." }
        check(directory.isDirectory || directory.mkdirs()) { "Cache d’analyse inaccessible." }
        val file = AtomicFile(File(directory, name))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    private companion object { const val MAX_BYTES = 128 * 1024 * 1024 }
}
