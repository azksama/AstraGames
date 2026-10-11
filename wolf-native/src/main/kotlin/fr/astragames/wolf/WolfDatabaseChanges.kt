package fr.astragames.wolf

/** CSV imports can change User/System DB in memory; the parsed game metadata remains immutable. */
class WolfDatabaseChanges(private val databases: Map<WolfDatabaseKind, WolfDatabase>) {
    data class RowKey(val kind: WolfDatabaseKind, val type: Int)
    data class Cell(val kind: WolfDatabaseKind, val type: Int, val row: Int, val field: Int)
    data class State(val rows: Map<RowKey, Int>, val numbers: Map<Cell, Int>, val strings: Map<Cell, String>,
        val names: Map<Pair<RowKey, Int>, String> = emptyMap())

    private var current = State(emptyMap(), emptyMap(), emptyMap())

    fun state(): State? = current.takeUnless { it.rows.isEmpty() && it.numbers.isEmpty() && it.strings.isEmpty() && it.names.isEmpty() }
        ?.let { State(it.rows.toMap(), it.numbers.toMap(), it.strings.toMap(), it.names.toMap()) }

    fun clear() { current = State(emptyMap(), emptyMap(), emptyMap()) }
    fun count(kind: WolfDatabaseKind, type: Int): Int = current.rows[RowKey(kind, type)] ?: descriptor(kind, type).rows.size
    fun name(kind: WolfDatabaseKind, type: Int, row: Int): String? = current.names[RowKey(kind, type) to row]
    fun number(kind: WolfDatabaseKind, type: Int, row: Int, field: Int): Int? {
        current.numbers[Cell(kind, type, row, field)]?.let { return it }
        val typeData = descriptor(kind, type)
        return if (row in typeData.rows.size until count(kind, type))
            typeData.fields.getOrNull(field)?.takeIf { it.valueType == WolfValueType.NUMBER }?.defaultValue else null
    }
    fun string(kind: WolfDatabaseKind, type: Int, row: Int, field: Int): String? {
        current.strings[Cell(kind, type, row, field)]?.let { return it }
        val typeData = descriptor(kind, type)
        return if (row in typeData.rows.size until count(kind, type) &&
            typeData.fields.getOrNull(field)?.valueType == WolfValueType.STRING) "" else null
    }

    /** The entire import is checked before replacing live values, including names and added rows. */
    internal fun replace(kind: WolfDatabaseKind, type: Int, count: Int, numbers: Map<Cell, Int>,
        strings: Map<Cell, String>, names: Map<Int, String> = emptyMap()) {
        val key = RowKey(kind, type)
        val rows = current.rows + (key to maxOf(count, this.count(kind, type)))
        val next = State(rows, current.numbers + numbers, current.strings + strings,
            current.names + names.mapKeys { key to it.key })
        validate(next)
        current = next
    }

    fun restore(state: State?) {
        val next = state ?: State(emptyMap(), emptyMap(), emptyMap())
        validate(next)
        current = State(next.rows.toMap(), next.numbers.toMap(), next.strings.toMap(), next.names.toMap())
    }

    private fun validate(state: State) {
        require(state.rows.size <= 300 && state.numbers.size.toLong() + state.strings.size + state.names.size <= 100_000) { "Import DB supérieur à la capacité de sauvegarde" }
        state.rows.forEach { (key, count) ->
            require(count in descriptor(key.kind, key.type).rows.size..10_000) { "Nombre de rangées DB importées invalide" }
        }
        fun field(cell: Cell, kind: WolfValueType) {
            require(cell.kind != WolfDatabaseKind.MUTABLE) { "Overlay de DB mutable dupliqué" }
            val type = descriptor(cell.kind, cell.type)
            require(cell.row in 0 until (state.rows[RowKey(cell.kind, cell.type)] ?: type.rows.size) &&
                type.fields.getOrNull(cell.field)?.valueType == kind) { "Cellule DB importée incompatible" }
        }
        state.numbers.keys.forEach { field(it, WolfValueType.NUMBER) }
        state.strings.forEach { (cell, text) -> field(cell, WolfValueType.STRING); require(text.length <= 1_000_000) { "Chaîne DB importée trop grande" } }
        state.names.forEach { (at, name) ->
            val (key, row) = at
            require(row in 0 until (state.rows[key] ?: descriptor(key.kind, key.type).rows.size) && name.length <= 1_000_000) { "Nom DB importé invalide" }
        }
    }
    private fun descriptor(kind: WolfDatabaseKind, type: Int): WolfDatabaseType =
        databases[kind]?.types?.getOrNull(type) ?: error("Type DB importé absent : $kind/$type")
}
