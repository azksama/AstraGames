package fr.astragames.app.data.local

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** Debug variant only: exercises real content:// child URIs, stream truncation and provider renames. */
class FixtureDocumentsProvider : DocumentsProvider() {
    private val root get() = File(context!!.filesDir, "fixtures").apply { mkdirs() }
    private val documentQueries = java.util.concurrent.atomic.AtomicInteger()
    private val childQueries = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var failingRename: String? = null
    override fun onCreate() = true
    private fun file(id: String): File = File(root, id).also {
        require(it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == "resetQueryStats") {
            documentQueries.set(0); childQueries.set(0)
            return Bundle()
        }
        if (method == "queryStats") return Bundle().apply {
            putInt("documents", documentQueries.get()); putInt("children", childQueries.get())
        }
        if (method == "failNextRename") {
            failingRename = arg
            return Bundle()
        }
        if (method == "fixture") {
            val id = arg!!
            val directory = file(id).apply { mkdirs() }
            File(directory, "game/save").mkdirs()
            File(directory, "game/save/file1.rpgsave").writeText("""{"party":{"gold":120}}""")
            File(directory, "game/script.rpy").writeText("original")
            File(directory, "mod/files").mkdirs()
            File(directory, "mod/files/script.rpy").writeText("mod")
            val uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, id)
            context!!.grantUriPermission(context!!.packageName, uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            return Bundle().apply { putString("uri", uri.toString()) }
        }
        if (method == "removeFixture") {
            context!!.revokeUriPermission(DocumentsContract.buildTreeDocumentUri(AUTHORITY, arg!!),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            file(arg).deleteRecursively()
            return Bundle()
        }
        return super.call(method, arg, extras)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        file(documentId).canonicalFile.toPath().startsWith(file(parentDocumentId).canonicalFile.toPath())

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        documentQueries.incrementAndGet()
        return MatrixCursor(projection ?: COLUMNS).apply { row(this, file(documentId)) }
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries.incrementAndGet()
        return MatrixCursor(projection ?: COLUMNS).apply { file(parentDocumentId).listFiles().orEmpty().forEach { row(this, it) } }
    }

    private fun row(cursor: MatrixCursor, file: File) {
        val values = mapOf(
            Document.COLUMN_DOCUMENT_ID to file.relativeTo(root).invariantSeparatorsPath,
            Document.COLUMN_DISPLAY_NAME to file.name,
            Document.COLUMN_MIME_TYPE to if (file.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.COLUMN_FLAGS to (Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
                if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0),
            Document.COLUMN_SIZE to file.length(), Document.COLUMN_LAST_MODIFIED to file.lastModified()
        )
        cursor.addRow(cursor.columnNames.map { values[it] })
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(!displayName.contains('/') && displayName != "..")
        val child = File(file(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) check(child.mkdir()) else check(child.createNewFile())
        return child.relativeTo(root).invariantSeparatorsPath
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).deleteRecursively()) }
    override fun renameDocument(documentId: String, displayName: String): String {
        val old = file(documentId)
        if (old.parentFile!!.relativeTo(root).invariantSeparatorsPath + "/" + displayName == failingRename) {
            failingRename = null
            throw java.io.IOException("Fixture: final rename rejected")
        }
        val renamed = File(old.parentFile, displayName)
        check(old.renameTo(renamed))
        return renamed.relativeTo(root).invariantSeparatorsPath
    }

    companion object {
        const val AUTHORITY = "fr.astragames.app.test.storage"
        private val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    }
}
