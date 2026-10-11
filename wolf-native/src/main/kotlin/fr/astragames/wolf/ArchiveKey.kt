package fr.astragames.wolf

/** A validated carrier profile; material is never exposed as a mutable public byte array. */
class WolfArchiveKey private constructor(val cryptVersion: Int, internal val material: ByteArray) {
    internal val password: ByteArray
        get() = material.copyOfRange(0, minOf(63, material.indexOf(0).let { if (it < 0) material.size else it }))

    internal val proKey: ByteArray
        get() {
            val offset = password.size + 1
            if (offset + 4 > material.size) keyFail("missing Pro AES key material")
            return material.copyOfRange(offset, offset + 4)
        }

    companion object {
        /**
         * Derive the evidenced Pro 3.1 / 3.3 material (also the exact 1020 profile) / CC2 key.
         * Positional reads only: even a large carrier is not loaded/extracted as a whole.
         * Ownership transfers on entry and the carrier closes on success or failure.
         * Reference: pinned MIT UberWolf WolfPro.cpp and WolfDxArcKey.hpp.
         */
        fun fromCarrier(source: WolfArchiveSource, expectedCryptVersion: Int? = null,
            maxCarrierBytes: Long = 256L * 1024 * 1024): WolfArchiveKey = source.use {
            if (maxCarrierBytes < 31 || source.size !in 31..minOf(maxCarrierBytes, 0x7fffffffL))
                keyFail("carrier size ${source.size} exceeds established bounds")
            fun read(offset: Long, length: Int): ByteArray {
                if (offset < 0 || length < 0 || offset > source.size || length.toLong() > source.size - offset)
                    keyFail("carrier key offset outside input")
                return source.read(offset, length).also { if (it.size != length) keyFail("short carrier positional read") }
            }
            val header = read(0, 31)
            val version = when (kb(header, 0)) {
                0xa0 -> 1000
                0xf1 -> 200
                // WolfPro supplies V2 material; DXArchive separately selects exact 1020 transforms.
                else -> if (expectedCryptVersion == 1020) 1020 else 1010
            }
            if (expectedCryptVersion != null && version != expectedCryptVersion)
                keyFail("carrier profile $version does not match archive profile $expectedCryptVersion")
            val key = when (version) {
                1000 -> {
                    if (source.size < 0x5cb8) keyFail("truncated Pro 3.1 carrier")
                    val count = kb(header, 19)
                    if (count == 0) keyFail("Pro 3.1 carrier has zero key length")
                    val step = 0x58b1 / count
                    val positions = IntArray(count) { 30 + it * step }
                    val selected = ByteArray(count)
                    val rng = CarrierMsvcRandom(kb(header, 4))
                    var next = 0
                    for (position in 20..positions.last()) {
                        val mask = rng.next() ushr 12
                        if (position == positions[next]) {
                            selected[next] = (kb(read(position.toLong(), 1), 0) xor mask).toByte()
                            if (++next == count) break
                        }
                    }
                    if (selected.last() == 0.toByte()) selected else selected + byteArrayOf(0)
                }
                1010, 1020 -> {
                    val size = (source.size - 31).toInt()
                    if (size == 0) keyFail("Pro 3.3 carrier body is missing")
                    val div = size / 3
                    val v1 = div + 71
                    val v2 = size xor 0x70
                    val v3 = size % 1200 + 152
                    val v4 = 3 * size + 85
                    val pro = intArrayOf(v4 xor v1, v3 + v2, v2 - v4, v2 * v4).map(Int::toByte).toByteArray()
                    val seeds = intArrayOf(v1 + kb(header, 3), v3 + kb(header, 7),
                        v2 + kb(header, 5), v4 + kb(header, 6)).map { it and 255 }.toIntArray()
                    // aesKeyGen reinitializes every RNG word and the counter with the byte seeds.
                    val aesMaterial = ProRng(seeds[0], seeds[1]).aesMaterial(seeds)
                    val aes = WolfAesCtr(aesMaterial.first, aesMaterial.second)
                    var multiplier = kb(header, 4) + ((kb(header, 3) * kb(header, 6)) and 0x3ff)
                    while (carrierGcd(size, multiplier) > 1) multiplier++
                    val count = kb(header, 19)
                    if (count == 0) keyFail("Pro 3.3 carrier has zero key length")
                    val key = ByteArray(count) { i ->
                        val offset = (i.toLong() * multiplier % size) + 30 + kb(header, 7)
                        val value = read(offset, 1)
                        if (offset < 30 + size) aes.xor(value, offset - 30, 0)
                        value[0]
                    }
                    if (key.take(63).all { it != 0.toByte() } && key.size > 63)
                        keyFail("Pro 3.3 carrier key exceeds the evidenced 63-byte password")
                    key + byteArrayOf(0) + pro
                }
                else -> cc2Material(source.size.toInt())
            }
            WolfArchiveKey(version, key)
        }

        private fun cc2Material(carrierSize: Int): ByteArray {
            val size = carrierSize - 31
            val remainder = size % 0x064b
            val x = size xor 0x70
            val sum = remainder + 152
            val div4 = size / 4 + 1285
            val div2 = size / 2 + 171
            val data = intArrayOf(div4 xor div2, x + sum, x - div4, div4 * x).map { it and 255 }
            val a = intArrayOf(0x3f, 0xa7, 0xd2, 0x1c)
            val b = intArrayOf(0xb4, 0xe1, 0x9d, 0x58)
            val c = intArrayOf(0x6a, 0x2b, 0x4c, 0x8e)
            return ByteArray(64) { i ->
                val index = i and 3
                val value = ((data[index] + b[index]) xor (a[index] + 17 * i)) and 255
                val rotated = if (i and 1 == 0) ((value ushr 5) or (value shl 3)) else ((value ushr 2) or (value shl 6))
                (rotated xor data[index] xor c[index]).inv().toByte()
            }
        }
    }
}

private class CarrierMsvcRandom(private var state: Int) {
    fun next(): Int { state = state * 214013 + 2531011; return (state ushr 16) and 0x7fff }
}
private fun carrierGcd(a: Int, b: Int): Int {
    var x = a
    var y = b
    while (y != 0) { val remainder = x % y; x = y; y = remainder }
    return x
}
private fun kb(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 255
private fun keyFail(message: String): Nothing = throw WolfFormatException("Wolf archive key: $message")
