package fr.astragames.app.data.saves

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

internal const val MAX_SAVE_BYTES = 64 * 1024 * 1024
internal const val MAX_SAVE_NODES = 500_000
internal const val MAX_SAVE_DEPTH = 128

internal fun inflateSave(data: ByteArray): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            check(count > 0 || inflater.finished()) { "Sauvegarde compressee incomplete ou invalide." }
            require(output.size().toLong() + count <= MAX_SAVE_BYTES) { "Sauvegarde trop volumineuse." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally {
        inflater.end()
    }
}

internal fun deflateSave(data: ByteArray): ByteArray {
    val deflater = Deflater()
    try {
        deflater.setInput(data)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (!deflater.finished()) output.write(buffer, 0, deflater.deflate(buffer))
        return output.toByteArray()
    } finally {
        deflater.end()
    }
}
