package fr.astragames.app.core.filesystem

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

class FileAccessResolver(private val context: Context) {
    fun physicalPath(treeUri: Uri, relativePath: String = ""): String? {
        if (treeUri.scheme == "file") return treeUri.path?.let { File(it, relativePath).path }
        if (treeUri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(treeUri)) return null
        val documentId = runCatching {
            if (DocumentsContract.isDocumentUri(context, treeUri)) DocumentsContract.getDocumentId(treeUri)
            else DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val base = when {
            volume.equals("primary", true) -> Environment.getExternalStorageDirectory().absolutePath
            else -> context.getExternalFilesDirs(null)
                .mapNotNull { it?.absolutePath }
                .firstOrNull { it.contains("/storage/$volume/") }
                ?.substringBefore("/Android/data/")
                ?: "/storage/$volume"
        }
        return listOf(base, parts[1], relativePath)
            .filter { it.isNotBlank() }
            .joinToString(File.separator)
    }
}
