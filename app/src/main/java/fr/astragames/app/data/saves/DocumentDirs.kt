package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

fun documentDir(context: Context, uri: Uri): DocumentFile? = when {
    uri.scheme == "file" -> uri.path?.let { DocumentFile.fromFile(File(it)) }
    DocumentsContract.isTreeUri(uri) -> DocumentFile.fromTreeUri(context, uri)
    else -> null // A single-document grant cannot enumerate or create children.
}?.takeIf { it.isDirectory }

internal fun documentFile(context: Context, uri: Uri): DocumentFile? =
    if (uri.scheme == "file") uri.path?.let { DocumentFile.fromFile(File(it)) }
    else DocumentFile.fromSingleUri(context, uri)
