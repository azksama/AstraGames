package fr.astragames.app.data.mods

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.ModEntity
import fr.astragames.app.data.local.ModInstallationEntity
import fr.astragames.app.data.local.ModInstalledFileEntity
import fr.astragames.app.data.saves.documentDir
import fr.astragames.app.data.saves.documentFile
import fr.astragames.app.data.saves.readBounded
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ModsManager(private val context: Context, private val dao: AstraDao) {
    private val lock = Mutex()
    fun observeMods() = dao.observeMods()
    fun observeInstallations(gameId: String) = dao.observeInstallationsForGame(gameId)

    suspend fun catalogFor(game: GameEntity): List<ModCatalogItem> = withContext(Dispatchers.IO) {
        val installations = dao.observeInstallationsForGame(game.id).firstValue()
        dao.getMods().map { mod ->
            val installation = installations.firstOrNull { it.modId == mod.id }
            val compatible = mod.engine == game.engine || mod.engine == "OTHER"
            val status = when {
                installation != null && installation.status != "INSTALLED" -> ModStatus.ERROR
                installation != null -> ModStatus.INSTALLED
                !compatible -> ModStatus.INCOMPATIBLE
                else -> ModStatus.AVAILABLE
            }
            ModCatalogItem(mod, status, installation, if (compatible) null else "Moteur incompatible")
        }.sortedWith(compareBy({ it.status.ordinal }, { it.entity.name.lowercase() }))
    }

    suspend fun scanRepository(rootUri: String?): Int = withContext(Dispatchers.IO) { lock.withLock {
        if (rootUri.isNullOrBlank()) return@withLock 0
        persistTree(Uri.parse(rootUri))
        val root = documentDir(context, Uri.parse(rootUri)) ?: error("Depot inaccessible.")
        val found = mutableListOf<ModEntity>()
        val modsRoot = root.findFile("Mods")?.takeIf { it.isDirectory } ?: root
        ENGINES.forEach { engine ->
            val folder = modsRoot.findFile(engineFolderName(engine)) ?: return@forEach
            folder.listFiles().filter { it.isDirectory && !it.name.orEmpty().startsWith(".astra-") }.forEach {
                found += readMod(engine, it)
            }
        }
        require(found.distinctBy { it.id }.size == found.size) { "Plusieurs mods utilisent le meme identifiant." }
        found.forEach { dao.upsertMod(it) }
        // Keep absent packages with installed files so their uninstall journal remains reachable.
        dao.getMods().filter { old -> found.none { it.id == old.id } }.forEach { old ->
            if (dao.getInstallationsForMod(old.id).isEmpty()) dao.deleteMods(listOf(old.id))
        }
        found.size
    } }

    suspend fun importZip(zipUri: Uri, rootUri: String, engineOverride: String?, replaceExisting: Boolean): ModEntity =
        withContext(Dispatchers.IO) { lock.withLock {
            persistTree(Uri.parse(rootUri))
            val repository = documentDir(context, Uri.parse(rootUri)) ?: error("Depot inaccessible.")
            val staging = File(context.cacheDir, "mod-import-${UUID.randomUUID()}").apply { mkdirs() }
            var temporary: DocumentFile? = null
            var previous: DocumentFile? = null
            var previousName: String? = null
            var committed = false
            try {
                val payload = context.contentResolver.openInputStream(zipUri)?.let { ModArchive.extract(it, staging) }
                    ?: error("Lecture ZIP impossible.")
                val manifest = File(payload, "astra-mod.json").takeIf { it.isFile }
                    ?.inputStream()?.use { it.readBounded(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8) }
                    ?.let(AstraModManifestParser::parse)
                val engine = normalizeEngine(engineOverride ?: manifest?.engines?.firstOrNull() ?: "OTHER")
                require(engine in ENGINES) { "Moteur de mod non pris en charge." }
                require(manifest == null || manifest.engines.isEmpty() || engine in manifest.engines || "OTHER" in manifest.engines) { "Moteur du manifeste incompatible." }
                val name = manifest?.id ?: "mod-" + UUID.randomUUID().toString().take(8)
                val root = repository.findFile("Mods")?.takeIf { it.isDirectory } ?: repository
                val engineFolder = childDirectory(root, engineFolderName(engine))
                val existing = engineFolder.findFile(name)
                val registered = dao.getMods().firstOrNull { it.id == name }
                require(registered == null || registered.folderUri == existing?.uri?.toString()) { "Identifiant deja present dans un autre dossier." }
                require(existing == null || replaceExisting) { "Un mod du meme nom existe deja." }
                require(registered == null || dao.getInstallationsForMod(registered.id).isEmpty()) { "Desinstallez ce mod avant de le remplacer." }
                temporary = childDirectory(engineFolder, ".astra-import-${UUID.randomUUID()}")
                copyPackage(DocumentFile.fromFile(payload), temporary)
                // Validate before replacing an existing package.
                readMod(engine, temporary)
                if (existing != null) {
                    previousName = existing.name ?: name
                    check(existing.renameTo(".astra-previous-${UUID.randomUUID()}")) { "Remplacement impossible." }
                    previous = existing
                }
                check(temporary.renameTo(name)) { "Finalisation de l import impossible." }
                val mod = readMod(engine, temporary)
                dao.upsertMod(mod)
                committed = true
                previous?.delete()
                mod
            } finally {
                if (!committed) {
                    temporary?.delete()
                    previousName?.let { previous?.renameTo(it) }
                }
                staging.deleteRecursively()
            }
        } }

    suspend fun install(game: GameEntity, mod: ModEntity): ModInstallationEntity = withContext(Dispatchers.IO) { lock.withLock {
        require(mod.engine == game.engine || mod.engine == "OTHER") { "Moteur incompatible." }
        val gameRoot = documentDir(context, game.documentUri.toUri())
        val translationData = gameRoot?.findFile("www")?.findFile("data") ?: gameRoot?.findFile("data")
        check(translationData?.findFile(".astra-translation")?.findFile("manifest.json") == null) {
            "Restaurez la traduction avant d’installer des mods."
        }
        val installed = dao.observeInstallationsForGame(game.id).firstValue()
        require(installed.none { it.modId == mod.id }) { "Ce mod est deja installe ou doit etre restaure." }
        val packageRoot = documentDir(context, Uri.parse(mod.folderUri)) ?: error("Dossier du mod introuvable.")
        val source = findDirectory(packageRoot, mod.filesRoot) ?: error("Dossier filesRoot absent : ${mod.filesRoot}")
        val root = documentDir(context, Uri.parse(game.documentUri)) ?: error("Dossier du jeu introuvable.")
        val target = if (mod.target != null) findDirectory(root, mod.target) ?: error("Dossier cible absent : ${mod.target}")
        else when {
            source.findFile("game")?.isDirectory == true || source.findFile("www")?.isDirectory == true -> root
            game.engine == "RENPY" -> root.findFile("game")?.takeIf { it.isDirectory } ?: error("Dossier game absent.")
            game.engine == "RPG_MAKER_MV" -> root.findFile("www")?.takeIf { it.isDirectory } ?: root
            else -> root
        }
        require(target.canWrite()) { "Le dossier du jeu est en lecture seule." }
        val files = mutableListOf<Pair<String, DocumentFile>>()
        fun collect(folder: DocumentFile, prefix: String, depth: Int) {
            require(depth <= 64 && files.size <= ZipPathGuard.MAX_FILES) { "Mod trop volumineux." }
            folder.listFiles().forEach { child ->
                val name = child.name ?: error("Fichier sans nom.")
                if (name == "astra-mod.json") return@forEach
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                require(ZipPathGuard.sanitize(path) == path) { "Chemin du mod invalide." }
                if (child.isDirectory) collect(child, path, depth + 1) else files += path to child
            }
        }
        collect(source, "", 0)
        require(files.isNotEmpty() && files.size <= ZipPathGuard.MAX_FILES) { "Mod vide ou trop volumineux." }
        val owned = installed.flatMap { dao.getInstalledFiles(it.id) }.map { it.relativePath }.toSet()
        files.forEach { (path, _) ->
            var folder: DocumentFile? = target
            path.split('/').dropLast(1).forEach { segment ->
                val next = folder?.findFile(segment)
                require(next == null || next.isDirectory) { "Un fichier bloque le dossier $path" }
                folder = next
            }
            val existing = folder?.findFile(path.substringAfterLast('/'))
            require(existing == null || existing.isFile) { "Un dossier bloque le fichier $path" }
            require(existing?.uri?.toString() !in owned) { "Un autre mod utilise $path. Desinstallez-le d abord." }
            require(mod.installMode != "COPY" || existing == null) { "Le mode COPY ne remplace pas $path." }
        }
        val installation = ModInstallationEntity(UUID.randomUUID().toString(), mod.id, game.id, System.currentTimeMillis(), mod.version, mod.installMode, "INSTALLING")
        dao.upsertInstallation(installation)
        try {
            files.forEach { (path, file) ->
                var parent = target
                path.split('/').dropLast(1).forEach { parent = childDirectory(parent, it) }
                val name = path.substringAfterLast('/')
                val existing = parent.findFile(name)
                val backup = existing?.let { backupOriginal(installation.id, it) }
                val originalHash = existing?.let(::hashOf)
                val installedHash = hashOf(file)
                val destination = existing ?: createFile(parent, name)
                val record = ModInstalledFileEntity(UUID.randomUUID().toString(), installation.id, destination.uri.toString(),
                    if (existing == null) "ADDED" else "REPLACED", originalHash, installedHash, backup?.absolutePath)
                // Journal before truncation so interruption cannot leave an untracked replacement.
                dao.insertInstalledFiles(listOf(record))
                copyDocument(file, destination)
                check(hashOf(destination) == record.installedHash) { "Verification echouee : $path" }
            }
            installation.copy(status = "INSTALLED").also { dao.upsertInstallation(it) }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                runCatching { uninstallFiles(installation, true) }.onFailure { failure.addSuppressed(it) }
            }
            throw failure
        }
    } }

    suspend fun uninstall(installation: ModInstallationEntity, force: Boolean = false): List<UninstallWarning> =
        withContext(Dispatchers.IO) { lock.withLock { uninstallFiles(installation, force) } }

    private suspend fun uninstallFiles(installation: ModInstallationEntity, force: Boolean): List<UninstallWarning> {
        val backupDirectory = backupDirectory(installation.id)
        val files = dao.getInstalledFiles(installation.id)
        val warnings = files.mapNotNull { record ->
            val dest = documentFile(context, Uri.parse(record.relativePath))
            val hash = dest?.takeIf { it.exists() }?.let(::hashOf)
            if (!force && hash != null && hash != record.installedHash && hash != record.originalHash)
                UninstallWarning(record.relativePath, "Fichier modifie depuis l installation") else null
        }
        if (warnings.isNotEmpty()) return warnings
        files.filter { it.action == "REPLACED" }.forEach { record ->
            val backup = managedBackup(record.backupUri)
            check(hashOf(DocumentFile.fromFile(backup)) == record.originalHash) { "Backup original endommage." }
        }
        files.forEach { record ->
            val dest = documentFile(context, Uri.parse(record.relativePath)) ?: error("Fichier inaccessible.")
            if (record.action == "ADDED") {
                check(!dest.exists() || dest.delete()) { "Suppression impossible : ${record.relativePath}" }
            } else {
                val backup = managedBackup(record.backupUri)
                check(hashOf(DocumentFile.fromFile(backup)) == record.originalHash) { "Backup original endommage." }
                copyDocument(DocumentFile.fromFile(backup), dest)
                check(hashOf(dest) == record.originalHash) { "Restauration incomplete." }
            }
        }
        dao.deleteInstalledFiles(installation.id)
        dao.deleteInstallation(installation.id)
        backupDirectory.deleteRecursively()
        return emptyList()
    }

    private fun backupOriginal(id: String, source: DocumentFile): File {
        val dir = backupDirectory(id).apply { mkdirs() }
        val backup = File(dir, UUID.randomUUID().toString())
        copyDocument(source, DocumentFile.fromFile(backup))
        check(hashOf(source) == hashOf(DocumentFile.fromFile(backup))) { "Backup original incomplet." }
        return backup
    }

    private fun backupDirectory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Identifiant d’installation invalide." }
        return File(context.filesDir, "mod-backups/$id")
    }

    private fun managedBackup(path: String?): File {
        val root = File(context.filesDir, "mod-backups").canonicalFile.toPath()
        val file = path?.let(::File)?.canonicalFile ?: error("Backup original introuvable.")
        require(file.toPath().startsWith(root) && file.toPath() != root && file.isFile) { "Backup original inaccessible." }
        return file
    }

    private fun createFile(parent: DocumentFile, name: String): DocumentFile {
        val created = parent.createFile("application/octet-stream", name) ?: error("Creation impossible : $name")
        if (created.name != name) check(created.renameTo(name)) { "Le fournisseur Android a modifie le nom $name." }
        return created
    }

    private fun copyDocument(from: DocumentFile, to: DocumentFile) {
        context.contentResolver.openInputStream(from.uri)?.use { input ->
            context.contentResolver.openOutputStream(to.uri, "wt")?.use { output -> input.copyTo(output) }
                ?: error("Ecriture impossible : ${to.name}")
        } ?: error("Lecture impossible : ${from.name}")
    }

    private fun copyPackage(from: DocumentFile, to: DocumentFile) {
        from.listFiles().forEach { file ->
            val name = file.name ?: error("Fichier sans nom.")
            if (file.isDirectory) copyPackage(file, childDirectory(to, name))
            else {
                val target = createFile(to, name)
                copyDocument(file, target)
                check(hashOf(file) == hashOf(target)) { "Copie incomplete : $name" }
            }
        }
    }

    private fun hashOf(file: DocumentFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(16384)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        } ?: error("Lecture impossible : ${file.name}")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readMod(engine: String, folder: DocumentFile): ModEntity {
        val manifest = folder.findFile("astra-mod.json")?.let { file ->
            val text = context.contentResolver.openInputStream(file.uri)?.use { it.readBounded(MAX_MANIFEST_BYTES).toString(Charsets.UTF_8) }
                ?: error("Manifeste illisible.")
            AstraModManifestParser.parse(text)
        }
        require(manifest == null || manifest.engines.isEmpty() || engine in manifest.engines || "OTHER" in manifest.engines) { "Moteur du manifeste incompatible avec le depot." }
        val filesRoot = manifest?.filesRoot ?: if (folder.findFile("files")?.isDirectory == true) "files" else "."
        require(findDirectory(folder, filesRoot) != null) { "Dossier filesRoot absent : $filesRoot" }
        val stableId = manifest?.id ?: "$engine:${folder.uri}"
        return ModEntity(id = stableId, modId = manifest?.id ?: folder.name.orEmpty(), name = manifest?.name ?: folder.name.orEmpty(),
            version = manifest?.version, author = manifest?.author, description = manifest?.description, engine = engine,
            folderUri = folder.uri.toString(), hasManifest = manifest != null, installMode = (manifest?.installMode ?: ModInstallMode.OVERLAY).name,
            target = manifest?.target, filesRoot = filesRoot, lastSeenAt = System.currentTimeMillis())
    }

    private fun findDirectory(root: DocumentFile, path: String): DocumentFile? {
        if (path.isBlank() || path == ".") return root
        val safe = ZipPathGuard.sanitize(path) ?: error("Chemin de dossier invalide.")
        var current = root
        safe.split('/').forEach { name ->
            current = current.listFiles().firstOrNull { it.isDirectory && it.name.equals(name, true) } ?: return null
        }
        return current
    }

    private fun childDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        require(existing == null || existing.isDirectory) { "Un fichier occupe le dossier $name" }
        return existing ?: parent.createDirectory(name) ?: error("Creation du dossier impossible : $name")
    }

    private fun persistTree(uri: Uri) {
        if (uri.scheme == "file") return
        context.contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 1024L * 1024
        val ENGINES = listOf("RENPY", "RPG_MAKER_MV", "RPG_MAKER_MZ", "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE", "WOLF_RPG", "OTHER")
    }
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()
