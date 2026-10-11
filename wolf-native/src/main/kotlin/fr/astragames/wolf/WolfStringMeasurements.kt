package fr.astragames.wolf

import java.nio.charset.StandardCharsets

/** Logical Wolf string measurements, not Android font or pixel measurements. */
internal object WolfStringMeasurements {
    private const val MAX_LENGTH = 1_000_000

    /**
     * W3 a2e64c counts UTF-8 bytes after reversing compiled controls (0094e760).
     * Plain text and protected backslashes are qualified. Other formatting must not
     * be counted as raw source: the original normalizes or removes compiled controls.
     */
    fun byteCount(input: String, convertSpecial: Boolean = true): Int {
        require(input.length <= MAX_LENGTH) { "Chaîne à mesurer supérieure à la capacité native" }
        val source = input.substringBefore('\u0000')
        if (!convertSpecial) return source.toByteArray(StandardCharsets.UTF_8).size
        val decoded = buildString {
            var offset = 0
            while (offset < source.length) {
                if (source.startsWith("\\\\", offset)) {
                    append('\\'); offset += 2
                } else {
                    require(WolfTextCodes.escape.matchAt(source, offset) == null) {
                        "Comptage d'octets avec codes de mise en forme non encore qualifié"
                    }
                    append(source[offset++])
                }
            }
        }
        return decoded.toByteArray(StandardCharsets.UTF_8).size
    }

    /**
     * W3 a2e36c / 00426470 removes display controls, keeps ruby's base, omits image
     * arguments and CR/LF, then 0042a760 counts UTF-8 one-byte characters as 1 and
     * others as 2, except U+FF66..U+FFDF as 1. This is intentionally not wcwidth.
     * Variables must already have been expanded by the VM.
     */
    fun widthCount(input: String, convertSpecial: Boolean = true): Int {
        require(input.length <= MAX_LENGTH) { "Chaîne à mesurer supérieure à la capacité native" }
        val source = input.substringBefore('\u0000')
        if (convertSpecial) WolfTextCodes.presentationProblem(source)?.let { error(it) }
        val visible = if (!convertSpecial) source else buildString {
            var offset = 0
            while (offset < source.length) {
                val match = WolfTextCodes.escape.matchAt(source, offset)
                if (match != null) {
                    val family = match.groupValues[1].ifEmpty { match.groupValues[3] }
                    when (family) {
                        "\\" -> append('\\')
                        "r" -> append(match.groupValues[2].substringBefore(','))
                    }
                    offset += match.value.length
                } else if (source.startsWith("<L>", offset) || source.startsWith("<C>", offset) || source.startsWith("<R>", offset)) {
                    offset += 3
                } else append(source[offset++])
            }
        }
        var width = 0
        var offset = 0
        while (offset < visible.length) {
            val cp = visible.codePointAt(offset)
            if (cp != '\r'.code && cp != '\n'.code) width += if (cp <= 0x7f || cp in 0xff66..0xffdf) 1 else 2
            offset += Character.charCount(cp)
        }
        return width
    }

    /** W3 0042ad20: zero-based inclusive LF lines; negative start and reversed end clamp. */
    fun cutLines(input: String, start: Int, end: Int): String {
        require(input.length <= MAX_LENGTH) { "Chaîne à découper supérieure à la capacité native" }
        val firstLine = start.coerceAtLeast(0)
        val lastLine = maxOf(firstLine, end)
        var line = 0
        var firstOffset = 0
        while (line < firstLine) {
            val newline = input.indexOf('\n', firstOffset)
            if (newline < 0) return ""
            firstOffset = newline + 1
            line++
        }
        var after = firstOffset
        var lastOffset = input.length
        while (line <= lastLine) {
            val newline = input.indexOf('\n', after)
            if (newline < 0) { lastOffset = input.length; break }
            lastOffset = newline
            after = newline + 1
            if (line == lastLine) break
            line++
        }
        return input.substring(firstOffset, lastOffset)
    }
}
