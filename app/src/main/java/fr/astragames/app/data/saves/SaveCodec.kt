package fr.astragames.app.data.saves

/** Pure format handling: no file writes, Android context or engine-specific UI branches. */
internal object SaveCodec {
    fun read(engine: String, bytes: ByteArray): SaveData = when (engine) {
        "RENPY" -> SaveData.RawSave(flattenPickle(PickleParser(RenPyArchive.decode(bytes).payload).parse()))
        "RPG_MAKER_MV", "RPG_MAKER_MZ" -> SaveData.JsonSave(RpgMakerSaveCodec.decode(bytes).json)
        "RPG_MAKER_XP", "RPG_MAKER_VX", "RPG_MAKER_VX_ACE" ->
            SaveData.RawSave(MarshalFlattener.flatten(rgssRoot(Marshal.loadAll(bytes))))
        else -> error("Moteur non pris en charge.")
    }

    private fun rgssRoot(roots: List<MarshalValue>): MarshalValue =
        if (roots.size == 1) roots.single() else MarshalValue.ArrayValue(roots.toMutableList())

    fun entries(data: SaveData): List<SaveEntry> = when (data) {
        is SaveData.JsonSave -> JsonSaveEditor.flatten(data.json)
        is SaveData.RawSave -> data.entries
    }

    fun patch(engine: String, original: ByteArray, edits: List<SaveEdit>): ByteArray {
        require(edits.isNotEmpty() && edits.distinctBy { it.path }.size == edits.size) { "Modifications vides ou dupliquees." }
        val available = entries(read(engine, original)).associateBy { it.path }
        edits.forEach { edit ->
            val entry = available[edit.path] ?: error("Chemin introuvable : ${edit.path}")
            require(entry.editable && entry.type == edit.type) { "Valeur non editable : ${edit.path}" }
            val value = SaveValues.parse(edit)
            if (engine in setOf("RPG_MAKER_MV", "RPG_MAKER_MZ") && value is Long) {
                require(value in -9_007_199_254_740_991L..9_007_199_254_740_991L) { "Entier hors de la precision JavaScript du jeu." }
            }
        }
        return when (engine) {
            "RENPY" -> {
                val archive = RenPyArchive.decode(original)
                var bytes = archive.payload
                edits.forEach { edit ->
                    val parser = PickleParser(bytes)
                    val node = findPickleNode(parser.parse(), edit.path) ?: error("Chemin pickle introuvable.")
                    val range = parser.range(node) ?: error("Plage pickle absente.")
                    bytes = when (val value = SaveValues.parse(edit)) {
                        is Long -> PickleSplicer.spliceInt(bytes, range, value)
                        is Double -> PickleSplicer.spliceFloat(bytes, range, value)
                        is Boolean -> PickleSplicer.spliceBool(bytes, range, value)
                        is String -> PickleSplicer.spliceString(bytes, range, value, legacyString = node is PickleNode.PBytes && node.legacyString)
                        else -> error("Type non editable.")
                    }
                }
                PickleParser(bytes).parse()
                RenPyArchive.encode(archive, bytes)
            }
            "RPG_MAKER_MV", "RPG_MAKER_MZ" -> {
                val decoded = RpgMakerSaveCodec.decode(original)
                edits.forEach { JsonSaveEditor.apply(decoded.json, it) }
                RpgMakerSaveCodec.encode(decoded)
            }
            else -> {
                // Keep the actual stream roots mutable too: XP/VX store scalar headers in separate streams.
                val roots = Marshal.loadAll(original).toMutableList()
                val root = MarshalValue.ArrayValue(roots)
                edits.forEach {
                    val path = if (roots.size == 1) "root[0]" + it.path.removePrefix("root") else it.path
                    check(MarshalFlattener.applyEdit(root, path, it.newValue, it.type))
                }
                roots.fold(ByteArray(0)) { output, value -> output + Marshal.dump(value) }
            }
        }.also { patched ->
            require(patched.size <= MAX_SAVE_BYTES) { "Sauvegarde modifiee trop volumineuse." }
            val after = entries(read(engine, patched)).associateBy { it.path }
            edits.forEach { edit ->
                val actual = after[edit.path] ?: error("Verification impossible : ${edit.path}")
                check(SaveValues.parse(edit.copy(newValue = actual.displayValue)) == SaveValues.parse(edit)) {
                    "Verification echouee : ${edit.path}"
                }
            }
        }
    }
}

internal object SaveValues {
    fun parse(edit: SaveEdit): Any = when (edit.type) {
        SaveEntryType.INT, SaveEntryType.VARIABLE -> edit.newValue.trim().toLongOrNull() ?: error("Nombre entier invalide.")
        SaveEntryType.FLOAT -> edit.newValue.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Nombre decimal invalide.")
        SaveEntryType.BOOLEAN, SaveEntryType.SWITCH -> when (edit.newValue.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> error("Booleen attendu : true ou false.")
        }
        SaveEntryType.STRING -> edit.newValue
        else -> error("Type non editable.")
    }
}
