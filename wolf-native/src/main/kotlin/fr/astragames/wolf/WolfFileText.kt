package fr.astragames.wolf

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** REA a0fe10: mode 0 detects UTF-8/SJIS, 1 forces UTF-8, 2 forces legacy SJIS. */
object WolfFileText {
    private val sjis = Charset.forName("windows-31j")
    private val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())

    fun decode(bytes: ByteArray, mode: Int, modern: Boolean): String {
        require(mode in 0..2) { "Encodage de fichier Wolf inconnu : $mode" }
        require(bytes.size <= 16 * 1024 * 1024) { "Texte Wolf trop volumineux" }
        val ended = bytes.indexOf(0).takeIf { it >= 0 } ?: bytes.size
        val hasBom = ended >= 3 && bytes.take(3).toByteArray().contentEquals(bom)
        val begin = if (hasBom) 3 else 0
        val data = ByteBuffer.wrap(bytes, begin, ended - begin)
        val charset = when {
            !modern || mode == 2 -> sjis
            mode == 1 || hasBom -> Charsets.UTF_8
            else -> try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(data.duplicate())
                Charsets.UTF_8
            } catch (_: CharacterCodingException) { sjis }
        }
        return charset.decode(data).toString()
    }

    /** Windows text-mode fopen: LF becomes CRLF; mode 1 adds one UTF-8 BOM. */
    fun encode(text: String, mode: Int, modern: Boolean, windowsTextMode: Boolean = true): ByteArray {
        require(mode in 0..2 && text.length <= 16 * 1024 * 1024) { "Texte ou encodage Wolf hors capacité" }
        val value = text.substringBefore('\u0000').let { if (windowsTextMode) it.replace("\n", "\r\n") else it }
        val data = value.toByteArray(if (!modern || mode == 2) sjis else Charsets.UTF_8)
        val result = if (modern && mode == 1 && !data.take(3).toByteArray().contentEquals(bom)) bom + data else data
        require(result.size <= 16 * 1024 * 1024) { "Texte Wolf trop volumineux" }
        return result
    }

    fun path(path: String, writing: Boolean = false): String {
        require(path.length <= 4096 && ':' !in path && '\u0000' !in path) { "Chemin Wolf invalide" }
        if (writing) require(path.none { it in "\\%:*?\"<>|" } &&
            listOf("//", "..", "./").none(path::contains)) { "Chemin d'écriture Wolf invalide" }
        return WolfParser.normalizePath(path).also {
            require(!writing || it.isNotBlank()) { "Fichier d'écriture Wolf absent" }
        }
    }
}
