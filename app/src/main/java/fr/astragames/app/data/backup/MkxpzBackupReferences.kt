package fr.astragames.app.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import fr.astragames.app.mkxpz.MkxpzRuntime
import fr.astragames.app.mkxpz.MkxpzSavePaths
import java.io.File

/** Archive-only capabilities. Tokens never remain in the live catalogue. */
internal object MkxpzBackupReferences {
    const val DIRECTORY = "mkxpz-saves"
    private const val SCHEME = "astra-mkxpz-save:"
    private const val PREFIX = "${SCHEME}v1:"
    private val referenceColumns = mapOf("game_save_locations" to "uri", "save_backups" to "sourceUri")

    data class Rebind(val table: String, val column: String, val id: String, val uri: String)
    data class ImportPlan(val files: List<Pair<File, File>>, val references: List<Rebind>)
    private data class Owner(val id: String, val engine: String) {
        val key get() = MkxpzSavePaths.ownerKey(id)
    }

    fun isToken(value: String) = value.startsWith(SCHEME)

    private fun columns(db: SQLiteDatabase, table: String) = db.rawQuery("PRAGMA table_info('$table')", null).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(1)) }
    }

    private fun owners(db: SQLiteDatabase): Map<String, Owner> {
        val engines = linkedMapOf<String, String>()
        db.rawQuery("SELECT id, engine FROM games", null).use { c -> while (c.moveToNext()) engines[c.getString(0)] = c.getString(1) }
        if ("engineOverride" in columns(db, "launch_profiles")) {
            db.rawQuery("SELECT gameId, engineOverride FROM launch_profiles", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    if (id in engines) c.getString(1)?.takeIf(String::isNotBlank)?.let { engines[id] = it }
                }
            }
        }
        return engines.mapValues { (id, engine) -> Owner(id, engine) }
    }

    /** Mutates only the exported SQLite copy. Never rewrite the active catalogue. */
    fun prepareExport(context: Context, db: SQLiteDatabase): List<Pair<String, File>> {
        val owners = owners(db)
        referenceColumns.forEach { (table, column) ->
            if (column !in columns(db, table)) return@forEach
            db.rawQuery("SELECT id, gameId, `$column` FROM `$table`", null).use { c ->
                while (c.moveToNext()) {
                    val value = c.getString(2) ?: continue
                    require(!isToken(value)) { "Référence MKXP-Z d'archive présente dans le catalogue actif." }
                    val uri = Uri.parse(value)
                    val path = if (uri.scheme.equals("file", true)) uri.path else value.takeIf { it.startsWith('/') }
                    if (path == null) continue
                    val owner = owners[c.getString(1)] ?: continue
                    val relative = MkxpzSavePaths.relative(context, owner.id, owner.engine, File(path), table == "game_save_locations") ?: continue
                    db.execSQL("UPDATE `$table` SET `$column` = ? WHERE id = ?", arrayOf("$PREFIX${owner.key}:$relative", c.getString(0)))
                }
            }
        }
        return buildList {
            owners.values.filter { MkxpzRuntime.rgssVersion(it.engine) != null }.forEach { owner ->
                val root = MkxpzSavePaths.resolve(context, owner.id, owner.engine, ".", true)
                val folders = listOf(".") + root.listFiles().orEmpty().map(File::getName)
                    .filter { MkxpzSavePaths.validRelative(it, owner.engine, true) }
                folders.forEach folderLoop@ { folder ->
                    val directory = MkxpzSavePaths.resolve(context, owner.id, owner.engine, folder, true)
                    if (!directory.isDirectory) return@folderLoop
                    directory.listFiles().orEmpty().filter { MkxpzSavePaths.isSaveFileName(it.name, owner.engine) }.forEach { file ->
                        val relative = if (folder == ".") file.name else "$folder/${file.name}"
                        val safe = MkxpzSavePaths.resolve(context, owner.id, owner.engine, relative, false)
                        require(safe.isFile) { "Sauvegarde MKXP-Z introuvable." }
                        add("$DIRECTORY/${owner.key}/$relative" to safe)
                    }
                }
            }
        }
    }

    /** Read-only validation of every capability and destination before live file writes. */
    fun planImport(context: Context, db: SQLiteDatabase, staging: File, exportedFiles: Set<String>? = null): ImportPlan {
        val owners = owners(db)
        val references = mutableListOf<Rebind>()
        // A typed token in any other field must never become a path capability.
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
                        require(referenceColumns[table] == column) { "Référence MKXP-Z interdite dans ce champ." }
                        val owner = owners[c.getString(c.getColumnIndexOrThrow("gameId"))]
                            ?: error("Jeu propriétaire de la sauvegarde MKXP-Z absent.")
                        require(value.startsWith("$PREFIX${owner.key}:")) { "Référence MKXP-Z d'un autre jeu ou version inconnue." }
                        val relative = value.removePrefix("$PREFIX${owner.key}:")
                        val file = MkxpzSavePaths.resolve(context, owner.id, owner.engine, relative, table == "game_save_locations")
                        if (table == "save_backups") {
                            val entry = "$DIRECTORY/${owner.key}/$relative"
                            require(if (exportedFiles != null) entry in exportedFiles else File(staging, entry).isFile) {
                                "La sauvegarde MKXP-Z référencée est absente de l’archive."
                            }
                        }
                        references += Rebind(table, column, c.getString(c.getColumnIndexOrThrow("id")), Uri.fromFile(file).toString())
                    }
                }
            }
        }
        val byKey = owners.values.associateBy(Owner::key)
        val source = File(staging, DIRECTORY)
        val files = source.walkTopDown().filter(File::isFile).map { file ->
            val path = file.relativeTo(source).invariantSeparatorsPath
            val key = path.substringBefore('/')
            val owner = byKey[key] ?: error("Jeu propriétaire du fichier MKXP-Z absent.")
            require('/' in path) { "Chemin MKXP-Z incomplet." }
            val destination = MkxpzSavePaths.resolve(context, owner.id, owner.engine, path.substringAfter('/'), false)
            file to destination
        }.toList()
        return ImportPlan(files, references)
    }

    fun allowedArchiveFile(path: String): Boolean {
        if (!path.startsWith("$DIRECTORY/")) return false
        val rest = path.removePrefix("$DIRECTORY/")
        if (!Regex("[a-f0-9]{64}").matches(rest.substringBefore('/')) || '/' !in rest) return false
        val relative = rest.substringAfter('/')
        return listOf("RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE").any { MkxpzSavePaths.validRelative(relative, it, false) }
    }
}
