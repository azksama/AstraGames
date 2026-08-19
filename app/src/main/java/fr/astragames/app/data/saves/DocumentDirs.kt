package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

fun documentDir(context: Context, uri: Uri): DocumentFile? {
    val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    if (tree != null && tree.isDirectory) return tree
    val single = DocumentFile.fromSingleUri(context, uri)
    if (single != null && single.isDirectory) return single
    if (DocumentsContract.isTreeUri(uri)) {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (docId != null) {
            val built = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            DocumentFile.fromSingleUri(context, built)?.takeIf { it.isDirectory }?.let { return it }
        }
    }
    return single
}
