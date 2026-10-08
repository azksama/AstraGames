package fr.astragames.app.windows

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.saves.documentDir
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
class WolfGameStorage(private val context: Context, gameId: String, private val sourceUri: String) {
    val directory = File(context.filesDir, "wolf-games/${MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }}")
    val game = File(directory, "game")
    val prefix = File(directory, "prefix")
    private val manifest = File(directory, "source-hashes.json")
    private var hashes = JSONObject()

    suspend fun prepare(progress: (String) -> Unit): File = withContext(Dispatchers.IO) {
        hashes = if (manifest.isFile) JSONObject(manifest.readText()) else JSONObject()
        game.mkdirs()
        val source = requireNotNull(documentDir(context, sourceUri.toUri())) { "Le dossier du jeu est inaccessible." }
        check(source.canRead()) { "Autorisation du dossier expirée." }
        var count = 0
        var lastProgress = android.os.SystemClock.elapsedRealtime()
        progress("Préparation du jeu : copie privée des fichiers")
        suspend fun copy(folder: Uri, relative: String, depth: Int) {
            check(depth <= 64) { "Arborescence du jeu trop profonde." }
            for (child in children(folder)) {
                coroutineContext.ensureActive()
                val name = child.name
                require(name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name)
                if (name.startsWith(".astra-")) continue
                val path = if (relative.isEmpty()) name else "$relative/$name"
                val target = safeFile(game, path)
                if (child.isDirectory) { target.mkdirs(); copy(child.uri, path, depth + 1) }
                else if (child.isFile) {
                    check(++count <= 100_000) { "Trop de fichiers dans le jeu." }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastProgress >= 1000) {
                        progress("Préparation du jeu : $count fichiers")
                        lastProgress = now
                    }
                    val localHash = if (target.isFile) WolfRuntimeInstaller.hash(target) else null
                    val previous = hashes.optString(path).takeIf(String::isNotEmpty)
                    // Preserve unsynchronized writes from a crash or revoked source permission.
                    if (localHash != null && localHash != previous) continue
                    target.parentFile!!.mkdirs()
                    val pending = File(directory, "import.tmp")
                    requireNotNull(context.contentResolver.openInputStream(child.uri)).use { input ->
                        pending.outputStream().use { output -> input.copyTo(output) }
                    }
                    val incoming = WolfRuntimeInstaller.hash(pending)
                    if (incoming == localHash) pending.delete()
                    else check(pending.renameTo(target)) { "Espace insuffisant pour préparer le jeu." }
                    hashes.put(path, incoming)
                    if (count % 2048 == 0) saveManifest()
                }
            }
        }
        try { copy(source.uri, "", 0) } finally { saveManifest() }
        progress("Préparation terminée : $count fichiers")
        // Apply the software renderer only to the private copy. Game.ini stays local.
        val ini = game.listFiles()?.firstOrNull { it.name.equals("Game.ini", true) } ?: File(game, "Game.ini")
        if (!ini.exists()) ini.writeText("[DEFAULT]\r\nSoftModeFlag=1\r\nWindowModeFlag=1\r\n")
        else {
            val charset = Charsets.ISO_8859_1
            val text = ini.readText(charset).replace(Regex("(?m)^SoftModeFlag=\\d+"), "SoftModeFlag=1")
                .replace(Regex("(?m)^WindowModeFlag=\\d+"), "WindowModeFlag=1")
            ini.writeText(text, charset)
        }
        game
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
        val source = documentDir(context, sourceUri.toUri()) ?: error("Dossier source inaccessible ; sauvegardes conservées dans Astra.")
        check(source.canWrite()) { "Dossier source en lecture seule ; sauvegardes conservées dans Astra." }
        var conflicts = 0
        for (file in game.walkTopDown().filter { it.isFile }) {
            coroutineContext.ensureActive()
            val path = file.relativeTo(game).invariantSeparatorsPath
            if (path.equals("Game.ini", true) || path.endsWith(".exe", true) || path.endsWith(".dll", true)) continue
            safeFile(game, path) // Reject links that escape the working copy.
            val local = WolfRuntimeInstaller.hash(file)
            val previous = hashes.optString(path).takeIf(String::isNotEmpty)
            if (local == previous) continue
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
            if (incoming == local) { hashes.put(path, local); continue }
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
                saveManifest()
            } catch (error: Exception) { staged.delete(); throw error }
        }
        saveManifest()
        conflicts
    }
    private fun saveManifest() {
        directory.mkdirs()
        val temporary = File(directory, "source-hashes.new").apply { writeText(hashes.toString()) }
        check(temporary.renameTo(manifest))
    }
    suspend fun exportSaves(output: OutputStream) = withContext(Dispatchers.IO) {
        ZipOutputStream(output).use { zip ->
            for (file in game.walkTopDown().filter { it.isFile }) {
                coroutineContext.ensureActive()
                val path = file.relativeTo(game).invariantSeparatorsPath
                safeFile(game, path)
                if (path.equals("Game.ini", true) || path.endsWith(".exe", true) || path.endsWith(".dll", true)) continue
                val likelySave = path.startsWith("Save/", true) || file.extension.lowercase() in setOf("sav", "save")
                if (!likelySave && WolfRuntimeInstaller.hash(file) == hashes.optString(path)) continue
                zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}

internal fun safeFile(root: File, relative: String): File {
    require(relative.isNotBlank() && !File(relative).isAbsolute && relative.split('/', '\\').none { it == ".." || it == "." } && '\u0000' !in relative)
    val file = File(root, relative).canonicalFile
    require(file.toPath().startsWith(root.canonicalFile.toPath())) { "Le fichier sort du dossier du jeu." }
    return file
}
