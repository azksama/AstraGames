package fr.astragames.app.core.filesystem

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException

internal data class DirectoryEntry(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val size: Long
)

private val entryColumns = arrayOf(
    Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
    Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE
)

/** Read the whole listing in one query. Only a complete listing can establish absence. */
internal fun listDirectoryEntries(context: Context, folder: Uri): List<DirectoryEntry> {
    if (folder.scheme == "file") {
        val files = File(requireNotNull(folder.path)).listFiles() ?: throw IOException("Dossier illisible.")
        return files.map { DirectoryEntry(Uri.fromFile(it), it.name, it.isDirectory, it.lastModified(), it.length()) }
    }
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, GameFolders.documentId(folder))
    val result = context.contentResolver.query(children, entryColumns, null, null, null)
        ?: throw IOException("Le fournisseur de stockage ne répond pas.")
    return result.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
        val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
        val mime = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
        val modified = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        val size = cursor.getColumnIndex(Document.COLUMN_SIZE)
        buildList {
            while (cursor.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(folder, requireNotNull(cursor.getString(id)))
                add(DirectoryEntry(
                    uri = child,
                    name = requireNotNull(cursor.getString(name)),
                    isDirectory = requireNotNull(cursor.getString(mime)) == Document.MIME_TYPE_DIR,
                    lastModified = if (modified >= 0 && !cursor.isNull(modified)) cursor.getLong(modified) else 0L,
                    size = if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size) else 0L
                ))
            }
            cursor.requireCompleteListing()
        }
    }
}

internal fun Cursor.requireCompleteListing() {
    extras.getString(DocumentsContract.EXTRA_ERROR)?.let { throw IOException(it) }
    if (extras.getBoolean(DocumentsContract.EXTRA_LOADING)) {
        throw IOException("Le contenu du dossier est encore en cours de chargement. Réessayez dans un instant.")
    }
}
