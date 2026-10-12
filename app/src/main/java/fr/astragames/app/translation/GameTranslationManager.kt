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
        require(game.engine in GameTextDocuments.engines) { "Traduction indisponible pour ce moteur." }
        val root = documentDir(context, game.documentUri.toUri()) ?: error("Dossier du jeu inaccessible.")
        val data = textDirectory(root, game.engine)
        return data?.takeIf { it.isDirectory && it.canRead() && (!requireWrite || it.canWrite()) }
            ?: error("Dossier de textes ${if (requireWrite) "inscriptible" else "lisible"} introuvable. Les données doivent être décompressées ; les archives protégées et scripts compilés ne sont pas modifiés.")
    }

    private fun textDirectory(root: DocumentFile, engine: String): DocumentFile? = when (engine) {
        "RPG_MAKER_MV", "RPG_MAKER_MZ" -> root.findFile("www")?.findFile("data") ?: root.findFile("data")
        "RENPY" -> root.findFile("game") ?: root.takeIf { it.listFiles().any { file -> file.isFile && file.name?.endsWith(".rpy", true) == true } }
        else -> root.findFile("Data") ?: root.findFile("data")
    }

    private fun patch(files: TranslationFiles, engine: String) = TranslationPatch(files) { GameTextDocuments.accepts(engine, it) }

    suspend fun hasBackup(game: GameEntity): Boolean = withContext(Dispatchers.IO) {
        if (game.engine !in GameTextDocuments.engines) false
        else patch(SafTranslationFiles(context, dataDir(game, requireWrite = false), game.engine), game.engine).exists()
    }

    suspend fun isLaunchSafe(game: GameEntity): Boolean = withContext(Dispatchers.IO) {
        if (game.engine !in GameTextDocuments.engines) return@withContext true
        val root = documentDir(context, game.documentUri.toUri()) ?: return@withContext true
        val data = textDirectory(root, game.engine) ?: return@withContext true
        patch(SafTranslationFiles(context, data, game.engine), game.engine).isLaunchSafe()
    }

    suspend fun analyze(game: GameEntity): TranslationAnalysis = withContext(Dispatchers.IO) {
        val dir = dataDir(game, requireWrite = false)
        val files = SafTranslationFiles(context, dir, game.engine)
        val installed = patch(files, game.engine).exists()
        if (installed) return@withContext TranslationAnalysis(0, 0, 0, true, patch(files, game.engine).preservedFragments())
        val documents = load(game, dir, files)
        val texts = documents.flatMap { it.texts }.distinct()
        TranslationAnalysis(documents.size, texts.size, texts.sumOf { it.length }, false)
    }

    /** No extraction or game JSON reads here: only cached counts and the recovery journal. */
    suspend fun savedAnalysis(game: GameEntity): TranslationAnalysis? = withContext(Dispatchers.IO) {
        val saved = analysisCache(game).summary()
        val patch = patch(SafTranslationFiles(context, dataDir(game, requireWrite = false), game.engine), game.engine)
        if (patch.exists()) (saved ?: TranslationAnalysis(0, 0, 0, true)).copy(installed = true, preservedFragments = patch.preservedFragments())
        else saved
    }

    private fun analysisCache(game: GameEntity) = TranslationAnalysisCache(File(context.noBackupFilesDir, "text-analysis/v1"), game)

    suspend fun translate(
        game: GameEntity, source: String, target: String, wifiOnly: Boolean,
        progress: (TranslationProgress) -> Unit
    ): TranslationAnalysis = withContext(Dispatchers.IO) {
        val supported = com.google.mlkit.nl.translate.TranslateLanguage.getAllLanguages()
        require(source in supported && target in supported && source != target) { "Choisissez deux langues différentes." }
        val dir = dataDir(game)
        val files = SafTranslationFiles(context, dir, game.engine)
        val patch = patch(files, game.engine)
        check(!patch.exists()) { "Restaurez les originaux avant une nouvelle traduction." }
        progress(TranslationProgress("Analyse des textes"))
        val documents = load(game, dir, files)
        val originalTexts = documents.flatMap { it.texts }.distinct()
        val protectedGroups = protectedActorNameGroups(documents)
        val translatableTexts = documents.flatMap { it.entries }.filterNot { it.identityGroup in protectedGroups }.map { it.text }.distinct()
        val fragments = translatableTexts.flatMap { ProtectedText.fragments(it, renpy = game.engine == "RENPY") }.distinct()
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
        val dictionary = translatableTexts.associateWith { ProtectedText.render(it, translated, renpy = game.engine == "RENPY") }
        val replacements = localTranslationReplacements(documents, dictionary)
        val changes = changes(game.engine, documents, files, replacements)
        if (changes.isNotEmpty()) {
            progress(TranslationProgress("Sauvegarde et application", fragments.size, fragments.size))
            // No suspension during the journaled write sequence; failures roll back synchronously.
            patch.apply(changes, source, target, preserved)
        }
        TranslationAnalysis(documents.size, originalTexts.size, originalTexts.sumOf { it.length }, changes.isNotEmpty(), preserved)
    }

    suspend fun restore(game: GameEntity) = withContext(Dispatchers.IO) {
        patch(SafTranslationFiles(context, dataDir(game), game.engine), game.engine).restore()
    }

    suspend fun exportManual(
        game: GameEntity, source: String, target: String, destination: Uri,
        progress: (TranslationProgress) -> Unit = {}
    ): TranslationAnalysis = withContext(Dispatchers.IO) {
        progress(TranslationProgress("Extraction des textes"))
        val dir = dataDir(game, requireWrite = false)
        val files = SafTranslationFiles(context, dir, game.engine)
        check(!patch(files, game.engine).exists()) { "Restaurez les originaux avant d’exporter leurs textes." }
        val documents = load(game, dir, files)
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
        val files = SafTranslationFiles(context, dir, game.engine)
        val patch = patch(files, game.engine)
        check(!patch.exists()) { "Restaurez les originaux avant de charger une nouvelle traduction." }
        val bytes = context.contentResolver.openInputStream(source)?.use { readTranslationBytes(it, ManualTranslationBundle.MAX_BYTES) }
            ?: error("Impossible de lire le fichier traduit.")
        currentCoroutineContext().ensureActive()
        val documents = load(game, dir, files)
        val imported = ManualTranslationBundle.import(bytes, game.id, game.engine, documents)
        val changes = changes(game.engine, documents, files, imported.replacements)
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
        val message = "Exportez vers un nouveau fichier, hors des données et des sauvegardes du jeu."
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
        engine: String, documents: List<TranslationSnapshot>, files: TranslationFiles, replacements: Map<String, Map<String, String>>
    ): List<TranslationPatch.Change> = documents.mapNotNull { snapshot ->
        currentCoroutineContext().ensureActive()
        // Check even unchanged sources: the exchange is bound to the complete exported game revision.
        val original = files.read(snapshot.name) ?: error("Fichier inaccessible : ${snapshot.name}")
        check(textHash(original) == snapshot.hash) { "Le jeu a changé : ${snapshot.name}" }
        val dictionary = replacements[snapshot.name].orEmpty()
        if (snapshot.entries.none { dictionary[it.path]?.let { replacement -> replacement != it.text } == true }) null
        else TranslationPatch.Change(snapshot.name, original, GameTextDocuments.read(engine, snapshot.name, original).translatedEntries(dictionary))
    }

    private suspend fun load(game: GameEntity, dir: DocumentFile, files: TranslationFiles): List<TranslationSnapshot> {
        val names = textFiles(dir, game.engine)
        require(names.size in 1..2000) { "Aucun texte source reconnu, ou trop de fichiers. Les archives et scripts compilés ne sont pas pris en charge." }
        require(names.distinct().size == names.size) { "Le dossier contient des fichiers de données portant le même nom." }
        val cache = analysisCache(game)
        val cached = cache.snapshots()
        var total = 0L
        val documents = names.map { name ->
            currentCoroutineContext().ensureActive()
            val bytes = files.read(name) ?: error("Fichier inaccessible : $name")
            total += bytes.size
            require(total <= 64L * 1024 * 1024) { "Les textes du jeu dépassent la limite de 64 Mo." }
            // Release map tile arrays and other non-text JSON data between files.
            val hash = textHash(bytes)
            cached[name]?.takeIf { it.hash == hash } ?: TranslationSnapshot(name, hash, GameTextDocuments.read(game.engine, name, bytes).entries)
        }
        currentCoroutineContext().ensureActive()
        cache.save(documents)
        return documents
    }

    private suspend fun textFiles(root: DocumentFile, engine: String): List<String> {
        val result = mutableListOf<String>()
        var visited = 0
        suspend fun visit(directory: DocumentFile, prefix: String, depth: Int) {
            require(depth <= 7) { "Arborescence de textes trop profonde." }
            currentCoroutineContext().ensureActive()
            directory.listFiles().forEach { child ->
                require(++visited <= 50_000) { "Trop de fichiers à analyser." }
                val name = child.name ?: return@forEach
                val path = prefix + name
                if (child.isFile && GameTextDocuments.accepts(engine, path)) result += path
                else if (child.isDirectory && when (engine) {
                    "WOLF_RPG" -> depth == 0 && name in setOf("BasicData", "MapData")
                    "RENPY" -> !name.startsWith('.') && name.lowercase() !in setOf("saves", "cache", "tl", "images", "audio", "music", "video", "fonts")
                    else -> false
                }) visit(child, "$path/", depth + 1)
                require(result.size <= 2000) { "Trop de fichiers de textes." }
            }
        }
        visit(root, "", 0)
        return result.sorted()
    }
}

internal class SafTranslationFiles(private val context: Context, private val root: DocumentFile, private val engine: String = "RPG_MAKER_MV") : TranslationFiles {
    private fun locate(path: String, create: Boolean = false): DocumentFile? {
        val parts = path.split('/')
        require(path.length <= 768 && parts.size <= 10 && parts.all {
            it.isNotBlank() && it != "." && it != ".." && it.none { c -> c < ' ' || c in "\\:" }
        })
        require(GameTextDocuments.accepts(engine, path) ||
            parts[0] == TranslationPatch.BACKUP && (parts.size == 2 && parts[1] in setOf("manifest.json", "pending") ||
                parts.size >= 3 && parts[1] in setOf("original", "translated") && GameTextDocuments.accepts(engine, parts.drop(2).joinToString("/"))))
        var parent = root
        parts.dropLast(1).forEach { part ->
            parent = parent.findFile(part) ?: if (create) parent.createDirectory(part) ?: error("Création du dossier impossible.") else return null
            check(parent.isDirectory)
            verifyInsideRoot(parent)
        }
        val file = parent.findFile(parts.last())
        if (file != null) { check(file.isFile); verifyInsideRoot(file); return file }
        // Never silently recreate a missing game file, except the interrupted journaled write target.
        if (!create) return null
        val name = parts.last()
        val mime = if (name.endsWith(".json")) "application/json" else "application/octet-stream"
        val created = parent.createFile(mime, name) ?: error("Création du fichier impossible.")
        // RawDocumentFile and some providers append the MIME extension (for example pending.bin).
        if (created.name != name) check(created.renameTo(name)) { "Nom du fichier refusé : $name" }
        return parent.findFile(name)?.takeIf { it.isFile } ?: error("Fichier créé introuvable : $name")
    }
    private fun verifyInsideRoot(file: DocumentFile) {
        if (root.uri.scheme == "file" && file.uri.scheme == "file") {
            val directory = File(requireNotNull(root.uri.path)).canonicalFile.toPath()
            require(File(requireNotNull(file.uri.path)).canonicalFile.toPath().startsWith(directory)) { "Fichier de texte hors du dossier du jeu." }
        }
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
