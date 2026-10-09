package fr.astragames.app.windows

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.core.filesystem.FileAccessResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** Persistent working copy. A interrupted game never loses its locally written saves. */
class WolfGameStorage(private val context: Context, gameId: String, private val sourceUri: String,
                      private val preferDirect: Boolean = true) {
    val directory = File(context.filesDir, "wolf-games/${MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }}")
    private val copiedGame = File(directory, "game")
    var game: File = copiedGame
        private set
    val isDirect get() = game != copiedGame
    val prefix = File(directory, "prefix")
    private val manifest = File(directory, "source-hashes.json")
    private val cache = File(directory, "import-state.json")
    private val refresh = File(directory, "refresh-requested")
    private val workingMode = File(directory, "working-mode.json")
    private val configuration = WolfDirectConfiguration(directory)
    private var hashes = JSONObject()
    private var stamps = JSONObject()

    /** Explicit asset updates never erase the working copy or pending saves. */
    fun requestRefresh() { directory.mkdirs(); refresh.writeText("1") }

    suspend fun prepare(executable: String? = null, progress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        val started = android.os.SystemClock.elapsedRealtime()
        hashes = if (manifest.isFile) JSONObject(manifest.readText()) else JSONObject()
        val state = if (cache.isFile) JSONObject(cache.readText()) else JSONObject()
        stamps = state.optJSONObject("stamps") ?: JSONObject()
        game = copiedGame
        configuration.restore()
        val source = requireNotNull(documentDir(context, sourceUri.toUri())) { "Le dossier du jeu est inaccessible." }
        check(source.canRead()) { "Autorisation du dossier expirée." }
        val lastMode = readWorkingMode()
        val direct = if (preferDirect) directDirectory(executable) else null
        if (direct != null) {
            // Migrate pending private writes once, before changing the working directory.
            // If both copies changed, keep the private version playable and exportable.
            val migrated = lastMode.optString("mode") == "DIRECT" && lastMode.optString("source") == sourceUri
            val conflicts = if (!migrated && manifest.isFile && copiedGame.isDirectory) synchronize() else 0
            if (conflicts == 0) {
                configuration.apply(direct, executable)
                game = direct
                writeWorkingMode("DIRECT")
                refresh.delete()
                progress("Dossier d’origine · aucun transfert du jeu")
                return@withContext game
            }
            progress("$conflicts conflit(s) de sauvegarde · copie privée conservée")
        } else if (preferDirect) progress("Accès direct indisponible · copie privée utilisée")
        if (lastMode.optString("mode") == "DIRECT") requestRefresh()
        game.mkdirs()
        // Older releases already completed a full import before creating the Wine prefix.
        // Reuse that copy; missing stamps are checked by hash on the first synchronization.
        val legacyReady = !cache.exists() && manifest.isFile && File(prefix, ".astra-ready").isFile
        val ready = (state.optBoolean("complete") && state.optString("source") == sourceUri || legacyReady) &&
            !refresh.exists() && (executable == null || safeFile(game, executable).isFile)
        val saveFolders = hashes.keys().asSequence().filter { isSave(it) }.map { it.substringBeforeLast('/', "") }.toSet()
        val imported = HashSet<String>()
        var count = 0
        var lastProgress = android.os.SystemClock.elapsedRealtime()
        progress(if (ready) "Copie du jeu prête · vérification des sauvegardes" else "Import du jeu · nécessaire une seule fois")
        suspend fun copy(folder: Uri, relative: String, depth: Int, savesOnly: Boolean) {
            check(depth <= 64) { "Arborescence du jeu trop profonde." }
            for (child in children(folder)) {
                coroutineContext.ensureActive()
                val name = child.name
                require(name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name)
                if (name.startsWith(".astra-")) continue
                val path = if (relative.isEmpty()) name else "$relative/$name"
                val target = safeFile(game, path)
                if (child.isDirectory) {
                    val saveDirectory = isSaveFolder(name)
                    if (!savesOnly || saveDirectory || saveFolders.any { it == path || it.startsWith("$path/") }) {
                        target.mkdirs(); copy(child.uri, path, depth + 1, savesOnly && !saveDirectory)
                    }
                }
                else if (child.isFile && (!savesOnly || isSave(path))) {
                    if (!ready) imported.add(path)
                    check(++count <= 100_000) { "Trop de fichiers dans le jeu." }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 1000) {
                        progress("Préparation du jeu : $count fichiers")
                        lastProgress = now
                    }
                    val previous = hashes.optString(path).takeIf(String::isNotEmpty)
                    val localHash = if (target.isFile) {
                        if (!isSave(path) && stamps.optString(path) == stamp(target)) previous
                        else WolfRuntimeInstaller.hash(target)
                    } else null
                    // Preserve unsynchronized writes from a crash or revoked source permission.
                    if (localHash != null && localHash != previous) continue
                    target.parentFile!!.mkdirs()
                    val pending = File(directory, "import.tmp")
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        requireNotNull(context.contentResolver.openInputStream(child.uri)).use { input ->
                            java.security.DigestInputStream(input, digest).use { hashed ->
                                pending.outputStream().use { output -> hashed.copyTo(output, 65536) }
                            }
                        }
                        val incoming = digest.digest().hexString()
                        if (incoming == localHash) pending.delete()
                        else check(pending.renameTo(target)) { "Espace insuffisant pour préparer le jeu." }
                        hashes.put(path, incoming)
                        stamps.put(path, stamp(target))
                    } finally {
                        pending.delete()
                    }
                    if (count % 2048 == 0) saveManifest()
                }
            }
        }
        // Invalidate before any full refresh, so an interrupted import is resumed next time.
        saveState(ready)
        try { copy(source.uri, "", 0, ready) } finally { saveManifest() }
        if (!ready) {
            // A removed mod must not remain active in the cached copy. Never remove a save
            // or anything changed locally, and never clean up after an incomplete import.
            for (path in hashes.keys().asSequence().toList()) {
                if (path in imported || isSave(path) || path.substringAfterLast('/').equals("Game.ini", true)) continue
                val local = safeFile(game, path)
                if (local.isFile && WolfRuntimeInstaller.hash(local) == hashes.optString(path)) {
                    check(local.delete()) { "Actualisation impossible : $path" }
                    hashes.remove(path); stamps.remove(path)
                }
            }
            saveManifest()
        }
        saveState(true)
        refresh.delete()
        progress(if (ready) "Jeu prêt en ${android.os.SystemClock.elapsedRealtime() - started} ms · $count fichiers de sauvegarde vérifiés"
            else "Préparation terminée : $count fichiers")
        // Apply the software renderer only to the private copy. Game.ini stays local.
        val executableDirectory = executable?.let { safeFile(game, it).parentFile } ?: game
        val ini = executableDirectory.listFiles()?.firstOrNull { it.name.equals("Game.ini", true) } ?: File(executableDirectory, "Game.ini")
        val original = if (ini.exists()) ini.readText(Charsets.ISO_8859_1) else ""
        val configured = wolfSoftwareConfiguration(original)
        if (configured != original) ini.writeText(configured, Charsets.ISO_8859_1)
        writeWorkingMode("COPY")
        game
    }

    private fun isSave(path: String) = path.split('/').dropLast(1).any { isSaveFolder(it) } ||
        path.substringAfterLast('.').lowercase() in setOf("sav", "save")

    private fun isSaveFolder(name: String) = name.lowercase() in setOf("save", "saves", "savedata", "savefiles")

    private fun directDirectory(executable: String?): File? = runCatching {
        val root = File(requireNotNull(FileAccessResolver(context).physicalPath(sourceUri.toUri()))).canonicalFile
        check(root.isDirectory && root.canRead() && root.canWrite())
        requireNotNull(root.list())
        if (executable != null) safeFile(root, executable).inputStream().use { it.read() }
        // Permission flags can be optimistic on Android's shared-storage filesystem.
        val probe = File(root, ".astra-wolf-access-${java.util.UUID.randomUUID()}")
        try { probe.outputStream().use { it.write(1) } } finally { probe.delete() }
        root
    }.getOrNull()

    private fun readWorkingMode() = runCatching { JSONObject(workingMode.readText()) }.getOrDefault(JSONObject())
    private fun writeWorkingMode(mode: String) {
        directory.mkdirs()
        val pending = File(directory, "working-mode.new").apply {
            writeText(JSONObject().put("source", sourceUri).put("mode", mode).toString())
        }
        check(pending.renameTo(workingMode))
    }

    internal fun restoreLastLocation() {
        val last = readWorkingMode()
        if (last.optString("mode") == "DIRECT" && last.optString("source") == sourceUri) {
            val path = FileAccessResolver(context).physicalPath(sourceUri.toUri())
            game = path?.let(::File)?.takeIf { it.isDirectory && it.canRead() }
                ?: error("Le dossier d’origine est inaccessible. Réautorisez-le pour retrouver les sauvegardes.")
        }
        hashes = if (manifest.isFile) JSONObject(manifest.readText()) else JSONObject()
        stamps = runCatching { JSONObject(cache.readText()).optJSONObject("stamps") }.getOrNull() ?: JSONObject()
    }

    internal fun restoreSourceConfiguration() = configuration.restore()

    internal suspend fun captureWindowsSaveBaseline() = withContext(Dispatchers.IO) {
        windowsSaveFiles() // Retain writes from an interrupted earlier session before resetting the baseline.
        val data = JSONObject()
        windowsUserFiles().forEach { data.put(it.relativeTo(prefix).invariantSeparatorsPath, stamp(it)) }
        directory.mkdirs()
        File(directory, "windows-save-baseline.json").writeText(data.toString())
    }

    private fun windowsUserFiles(): Sequence<File> {
        val users = File(prefix, "drive_c/users")
        return users.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
    }

    internal data class SaveFile(val file: File, val path: String)

    internal suspend fun saveFiles(): List<SaveFile> = withContext(Dispatchers.IO) {
        restoreLastLocation()
        val saves = mutableListOf<SaveFile>()
        for (file in game.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.filter { it.isFile }) {
            coroutineContext.ensureActive()
            val path = file.relativeTo(game).invariantSeparatorsPath
            if (path.split('/').any { it.startsWith(".astra-") } || file.name.equals("Game.ini", true) ||
                file.extension.lowercase() in setOf("exe", "dll") || java.nio.file.Files.isSymbolicLink(file.toPath())) continue
            val known = isSave(path)
            val changed = !isDirect && stamps.optString(path) != stamp(file) &&
                WolfRuntimeInstaller.hash(file) != hashes.optString(path)
            if (known || changed) saves += SaveFile(safeFile(game, path), path)
        }
        saves += windowsSaveFiles()
        saves.sortedBy { it.path }
    }

    private fun windowsSaveFiles(): List<SaveFile> {
        val saves = mutableListOf<SaveFile>()
        val baselineFile = File(directory, "windows-save-baseline.json")
        val baseline = runCatching { JSONObject(baselineFile.readText()) }.getOrDefault(JSONObject())
        val trackedFile = File(directory, "windows-save-paths.json")
        val tracked = runCatching { JSONObject(trackedFile.readText()) }.getOrDefault(JSONObject())
        for (file in windowsUserFiles()) {
            val path = file.relativeTo(prefix).invariantSeparatorsPath
            // An older app version may never have observed a save outside the game folder.
            // Each prefix belongs to one game, so retain its existing user data as well.
            val userData = !file.name.equals("desktop.ini", true) &&
                !path.lowercase().contains("/microsoft/windows/") && file.extension.lowercase() !in setOf("lnk", "url", "tmp", "log")
            if (isSave(path) || userData || tracked.has(path) || (baselineFile.isFile && baseline.optString(path) != stamp(file))) {
                tracked.put(path, true)
                saves += SaveFile(safeFile(prefix, path), "Windows/$path")
            }
        }
        if (tracked.length() > 0) {
            directory.mkdirs()
            val pending = File(directory, "windows-save-paths.new").apply { writeText(tracked.toString()) }
            check(pending.renameTo(trackedFile))
        }
        return saves
    }
    private fun stamp(file: File) = "${file.length()}:${file.lastModified()}"
    private fun saveState(complete: Boolean) {
        val state = JSONObject().put("source", sourceUri).put("complete", complete).put("stamps", stamps)
        val pending = File(directory, "import-state.new").apply { writeText(state.toString()) }
        check(pending.renameTo(cache))
    }

    private data class Entry(val uri: Uri, val name: String, val isDirectory: Boolean, val isFile: Boolean)

    /** Fetch names and types in one provider query per folder, not several queries per file. */
    private fun children(folder: Uri): List<Entry> {
        if (folder.scheme == "file") return requireNotNull(File(requireNotNull(folder.path)).listFiles()) {
            "Impossible de lire le dossier du jeu."
        }.map { Entry(Uri.fromFile(it), it.name, it.isDirectory, it.isFile) }
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getDocumentId(folder))
        return requireNotNull(context.contentResolver.query(query, columns, null, null, null)) {
            "Impossible de lire le dossier du jeu."
        }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2)
                    val directory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    add(Entry(DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)),
                        requireNotNull(cursor.getString(1)), directory, !directory && !mime.isNullOrEmpty()))
                }
            }
        }
    }

    /** Never overwrites a source edited since import. Conflicting saves remain local. */
    suspend fun synchronize(): Int = withContext(Dispatchers.IO) {
        if (isDirect) return@withContext 0 // The game already writes into its original folder.
        val source = documentDir(context, sourceUri.toUri()) ?: error("Dossier source inaccessible ; sauvegardes conservées dans Astra.")
        check(source.canWrite()) { "Dossier source en lecture seule ; sauvegardes conservées dans Astra." }
        var conflicts = 0
        for (file in game.walkTopDown().filter { it.isFile }) {
            coroutineContext.ensureActive()
            val path = file.relativeTo(game).invariantSeparatorsPath
            if (file.name.equals("Game.ini", true) || path.endsWith(".exe", true) || path.endsWith(".dll", true)) continue
            safeFile(game, path) // Reject links that escape the working copy.
            // Game saves are always hashed; asset contents are read only after a metadata change.
            if (!isSave(path) && stamps.optString(path) == stamp(file)) continue
            val local = WolfRuntimeInstaller.hash(file)
            val previous = hashes.optString(path).takeIf(String::isNotEmpty)
            if (local == previous) { stamps.put(path, stamp(file)); continue }
            var parent = source
            val segments = path.split('/')
            for (name in segments.dropLast(1)) parent = parent.findFile(name) ?: parent.createDirectory(name) ?: error("Écriture du dossier impossible.")
            val name = segments.last()
            val existing = parent.findFile(name)
            val incoming = existing?.let { doc ->
                val temp = File(directory, "source-save.tmp")
                requireNotNull(context.contentResolver.openInputStream(doc.uri)).use { input -> temp.outputStream().use { input.copyTo(it) } }
                WolfRuntimeInstaller.hash(temp)
            }
            if (incoming == local) { hashes.put(path, local); stamps.put(path, stamp(file)); continue }
            if (incoming != previous) { conflicts++; continue }
            val staged = parent.createFile("application/octet-stream", ".astra-wolf-${System.nanoTime()}") ?: error("Création de la sauvegarde impossible.")
            try {
                requireNotNull(context.contentResolver.openOutputStream(staged.uri, "wt")).use { output -> file.inputStream().use { it.copyTo(output) } }
                val backup = ".astra-wolf-backup-${System.nanoTime()}-$name"
                if (existing != null) check(existing.renameTo(backup)) { "Le fournisseur ne permet pas une sauvegarde sûre. La copie reste dans Astra." }
                if (!staged.renameTo(name)) {
                    existing?.renameTo(name)
                    error("Finalisation impossible ; sauvegarde conservée dans Astra.")
                }
                // Keep the previous source version as a hidden backup.
                hashes.put(path, local)
                stamps.put(path, stamp(file))
                saveManifest()
            } catch (error: Exception) { staged.delete(); throw error }
        }
        saveManifest()
        // Do not certify an incomplete import after a failed preparation.
        val complete = cache.isFile && JSONObject(cache.readText()).optBoolean("complete")
        saveState(complete)
        conflicts
    }
    private fun saveManifest() {
        directory.mkdirs()
        val temporary = File(directory, "source-hashes.new").apply { writeText(hashes.toString()) }
        check(temporary.renameTo(manifest))
    }
    suspend fun exportSaves(output: OutputStream) = withContext(Dispatchers.IO) {
        val files = saveFiles()
        check(files.isNotEmpty()) { "Aucune sauvegarde repérée pour ce jeu. Faites une sauvegarde dans le jeu puis fermez la partie." }
        ZipOutputStream(output).use { zip ->
            for ((file, path) in files) {
                coroutineContext.ensureActive()
                zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

/** Wolf's flat Game.ini can omit these keys. Replacing existing keys alone leaves 3D enabled. */
internal fun wolfSoftwareConfiguration(original: String): String {
    var text = original
    for (key in listOf("SoftModeFlag", "WindowModeFlag")) {
        val pattern = Regex("(?mi)^([ \\t]*)$key[ \\t]*=[ \\t]*\\d+")
        text = if (pattern.containsMatchIn(text)) text.replace(pattern, "$key=1")
            else text.trimEnd('\r', '\n') + (if (text.isEmpty()) "" else "\r\n") + "$key=1\r\n"
    }
    return text
}

internal fun safeFile(root: File, relative: String): File {
    require(relative.isNotBlank() && !File(relative).isAbsolute && relative.split('/', '\\').none { it == ".." || it == "." } && '\u0000' !in relative)
    val file = File(root, relative).canonicalFile
    require(file.toPath().startsWith(root.canonicalFile.toPath())) { "Le fichier sort du dossier du jeu." }
    return file
}
