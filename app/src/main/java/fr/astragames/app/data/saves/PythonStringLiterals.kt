package fr.astragames.app.data.saves

/** Decode pickle's literal escapes without evaluating Python code. */
internal fun decodePythonEscapes(raw: String, unicode: Boolean): String = buildString {
    var index = 0
    while (index < raw.length) {
        val character = raw[index++]
        if (character != '\\' || index == raw.length) { append(character); continue }
        val escaped = raw[index++]
        val digits = when {
            unicode && escaped == 'u' -> 4
            unicode && escaped == 'U' -> 8
            !unicode && escaped == 'x' -> 2
            else -> 0
        }
        if (digits > 0) {
            require(index + digits <= raw.length) { "Echappement pickle tronque." }
            val codePoint = raw.substring(index, index + digits).toLongOrNull(16)
                ?.takeIf { it in 0..0x10ffff } ?: error("Echappement pickle invalide.")
            appendCodePoint(codePoint.toInt())
            index += digits
        } else if (unicode) {
            append('\\'); append(escaped)
        } else if (escaped in '0'..'7') {
            val start = index - 1
            while (index < raw.length && index - start < 3 && raw[index] in '0'..'7') index++
            append((raw.substring(start, index).toInt(8) and 255).toChar())
        } else {
            when (escaped) {
                '\\', '\'', '"' -> append(escaped)
                'n' -> append('\n')
                'r' -> append('\r')
                't' -> append('\t')
                'b' -> append('\b')
                'f' -> append('\u000c')
                'a' -> append('\u0007')
                'v' -> append('\u000b')
                '\n' -> Unit
                else -> { append('\\'); append(escaped) }
            }
        }
    }
}
