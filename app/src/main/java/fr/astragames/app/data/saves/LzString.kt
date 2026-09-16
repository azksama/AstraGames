package fr.astragames.app.data.saves

import org.json.JSONArray
import org.json.JSONObject

private const val KEY_STR_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

internal fun decodeRpgMakerJson(raw: String): JSONObject {
    val trimmed = raw.trim()
    val text = when {
        trimmed.startsWith("{") -> trimmed
        else -> lzDecompressFromBase64(trimmed)
    }.trim()

    return when {
        text.startsWith("{") -> JSONObject(text)
        else -> error("Sauvegarde RPG Maker JSON illisible.")
    }
}

internal fun encodeRpgMakerJson(json: JSONObject): String {
    val compressed = lzCompress(json.toString(), 6) { KEY_STR_BASE64[it] }
    return compressed + when (compressed.length % 4) {
        0 -> ""
        1 -> "==="
        2 -> "=="
        else -> "="
    }
}

/** Kotlin port of the lz-string base64 codec used by RPG Maker MV saves. */
private fun lzCompress(
    uncompressed: String,
    bitsPerChar: Int,
    getCharFromInt: (Int) -> Char
): String {
    val dictionary = HashMap<String, Int>()
    val dictionaryToCreate = HashSet<String>()
    var c = ""
    var wc: String
    var w = ""
    var enlargeIn = 2
    var dictSize = 3
    var numBits = 2
    val data = StringBuilder()
    var dataVal = 0
    var dataPosition = 0

    fun writeBits(valueToWrite: Int, bitCount: Int) {
        var value = valueToWrite
        repeat(bitCount) {
            dataVal = (dataVal shl 1) or (value and 1)
            if (dataPosition == bitsPerChar - 1) {
                dataPosition = 0
                data.append(getCharFromInt(dataVal))
                dataVal = 0
            } else {
                dataPosition++
            }
            value = value shr 1
        }
    }

    for (character in uncompressed) {
        c = character.toString()
        if (!dictionary.containsKey(c)) {
            dictionary[c] = dictSize++
            dictionaryToCreate += c
        }
        wc = w + c
        if (dictionary.containsKey(wc)) {
            w = wc
            continue
        }

        if (dictionaryToCreate.contains(w)) {
            if (w[0].code < 256) {
                writeBits(0, numBits)
                writeBits(w[0].code, 8)
            } else {
                writeBits(1, numBits)
                writeBits(w[0].code, 16)
            }
            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            dictionaryToCreate.remove(w)
        } else {
            writeBits(dictionary.getValue(w), numBits)
        }

        enlargeIn--
        if (enlargeIn == 0) {
            enlargeIn = 1 shl numBits
            numBits++
        }
        dictionary[wc] = dictSize++
        w = c
    }

    if (w.isNotEmpty()) {
        if (dictionaryToCreate.contains(w)) {
            if (w[0].code < 256) {
                writeBits(0, numBits)
                writeBits(w[0].code, 8)
            } else {
                writeBits(1, numBits)
                writeBits(w[0].code, 16)
            }
            enlargeIn--
            if (enlargeIn == 0) {
                enlargeIn = 1 shl numBits
                numBits++
            }
            dictionaryToCreate.remove(w)
        } else {
            writeBits(dictionary.getValue(w), numBits)
        }
        enlargeIn--
        if (enlargeIn == 0) {
            enlargeIn = 1 shl numBits
            numBits++
        }
    }

    writeBits(2, numBits)
    while (true) {
        dataVal = dataVal shl 1
        if (dataPosition == bitsPerChar - 1) {
            data.append(getCharFromInt(dataVal))
            break
        }
        dataPosition++
    }
    return data.toString()
}

private fun lzDecompressFromBase64(input: String): String {
    require(input.isNotEmpty()) { "Sauvegarde RPG Maker vide." }
    return lzDecompress(input.length, 32) { index ->
        if (index !in input.indices) 0 else KEY_STR_BASE64.indexOf(input[index]).coerceAtLeast(0)
    }
}

private fun lzDecompress(
    length: Int,
    resetValue: Int,
    getNextValue: (Int) -> Int
): String {
    val dictionary = mutableListOf<String>()
    val result = StringBuilder()
    var enlargeIn = 4
    var dictSize = 4
    var numBits = 3
    var dataVal = getNextValue(0)
    var dataPosition = resetValue
    var dataIndex = 1

    repeat(3) { dictionary += it.toString() }

    fun readBits(bitCount: Int): Int {
        var bits = 0
        var power = 1
        repeat(bitCount) {
            val resb = dataVal and dataPosition
            dataPosition = dataPosition ushr 1
            if (dataPosition == 0) {
                dataPosition = resetValue
                dataVal = getNextValue(dataIndex++)
            }
            if (resb > 0) bits = bits or power
            power = power shl 1
        }
        return bits
    }

    val first = when (readBits(2)) {
        0 -> readBits(8).toChar().toString()
        1 -> readBits(16).toChar().toString()
        2 -> return ""
        else -> return ""
    }
    dictionary += first
    var w = first
    result.append(first)

    while (true) {
        if (dataIndex > length) return ""
        val code = readBits(numBits)
        val entry: String
        when (code) {
            0 -> {
                dictionary += readBits(8).toChar().toString()
                dictSize++
                enlargeIn--
            }
            1 -> {
                dictionary += readBits(16).toChar().toString()
                dictSize++
                enlargeIn--
            }
            2 -> return result.toString()
        }

        if (enlargeIn == 0) {
            enlargeIn = 1 shl numBits
            numBits++
        }

        entry = dictionary.getOrNull(if (code == 0 || code == 1) dictSize - 1 else code)
            ?: if (code == dictSize) w + w.first() else return ""
        result.append(entry)
        dictionary += w + entry.first()
        dictSize++
        enlargeIn--
        w = entry
        if (enlargeIn == 0) {
            enlargeIn = 1 shl numBits
            numBits++
        }
    }
}
