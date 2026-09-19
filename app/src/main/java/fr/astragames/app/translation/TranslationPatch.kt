package fr.astragames.app.translation

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal interface TranslationFiles {
    fun read(path: String): ByteArray?
    fun write(path: String, bytes: ByteArray)
    fun delete(path: String)
}

internal fun textHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

/** The immutable journal and both versions live beside the game, independently of app data. */
internal class TranslationPatch(private val files: TranslationFiles) {
    data class Change(val name: String, val original: ByteArray, val translated: ByteArray)
    private data class Record(val name: String, val original: String, val translated: String)
    fun exists() = files.read(MANIFEST) != null

    fun preservedFragments(): Int = files.read(MANIFEST)?.let {
        JSONObject(it.toString(Charsets.UTF_8)).optInt("preservedFragments", 0).coerceAtLeast(0)
    } ?: 0

    fun isLaunchSafe(): Boolean = runCatching {
        val bytes = files.read(MANIFEST) ?: return true
        if (files.read(PENDING) != null) return false
        require(bytes.size <= 1024 * 1024)
        val journal = JSONObject(bytes.toString(Charsets.UTF_8))
        require(journal.getInt("version") == 1)
        val rows = journal.getJSONArray("files")
        require(rows.length() in 1..2000)
        val names = mutableSetOf<String>()
        val states = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val name = row.getString("name")
            require(RpgTextDocument.accepts(name))
            require(names.add(name))
            val hash = files.read(name)?.let(::textHash)
            (hash == row.getString("original")) to (hash == row.getString("translated"))
        }
        states.all { it.first } || states.all { it.second }
    }.getOrDefault(false)

    fun apply(changes: List<Change>, source: String, target: String, preservedFragments: Int = 0) {
        check(!exists()) { "Restaurez la traduction précédente avant de la remplacer." }
        require(changes.isNotEmpty() && changes.size <= 2000)
        require(changes.map { it.name }.distinct().size == changes.size)
        changes.forEach {
            require(RpgTextDocument.accepts(it.name))
            check(files.read(it.name)?.contentEquals(it.original) == true) { "Le jeu a changé : ${it.name}" }
        }
        val records = changes.map { Record(it.name, textHash(it.original), textHash(it.translated)) }
        // All backups must be verified before any original game file is touched.
        changes.forEach {
            verifiedWrite("$BACKUP/original/${it.name}", it.original)
            verifiedWrite("$BACKUP/translated/${it.name}", it.translated)
        }
        val journal = JSONObject().put("version", 1).put("source", source).put("target", target)
            .put("preservedFragments", preservedFragments)
            .put("files", JSONArray(records.map { JSONObject().put("name", it.name).put("original", it.original).put("translated", it.translated) }))
        try {
            verifiedWrite(MANIFEST, journal.toString().toByteArray())
        } catch (failure: Exception) {
            // No game file has been touched yet; discard an incomplete journal when possible.
            try { files.delete(MANIFEST) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        try {
            changes.forEach {
                check(files.read(it.name)?.contentEquals(it.original) == true) { "Le jeu a changé : ${it.name}" }
                verifiedWrite(PENDING, it.name.toByteArray())
                verifiedWrite(it.name, it.translated)
                files.delete(PENDING)
            }
        } catch (failure: Exception) {
            try { restore() } catch (restore: Exception) { failure.addSuppressed(restore) }
            throw failure
        }
    }

    fun restore() {
        val bytes = files.read(MANIFEST) ?: return
        require(bytes.size <= 1024 * 1024) { "Journal trop volumineux." }
        val journal = JSONObject(bytes.toString(Charsets.UTF_8))
        require(journal.getInt("version") == 1) { "Version du journal inconnue." }
        val rows = journal.getJSONArray("files")
        require(rows.length() in 1..2000)
        val records = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            Record(row.getString("name"), row.getString("original"), row.getString("translated"))
        }
        require(records.map { it.name }.distinct().size == records.size)
        val pending = files.read(PENDING)?.toString(Charsets.UTF_8)
        records.forEach {
            require(RpgTextDocument.accepts(it.name)) { "Chemin de restauration invalide." }
            require(it.original.matches(Regex("[0-9a-f]{64}")) && it.translated.matches(Regex("[0-9a-f]{64}")))
            check(textHash(files.read("$BACKUP/original/${it.name}") ?: error("Original absent : ${it.name}")) == it.original) { "Original endommagé : ${it.name}" }
            val current = files.read(it.name)?.let(::textHash)
            check(current == it.original || current == it.translated || pending == it.name) {
                "Fichier modifié en dehors d’Astra : ${it.name}. Restauration interrompue."
            }
        }
        // Repair an interrupted target before changing the pending marker for another file.
        records.sortedBy { if (it.name == pending) 0 else 1 }.forEach {
            if (files.read(it.name)?.let(::textHash) != it.original) {
                val original = files.read("$BACKUP/original/${it.name}")!!
                check(textHash(original) == it.original) { "Original endommagé : ${it.name}" }
                verifiedWrite(PENDING, it.name.toByteArray())
                verifiedWrite(it.name, original)
            }
            files.delete(PENDING)
        }
        files.delete(MANIFEST)
        check(!exists()) { "Impossible de finaliser la restauration." }
    }

    private fun verifiedWrite(path: String, bytes: ByteArray) {
        files.write(path, bytes)
        check(files.read(path)?.contentEquals(bytes) == true) { "Écriture incomplète : $path" }
    }

    companion object {
        const val BACKUP = ".astra-translation"
        const val MANIFEST = "$BACKUP/manifest.json"
        private const val PENDING = "$BACKUP/pending"
    }
}
