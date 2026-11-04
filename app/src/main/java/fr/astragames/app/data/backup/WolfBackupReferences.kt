package fr.astragames.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import fr.astragames.app.mkxpz.MkxpzSavePaths
import fr.astragames.app.windows.WolfGameStorage
import fr.astragames.app.windows.WolfRestoredSavePaths
import org.json.JSONObject
import java.io.File

/** Data-only Wolf snapshots. SAF exports restore as a private copy, with their conflict baseline. */
internal object WolfBackupReferences {
    const val DIRECTORY = "wolf-saves"
    private const val SCHEME = "astra-wolf-save:"
    private const val PREFIX = "${SCHEME}v1:"
    private val referenceColumns = mapOf("game_save_locations" to "uri", "save_backups" to "sourceUri")
    private val hash = Regex("[a-f0-9]{64}")
    private val saveFolders = setOf("save", "saves", "savedata", "savefiles", "save_mtool")
    private val executableExtensions = setOf("exe", "dll", "so", "rb", "rbw", "js", "py", "bat", "cmd", "ps1", "sh", "ini", "lnk", "url", "tmp", "log")
    private data class Owner(val id: String, val engine: String, val source: String) {
        val key get() = MkxpzSavePaths.ownerKey(id)
    }

    fun isToken(value: String) = value.startsWith(SCHEME)

    private fun columns(db: SQLiteDatabase, table: String) = db.rawQuery("PRAGMA table_info('$table')", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(1)) }
    }

    private fun owners(db: SQLiteDatabase): Map<String, Owner> {
        val owners = linkedMapOf<String, Owner>()
        db.rawQuery("SELECT id, engine, documentUri FROM games", null).use { c ->
            while (c.moveToNext()) owners[c.getString(0)] = Owner(c.getString(0), c.getString(1), c.getString(2))
        }
        if ("engineOverride" in columns(db, "launch_profiles")) {
            db.rawQuery("SELECT gameId, engineOverride FROM launch_profiles", null).use { c ->
                while (c.moveToNext()) owners[c.getString(0)]?.let { owner ->
                    c.getString(1)?.takeIf(String::isNotBlank)?.let { owners[owner.id] = owner.copy(engine = it) }
                }
            }
        }
        return owners
    }

    private fun valid(relative: String, directory: Boolean): Boolean {
        val parts = relative.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." || it.startsWith(".astra-") ||
                it.any { ch -> ch == '\\' || ch == ':' || ch == '%' || ch.code < 32 || ch.code == 127 } }) return false
        if (parts.first() !in setOf("game", "windows") || parts.size > 64) return false
        if (directory) return parts.first() == "game" || parts.size >= 2
        val extension = parts.last().substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
        if (extension in executableExtensions) return false
        if (parts.first() == "windows") return parts.size >= 3
        return parts.size >= 2 && (extension in setOf("sav", "save") ||
            parts.drop(1).dropLast(1).any { it.lowercase(java.util.Locale.ROOT) in saveFolders })
    }

    private fun resolve(context: Context, owner: Owner, relative: String, directory: Boolean): File {
        require(owner.engine == "WOLF_RPG" && valid(relative, directory)) { "Chemin de sauvegarde Wolf non reconnu." }
        val base = File(context.filesDir, "wolf-games/${owner.key}")
        val target = if (relative == "game") File(base, "game")
            else if (relative.startsWith("game/")) File(base, relative)
            else File(base, "prefix/drive_c/users/${relative.removePrefix("windows/")}")
        MkxpzSavePaths.requireNoSymlinks(context.filesDir, target)
        require(!target.exists() || if (directory) target.isDirectory else target.isFile) { "Type de sauvegarde Wolf invalide." }
        return target
    }

    private fun relative(context: Context, owner: Owner, path: File, directory: Boolean): String? {
        val ownerDirectory = File(context.filesDir, "wolf-games/${owner.key}").absoluteFile
        MkxpzSavePaths.requireNoSymlinks(context.filesDir, ownerDirectory)
        val base = ownerDirectory.toPath()
        val canonicalBase = ownerDirectory.canonicalFile.toPath()
        val absolute = path.absoluteFile.toPath()
        // Wolf's safeFile returns canonical paths; Android may expose the trusted data root
        // through /data/user/0 as well as /data/data. App-controlled links remain forbidden.
        val matched = when {
            absolute.normalize().startsWith(base) -> base
            absolute.normalize().startsWith(canonicalBase) -> canonicalBase
            else -> return null
        }
        MkxpzSavePaths.requireNoSymlinks(if (matched == base) context.filesDir else context.filesDir.canonicalFile, path)
        val local = matched.relativize(absolute).toString().replace(File.separatorChar, '/')
        val saf = "saf-${MkxpzSavePaths.ownerKey(owner.source).take(24)}/export"
        val result = when {
            local == "game" || local.startsWith("game/") -> local
            local.startsWith("prefix/drive_c/users/") -> "windows/" + local.removePrefix("prefix/drive_c/users/")
            local == saf -> "game"
            local.startsWith("$saf/") -> "game/" + local.removePrefix("$saf/")
            else -> error("Emplacement privé Wolf non exportable.")
        }
        require(valid(result, directory)) { "Chemin de sauvegarde Wolf non reconnu." }
        return result
    }

    private fun checkPrivateTree(context: Context, directory: File) {
        MkxpzSavePaths.requireNoSymlinks(context.filesDir, directory)
        directory.walkTopDown().onEnter { MkxpzSavePaths.requireNoSymlinks(context.filesDir, it); true }
            .forEach { MkxpzSavePaths.requireNoSymlinks(context.filesDir, it) }
    }

    /** Only the SQLite snapshot changes; save bytes are read, never published back to the source. */
    suspend fun prepareExport(context: Context, db: SQLiteDatabase, staging: File): List<Pair<String, File>> {
        val owners = owners(db)
        val files = linkedMapOf<String, File>()
        val available = mutableSetOf<String>()
        owners.values.filter { it.engine == "WOLF_RPG" }.forEach { owner ->
            val storage = WolfGameStorage(context, owner.id, owner.source)
            val baselines = JSONObject()
            try {
                MkxpzSavePaths.requireNoSymlinks(context.filesDir, storage.directory)
                MkxpzSavePaths.requireNoSymlinks(context.filesDir, File(storage.directory, "source-hashes.json"))
                MkxpzSavePaths.requireNoSymlinks(context.filesDir, File(storage.directory, "working-mode.json"))
                MkxpzSavePaths.requireNoSymlinks(context.filesDir, File(storage.directory, "import-state.json"))
                checkPrivateTree(context, File(storage.directory, "game"))
                val copied = File(storage.directory, "source-hashes.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: JSONObject()
                val safState = File(storage.directory, "saf-${MkxpzSavePaths.ownerKey(owner.source).take(24)}")
                checkPrivateTree(context, safState)
                // saveFiles materializes sealed pending SAF writes and preserves their source baseline.
                val saves = storage.saveFiles()
                val exported = File(safState, "export-baseline.json").takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: JSONObject()
                saves.forEach saveLoop@ { saved ->
                    val relative = relative(context, owner, saved.file, false) ?: return@saveLoop
                    val name = "$DIRECTORY/${owner.key}/$relative"
                    require(files.put(name, saved.file) == null) { "Sauvegarde Wolf dupliquée." }
                    available += "${owner.key}:$relative"
                    if (relative.startsWith("game/")) {
                        val sourcePath = relative.removePrefix("game/")
                        val sourceHash = (if (storage.isSaf) exported else copied).optString(sourcePath).takeIf(hash::matches)
                        baselines.put(sourcePath, sourceHash ?: JSONObject.NULL)
                    }
                }
                if (baselines.length() > 0) {
                    val metadata = File(staging, "$DIRECTORY/${owner.key}/baselines.json").apply { parentFile!!.mkdirs(); writeText(baselines.toString()) }
                    files["$DIRECTORY/${owner.key}/baselines.json"] = metadata
                }
            } finally { storage.closeMount() }
        }
        referenceColumns.forEach { (table, column) ->
            if (column !in columns(db, table)) return@forEach
            db.rawQuery("SELECT id, gameId, `$column` FROM `$table`", null).use { c ->
                while (c.moveToNext()) {
                    val value = c.getString(2) ?: continue
                    require(!isToken(value)) { "Référence Wolf d’archive présente dans le catalogue actif." }
                    val owner = owners[c.getString(1)]?.takeIf { it.engine == "WOLF_RPG" } ?: continue
                    val uri = Uri.parse(value)
                    val path = if (uri.scheme.equals("file", true)) uri.path else value.takeIf { it.startsWith('/') }
                    if (path == null) continue
                    val directory = table == "game_save_locations"
                    val relative = relative(context, owner, File(path), directory) ?: continue
                    require(directory || "${owner.key}:$relative" in available) { "Sauvegarde privée Wolf introuvable ; export annulé." }
                    db.execSQL("UPDATE `$table` SET `$column` = ? WHERE id = ?", arrayOf("$PREFIX${owner.key}:$relative", c.getString(0)))
                }
            }
        }
        return files.toList()
    }

    fun planImport(context: Context, db: SQLiteDatabase, staging: File, exportedFiles: Set<String>? = null): MkxpzBackupReferences.ImportPlan {
        val owners = owners(db)
        val references = mutableListOf<MkxpzBackupReferences.Rebind>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { tables ->
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                val quoted = table.replace("\"", "\"\"")
                db.rawQuery("SELECT * FROM \"$quoted\"", null).use { c ->
                    while (c.moveToNext()) for (index in 0 until c.columnCount) {
                        if (c.getType(index) != android.database.Cursor.FIELD_TYPE_STRING) continue
                        val value = c.getString(index)
                        if (!isToken(value)) continue
                        val column = c.getColumnName(index)
                        require(referenceColumns[table] == column) { "Référence Wolf interdite dans ce champ." }
                        val owner = owners[c.getString(c.getColumnIndexOrThrow("gameId"))] ?: error("Jeu propriétaire Wolf absent.")
                        require(value.startsWith("$PREFIX${owner.key}:")) { "Référence Wolf d’un autre jeu ou version inconnue." }
                        val relative = value.removePrefix("$PREFIX${owner.key}:")
                        val file = resolve(context, owner, relative, table == "game_save_locations")
                        if (table == "save_backups") {
                            val entry = "$DIRECTORY/${owner.key}/$relative"
                            require(if (exportedFiles != null) entry in exportedFiles else File(staging, entry).isFile) {
                                "La sauvegarde Wolf référencée est absente de l’archive."
                            }
                        }
                        references += MkxpzBackupReferences.Rebind(table, column, c.getString(c.getColumnIndexOrThrow("id")), Uri.fromFile(file.canonicalFile).toString())
                    }
                }
            }
        }
        val byKey = owners.values.associateBy(Owner::key)
        val source = File(staging, DIRECTORY)
        val files = mutableListOf<Pair<File, File>>()
        val gamePaths = linkedMapOf<Owner, MutableSet<String>>()
        source.walkTopDown().filter(File::isFile).forEach { file ->
            val path = file.relativeTo(source).invariantSeparatorsPath
            val owner = byKey[path.substringBefore('/')] ?: error("Jeu propriétaire du fichier Wolf absent.")
            require(owner.engine == "WOLF_RPG" && '/' in path) { "Propriétaire Wolf invalide." }
            val relative = path.substringAfter('/')
            if (relative == "baselines.json") return@forEach
            files += file to resolve(context, owner, relative, false)
            if (relative.startsWith("game/")) gamePaths.getOrPut(owner) { linkedSetOf() } += relative.removePrefix("game/")
        }
        gamePaths.forEach { (owner, paths) ->
            val baselineFile = File(source, "${owner.key}/baselines.json")
            require(baselineFile.isFile) { "Références source Wolf absentes de l’archive." }
            val baseline = JSONObject(baselineFile.readText())
            require(baseline.keys().asSequence().toSet() == paths) { "Références source Wolf incohérentes." }
            // Retain the live asset baseline: resetting it would reclassify existing game
            // assets as new private writes and could publish them on the next synchronization.
            val destination = File(context.filesDir, "wolf-games/${owner.key}")
            val currentHashes = File(destination, "source-hashes.json")
            MkxpzSavePaths.requireNoSymlinks(context.filesDir, currentHashes)
            val hashes = currentHashes.takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: JSONObject()
            val currentMode = File(destination, "working-mode.json")
            MkxpzSavePaths.requireNoSymlinks(context.filesDir, currentMode)
            val mode = currentMode.takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: JSONObject()
            // A second restore must not discard unresolved private saves absent from its archive.
            val restoredPaths = WolfRestoredSavePaths.read(mode, destination, owner.source) + paths
            paths.forEach { path ->
                require(baseline.isNull(path) || baseline.get(path) is String && hash.matches(baseline.getString(path))) { "Empreinte source Wolf invalide." }
                if (!baseline.isNull(path)) hashes.put(path, baseline.getString(path)) else hashes.remove(path)
            }
            // Rebuild only our own data metadata. No archived Wine prefix/configuration or executable is trusted.
            val generated = File(staging, "wolf-generated/${owner.key}").apply { mkdirs() }
            mapOf(
                "source-hashes.json" to hashes,
                "working-mode.json" to WolfRestoredSavePaths.write(JSONObject().put("mode", "COPY")
                    .put("source", owner.source).put("restoredArchive", true), destination, restoredPaths),
                "import-state.json" to JSONObject().put("complete", false).put("source", owner.source).put("stamps", JSONObject())
            ).forEach { (name, json) ->
                val target = File(destination, name)
                MkxpzSavePaths.requireNoSymlinks(context.filesDir, target)
                files += File(generated, name).apply { writeText(json.toString()) } to target
            }
        }
        return MkxpzBackupReferences.ImportPlan(files, references)
    }

    fun allowedArchiveFile(path: String): Boolean {
        if (!path.startsWith("$DIRECTORY/")) return false
        val rest = path.removePrefix("$DIRECTORY/")
        if (!hash.matches(rest.substringBefore('/')) || '/' !in rest) return false
        val relative = rest.substringAfter('/')
        return relative == "baselines.json" || valid(relative, false)
    }
}
