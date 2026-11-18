package fr.astragames.app.core.filesystem

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.File

/** Compare documents, not grant URLs: two selected trees can expose the same folder. */
object GameFolders {
    fun documentId(uri: Uri): String = if (DocumentsContract.isTreeUri(uri) && uri.pathSegments.size == 2)
        DocumentsContract.getTreeDocumentId(uri) else DocumentsContract.getDocumentId(uri)

    fun same(first: String, second: String): Boolean = runCatching {
        val a = Uri.parse(first)
        val b = Uri.parse(second)
        when {
            a.scheme == "file" && b.scheme == "file" -> File(requireNotNull(a.path)).canonicalFile == File(requireNotNull(b.path)).canonicalFile
            a.scheme == "content" && b.scheme == "content" && a.authority == b.authority -> documentId(a) == documentId(b)
            else -> first == second
        }
    }.getOrDefault(first == second)

    fun contains(context: Context, parent: String, child: String): Boolean {
        if (same(parent, child)) return true
        val a = Uri.parse(parent)
        val b = Uri.parse(child)
        if (a.scheme == "file" && b.scheme == "file") return runCatching {
            File(requireNotNull(b.path)).canonicalFile.toPath().startsWith(File(requireNotNull(a.path)).canonicalFile.toPath())
        }.getOrDefault(false)
        if (a.scheme != "content" || b.scheme != "content" || a.authority != b.authority) return false
        // This provider explicitly uses volume:path IDs; other providers have opaque IDs.
        if (a.authority == "com.android.externalstorage.documents") return runCatching {
            documentId(b).startsWith(externalChildPrefix(documentId(a)))
        }.getOrDefault(false)
        return runCatching {
            val parentDocument = DocumentsContract.buildDocumentUriUsingTree(a, documentId(a))
            val childDocument = DocumentsContract.buildDocumentUriUsingTree(b, documentId(b))
            if (Build.VERSION.SDK_INT >= 29) DocumentsContract.isChildDocument(context.contentResolver, parentDocument, childDocument)
            else DocumentsContract.findDocumentPath(context.contentResolver, childDocument)?.path?.contains(documentId(a)) == true
        }.getOrDefault(false)
    }

    internal fun externalChildPrefix(id: String): String =
        if (id.substringAfter(':', id).isEmpty()) id else id.trimEnd('/') + "/"
}
