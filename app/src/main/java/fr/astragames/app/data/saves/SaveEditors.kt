package fr.astragames.app.data.saves

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.astragames.app.data.local.AstraDao
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.GameSaveLocationEntity
import fr.astragames.app.data.local.SaveBackupEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

interface SaveEditorEngine {
    fun supports(game: GameEntity): Boolean
    suspend fun findSaves(game: GameEntity, locations: List<GameSaveLocationEntity>): List<GameSave>
    suspend fun read(save: GameSave): SaveData
    suspend fun write(save: GameSave, data: SaveData)
}

data class SaveEdit(
    val path: String,
    val type: SaveEntryType,
    val newValue: String
)

data class SimpleSaveFields(
    val money: SaveEntry? = null,
    val level: SaveEntry? = null,
    val experience: SaveEntry? = null,
    val hp: SaveEntry? = null,
    val mp: SaveEntry? = null,
    val inventory: List<SaveEntry> = emptyList(),
    val relations: List<SaveEntry> = emptyList(),
    val progress: List<SaveEntry> = emptyList(),
    val variables: List<SaveEntry> = emptyList(),
    val switches: List<SaveEntry> = emptyList()
)

object SaveEditorFactory {
    fun forEngine(engine: String, context: Context, finder: SaveFinder): SaveEditorEngine? = when (engine) {
        "RENPY" -> RenPySaveEditor(context, finder)
        "RPG_MAKER_MV" -> RpgMakerJsonSaveEditor(context, finder, "RPG_MAKER_MV")
        "RPG_MAKER_MZ" -> RpgMakerJsonSaveEditor(context, finder, "RPG_MAKER_MZ")
        "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE" -> RgssSaveEditor(context, finder)
        else -> null
    }
}

class RenPySaveEditor(
    private val context: Context,
    private val finder: SaveFinder
) : SaveEditorEngine {
    override fun supports(game: GameEntity) = game.engine == "RENPY"

    override suspend fun findSaves(game: GameEntity, locations: List<GameSaveLocationEntity>) =
        finder.saves(game, locations)

    override suspend fun read(save: GameSave): SaveData {
        val bytes = readBytes(context, save.uri)
        val payload = RenPyArchive.unwrap(bytes).first
        val parser = PickleParser(payload)
        val root = parser.parse()
        return SaveData.RawSave(flattenPickle(root))
    }

    override suspend fun write(save: GameSave, data: SaveData) {
        error("Utiliser applyEdits pour les sauvegardes Ren Py.")
    }

    fun applyEdits(original: ByteArray, edits: List<SaveEdit>): ByteArray {
        val unwrapped = RenPyArchive.unwrap(original)
        var current = unwrapped.first
        val compressed = unwrapped.second
        edits.forEach { edit ->
            val parser = PickleParser(current)
            val root = parser.parse()
            val node = findPickleNode(root, edit.path) ?: error("Chemin introuvable : " + edit.path)
            val range = parser.range(node) ?: error("Plage pickle introuvable pour " + edit.path)
            current = when (edit.type) {
                SaveEntryType.INT, SaveEntryType.VARIABLE -> {
                    val value = edit.newValue.trim().toLongOrNull() ?: error("Nombre invalide")
                    PickleSplicer.spliceInt(current, range, value)
                }
                SaveEntryType.FLOAT -> {
                    val value = edit.newValue.trim().toDoubleOrNull() ?: error("Nombre decimal invalide")
                    PickleSplicer.spliceFloat(current, range, value)
                }
                SaveEntryType.BOOLEAN, SaveEntryType.SWITCH -> PickleSplicer.spliceBool(
                    current, range, edit.newValue.trim().equals("true", ignoreCase = true)
                )
                SaveEntryType.STRING -> PickleSplicer.spliceString(current, range, edit.newValue)
                else -> error("Type non editable")
            }
        }
        return RenPyArchive.wrap(current, compressed)
    }
}

class RpgMakerJsonSaveEditor(
    private val context: Context,
    private val finder: SaveFinder,
    private val engine: String
) : SaveEditorEngine {
    override fun supports(game: GameEntity) = game.engine == engine

    override suspend fun findSaves(game: GameEntity, locations: List<GameSaveLocationEntity>) =
        finder.saves(game, locations)

    override suspend fun read(save: GameSave): SaveData {
        val text = String(readBytes(context, save.uri), StandardCharsets.UTF_8)
        val json = runCatching { JSONObject(text) }.getOrElse { error("Sauvegarde JSON illisible.") }
        return SaveData.JsonSave(json)
    }

    override suspend fun write(save: GameSave, data: SaveData) {
        val json = (data as? SaveData.JsonSave)?.json ?: error("Donnees JSON attendues.")
        writeBytes(context, save.uri, json.toString().toByteArray(StandardCharsets.UTF_8))
    }
}

class RgssSaveEditor(
    private val context: Context,
    private val finder: SaveFinder
) : SaveEditorEngine {
    override fun supports(game: GameEntity) =
        game.engine in setOf("RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE")

    override suspend fun findSaves(game: GameEntity, locations: List<GameSaveLocationEntity>) =
        finder.saves(game, locations)

    override suspend fun read(save: GameSave): SaveData {
        val root = Marshal.load(readBytes(context, save.uri))
        return SaveData.RawSave(MarshalFlattener.flatten(root))
    }

    override suspend fun write(save: GameSave, data: SaveData) {
        error("Utiliser applyEdits pour les sauvegardes RGSS.")
    }

    fun applyEdits(original: ByteArray, edits: List<SaveEdit>): ByteArray {
        val root = Marshal.load(original)
        edits.forEach { edit ->
            check(MarshalFlattener.applyEdit(root, edit.path, edit.newValue, edit.type)) {
                "Impossible d appliquer " + edit.path
            }
        }
        return Marshal.dump(root)
    }
}

object JsonSaveEditor {
    fun flatten(json: JSONObject, limit: Int = 4000): List<SaveEntry> {
        val result = mutableListOf<SaveEntry>()
        fun walk(value: Any?, path: String, depth: Int) {
            if (result.size >= limit || depth > 8) return
            when (value) {
                null, JSONObject.NULL -> result += SaveEntry(path, SaveEntryType.UNKNOWN, "null", false)
                is Boolean -> result += SaveEntry(path, SaveEntryType.BOOLEAN, if (value) "true" else "false", true)
                is Int, is Long -> result += SaveEntry(path, SaveEntryType.INT, value.toString(), true)
                is Double, is Float -> result += SaveEntry(path, SaveEntryType.FLOAT, value.toString(), true)
                is Number -> {
                    val asDouble = value.toDouble()
                    val type = if (asDouble % 1.0 == 0.0) SaveEntryType.INT else SaveEntryType.FLOAT
                    val shown = if (type == SaveEntryType.INT) asDouble.toLong().toString() else asDouble.toString()
                    result += SaveEntry(path, type, shown, true)
                }
                is String -> result += SaveEntry(path, SaveEntryType.STRING, value, true)
                is JSONObject -> {
                    result += SaveEntry(path, SaveEntryType.OBJECT, "{" + value.length() + "}", false)
                    value.keys().asSequence().take(250).forEach { key -> walk(value.opt(key), path + "." + key, depth + 1) }
                }
                is JSONArray -> {
                    result += SaveEntry(path, SaveEntryType.LIST, "[" + value.length() + "]", false)
                    for (index in 0 until value.length().coerceAtMost(250)) {
                        walk(value.opt(index), path + "[" + index + "]", depth + 1)
                    }
                }
            }
        }
        walk(json, "root", 0)
        return result
    }

    fun apply(json: JSONObject, edit: SaveEdit) {
        val tokens = parseJsonPath(edit.path) ?: error("Chemin JSON invalide")
        require(tokens.isNotEmpty()) { "Chemin JSON vide" }
        var current: Any = json
        tokens.dropLast(1).forEach { token ->
            current = when (token) {
                is JsonToken.Field -> (current as? JSONObject)?.opt(token.name)
                is JsonToken.Index -> (current as? JSONArray)?.opt(token.index)
            } ?: error("Chemin introuvable : " + edit.path)
        }
        val last = tokens.last()
        val coerced: Any = when (edit.type) {
            SaveEntryType.INT, SaveEntryType.VARIABLE -> edit.newValue.trim().toLongOrNull() ?: error("Nombre invalide")
            SaveEntryType.FLOAT -> edit.newValue.trim().toDoubleOrNull() ?: error("Nombre decimal invalide")
            SaveEntryType.BOOLEAN, SaveEntryType.SWITCH -> edit.newValue.trim().equals("true", ignoreCase = true)
            SaveEntryType.STRING -> edit.newValue
            else -> error("Type non editable")
        }
        when (last) {
            is JsonToken.Field -> (current as? JSONObject)?.put(last.name, coerced) ?: error("Objet JSON attendu")
            is JsonToken.Index -> (current as? JSONArray)?.put(last.index, coerced) ?: error("Tableau JSON attendu")
        }
    }

    private sealed interface JsonToken {
        data class Field(val name: String) : JsonToken
        data class Index(val index: Int) : JsonToken
    }

    private fun parseJsonPath(path: String): List<JsonToken>? {
        if (!path.startsWith("root")) return null
        var rest = path.removePrefix("root")
        val tokens = mutableListOf<JsonToken>()
        while (rest.isNotEmpty()) {
            when {
                rest.startsWith(".") -> {
                    val body = rest.substring(1)
                    val end = body.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) body.length else it }
                    tokens += JsonToken.Field(body.substring(0, end))
                    rest = body.substring(end)
                }
                rest.startsWith("[") -> {
                    val end = rest.indexOf(']')
                    if (end < 0) return null
                    val index = rest.substring(1, end).toIntOrNull() ?: return null
                    tokens += JsonToken.Index(index)
                    rest = rest.substring(end + 1)
                }
                else -> return null
            }
        }
        return tokens
    }
}

object SaveFieldClassifier {
    fun classify(entries: List<SaveEntry>): SimpleSaveFields {
        fun first(vararg needles: String) = entries.firstOrNull { entry ->
            entry.editable && needles.any { needle -> entry.path.lowercase(Locale.ROOT).contains(needle) }
        }
        fun many(vararg needles: String) = entries.filter { entry ->
            entry.editable && needles.any { needle -> entry.path.lowercase(Locale.ROOT).contains(needle) }
        }.take(40)
        return SimpleSaveFields(
            money = first("gold", "money", "argent", "_gold", "partygold"),
            level = first("level", "niveau", "_level"),
            experience = first("exp", "experience", "xp", "_exp"),
            hp = first(".hp", "_hp", "hitpoints", "hit_points", "currenthp"),
            mp = first(".mp", "_mp", "mana", "sp", "currentmp"),
            inventory = many("item", "invent", "bag", "equip"),
            relations = many("affection", "relation", "love", "friend"),
            progress = many("chapter", "progress", "scene", "label", "map"),
            variables = many("variable", "var[", "gamevariables"),
            switches = many("switch", "flag", "gameswitches")
        )
    }
}

class SaveManager(
    private val context: Context,
    private val dao: AstraDao,
    private val finder: SaveFinder
) {
    fun observeLocations(gameId: String) = dao.observeSaveLocations(gameId)
    fun observeBackups(gameId: String) = dao.observeSaveBackups(gameId)

    suspend fun locations(gameId: String) = dao.getSaveLocations(gameId)

    suspend fun detectLocations(game: GameEntity): List<GameSaveLocationEntity> {
        val root = documentDir(context, Uri.parse(game.documentUri)) ?: return emptyList()
        val detected = finder.locations(game, root.uri)
        val existing = dao.getSaveLocations(game.id).map { it.uri }.toSet()
        detected.filter { it.uri !in existing }.forEach { dao.upsertSaveLocation(it) }
        return dao.getSaveLocations(game.id)
    }

    suspend fun addLocation(game: GameEntity, uri: Uri, displayName: String): GameSaveLocationEntity {
        persistTree(uri)
        val location = GameSaveLocationEntity(
            id = UUID.randomUUID().toString(),
            gameId = game.id,
            uri = uri.toString(),
            type = "CUSTOM",
            displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            autoDetected = false,
            enabled = true,
            addedAt = System.currentTimeMillis()
        )
        dao.upsertSaveLocation(location)
        return location
    }

    suspend fun removeLocation(id: String) = dao.deleteSaveLocation(id)

    suspend fun listSaves(game: GameEntity): List<GameSave> {
        val stored = dao.getSaveLocations(game.id)
        val locations = if (stored.isEmpty()) detectLocations(game) else stored
        return finder.saves(game, locations)
    }

    suspend fun readSave(game: GameEntity, save: GameSave): Pair<SaveData, List<SaveEntry>> {
        val editor = SaveEditorFactory.forEngine(game.engine, context, finder)
            ?: error("Moteur non pris en charge")
        val data = editor.read(save)
        val entries = when (data) {
            is SaveData.JsonSave -> JsonSaveEditor.flatten(data.json)
            is SaveData.RawSave -> data.entries
        }
        return data to entries
    }

    suspend fun writeSave(game: GameEntity, save: GameSave, edits: List<SaveEdit>): SaveBackupEntity {
        require(edits.isNotEmpty()) { "Aucune modification a enregistrer." }
        val original = readBytes(context, save.uri)
        val backup = backup(game, save, original)
        val patched = when (game.engine) {
            "RENPY" -> RenPySaveEditor(context, finder).applyEdits(original, edits)
            "RPG_MAKER_MV", "RPG_MAKER_MZ" -> {
                val json = JSONObject(String(original, StandardCharsets.UTF_8))
                edits.forEach { JsonSaveEditor.apply(json, it) }
                json.toString().toByteArray(StandardCharsets.UTF_8)
            }
            "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE" ->
                RgssSaveEditor(context, finder).applyEdits(original, edits)
            else -> error("Moteur non pris en charge")
        }
        writeBytes(context, save.uri, patched)
        return backup
    }

    suspend fun backup(game: GameEntity, save: GameSave, original: ByteArray = readBytes(context, save.uri)): SaveBackupEntity {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backupName = save.name + ".astra-backup-" + stamp
        val parent = parentDocument(save.uri)
        val backupUri: String
        if (parent != null) {
            val created = parent.createFile("application/octet-stream", backupName)
                ?: error("Impossible de creer le backup.")
            context.contentResolver.openOutputStream(created.uri, "w")?.use { it.write(original) }
                ?: error("Ecriture du backup impossible.")
            backupUri = created.uri.toString()
        } else {
            val dir = java.io.File(context.filesDir, "save-backups/" + game.id)
            dir.mkdirs()
            val file = java.io.File(dir, backupName)
            file.writeBytes(original)
            backupUri = android.net.Uri.fromFile(file).toString()
        }
        val entity = SaveBackupEntity(
            id = UUID.randomUUID().toString(),
            gameId = game.id,
            sourceUri = save.uri,
            sourceName = save.name,
            backupUri = backupUri,
            createdAt = System.currentTimeMillis(),
            sizeBytes = original.size.toLong()
        )
        dao.insertSaveBackup(entity)
        rotateBackups(save.uri)
        return entity
    }

    suspend fun restoreBackup(backup: SaveBackupEntity) {
        val bytes = readBackupBytes(backup.backupUri)
        writeBytes(context, backup.sourceUri, bytes)
    }

    suspend fun deleteBackup(backup: SaveBackupEntity) {
        val uri = Uri.parse(backup.backupUri)
        if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
        else DocumentFile.fromSingleUri(context, uri)?.delete()
        dao.deleteSaveBackup(backup.id)
    }

    private fun readBackupBytes(uriValue: String): ByteArray {
        val uri = Uri.parse(uriValue)
        if (uri.scheme == "file") return java.io.File(uri.path ?: error("Backup local introuvable.")).readBytes()
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Lecture du backup impossible.")
    }

    private suspend fun rotateBackups(sourceUri: String) {
        val existing = dao.getSaveBackupsForSource(sourceUri)
        existing.drop(5).forEach { stale ->
            val uri = Uri.parse(stale.backupUri)
            if (uri.scheme == "file") uri.path?.let { java.io.File(it).delete() }
            else DocumentFile.fromSingleUri(context, uri)?.delete()
            dao.deleteSaveBackup(stale.id)
        }
    }

    private fun persistTree(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun parentDocument(fileUri: String): DocumentFile? {
        val file = DocumentFile.fromSingleUri(context, Uri.parse(fileUri)) ?: return null
        return file.parentFile
    }
}

internal fun flattenPickle(root: PickleNode, limit: Int = 4000): List<SaveEntry> {
    val result = mutableListOf<SaveEntry>()
    fun walk(node: PickleNode, path: String, depth: Int) {
        if (result.size >= limit || depth > 8) return
        when (node) {
            is PickleNode.PInt -> result += SaveEntry(path, SaveEntryType.INT, node.value.toString(), true)
            is PickleNode.PFloat -> result += SaveEntry(path, SaveEntryType.FLOAT, node.value.toString(), true)
            is PickleNode.PBool -> result += SaveEntry(path, SaveEntryType.BOOLEAN, if (node.value) "true" else "false", true)
            is PickleNode.PString -> result += SaveEntry(path, SaveEntryType.STRING, node.text, true)
            is PickleNode.PBytes -> result += SaveEntry(path, SaveEntryType.STRING, String(node.bytes, StandardCharsets.UTF_8), false)
            is PickleNode.PNil -> result += SaveEntry(path, SaveEntryType.UNKNOWN, "null", false)
            is PickleNode.PList -> {
                result += SaveEntry(path, SaveEntryType.LIST, "[" + node.items.size + "]", false)
                node.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
            }
            is PickleNode.PTuple -> {
                result += SaveEntry(path, SaveEntryType.LIST, "(" + node.items.size + ")", false)
                node.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
            }
            is PickleNode.PDict -> {
                result += SaveEntry(path, SaveEntryType.OBJECT, "{" + node.entries.size + "}", false)
                node.entries.entries.take(200).forEach { (key, value) ->
                    walk(value, path + "[" + pickleKey(key) + "]", depth + 1)
                }
            }
            is PickleNode.PSet -> {
                result += SaveEntry(path, SaveEntryType.LIST, "set(" + node.items.size + ")", false)
                node.items.forEachIndexed { index, item -> walk(item, path + "[" + index + "]", depth + 1) }
            }
            is PickleNode.PObject -> {
                result += SaveEntry(path, SaveEntryType.OBJECT, node.className, false)
                node.args.forEachIndexed { index, item -> walk(item, path + ".arg" + index, depth + 1) }
                node.state?.let { walk(it, path + ".state", depth + 1) }
            }
        }
    }
    walk(root, "root", 0)
    return result
}

internal fun findPickleNode(root: PickleNode, path: String): PickleNode? {
    if (path == "root") return root
    if (!path.startsWith("root")) return null
    var current: PickleNode = root
    var rest = path.removePrefix("root")
    while (rest.isNotEmpty()) {
        when {
            rest.startsWith(".arg") -> {
                val body = rest.removePrefix(".arg")
                val end = body.indexOfFirst { it == '.' || it == '[' }.let { if (it < 0) body.length else it }
                val index = body.substring(0, end).toIntOrNull() ?: return null
                current = (current as? PickleNode.PObject)?.args?.getOrNull(index) ?: return null
                rest = body.substring(end)
            }
            rest.startsWith(".state") -> {
                current = (current as? PickleNode.PObject)?.state ?: return null
                rest = rest.removePrefix(".state")
            }
            rest.startsWith(".") -> return null
            rest.startsWith("[") -> {
                val end = rest.indexOf(']')
                if (end < 0) return null
                val inside = rest.substring(1, end)
                current = when (current) {
                    is PickleNode.PList -> current.items.getOrNull(inside.toIntOrNull() ?: return null)
                    is PickleNode.PTuple -> current.items.getOrNull(inside.toIntOrNull() ?: return null)
                    is PickleNode.PSet -> current.items.getOrNull(inside.toIntOrNull() ?: return null)
                    is PickleNode.PDict -> current.entries.entries.firstOrNull { pickleKey(it.key) == inside }?.value
                    else -> null
                } ?: return null
                rest = rest.substring(end + 1)
            }
            else -> return null
        }
    }
    return current
}

internal fun pickleKey(node: PickleNode): String = when (node) {
    is PickleNode.PString -> node.text
    is PickleNode.PInt -> node.value.toString()
    is PickleNode.PBool -> if (node.value) "true" else "false"
    is PickleNode.PBytes -> String(node.bytes, StandardCharsets.UTF_8)
    else -> "?"
}

internal fun readBytes(context: Context, uri: String): ByteArray {
    val parsed = Uri.parse(uri)
    return context.contentResolver.openInputStream(parsed)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("Lecture impossible.")
}

internal fun writeBytes(context: Context, uri: String, bytes: ByteArray) {
    context.contentResolver.openOutputStream(Uri.parse(uri), "w")?.use { it.write(bytes) }
        ?: error("Ecriture impossible.")
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
