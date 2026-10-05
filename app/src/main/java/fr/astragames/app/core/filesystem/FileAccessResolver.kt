package fr.astragames.app.core.filesystem

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

class FileAccessResolver(private val context: Context) {
    fun physicalPath(treeUri: Uri, relativePath: String = ""): String? {
        if (treeUri.scheme == "file") return treeUri.path?.let { confinedPath(File(it), relativePath) }
        if (treeUri.authority != "com.android.externalstorage.documents" || !DocumentsContract.isTreeUri(treeUri)) return null
        val documentId = runCatching {
            if (DocumentsContract.isDocumentUri(context, treeUri)) DocumentsContract.getDocumentId(treeUri)
            else DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        if (!volume.matches(Regex("[A-Za-z0-9_-]+"))) return null
        val base = when {
            volume.equals("primary", true) -> Environment.getExternalStorageDirectory().absolutePath
            else -> context.getExternalFilesDirs(null)
                .mapNotNull { it?.absolutePath }
                .firstOrNull { it.contains("/storage/$volume/") }
                ?.substringBefore("/Android/data/")
                ?: "/storage/$volume"
        }
        val selected = confinedPath(File(base), parts[1])?.let(::File) ?: return null
        return confinedPath(selected, relativePath)
    }
}

internal fun confinedPath(root: File, relative: String): String? = runCatching {
    if (File(relative).isAbsolute || relative.split('/', '\\').any { it == ".." }) return null
    val base = root.canonicalFile.toPath()
    val path = File(root, relative).canonicalFile.toPath()
    path.takeIf { it.startsWith(base) }?.toString()
}.getOrNull()
