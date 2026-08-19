package fr.astragames.app.data.mods

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.ModEntity
import fr.astragames.app.data.local.ModInstallationEntity
import fr.astragames.app.data.local.ModInstalledFileEntity
import fr.astragames.app.data.saves.sha256Hex
import fr.astragames.app.data.saves.documentDir
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

enum class ModInstallMode { OVERLAY, COPY, REPLACE }
enum class ModFileAction { ADDED, REPLACED }
enum class ModStatus { AVAILABLE, INSTALLED, INCOMPATIBLE, ERROR }

data class AstraModManifest(
    val formatVersion: Int,
    val id: String,
    val name: String,
    val version: String?,
    val author: String?,
    val description: String?,
    val engines: List<String>,
    val installMode: ModInstallMode,
    val target: String?,
    val filesRoot: String
)

data class ModCatalogItem(
    val entity: ModEntity,
    val status: ModStatus,
    val installation: ModInstallationEntity?,
    val incompatibleReason: String? = null
)

data class ZipImportPreview(
    val suggestedName: String,
    val engineHint: String?,
    val hasManifest: Boolean,
    val fileCount: Int,
    val uncompressedBytes: Long
)

data class UninstallWarning(
    val relativePath: String,
    val reason: String
)

object AstraModManifestParser {
    fun parse(json: String): AstraModManifest {
        val obj = JSONObject(json)
        val engines = obj.optJSONArray("engines")
        val engineList = mutableListOf<String>()
        if (engines != null) {
            for (index in 0 until engines.length()) engineList += engines.optString(index)
        }
        val mode = obj.optString("installMode", "OVERLAY").uppercase(Locale.ROOT)
        return AstraModManifest(
            formatVersion = obj.optInt("formatVersion", 1),
            id = obj.getString("id"),
            name = obj.getString("name"),
            version = obj.optString("version").ifBlank { null },
            author = obj.optString("author").ifBlank { null },
            description = obj.optString("description").ifBlank { null },
            engines = engineList.map(::normalizeEngine).filter { it.isNotBlank() },
            installMode = runCatching { ModInstallMode.valueOf(mode) }.getOrDefault(ModInstallMode.OVERLAY),
            target = obj.optString("target").ifBlank { null },
            filesRoot = obj.optString("filesRoot", "files").ifBlank { "files" }
        )
    }
}

object ZipPathGuard {
    const val MAX_UNCOMPRESSED_BYTES = 200L * 1024 * 1024
    const val MAX_FILES = 4000

    fun sanitize(entryName: String): String? {
        val raw = entryName.replace("\\", "/").trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.matches(Regex("^[A-Za-z]:/.*"))) return null
        if (raw.contains("..")) return null
        return raw
    }
}

fun normalizeEngine(raw: String): String {
    val value = raw.trim().uppercase(Locale.ROOT).replace(" ", "_").replace("-", "_")
    return when (value) {
        "RENPY", "REN_PY" -> "RENPY"
        "RPGMAKERMV", "RPG_MAKER_MV", "MV" -> "RPG_MAKER_MV"
        "RPGMAKERMZ", "RPG_MAKER_MZ", "MZ" -> "RPG_MAKER_MZ"
        "RPGMAKERXP", "RPG_MAKER_XP", "XP" -> "RPG_MAKER_XP"
        "RPGMAKERVX", "RPG_MAKER_VX", "VX" -> "RPG_MAKER_VX"
        "RPGMAKERVXACE", "RPG_MAKER_VX_ACE", "VXACE", "VX_ACE" -> "RPG_MAKER_VX_ACE"
        "WOLFRPG", "WOLF_RPG" -> "WOLF_RPG"
        "OTHER", "UNKNOWN" -> "OTHER"
        else -> value
    }
}

fun engineFolderName(engine: String) = when (engine) {
    "RENPY" -> "RenPy"
    "RPG_MAKER_MV" -> "RPGMakerMV"
    "RPG_MAKER_MZ" -> "RPGMakerMZ"
    "RPG_MAKER_XP" -> "RPGMakerXP"
    "RPG_MAKER_VX" -> "RPGMakerVX"
    "RPG_MAKER_VX_ACE" -> "RPGMakerVXAce"
    "WOLF_RPG" -> "WolfRPG"
    else -> "Other"
}

class ModsManager(
    private val context: Context,
    private val dao: AstraDao
) {
    fun observeMods() = dao.observeMods()
    fun observeInstallations(gameId: String) = dao.observeInstallationsForGame(gameId)

    suspend fun catalogFor(game: GameEntity): List<ModCatalogItem> {
        val mods = dao.getMods()
        return mods.map { mod ->
            val installation = dao.getInstallationsForMod(mod.id).firstOrNull { it.gameId == game.id && it.status == "INSTALLED" }
            val compatible = mod.engine == game.engine || mod.engine == "OTHER"
            val status = when {
                !compatible -> ModStatus.INCOMPATIBLE
                installation != null -> ModStatus.INSTALLED
                else -> ModStatus.AVAILABLE
            }
            ModCatalogItem(
                entity = mod,
                status = status,
                installation = installation,
                incompatibleReason = if (compatible) null else "Moteur incompatible"
            )
        }.sortedWith(compareBy({ it.status.ordinal }, { it.entity.name.lowercase(Locale.ROOT) }))
    }

    suspend fun scanRepository(rootUri: String?): Int {
        if (rootUri.isNullOrBlank()) return 0
        persistTree(Uri.parse(rootUri))
        val root = documentDir(context, Uri.parse(rootUri)) ?: return 0
        val now = System.currentTimeMillis()
        val found = mutableListOf<ModEntity>()
        engineFolders(root).forEach { (engine, folder) ->
            folder.listFiles().filter { it.isDirectory }.forEach { modFolder ->
                found += readMod(engine, modFolder, now)
            }
        }
        found.forEach { dao.upsertMod(it) }
        if (found.isNotEmpty()) dao.deleteModsNotIn(found.map { it.id })
        return found.size
    }

    suspend fun importZip(zipUri: Uri, rootUri: String, engineOverride: String?, replaceExisting: Boolean): ModEntity {
        persistTree(Uri.parse(rootUri))
        val root = documentDir(context, Uri.parse(rootUri)) ?: error("Depot de mods introuvable.")
        val preview = previewZip(zipUri)
        val engine = normalizeEngine(engineOverride ?: preview.engineHint ?: "OTHER")
        val engineFolder = ensureChild(rootAfterMods(root), engineFolderName(engine), true)
        val existing = engineFolder.findFile(preview.suggestedName)
        if (existing != null && !replaceExisting) error("Un mod du meme nom existe deja.")
        if (existing != null && replaceExisting) existing.delete()
        val dest = ensureChild(engineFolder, preview.suggestedName, true)
        extractZip(zipUri, dest)
        val entity = readMod(engine, dest, System.currentTimeMillis())
        dao.upsertMod(entity)
        return entity
    }

    fun previewZip(zipUri: Uri): ZipImportPreview {
        var fileCount = 0
        var uncompressed = 0L
        var manifestJson: String? = null
        var firstFolder: String? = null
        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val safe = ZipPathGuard.sanitize(entry.name) ?: error("Chemin ZIP refuse : " + entry.name)
                    if (firstFolder == null && safe.contains("/")) firstFolder = safe.substringBefore("/")
                    if (entry.isDirectory) continue
                    fileCount++
                    if (fileCount > ZipPathGuard.MAX_FILES) error("ZIP trop volumineux.")
                    val bytes = zip.readBytes()
                    uncompressed += bytes.size
                    if (uncompressed > ZipPathGuard.MAX_UNCOMPRESSED_BYTES) error("ZIP trop volumineux.")
                    if (safe.endsWith("astra-mod.json", ignoreCase = true) && manifestJson == null) {
                        manifestJson = String(bytes, StandardCharsets.UTF_8)
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Lecture du ZIP impossible.")
        val manifest = manifestJson?.let { runCatching { AstraModManifestParser.parse(it) }.getOrNull() }
        return ZipImportPreview(
            suggestedName = (manifest?.id ?: firstFolder ?: "mod-" + System.currentTimeMillis()).replace("/", "-"),
            engineHint = manifest?.engines?.firstOrNull(),
            hasManifest = manifest != null,
            fileCount = fileCount,
            uncompressedBytes = uncompressed
        )
    }

    suspend fun install(game: GameEntity, mod: ModEntity): ModInstallationEntity {
        require(mod.engine == game.engine || mod.engine == "OTHER") { "Moteur incompatible" }
        val already = dao.getInstallationsForMod(mod.id).firstOrNull { it.gameId == game.id && it.status == "INSTALLED" }
        if (already != null) error("Ce mod est deja installe sur ce jeu.")
        val modRoot = documentDir(context, Uri.parse(mod.folderUri))
            ?: error("Dossier du mod introuvable.")
        val filesRoot = findChildPath(modRoot, mod.filesRoot) ?: modRoot
        val targetRoot = resolveTarget(game, mod.target)
        val records = mutableListOf<ModInstalledFileEntity>()
        val installationId = UUID.randomUUID().toString()
        copyTree(filesRoot, targetRoot, "", installationId, records)
        val installation = ModInstallationEntity(
            id = installationId,
            modId = mod.id,
            gameId = game.id,
            installedAt = System.currentTimeMillis(),
            modVersion = mod.version,
            installMode = mod.installMode,
            status = "INSTALLED"
        )
        dao.upsertInstallation(installation)
        dao.insertInstalledFiles(records)
        return installation
    }

    suspend fun uninstall(installation: ModInstallationEntity, force: Boolean = false): List<UninstallWarning> {
        val files = dao.getInstalledFiles(installation.id)
        val warnings = mutableListOf<UninstallWarning>()
        files.forEach { record ->
            val dest = DocumentFile.fromSingleUri(context, Uri.parse(record.relativePath))
            val currentHash = dest?.takeIf { it.isFile }?.let { hashOf(it) }
            when (record.action) {
                "ADDED" -> {
                    if (dest == null || !dest.exists()) return@forEach
                    if (currentHash != null && record.installedHash != null && currentHash != record.installedHash && !force) {
                        warnings += UninstallWarning(record.relativePath, "Fichier modifie depuis l installation")
                    } else {
                        dest.delete()
                    }
                }
                "REPLACED" -> {
                    if (currentHash != null && record.installedHash != null && currentHash != record.installedHash && !force) {
                        warnings += UninstallWarning(record.relativePath, "Fichier modifie depuis l installation")
                        return@forEach
                    }
                    val backup = record.backupUri?.let { File(it) }
                    if (backup != null && backup.exists() && dest != null) {
                        context.contentResolver.openOutputStream(dest.uri, "w")?.use { output ->
                            backup.inputStream().use { input -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
        if (warnings.isNotEmpty() && !force) return warnings
        dao.deleteInstalledFiles(installation.id)
        dao.deleteInstallation(installation.id)
        File(context.filesDir, "mod-backups/" + installation.id).deleteRecursively()
        return emptyList()
    }

    private fun copyTree(
        source: DocumentFile,
        destRoot: DocumentFile,
        relative: String,
        installationId: String,
        records: MutableList<ModInstalledFileEntity>
    ) {
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val nextRelative = if (relative.isBlank()) name else relative + "/" + name
            if (child.isDirectory) {
                val nextDest = destRoot.findFile(name)?.takeIf { it.isDirectory } ?: destRoot.createDirectory(name)
                    ?: error("Impossible de creer " + nextRelative)
                copyTree(child, nextDest, nextRelative, installationId, records)
            } else {
                val existing = destRoot.findFile(name)
                val action: String
                val originalHash: String?
                val backupUri: String?
                if (existing != null && existing.isFile) {
                    action = ModFileAction.REPLACED.name
                    originalHash = hashOf(existing)
                    backupUri = backupReplaced(installationId, nextRelative, existing)
                } else {
                    action = ModFileAction.ADDED.name
                    originalHash = null
                    backupUri = null
                }
                val target = existing ?: destRoot.createFile(child.type ?: "application/octet-stream", name)
                    ?: error("Impossible de copier " + nextRelative)
                copyDocument(child, target)
                records += ModInstalledFileEntity(
                    id = UUID.randomUUID().toString(),
                    installationId = installationId,
                    relativePath = target.uri.toString(),
                    action = action,
                    originalHash = originalHash,
                    installedHash = hashOf(target),
                    backupUri = backupUri
                )
            }
        }
    }

    private fun backupReplaced(installationId: String, relative: String, file: DocumentFile): String {
        val dir = File(context.filesDir, "mod-backups/" + installationId)
        dir.mkdirs()
        val dest = File(dir, relative.replace("/", "__"))
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Backup du fichier original impossible.")
        return dest.absolutePath
    }

    private fun copyDocument(from: DocumentFile, to: DocumentFile) {
        context.contentResolver.openInputStream(from.uri)?.use { input ->
            context.contentResolver.openOutputStream(to.uri, "w")?.use { output -> input.copyTo(output) }
        } ?: error("Copie impossible.")
    }

    private fun hashOf(file: DocumentFile): String {
        val bytes = context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() } ?: ByteArray(0)
        return sha256Hex(bytes)
    }

    private fun resolveTarget(game: GameEntity, target: String?): DocumentFile {
        val root = documentDir(context, Uri.parse(game.documentUri))
            ?: error("Dossier du jeu introuvable.")
        val preferred = target?.takeIf { it.isNotBlank() } ?: defaultTarget(game.engine)
        return findChildPath(root, preferred) ?: root
    }

    private fun defaultTarget(engine: String) = when (engine) {
        "RENPY" -> "game"
        "RPG_MAKER_MV" -> "www"
        else -> ""
    }

    private fun findChildPath(root: DocumentFile, path: String): DocumentFile? {
        if (path.isBlank() || path == ".") return root
        var current: DocumentFile? = root
        path.replace("\\", "/").split("/").filter { it.isNotBlank() && it != "." }.forEach { segment ->
            current = current?.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(segment, ignoreCase = true) }
        }
        return current
    }

    private fun rootAfterMods(root: DocumentFile): DocumentFile =
        root.findFile("Mods")?.takeIf { it.isDirectory } ?: root

    private fun engineFolders(root: DocumentFile): List<Pair<String, DocumentFile>> {
        val modsRoot = rootAfterMods(root)
        val known = listOf(
            "RenPy" to "RENPY",
            "RPGMakerMV" to "RPG_MAKER_MV",
            "RPGMakerMZ" to "RPG_MAKER_MZ",
            "RPGMakerXP" to "RPG_MAKER_XP",
            "RPGMakerVX" to "RPG_MAKER_VX",
            "RPGMakerVXAce" to "RPG_MAKER_VX_ACE",
            "WolfRPG" to "WOLF_RPG",
            "Other" to "OTHER"
        )
        return known.mapNotNull { (name, engine) ->
            val folder = modsRoot.findFile(name)?.takeIf { it.isDirectory } ?: modsRoot.createDirectory(name)
            folder?.let { engine to it }
        }
    }

    private fun readMod(engine: String, folder: DocumentFile, now: Long): ModEntity {
        val manifestFile = folder.findFile("astra-mod.json")
        val manifest = manifestFile?.let { file ->
            context.contentResolver.openInputStream(file.uri)?.use { String(it.readBytes(), StandardCharsets.UTF_8) }
                ?.let { runCatching { AstraModManifestParser.parse(it) }.getOrNull() }
        }
        val id = manifest?.id ?: (engine + ":" + folder.uri)
        return ModEntity(
            id = id,
            modId = manifest?.id ?: folder.name.orEmpty(),
            name = manifest?.name ?: folder.name.orEmpty(),
            version = manifest?.version,
            author = manifest?.author,
            description = manifest?.description,
            engine = manifest?.engines?.firstOrNull() ?: engine,
            folderUri = folder.uri.toString(),
            hasManifest = manifest != null,
            installMode = (manifest?.installMode ?: ModInstallMode.OVERLAY).name,
            target = manifest?.target,
            filesRoot = manifest?.filesRoot ?: "files",
            lastSeenAt = now
        )
    }

    private fun extractZip(zipUri: Uri, dest: DocumentFile) {
        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var files = 0
                var uncompressed = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val safe = ZipPathGuard.sanitize(entry.name) ?: error("Chemin ZIP refuse : " + entry.name)
                    if (entry.isDirectory) {
                        ensurePath(dest, safe, directory = true)
                        zip.closeEntry()
                        continue
                    }
                    files++
                    if (files > ZipPathGuard.MAX_FILES) error("ZIP trop volumineux.")
                    val bytes = zip.readBytes()
                    uncompressed += bytes.size
                    if (uncompressed > ZipPathGuard.MAX_UNCOMPRESSED_BYTES) error("ZIP trop volumineux.")
                    val file = ensurePath(dest, safe, directory = false)
                    context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(bytes) }
                    zip.closeEntry()
                }
            }
        } ?: error("Extraction ZIP impossible.")
    }

    private fun ensurePath(root: DocumentFile, relative: String, directory: Boolean): DocumentFile {
        val parts = relative.split("/").filter { it.isNotBlank() }
        var current = root
        parts.forEachIndexed { index, part ->
            val last = index == parts.lastIndex
            if (!last || directory) {
                current = current.findFile(part)?.takeIf { it.isDirectory } ?: current.createDirectory(part)
                    ?: error("Impossible de creer le dossier " + part)
            } else {
                current = current.findFile(part) ?: current.createFile("application/octet-stream", part)
                    ?: error("Impossible de creer le fichier " + part)
            }
        }
        return current
    }

    private fun ensureChild(parent: DocumentFile, name: String, directory: Boolean): DocumentFile {
        parent.findFile(name)?.let { existing ->
            if (directory && existing.isDirectory) return existing
            if (!directory && existing.isFile) return existing
        }
        return if (directory) parent.createDirectory(name) ?: error("Impossible de creer " + name)
        else parent.createFile("application/octet-stream", name) ?: error("Impossible de creer " + name)
    }

    private fun persistTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}

private fun ZipInputStream.readBytes(): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
