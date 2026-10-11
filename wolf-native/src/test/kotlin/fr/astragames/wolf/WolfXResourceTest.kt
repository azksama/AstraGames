package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test

class WolfXResourceTest {
    private data class Vector(
        val id: Int, val key: ByteArray, val stringIndex: Int, val numberIndex: Int,
        val string: ByteArray, val number: Int, val payload: ByteArray, val encrypted: ByteArray,
    )
    private fun hex(value: String): ByteArray = if (value == "-") byteArrayOf() else
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun vectors(): List<Vector> = javaClass.getResourceAsStream("/wolfx/native.tsv")!!.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val p = line.split('\t')
            Vector(p[0].toInt(), hex(p[1]), p[2].toInt(), p[3].toInt(), hex(p[4]),
                p[5].toLong().toInt(), hex(p[6]), hex(p[7]))
        }.toList()
    }
    private fun decode(vector: Vector) = WolfXResource.decode(vector.encrypted, vector.key,
        stringVariable = { index -> assertEquals(vector.stringIndex, index); vector.string },
        numberVariable = { index -> assertEquals(vector.numberIndex, index); vector.number })

    @Test fun pinnedCppOracleCoversKeysVariablesAndBlockEdges() {
        val samples = vectors()
        assertEquals(192, samples.size)
        for (sample in samples) assertArrayEquals("WOLFX vector ${sample.id}", sample.payload, decode(sample))
    }

    @Test fun defaultEmptyKeyDoesNotConsultUnreferencedVariables() {
        val sample = vectors().first { it.key.isEmpty() && it.stringIndex >= 10_000 && it.numberIndex >= 1_000_000 }
        assertTrue(WolfXResource.isEncrypted(sample.encrypted))
        assertArrayEquals(sample.payload, WolfXResource.decode(sample.encrypted))
        assertArrayEquals(sample.payload, WolfXResource.decode(sample.encrypted,
            stringVariable = { fail("Unreferenced string variable"); null },
            numberVariable = { fail("Unreferenced numeric variable"); null }))
    }

    @Test fun requiredVariablesRemainUnavailableUntilProvided() {
        val text = vectors().first { it.stringIndex < 10_000 }
        assertThrows(WolfXResourceUnavailable::class.java) { WolfXResource.decode(text.encrypted, text.key) }
        val numeric = vectors().first { it.stringIndex >= 10_000 && it.numberIndex < 1_000_000 }
        assertThrows(WolfXResourceUnavailable::class.java) { WolfXResource.decode(numeric.encrypted, numeric.key) }
        assertArrayEquals(numeric.payload, decode(numeric))
    }

    @Test fun checksumRejectsWrongKnownValuesAndDamagedPayload() {
        val sample = vectors().first { it.key.isEmpty() && it.stringIndex >= 10_000 && it.numberIndex >= 1_000_000 }
        val damaged = sample.encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(WolfXResourceUnavailable::class.java) { WolfXResource.decode(damaged) }
        val numeric = vectors().first { it.numberIndex < 1_000_000 && it.number != 0 }
        assertThrows(WolfXResourceUnavailable::class.java) {
            WolfXResource.decode(numeric.encrypted, numeric.key, { numeric.string }, { numeric.number xor 1 })
        }
    }

    @Test fun malformedHeaderAndLimitsFailBeforeCallbacks() {
        assertFalse(WolfXResource.isEncrypted(byteArrayOf(87, 79, 76, 70)))
        assertThrows(IllegalArgumentException::class.java) { WolfXResource.decode(ByteArray(19)) }
        assertThrows(IllegalArgumentException::class.java) { WolfXResource.decode("WOLFX".toByteArray() + ByteArray(16)) }
        val sample = vectors().first()
        assertThrows(IllegalArgumentException::class.java) { WolfXResource.decode(sample.encrypted, ByteArray(65_537)) }
        assertThrows(IllegalArgumentException::class.java) {
            WolfXResource.decode(sample.encrypted.copyOf().also { it[0] = 0 })
        }
    }

    @Test fun sourceAndKeyArraysRemainUnchanged() {
        val sample = vectors().last()
        val source = sample.encrypted.copyOf()
        val key = sample.key.copyOf()
        val text = sample.string.copyOf()
        val output = decode(sample)
        output[0] = (output[0].toInt() xor 127).toByte()
        assertArrayEquals(source, sample.encrypted)
        assertArrayEquals(key, sample.key)
        assertArrayEquals(text, sample.string)
    }
}
