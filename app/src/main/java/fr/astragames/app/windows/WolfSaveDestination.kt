package fr.astragames.app.windows

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A false result retains both versions and keeps the private game playable. */
internal sealed interface WolfSaveDestination {
    suspend fun recover(file: File, path: String, previous: String?, local: String): Boolean
}

/** No DocumentFile lookup is needed when the original directory is already accessible. */
internal class WolfFileSaveDestination(private val root: File) : WolfSaveDestination {
    override suspend fun recover(file: File, path: String, previous: String?, local: String): Boolean {
        val target = safeFile(root, path)
        if (target.exists() && !target.isFile) return false
        val incoming = if (target.isFile) wolfSaveHash(target) else null
        if (incoming == local) return true
        if (incoming != previous) return false
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Écriture du dossier impossible ; sauvegardes conservées dans Astra." }
        val staged = File.createTempFile(".astra-wolf-", ".tmp", parent)
        var backup: File? = null
        var committed = false
        try {
            check(writeSave(file, staged) == local) { "La sauvegarde locale a changé pendant sa récupération. Relancez le jeu." }
            if (target.isFile) {
                backup = File.createTempFile(".astra-wolf-backup-", "-${target.name}", parent)
                // Copy the backup, keeping the original in place until the atomic replacement.
                if (writeSave(target, backup) != previous) return false
            }
            coroutineContext.ensureActive()
            if ((if (target.isFile) wolfSaveHash(target) else null) != previous) return false
            // Same-directory rename: readers see the old complete file or the new complete file.
            check(staged.renameTo(target)) { "Finalisation impossible ; sauvegarde conservée dans Astra." }
            committed = true
            return true
        } finally {
            staged.delete()
            if (!committed) backup?.delete()
        }
    }

    private suspend fun writeSave(source: File, target: File): String = source.inputStream().use { input ->
        FileOutputStream(target).use { output ->
            copySave(input, output).also { output.fd.sync() }
        }
    }
}

/** Cloud sources query names and types in batches, instead of findFile/getName for every child. */
internal class WolfDocumentSaveDestination(private val context: Context, private val root: Uri) : WolfSaveDestination {
    private data class Document(val uri: Uri, val directory: Boolean)
    private val folders = HashMap<Uri, MutableMap<String, Document>>()

    override suspend fun recover(file: File, path: String, previous: String?, local: String): Boolean {
        val segments = path.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." || '\\' in it || '\u0000' in it })
        var parent = root
        for (name in segments.dropLast(1)) {
            val children = children(parent)
            val child = children[name] ?: Document(requireNotNull(DocumentsContract.createDocument(context.contentResolver,
                parent, DocumentsContract.Document.MIME_TYPE_DIR, name)) { "Écriture du dossier impossible." }, true)
                .also { children[name] = it }
            check(child.directory) { "Un fichier empêche la récupération du dossier ; sauvegardes conservées dans Astra." }
            parent = child.uri
        }
        val name = segments.last()
        val existing = children(parent)[name]
        if (existing?.directory == true) return false
        val incoming = existing?.let { hash(it.uri) }
        if (incoming == local) return true
        if (incoming != previous) return false
        var staged: Uri? = requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent,
            "application/octet-stream", ".astra-wolf-${UUID.randomUUID()}")) { "Création de la sauvegarde impossible." }
        var backup: Uri? = null
        try {
            val written = requireNotNull(context.contentResolver.openOutputStream(staged!!, "wt")).use { output ->
                file.inputStream().use { copySave(it, output) }
            }
            check(written == local && hash(staged!!) == local) { "Vérification de la sauvegarde impossible ; copie conservée dans Astra." }
            // Recheck only modified files before replacing them, including newly created sources.
            // A folder snapshot is a lookup cache, never authority for overwriting an external edit.
            val current = children(parent, reload = true)[name]
            if (current?.directory == true) return false
            val currentHash = current?.let { hash(it.uri) }
            if (currentHash == local) return true
            if (currentHash != previous) return false
            coroutineContext.ensureActive()
            if (current != null) backup = requireNotNull(DocumentsContract.renameDocument(context.contentResolver,
                current.uri, ".astra-wolf-backup-${UUID.randomUUID()}-$name")) {
                "Le fournisseur ne permet pas une sauvegarde sûre. La copie reste dans Astra."
            }
            val destination = requireNotNull(DocumentsContract.renameDocument(context.contentResolver, staged!!, name)) {
                "Finalisation impossible ; sauvegarde conservée dans Astra."
            }
            staged = null
            folders.getValue(parent)[name] = Document(destination, false)
            return true
        } catch (error: Exception) {
            backup?.let { uri ->
                runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, name) }
                    .onFailure { error.addSuppressed(it) }
            }
            throw error
        } finally {
            staged?.let { uri -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
        }
    }

    private suspend fun hash(uri: Uri) = requireNotNull(context.contentResolver.openInputStream(uri)).use { copySave(it) }

    private suspend fun children(parent: Uri, reload: Boolean = false): MutableMap<String, Document> {
        if (!reload) folders[parent]?.let { return it }
        val result = suspendCancellableCoroutine<MutableMap<String, Document>> { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                val query = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
                val documents = requireNotNull(context.contentResolver.query(query, columns, null, null, null, signal)) {
                    "Impossible de lire le dossier source ; sauvegardes conservées dans Astra."
                }.use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            signal.throwIfCanceled()
                            val name = requireNotNull(cursor.getString(1))
                            val uri = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                            put(name, Document(uri, cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR))
                        }
                    }.toMutableMap()
                }
                continuation.resume(documents)
            } catch (error: Exception) { continuation.resumeWithException(error) }
        }
        folders[parent] = result
        return result
    }
}

internal suspend fun wolfSaveHash(file: File): String = file.inputStream().use { copySave(it) }

/** Hash the bytes actually read or written, and allow cancellation even during a large save. */
private suspend fun copySave(input: InputStream, output: OutputStream? = null): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
        output?.write(buffer, 0, count)
    }
    return digest.digest().hexString()
}
