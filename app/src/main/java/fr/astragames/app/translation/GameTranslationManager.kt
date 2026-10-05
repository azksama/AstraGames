package fr.astragames.app.translation

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
    private fun dataDir(game: GameEntity, requireWrite: Boolean = true): DocumentFile {
        require(game.engine in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ")) { "Seuls RPG Maker MV et MZ sont pris en charge." }
        val root = documentDir(context, game.documentUri.toUri()) ?: error("Dossier du jeu inaccessible.")
        val data = root.findFile("www")?.findFile("data") ?: root.findFile("data")
        return data?.takeIf { it.isDirectory && it.canRead() && (!requireWrite || it.canWrite()) }
            ?: error(if (requireWrite) "Dossier data accessible en écriture introuvable." else "Dossier data accessible en lecture introuvable.")
    }

    suspend fun hasBackup(game: GameEntity): Boolean = withContext(Dispatchers.IO) {
        if (game.engine !in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ")) false
        else TranslationPatch(SafTranslationFiles(context, dataDir(game, requireWrite = false))).exists()
    }

    suspend fun isLaunchSafe(game: GameEntity): Boolean = withContext(Dispatchers.IO) {
        if (game.engine !in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ")) return@withContext true
        val root = documentDir(context, game.documentUri.toUri()) ?: return@withContext true
        val data = root.findFile("www")?.findFile("data") ?: root.findFile("data") ?: return@withContext true
        TranslationPatch(SafTranslationFiles(context, data)).isLaunchSafe()
    }

    suspend fun analyze(game: GameEntity): TranslationAnalysis = withContext(Dispatchers.IO) {
        val dir = dataDir(game, requireWrite = false)
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
        val protectedGroups = protectedActorNameGroups(documents)
        val translatableTexts = documents.flatMap { it.entries }.filterNot { it.identityGroup in protectedGroups }.map { it.text }.distinct()
        val fragments = translatableTexts.flatMap(ProtectedText::fragments).distinct()
        require(fragments.isNotEmpty()) { "Aucun texte à traduire." }
        val cache = TranslationCache(File(context.noBackupFilesDir, "translation-cache/v2/$source-$target"))
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
                val value = TranslationOutput.validated(translator.translate(text))
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
        val dictionary = translatableTexts.associateWith { ProtectedText.render(it, translated) }
        val replacements = localTranslationReplacements(documents, dictionary)
        val changes = changes(documents, files, replacements)
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

    suspend fun exportManual(
        game: GameEntity, source: String, target: String, destination: Uri,
        progress: (TranslationProgress) -> Unit = {}
    ): TranslationAnalysis = withContext(Dispatchers.IO) {
        progress(TranslationProgress("Extraction des textes"))
        val dir = dataDir(game, requireWrite = false)
        val files = SafTranslationFiles(context, dir)
        check(!TranslationPatch(files).exists()) { "Restaurez les originaux avant d’exporter leurs textes." }
        val documents = load(dir, files)
        val bytes = ManualTranslationBundle.export(game.id, game.title, game.engine, source, target, documents)
        currentCoroutineContext().ensureActive()
        verifyExportDestination(dir, destination)
        val destinationIsEmpty = context.contentResolver.openInputStream(destination)?.use { it.read() == -1 }
            ?: error("Impossible de vérifier le fichier d’export.")
        require(destinationIsEmpty) { "Choisissez un nouveau fichier vide pour l’export. Aucun fichier existant ne sera remplacé." }
        context.contentResolver.openOutputStream(destination, "wt")?.use { it.write(bytes) } ?: error("Impossible d’écrire le fichier d’export.")
        val verified = context.contentResolver.openInputStream(destination)?.use { readTranslationBytes(it, ManualTranslationBundle.MAX_BYTES) }
        check(verified?.contentEquals(bytes) == true) { "Export incomplet : recommencez avec un autre emplacement." }
        analysis(documents, installed = false)
    }

    suspend fun importManual(
        game: GameEntity, source: Uri,
        progress: (TranslationProgress) -> Unit = {}
    ): TranslationAnalysis = withContext(Dispatchers.IO) {
        progress(TranslationProgress("Vérification du fichier traduit"))
        val dir = dataDir(game)
        val files = SafTranslationFiles(context, dir)
        val patch = TranslationPatch(files)
        check(!patch.exists()) { "Restaurez les originaux avant de charger une nouvelle traduction." }
        val bytes = context.contentResolver.openInputStream(source)?.use { readTranslationBytes(it, ManualTranslationBundle.MAX_BYTES) }
            ?: error("Impossible de lire le fichier traduit.")
        currentCoroutineContext().ensureActive()
        val documents = load(dir, files)
        val imported = ManualTranslationBundle.import(bytes, game.id, game.engine, documents)
        val changes = changes(documents, files, imported.replacements)
        currentCoroutineContext().ensureActive()
        if (changes.isNotEmpty()) {
            progress(TranslationProgress("Sauvegarde et application"))
            patch.apply(changes, imported.source, imported.target, imported.preserved)
        }
        analysis(documents, installed = changes.isNotEmpty(), preserved = imported.preserved)
    }

    private fun analysis(documents: List<TranslationSnapshot>, installed: Boolean, preserved: Int = 0): TranslationAnalysis {
        val texts = documents.flatMap { it.texts }.distinct()
        return TranslationAnalysis(documents.size, texts.size, texts.sumOf { it.length }, installed, preserved)
    }

    private fun verifyExportDestination(dir: DocumentFile, destination: Uri) {
        val message = "Exportez vers un nouveau fichier, hors du dossier data et des sauvegardes du jeu."
        if (dir.uri.scheme == "file" && destination.scheme == "file") {
            val root = File(requireNotNull(dir.uri.path)).canonicalFile.toPath()
            require(!File(requireNotNull(destination.path)).canonicalFile.toPath().startsWith(root)) { message }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29 && dir.uri.authority == destination.authority && DocumentsContract.isDocumentUri(context, dir.uri) &&
            DocumentsContract.isDocumentUri(context, destination)) {
            val child = runCatching { DocumentsContract.isChildDocument(context.contentResolver, dir.uri, destination) }.getOrDefault(false)
            require(!child) { message }
        }
        // Providers may omit isChildDocument support, or expose both tree and single-document URIs.
        val children = dir.listFiles()
        require(children.none { sameDocument(it.uri, destination) }) { message }
        children.firstOrNull { it.name == TranslationPatch.BACKUP && it.isDirectory }?.let { backup ->
            backup.listFiles().forEach { child ->
                require(!sameDocument(child.uri, destination)) { message }
                if (child.isDirectory && child.name in setOf("original", "translated")) {
                    require(child.listFiles().none { sameDocument(it.uri, destination) }) { message }
                }
            }
        }
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first.normalizeScheme() == second.normalizeScheme()) return true
        if (first.scheme == "file" && second.scheme == "file") {
            return File(requireNotNull(first.path)).canonicalFile == File(requireNotNull(second.path)).canonicalFile
        }
        if (first.authority != second.authority) return false
        return runCatching { DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second) }.getOrDefault(false)
    }

    private suspend fun changes(
        documents: List<TranslationSnapshot>, files: TranslationFiles, replacements: Map<String, Map<String, String>>
    ): List<TranslationPatch.Change> = documents.mapNotNull { snapshot ->
        currentCoroutineContext().ensureActive()
        // Check even unchanged sources: the exchange is bound to the complete exported game revision.
        val original = files.read(snapshot.name) ?: error("Fichier inaccessible : ${snapshot.name}")
        check(textHash(original) == snapshot.hash) { "Le jeu a changé : ${snapshot.name}" }
        val dictionary = replacements[snapshot.name].orEmpty()
        if (snapshot.entries.none { dictionary[it.path]?.let { replacement -> replacement != it.text } == true }) null
        else TranslationPatch.Change(snapshot.name, original, RpgTextDocument(snapshot.name, original).translatedEntries(dictionary))
    }

    private suspend fun load(dir: DocumentFile, files: TranslationFiles): List<TranslationSnapshot> {
        val names = dir.listFiles().filter { it.isFile && RpgTextDocument.accepts(it.name.orEmpty()) }.map { it.name!! }.sorted()
        require(names.size in 1..2000) { "Aucun fichier RPG Maker lisible, ou trop de fichiers." }
        require(names.distinct().size == names.size) { "Le dossier contient des fichiers de données portant le même nom." }
        var total = 0L
        return names.map { name ->
            currentCoroutineContext().ensureActive()
            val bytes = files.read(name) ?: error("Fichier inaccessible : $name")
            total += bytes.size
            require(total <= 64L * 1024 * 1024) { "Les textes du jeu dépassent la limite de 64 Mo." }
            // Release map tile arrays and other non-text JSON data between files.
            TranslationSnapshot(name, textHash(bytes), RpgTextDocument(name, bytes).entries)
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
            readTranslationBytes(input, RpgTextDocument.MAX_FILE_BYTES)
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
        val bytes = AtomicFile(File(directory, textHash(text.toByteArray()))).openRead().use { readTranslationBytes(it, 128 * 1024) }
        val value = TranslationJson.parse(bytes, 128 * 1024) as JSONObject
        value.getString("translation").takeIf { value.getString("source") == text && TranslationOutput.validated(it) == it }
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
