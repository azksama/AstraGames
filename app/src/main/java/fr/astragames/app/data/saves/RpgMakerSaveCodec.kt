package fr.astragames.app.data.saves

import org.json.JSONObject
import java.util.Base64

/** Preserve the original container, including plain JSON exports. */
internal object RpgMakerSaveCodec {
    enum class Format { JSON, MV_LZSTRING, MZ_ZLIB }
    data class Decoded(val json: JSONObject, val format: Format)

    fun decode(bytes: ByteArray): Decoded {
        require(bytes.size <= MAX_SAVE_BYTES) { "Sauvegarde trop volumineuse." }
        val text = bytes.toString(Charsets.UTF_8).trim().removePrefix("\uFEFF")
        if (text.startsWith("{")) return Decoded(JSONObject(text), Format.JSON)
        val binary = runCatching { Base64.getDecoder().decode(text) }.getOrNull()
        if (binary != null && binary.size >= 2 &&
            (binary[0].toInt() and 15) == 8 &&
            (((binary[0].toInt() and 255) shl 8) + (binary[1].toInt() and 255)) % 31 == 0
        ) return Decoded(JSONObject(inflateSave(binary).toString(Charsets.UTF_8)), Format.MZ_ZLIB)
        return Decoded(decodeRpgMakerJson(text), Format.MV_LZSTRING)
    }

    fun encode(save: Decoded): ByteArray = when (save.format) {
        Format.JSON -> save.json.toString()
        Format.MV_LZSTRING -> encodeRpgMakerJson(save.json)
        Format.MZ_ZLIB -> Base64.getEncoder().encodeToString(deflateSave(save.json.toString().toByteArray(Charsets.UTF_8)))
    }.toByteArray(Charsets.UTF_8)
}
