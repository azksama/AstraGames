package fr.astragames.app.data.scanner

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.core.filesystem.listDirectoryEntries
import fr.astragames.app.data.local.GameSourceEntity
import fr.astragames.app.data.saves.documentDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Resolves names by walking the granted tree; SAF document IDs need not be paths. */
class SourceFolderBrowser(private val context: Context) {
    suspend fun resolve(source: GameSourceEntity, segments: List<String>): DocumentFile = withContext(Dispatchers.IO) {
        require(segments.size <= 128 && segments.all {
            it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it && '\u0000' !in it
        }) { "Chemin de sous-dossier invalide" }
        val root = documentDir(context, source.treeUri.toUri())
        check(root != null && root.exists() && root.canRead()) { "Permission de stockage absente ou expirée" }
        var folder: DocumentFile = root
        for (segment in segments) {
            coroutineContext.ensureActive()
            val child = listDirectoryEntries(context, folder.uri).firstOrNull { it.name == segment && it.isDirectory }
            folder = child?.let { documentDir(context, it.uri) }?.takeIf { it.canRead() }
                ?: error("Sous-dossier introuvable ou inaccessible : $segment")
            if (root.uri.scheme == "file") {
                val base = File(requireNotNull(root.uri.path)).canonicalFile.toPath()
                check(File(requireNotNull(folder.uri.path)).canonicalFile.toPath().startsWith(base)) {
                    "Le sous-dossier sort de la source autorisée"
                }
            }
        }
        folder
    }

    suspend fun listFolders(source: GameSourceEntity, segments: List<String>): List<String> = withContext(Dispatchers.IO) {
        listDirectoryEntries(context, resolve(source, segments).uri).asSequence()
            .filter { it.isDirectory && it.name != ".astra-translation" }
            .map { it.name }
            .filter { source.includeHiddenFolders || !it.startsWith('.') }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
    }
}
