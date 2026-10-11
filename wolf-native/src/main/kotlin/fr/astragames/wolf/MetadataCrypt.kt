package fr.astragames.wolf

/** Established no-password metadata envelopes from the pinned MIT WolfTL/UberWolf routines. */
internal enum class WolfMetadataKind(val salt: String, val seedIndices: IntArray) {
    GAME("basicD1", intArrayOf(0, 8, 6)),
    COMMON("Commo2", intArrayOf(0, 3, 9)),
    DATABASE("DBase4", intArrayOf(0, 3, 9)),
    TILESET("TilesetA", intArrayOf(0, 3, 9));

    fun header(utf8: Boolean): ByteArray = byteArrayOf(0, 0x57, 0, 0, 0x4f, 0x4c, 0, 0x46,
        if (this == COMMON) 0x43 else 0x4d, 0).also {
        if (utf8) it[if (this == GAME) 9 else 6] = 0x55
    }
}

internal data class WolfDecodedMetadata(val bytes: ByteArray, val projectKey: Int? = null, val encrypted: Boolean = false)

internal object WolfMetadataCrypt {
    // This exact NUL-terminated prefix occurs before clear metadata in the
    // distributed Pro 3.69 corpus. It is not a password or an encryption header.
    private val notice = "Extracting data from encrypted files violates the guidelines.\u0000"
        .toByteArray(Charsets.US_ASCII)

    /** No global RNG/project state: each database keeps the key from its own .dat envelope. */
    fun decode(input: ByteArray, kind: WolfMetadataKind, name: String, budget: WolfParseBudget): WolfDecodedMetadata {
        if (input.size >= notice.size && notice.indices.all { input[it] == notice[it] }) {
            // Admit only the observed clear body for the requested metadata kind.
            // Do not scan for a later magic or strip banners from arbitrary assets.
            if (input.size - notice.size < 10) metadataFail(name, "truncated clear metadata after notice")
            val header = kind.header(false)
            val encodingIndex = if (kind == WolfMetadataKind.GAME) 9 else 6
            if (header.indices.any { i -> input[notice.size + i] != header[i] &&
                    !(i == encodingIndex && input[notice.size + i] == 0x55.toByte()) })
                metadataFail(name, "unsupported metadata body after notice")
            budget.allocate((input.size - notice.size).toLong(), "$name: clear metadata notice")
            return WolfDecodedMetadata(input.copyOfRange(notice.size, input.size))
        }
        if (input.isEmpty()) return WolfDecodedMetadata(input)
        if (input.size < 10) {
            if (input[0] == 0.toByte()) return WolfDecodedMetadata(input)
            metadataFail(name, "truncated encrypted metadata header")
        }
        if (metaByte(input, 1) != 0x50) {
            if (input[0] == 0.toByte()) return WolfDecodedMetadata(input)
            if (kind !in setOf(WolfMetadataKind.GAME, WolfMetadataKind.COMMON))
                metadataFail(name, "unsupported historical ${kind.name.lowercase()} envelope")
            budget.allocate(input.size.toLong(), "$name: historical metadata")
            val data = input.copyOf()
            xorHistoric(data, kind.seedIndices)
            kind.header(false).copyInto(data)
            return WolfDecodedMetadata(data, encrypted = true)
        }
        val version = metaByte(input, 5)
        // Only the evidenced T/U/V/W headers are admitted; future bytes are not aliases.
        if (version !in 0x54..0x57) metadataFail(name, "unsupported Pro metadata protection $version; key chain is not established")
        if (version == 0x54 && input[0] == 0.toByte())
            metadataFail(name, "invalid Pro 3.1 encryption indicator zero")
        if (version != 0x57 && kind == WolfMetadataKind.TILESET)
            metadataFail(name, "unsupported Pro tileset protection $version")
        val minimum = if (version == 0x54) 20 else 143
        if (input.size < minimum) metadataFail(name, "truncated Pro metadata header $version")
        budget.allocate(input.size.toLong() + (if (version == 0x54) 10 else input.size - 133) + 96,
            "$name: Pro metadata envelope")
        val data = input.copyOf()
        val projectKey: Int
        val bodyOffset: Int
        when (version) {
            0x54 -> {
                // FileCoder::decryptV3_1 validates the database's T/K markers separately.
                if (kind == WolfMetadataKind.DATABASE && metaByte(input, 7) != 0x4b)
                    metadataFail(name, "invalid Pro 3.1 database K marker")
                xorHistoric(data, kind.seedIndices)
                val length = metaUnsigned(data, 15)
                if (length > data.size - 19L) metadataFail(name, "Pro 3.1 key length exceeds input")
                if (kind != WolfMetadataKind.GAME && length == 0L)
                    metadataFail(name, "Pro 3.1 project key is missing")
                projectKey = if (kind == WolfMetadataKind.GAME) 0 else data[19].toInt()
                bodyOffset = 19 + length.toInt()
            }
            0x55, 0x56 -> {
                budget.allocate(40L * 1024, "$name: Pro 3.3 RNG state")
                xorMt(data, kind.seedIndices)
                val seed = intArrayOf(
                    (metaByte(data, 7) + 3 * metaByte(data, 11)) and 255,
                    metaByte(data, 12) xor metaByte(data, 13),
                    metaByte(data, 14) xor metaByte(data, 7),
                    (metaByte(data, 13) + metaByte(data, 7) - metaByte(data, 11)) and 255)
                val material = ProRng(seed[0], seed[1]).aesMaterial(seed)
                val count = minOf(data.size - 20, 326)
                aesRegion(data, 20, count, material.first, material.second)
                projectKey = metaByte(data, 20)
                bodyOffset = 143
            }
            else -> {
                budget.allocate(4L * 1024, "$name: Pro 3.5 hash/AES state")
                xorPro35(data, kind.seedIndices)
                val rng = MetadataMsvcRandom(metaByte(data, 12))
                var count = data.size - 20
                if (count >= rng.next() % 126 + 200) count = minOf(count, rng.next() % 126 + 200)
                val d0 = metaByte(data, 7)
                val d1 = metaByte(data, 11)
                val d2 = metaByte(data, 13)
                val salt = intArrayOf((d0 + 2 * d1) % 246, d2 xor metaByte(data, 14),
                    d0 xor metaByte(data, 12), (d0 + d2 - d1) and 255)
                    .map { (if (it == 0) 1 else it).toByte() }.toByteArray()
                val digest = WolfMetadataHash.digest(salt + kind.salt.toByteArray(Charsets.US_ASCII))
                    .toByteArray(Charsets.US_ASCII)
                aesRegion(data, 20, count, digest.copyOfRange(12, 28), digest.copyOfRange(73, 89))
                projectKey = 0 // FileCoder::decryptV3_5 explicitly selects project RNG seed zero.
                bodyOffset = 143
            }
        }
        if (bodyOffset >= data.size) metadataFail(name, "Pro metadata body is missing")
        val outputSize = data.size - bodyOffset + 10
        if (version == 0x54) budget.allocate(outputSize.toLong(), "$name: Pro 3.1 body")
        val output = ByteArray(outputSize)
        kind.header(true).copyInto(output)
        data.copyInto(output, 10, bodyOffset)
        return WolfDecodedMetadata(output, if (kind == WolfMetadataKind.DATABASE) projectKey else null, true)
    }

    fun project(input: ByteArray, key: Int?, name: String, budget: WolfParseBudget): ByteArray {
        if (key == null) return input
        budget.allocate(input.size.toLong(), "$name: project decryption")
        val rng = MetadataMsvcRandom(key)
        return input.copyOf().also { data ->
            for (i in data.indices) data[i] = (metaByte(data, i) xor rng.next()).toByte()
        }
    }

    private fun xorHistoric(data: ByteArray, indices: IntArray) {
        for ((round, interval) in intArrayOf(1, 2, 5).withIndex()) {
            val rng = MetadataMsvcRandom(metaByte(data, indices[round]))
            for (i in 10 until data.size step interval) data[i] = (metaByte(data, i) xor (rng.next() ushr 12)).toByte()
        }
    }

    private fun xorMt(data: ByteArray, indices: IntArray) {
        var seed = (metaByte(data, indices[0]) shl 16) or (metaByte(data, indices[1]) shl 8) or metaByte(data, indices[2])
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        val mt = MetadataMt19937(seed)
        val masks = IntArray(128) { mt.next() }
        for (i in 10 until data.size) data[i] = (metaByte(data, i) xor masks[i % 128]).toByte()
    }

    private fun xorPro35(data: ByteArray, indices: IntArray) {
        var state = 0x0b000000 or (metaByte(data, indices[0]) shl 16) or
            (metaByte(data, indices[1]) shl 8) or metaByte(data, indices[2])
        state = state xor (state shl 11)
        state = state xor (state ushr 19)
        state = state xor (state shl 7)
        for (i in 10 until data.size) {
            val part = state xor (state shl 15)
            val next = (part shr 21) xor part // signed int32 shift/remainder in WolfDataDecrypt
            state = next xor (next shl 9)
            data[i] = (metaByte(data, i) xor (state % 249)).toByte()
        }
    }

    private fun aesRegion(data: ByteArray, start: Int, size: Int, key: ByteArray, iv: ByteArray) {
        // At most 326 bytes; the large body is not copied a second time.
        val region = data.copyOfRange(start, start + size)
        WolfAesCtr(key, iv).xor(region, 0, 0)
        region.copyInto(data, start)
    }
}

private fun metadataFail(name: String, text: String): Nothing = throw WolfFormatException("$name: $text")
private fun metaByte(data: ByteArray, index: Int): Int = data[index].toInt() and 255
private fun metaUnsigned(data: ByteArray, index: Int): Long = (0..3).fold(0L) { r, i -> r or (metaByte(data, index + i).toLong() shl (8 * i)) }
private fun unsignedRemainder(value: Int, divisor: Int): Int = ((value.toLong() and 0xffffffffL) % divisor).toInt()
private class MetadataMsvcRandom(private var state: Int) {
    fun next(): Int { state = state * 214013 + 2531011; return (state ushr 16) and 32767 }
}

/** std::mt19937's 32-bit state/tempering, seeded as specified by WolfCryptUtils. */
private class MetadataMt19937(seed: Int) {
    private val state = IntArray(624)
    private var cursor = 624
    init {
        state[0] = seed
        for (i in 1 until state.size) state[i] = 1812433253 * (state[i - 1] xor (state[i - 1] ushr 30)) + i
    }
    fun next(): Int {
        if (cursor == 624) {
            for (i in state.indices) {
                val joined = (state[i] and Int.MIN_VALUE) or (state[(i + 1) % 624] and Int.MAX_VALUE)
                state[i] = state[(i + 397) % 624] xor (joined ushr 1) xor (if (joined and 1 != 0) 0x9908b0df.toInt() else 0)
            }
            cursor = 0
        }
        var value = state[cursor++]
        value = value xor (value ushr 11)
        value = value xor ((value shl 7) and 0x9d2c5680.toInt())
        value = value xor ((value shl 15) and 0xefc60000.toInt())
        return value xor (value ushr 18)
    }
}

/** The Pro 3.3 key generator is finite (32×256 state words, at most 50 mix iterations per byte). */
internal class ProRng(private var seed1: Int, private var seed2: Int) {
    private val table = Array(32) { IntArray(256) }
    private var counter = 0
    init { table.forEach(::chain) }

    private fun next1(): Int {
        val part = seed1 xor (((seed1 shl 11) xor seed1) ushr 8)
        var state = 1664525 * ((seed1 shl 11) xor part) + 1013904223
        state = state xor (if ((13 * part + 95) and 1 == 0) state ushr 3 else state shl 2)
        if (state and 0x400 != 0) { state = state xor (state shl 21); state = state xor (state ushr 9) }
        else { state = state xor (state shl 2); state = state xor (state ushr 22) }
        if (state and 0xfffff == 0) state += 256
        seed1 = state
        return state
    }

    private fun next2(): Int {
        val shift = (seed1 and 7) + 1
        var state = 1664525 * seed1 + 1013904223
        state = when (unsignedRemainder(state, 3)) {
            0 -> state xor (state shl shift)
            1 -> state xor (state ushr shift)
            else -> state.inv() + (state shl shift)
        }
        if (state == 0) state = 0x173bef else if (state and 65535 == 0) state = state xor 0x55aa55aa
        seed1 = state
        return state
    }

    private fun next3(): Int {
        var state = (1566083941 * seed2) xor (292331520 * seed2)
        state = state xor (state ushr 17) xor ((state xor (state ushr 17)) shl 5)
        state = 69069 * (state xor ((state xor (state ushr 11)) and 0x3fffffff))
        if (state == 0) state = 1566083941
        else {
            if (state and 65535 == 0) state = state xor 0x59a6f141
            if (state and 0xfffff == 0) state += 256
        }
        seed2 = state
        return state
    }

    private fun chain(data: IntArray) {
        for (i in data.indices) {
            val rn = next2()
            var value = rn xor next3()
            counter++
            if (counter and 1 == 0) value += next3()
            val c = counter
            if (c % 3 == 0) value = value xor (next1() + 3)
            if (c % 7 == 0) value += next3() + 1
            if (c and 7 == 0) value *= next1()
            if (unsignedRemainder(i + seed1, 5) == 0) value = value xor next1()
            if (c % 9 == 0) value += next2() + 4
            if (c % 24 == 0) value += next2() + 7
            if (c % 31 == 0) value += 3 * next3()
            if (c % 61 == 0) value += next3() + 1
            if (c % 161 == 0) value += next2()
            if (rn and 65535 == 256) value += 3 * next3()
            data[i] = value
        }
    }

    fun aesMaterial(seed: IntArray): Pair<ByteArray, ByteArray> {
        val crypt = ByteArray(48)
        for (index in crypt.indices) {
            var a2 = index + seed[3]
            var a3 = seed[2] - index
            var limit = 20
            var i = 0
            while (i++ < limit) {
                val row = (a2 xor next1()) and 31
                val column = (a3 xor next2()) and 255
                a3 = table[row][column]
                when (unsignedRemainder(a2 + counter, 20)) {
                    1 -> chain(table[(a2 + 5) and 31])
                    2 -> a3 = a3 xor next1()
                    5 -> if (a2 and 0xfffff == 0) crypt[index] = (metaByte(crypt, index) xor next3()).toByte()
                    9, 14 -> { val at = unsignedRemainder(next2(), 48); crypt[at] = (metaByte(crypt, at) + a3).toByte() }
                    11 -> crypt[index] = (metaByte(crypt, index) xor next1()).toByte()
                    17 -> limit++
                    19 -> if (a2 and 65535 == 0) crypt[index] = (metaByte(crypt, index) xor next2()).toByte()
                }
                a2 += next3()
                limit = minOf(limit, 50)
            }
            crypt[index] = (metaByte(crypt, index) + a3).toByte()
        }
        val permutation = IntArray(48) { it }
        val rng = MetadataMsvcRandom(seed[1] xor seed[2])
        for (i in permutation.indices) {
            val j = rng.next() % 48
            val old = permutation[i]
            permutation[i] = permutation[j]
            permutation[j] = old
        }
        val mixed = ByteArray(32) { crypt[permutation[it]] }
        return mixed.copyOfRange(0, 16) to mixed.copyOfRange(16, 32)
    }
}

/** Wolf 3.5 changes SHA-512's initial words, choice term and final accumulation. */
internal object WolfMetadataHash {
    private val initial = arrayOf("123456789abcdef0", "fedcba9876543210", "0f1e2d3c4b5a6978", "89abcdef01234567",
        "13579bdf02468ace", "f0e1d2c3b4a59687", "5a6b7c8d9e0f1a2b", "1a2b3c4d5e6f7890").map { it.toULong(16).toLong() }.toLongArray()
    private val constants = ("428a2f98d728ae22 7137449123ef65cd b5c0fbcfec4d3b2f e9b5dba58189dbbc 3956c25bf348b538 " +
        "59f111f1b605d019 923f82a4af194f9b ab1c5ed5da6d8118 d807aa98a3030242 12835b0145706fbe " +
        "243185be4ee4b28c 550c7dc3d5ffb4e2 72be5d74f27b896f 80deb1fe3b1696b1 9bdc06a725c71235 " +
        "c19bf174cf692694 e49b69c19ef14ad2 efbe4786384f25e3 0fc19dc68b8cd5b5 240ca1cc77ac9c65 " +
        "2de92c6f592b0275 4a7484aa6ea6e483 5cb0a9dcbd41fbd4 76f988da831153b5 983e5152ee66dfab " +
        "a831c66d2db43210 b00327c898fb213f bf597fc7beef0ee4 c6e00bf33da88fc2 d5a79147930aa725 " +
        "06ca6351e003826f 142929670a0e6e70 27b70a8546d22ffc 2e1b21385c26c926 4d2c6dfc5ac42aed " +
        "53380d139d95b3df 650a73548baf63de 766a0abb3c77b2a8 81c2c92e47edaee6 92722c851482353b " +
        "a2bfe8a14cf10364 a81a664bbc423001 c24b8b70d0f89791 c76c51a30654be30 d192e819d6ef5218 " +
        "d69906245565a910 f40e35855771202a 106aa07032bbd1b8 19a4c116b8d2d0c8 1e376c085141ab53 " +
        "2748774cdf8eeb99 34b0bcb5e19b48a8 391c0cb3c5c95a63 4ed8aa4ae3418acb 5b9cca4f7763e373 " +
        "682e6ff3d6b2b8a3 748f82ee5defb2fc 78a5636f43172f60 84c87814a1f0ab72 8cc702081a6439ec " +
        "90befffa23631e28 a4506cebde82bde9 bef9a3f7b2c67915 c67178f2e372532b ca273eceea26619c " +
        "d186b8c721c0c207 eada7dd6cde0eb1e f57d4f7fee6ed178 06f067aa72176fba 0a637dc5a2c898a6 " +
        "113f9804bef90dae 1b710b35131c471b 28db77f523047d84 32caab7b40c72493 3c9ebe0a15c9bebc " +
        "431d67c49c100d4c 4cc5d4becb3e42b6 597f299cfc657e2a 5fcb6fab3ad6faec 6c44198c4a475817")
        .split(' ').map { it.toULong(16).toLong() }.toLongArray()

    fun digest(input: ByteArray): String {
        // This routine is used only for four salt bytes plus an at-most-eight-byte literal.
        require(input.size <= 64)
        val buffer = ByteArray(128)
        input.copyInto(buffer)
        buffer[input.size] = 0x80.toByte()
        val bitSize = input.size.toLong() * 8
        for (i in 0..7) buffer[127 - i] = (bitSize ushr (i * 8)).toByte()
        val schedule = LongArray(80)
        for (i in 0..15) schedule[i] = (0..7).fold(0L) { r, j -> (r shl 8) or metaByte(buffer, i * 8 + j).toLong() }
        fun rotate(v: Long, n: Int) = java.lang.Long.rotateRight(v, n)
        for (i in 16..79) {
            val a = schedule[i - 15]
            val b = schedule[i - 2]
            schedule[i] = schedule[i - 16] + (rotate(a, 1) xor rotate(a, 8) xor (a ushr 7)) +
                schedule[i - 7] + (rotate(b, 19) xor rotate(b, 61) xor (b ushr 6))
        }
        val state = initial.copyOf()
        for (i in 0..79) {
            val choice = (state[4] and state[5]) xor (state[4].inv() and state[6])
            val majority = (state[0] and state[1]) xor (state[0] and state[2]) xor (state[1] and state[2])
            val first = state[7] + (rotate(state[4], 14) xor rotate(state[4], 18) xor rotate(state[4], 41)) +
                ((state[4] ushr 3) xor choice) + constants[i] + schedule[i]
            val second = (rotate(state[0], 28) xor rotate(state[0], 34) xor rotate(state[0], 39)) + majority
            for (j in 7 downTo 1) state[j] = if (j == 4) state[3] + first else state[j - 1]
            state[0] = first + second
        }
        return state.indices.joinToString("") { i ->
            java.lang.Long.toUnsignedString(initial[i] + (state[i] xor 0x123456789abcdef0L), 16).padStart(16, '0')
        }
    }
}
