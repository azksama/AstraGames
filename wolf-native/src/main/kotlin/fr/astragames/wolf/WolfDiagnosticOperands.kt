package fr.astragames.wolf

/** Bounded wire operands for aggregate diagnostics, without copying game dialogue. */
internal object WolfDiagnosticOperands {
    private fun words(values: IntArray): String = values.take(32).joinToString(",") {
        "$it(0x${it.toUInt().toString(16)})"
    } + if (values.size > 32) ",…(${values.size} mots)" else ""
    private fun hex(bytes: ByteArray) = bytes.take(64).joinToString("") { "%02x".format(it.toInt().and(255)) } +
        if (bytes.size > 64) "…(${bytes.size} octets)" else ""
    private fun route(value: WolfRoute) = "routeFlags=0x${value.flags.toString(16)}; routeHeader=${hex(value.header)}; route=" +
        value.commands.take(16).joinToString(";") { "${it.opcode}[${words(it.arguments)}]" } +
        if (value.commands.size > 16) ";…(${value.commands.size} commandes)" else ""
    fun command(value: WolfCommand): String = "args=[${words(value.arguments)}]; extension=${hex(value.extension)}" +
        value.route?.let { "; ${route(it)}" }.orEmpty()
    fun page(value: WolfPage): String = "page=${value.index}; movement=${hex(value.movement)}; " + route(value.route)
}
