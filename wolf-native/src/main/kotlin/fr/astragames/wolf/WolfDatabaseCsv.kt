package fr.astragames.wolf

/** Reconstructed 4bf540/942540 CSV states, including Wolf's header and data-name extension. */
internal object WolfDatabaseCsv {
    private const val NAME = "<<!--DATANAME--!>>"
    data class Row(val fields: Map<Int, String>, val name: String? = null)

    fun read(input: String, fields: Int, count: Int, modern: Boolean): List<Row> {
        require(input.length <= 16 * 1024 * 1024 && fields in 0..100 && count in 1..10_000) { "CSV DB hors capacité" }
        val text = input.substringBefore('\u0000').removePrefix("\ufeff")
        // The first physical line is a heading, not database row zero.
        var offset = text.indexOf('\n').let { if (it < 0) text.length else it + 1 }
        val result = mutableListOf<Row>()
        var mode = if ('\n' in text) 0 else -1 // empty / quoted / unquoted / after-quote
        var column = 0
        var name: String? = null
        val values = linkedMapOf<Int, String>()
        val value = StringBuilder()
        fun store() {
            var captured = value.toString()
            if (modern && captured.startsWith(NAME)) {
                name = captured.substring(NAME.length).replace("<<COMMA>>", ",")
                captured = ""
            }
            if (column < fields) values[column] = captured
            value.setLength(0)
        }
        fun nextRow() {
            result.add(Row(values.toMap(), name)); values.clear(); name = null; value.setLength(0); column = 0; mode = 0
        }
        while (offset < text.length) {
            val ch = text[offset++]
            when {
                ch == '\u0001' -> error("Séquence binaire dans le CSV DB : lecture textuelle insuffisante")
                ch == '\r' -> Unit
                ch == '"' -> when (mode) {
                    0 -> mode = 1
                    1 -> if (offset < text.length && text[offset] == '"') { value.append('"'); offset++ }
                        else { store(); mode = 3 }
                }
                ch == ',' || modern && ch == '\t' -> when (mode) {
                    0, 2 -> { store(); column++; mode = 0 }
                    1 -> value.append(',') // Native TSV uses the same quoted comma branch.
                    3 -> { column++; mode = 0 }
                }
                ch == '\n' -> when (mode) {
                    1 -> value.append("\r\n")
                    0, 2, 3 -> {
                        if (mode == 2) store()
                        nextRow()
                        if (result.size >= count) return result
                    }
                }
                else -> when (mode) {
                    0 -> { mode = 2; value.append(ch) }
                    1, 2 -> value.append(ch)
                }
            }
            require(value.length <= 1_000_000) { "Champ CSV DB trop grand" }
        }
        // Native EOF does not commit an unterminated bare field. A closed quote was committed above.
        if (mode != 0 || column != 0) nextRow()
        return result
    }

    fun write(type: WolfDatabaseType, start: Int, count: Int, modern: Boolean, version: Int,
        number: (Int, Int) -> Int, string: (Int, Int) -> String, name: (Int) -> String): String {
        require(start >= 0 && count in 0..10_000 && type.fields.size <= 100) { "Export CSV DB hors capacité" }
        val out = StringBuilder()
        if (modern) out.append('\ufeff') // 941ad0 always writes a UTF-8 BOM in its binary output.
        fun quote(text: String) { out.append('"').append(if (modern && version >= 339) text.replace("\"", "\"\"") else text).append('"') }
        type.fields.forEach { quote(it.name); out.append(',') }; out.append("\r\n")
        for (row in start until start + count) {
            type.fields.forEachIndexed { field, descriptor ->
                if (descriptor.valueType == WolfValueType.NUMBER) out.append(number(row, field)) else quote(string(row, field))
                out.append(',')
            }
            if (modern && type.dataIdMethod == 0) out.append(NAME).append(name(row).replace(",", "<<COMMA>>")).append(',')
            out.append("\r\n")
            require(out.length <= 16 * 1024 * 1024) { "Export CSV DB trop grand" }
        }
        return out.toString()
    }
}
