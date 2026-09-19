package fr.astragames.app.translation

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.documentDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import android.util.AtomicFile

internal data class TranslationAnalysis(val files: Int, val texts: Int, val characters: Int, val installed: Boolean, val preservedFragments: Int = 0)
internal data class TranslationProgress(val phase: String, val completed: Int = 0, val total: Int = 0, val remainingSeconds: Long? = null)

internal class GameTranslationManager(
    private val context: Context,
    private val createTranslator: (String, String) -> TextTranslator = ::LocalTranslator
) {
    private fun dataDir(game: GameEntity): DocumentFile {
        require(game.engine in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ")) { "Seuls RPG Maker MV et MZ sont pris en charge." }
        val root = documentDir(context, game.documentUri.toUri()) ?: error("Dossier du jeu inaccessible.")
        val data = root.findFile("www")?.findFile("data") ?: root.findFile("data")
        return data?.takeIf { it.isDirectory && it.canRead() && it.canWrite() } ?: error("Dossier data accessible en écriture introuvable.")
    }

    suspend fun hasBackup(game: GameEntity): Boolean = withContext(Dispatchers.IO) {
        if (game.engine !in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ")) false
        else TranslationPatch(SafTranslationFiles(context, dataDir(game))).exists()
    }

    suspend fun isLaunchSafe(game: GameEntity): Boolean = withContext(Dispatchers.IO) {
        if (game.engine !in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ")) return@withContext true
        val root = documentDir(context, game.documentUri.toUri()) ?: return@withContext true
        val data = root.findFile("www")?.findFile("data") ?: root.findFile("data") ?: return@withContext true
        TranslationPatch(SafTranslationFiles(context, data)).isLaunchSafe()
    }

    suspend fun analyze(game: GameEntity): TranslationAnalysis = withContext(Dispatchers.IO) {
        val dir = dataDir(game)
        val files = SafTranslationFiles(context, dir)
        val installed = TranslationPatch(files).exists()
        if (installed) return@withContext TranslationAnalysis(0, 0, 0, true, TranslationPatch(files).preservedFragments())
        val documents = load(dir, files)
        val texts = documents.flatMap { it.texts }.distinct()
        TranslationAnalysis(documents.size, texts.size, texts.sumOf { it.length }, false)
    }

    suspend fun translate(
        game: GameEntity, source: String, target: String, wifiOnly: Boolean,
        progress: (TranslationProgress) -> Unit
    ): TranslationAnalysis = withContext(Dispatchers.IO) {
        val supported = com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages()
        require(source in supported && target in supported && source != target) { "Choisissez deux langues différentes." }
        val dir = dataDir(game)
        val files = SafTranslationFiles(context, dir)
        val patch = TranslationPatch(files)
        check(!patch.exists()) { "Restaurez les originaux avant une nouvelle traduction." }
        progress(TranslationProgress("Analyse des textes"))
        val documents = load(dir, files)
        val originalTexts = documents.flatMap { it.texts }.distinct()
        val fragments = originalTexts.flatMap(ProtectedText::fragments).distinct()
        require(fragments.isNotEmpty()) { "Aucun texte à traduire." }
        val cache = TranslationCache(File(context.noBackupFilesDir, "translation-cache/v1/$source-$target"))
        val translated = mutableMapOf<String, String>()
        val pending = mutableListOf<String>()
        var preserved = 0
        fragments.forEach { text -> cache.read(text)?.let { translated[text] = it } ?: run { pending += text } }
        if (pending.isNotEmpty()) createTranslator(source, target).use { translator ->
            progress(TranslationProgress("Téléchargement des langues", translated.size, fragments.size))
            translator.prepare(wifiOnly)
            val timing = TranslationTiming(android.os.SystemClock.elapsedRealtime(), pending.sumOf { it.length.toLong() })
            progress(TranslationProgress("Traduction locale", translated.size, fragments.size))
            pending.forEach { text ->
                currentCoroutineContext().ensureActive()
                val value = if (text.length <= 4000) TranslationOutput.validated(translator.translate(text)) else null
                if (value == null) {
                    // Keep this fragment verbatim; never cache a failed translation as a success.
                    preserved++
                    translated[text] = text
                } else {
                    cache.write(text, value)
                    translated[text] = value
                }
                val remaining = timing.completed(text.length, android.os.SystemClock.elapsedRealtime())
                progress(TranslationProgress("Traduction locale", translated.size, fragments.size, remaining))
            }
        }
        currentCoroutineContext().ensureActive()
        val dictionary = originalTexts.associateWith { ProtectedText.render(it, translated) }
        val changes = documents.mapNotNull { snapshot ->
            currentCoroutineContext().ensureActive()
            if (snapshot.texts.none { dictionary[it] != it }) null
            else {
                val original = files.read(snapshot.name) ?: error("Fichier inaccessible : ${snapshot.name}")
                check(textHash(original) == snapshot.hash) { "Le jeu a changé : ${snapshot.name}" }
                val doc = RpgTextDocument(snapshot.name, original)
                TranslationPatch.Change(snapshot.name, original, doc.translated(dictionary))
            }
        }
        if (changes.isNotEmpty()) {
            progress(TranslationProgress("Sauvegarde et application", fragments.size, fragments.size))
            // No suspension during the journaled write sequence; failures roll back synchronously.
            patch.apply(changes, source, target, preserved)
        }
        TranslationAnalysis(documents.size, originalTexts.size, originalTexts.sumOf { it.length }, changes.isNotEmpty(), preserved)
    }

    suspend fun restore(game: GameEntity) = withContext(Dispatchers.IO) {
        TranslationPatch(SafTranslationFiles(context, dataDir(game))).restore()
    }

    private data class DocumentSnapshot(val name: String, val hash: String, val texts: List<String>)

    private suspend fun load(dir: DocumentFile, files: TranslationFiles): List<DocumentSnapshot> {
        val names = dir.listFiles().filter { it.isFile && RpgTextDocument.accepts(it.name.orEmpty()) }.map { it.name!! }.sorted()
        require(names.size in 1..2000) { "Aucun fichier RPG Maker lisible, ou trop de fichiers." }
        var total = 0L
        return names.map { name ->
            currentCoroutineContext().ensureActive()
            val bytes = files.read(name) ?: error("Fichier inaccessible : $name")
            total += bytes.size
            require(total <= 64L * 1024 * 1024) { "Les textes du jeu dépassent la limite de 64 Mo." }
            // Release map tile arrays and other non-text JSON data between files.
            DocumentSnapshot(name, textHash(bytes), RpgTextDocument(name, bytes).texts)
        }
    }
}

internal class SafTranslationFiles(private val context: Context, private val root: DocumentFile) : TranslationFiles {
    private fun locate(path: String, create: Boolean = false): DocumentFile? {
        val parts = path.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && ':' !in it })
        require(parts.size == 1 && RpgTextDocument.accepts(parts[0]) ||
            parts[0] == TranslationPatch.BACKUP && (parts.size == 2 && parts[1] in setOf("manifest.json", "pending") ||
                parts.size == 3 && parts[1] in setOf("original", "translated") && RpgTextDocument.accepts(parts[2])))
        var parent = root
        parts.dropLast(1).forEach { part ->
            parent = parent.findFile(part) ?: if (create) parent.createDirectory(part) ?: error("Création du dossier impossible.") else return null
            check(parent.isDirectory)
        }
        val file = parent.findFile(parts.last())
        if (file != null) { check(file.isFile); return file }
        // Never silently recreate a missing game file, except the interrupted journaled write target.
        if (!create) return null
        val name = parts.last()
        val mime = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
        val created = parent.createFile(mime, name) ?: error("Création du fichier impossible.")
        // RawDocumentFile and some providers append the MIME extension (for example pending.bin).
        if (created.name != name) check(created.renameTo(name)) { "Nom du fichier refusé : $name" }
        return parent.findFile(name)?.takeIf { it.isFile } ?: error("Fichier créé introuvable : $name")
    }
    override fun read(path: String): ByteArray? {
        val file = locate(path) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= RpgTextDocument.MAX_FILE_BYTES) { "Fichier trop volumineux : $path" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Lecture impossible : $path")
    }
    override fun write(path: String, bytes: ByteArray) {
        require(bytes.size <= RpgTextDocument.MAX_FILE_BYTES)
        val file = locate(path, true)!!
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } ?: error("Écriture impossible : $path")
    }
    override fun delete(path: String) { locate(path)?.let { check(it.delete()) { "Suppression du journal impossible." } } }
}

internal class TranslationCache(private val directory: File) {
    fun read(text: String): String? = runCatching {
        val value = JSONObject(AtomicFile(File(directory, textHash(text.toByteArray()))).readFully().toString(Charsets.UTF_8))
        value.getString("translation").takeIf { value.getString("source") == text && it.isNotBlank() && ProtectedText.controls(it).isEmpty() }
    }.getOrNull()
    fun write(text: String, translation: String) {
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, textHash(text.toByteArray())))
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("source", text).put("translation", translation).toString().toByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
}
