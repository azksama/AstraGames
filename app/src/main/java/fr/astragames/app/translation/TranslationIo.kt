package fr.astragames.app.translation

import java.io.ByteArrayOutputStream
import java.io.InputStream

internal fun readTranslationBytes(input: InputStream, maximum: Int): ByteArray {
    require(maximum >= 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) {
            // Some document providers return zero without EOF; a one-byte read must make progress.
            val byte = input.read()
            if (byte < 0) break
            require(output.size() < maximum) { "Fichier trop volumineux." }
            output.write(byte)
        } else {
            require(output.size() <= maximum - count) { "Fichier trop volumineux." }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}
