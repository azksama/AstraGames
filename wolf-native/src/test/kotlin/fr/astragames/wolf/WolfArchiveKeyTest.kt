package fr.astragames.wolf

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Synthetic assets protected by the pinned C++ reference, independently of the Kotlin decoder. */
class WolfArchiveKeyTest {
    @Test fun carrierProfilesMatchCppAndOnlyReadHeaderAndSelectedBytes() {
        val vectors = vectors()
        for (version in listOf(1000, 1010, 1020, 200)) {
            val source = Source(resource("carrier${if (version == 1020) 1010 else version}.bin"))
            val original = source.bytes.clone()
            val key = WolfArchiveKey.fromCarrier(source, version)
            assertEquals(version, key.cryptVersion)
            assertArrayEquals(hex(vectors.getValue("key${if (version == 1020) 1010 else version}")), key.material)
            assertTrue(source.closed)
            assertArrayEquals(original, source.bytes)
            assertTrue(source.reads.sumOf { it.second } <= 31 + 255)
            assertFalse(source.reads.any { it.first == 0L && it.second == original.size })
        }
    }

    private val expectedAssets = mapOf(
            "BasicData/Game.dat" to "2a2737493cd073fd0ba5bfd25d9618456d18792b74504b56ffbd3b91f15895d4",
            "BasicData/CommonEvent.dat" to "191654efe5d0c661f2ab8e377898f18798db2085078912df53bd6d34faea2a47",
            "BasicData/DataBase.dat" to "996426a76e65c90e585a79d3e4d1f7cce430663becaceebfe7529bd2c9b98786",
            "BasicData/DataBase.project" to "3d5c2ee813ef6065a030457f267e2e2293dd972e9ca706eefd550780e5f0e555",
            "BasicData/TileSetData.dat" to "e90912fa0a15e935a2190cf88f63ea88083dd051f91dafe9292942171a4cd024",
            "MapData/start.mps" to "17f4390aeec6c8b25c446ed1225a221af4844d0000079d3a028473301c8ed272",
            "Picture/魔女.png" to "e6d66889131220f931fddfb05730d647a0992456c63ae0a8154b4ae32ff219ef",
            "Audio/歩く.wav" to "69cc381009586842928aa4d0807c054368f8182498d799616380d73f11a1c705",
            "Root.txt" to "2466299617c6eb8230d78f1cb222ab559f65b22f05d9741591b5de90ac3b3748")

    @Test fun proCarrierArchivesReadExactStructuredAssetsWithoutExtraction() {
        for (version in listOf(1000, 1010, 1020, 200, 100)) {
            val key = if (version == 100) null else WolfArchiveKey.fromCarrier(Source(resource("carrier${if (version == 1020) 1010 else version}.bin")), version)
            val source = Source(resource("Data$version.wolf"))
            val original = sha(source.bytes)
            WolfArchive.open(source, carrierKey = key).use { archive ->
                assertEquals(version, archive.cryptVersion)
                assertEquals(setOf("BasicData", "MapData", "Picture", "Audio", "Root.txt"), archive.list("").toSet())
                assertEquals(2, source.reads.size)
                for ((path, digest) in expectedAssets) assertEquals("$version/$path", digest, sha(archive.read(path)))
                assertEquals("魔女の道", WolfFormats.parseGame(archive.read("basicdata/game.DAT")).title)
                assertEquals("魔女", WolfFormats.parseCommonEvents(archive.read("BasicData/CommonEvent.dat")).single().name)
                assertEquals("属性", WolfFormats.parseDatabase(archive.read("BasicData/DataBase.project"),
                    archive.read("BasicData/DataBase.dat")).types.single().name)
                assertEquals(2 to 2, WolfImageHeader.dimensions(archive.read("Picture/魔女.png")))
                assertFalse(source.reads.any { it.first == 0L && it.second.toLong() == source.size })
            }
            assertTrue(source.closed)
            assertEquals(original, sha(source.bytes))
        }
    }

    @Test fun cc2StreamsMatchCppAtUnalignedAndUint32Boundaries() {
        val key = WolfArchiveKey.fromCarrier(Source(resource("carrier200.bin")), 200)
        val crypt = WolfArchiveChaCha(key.material)
        val vectors = vectors()
        for (position in listOf(0L, 1L, 63L, 64L, 65535L, 0xfffffff0L)) {
            val all = ByteArray(128).also { crypt.decodeInner(it, position) }
            assertArrayEquals(hex(vectors.getValue("cc$position")), all)
            val parts = ByteArray(128)
            for (i in 0 until 128 step 17) {
                // A new KeyConv call casts each starting position to uint32. Stay before wrap.
                if (position + i > 0xffffffffL) break
                val part = ByteArray(minOf(17, 128 - i)).also { crypt.decodeInner(it, position + i) }
                part.copyInto(parts, i)
            }
            if (position < 0xffffff80L) assertArrayEquals(all, parts)
            crypt.decodeInner(all, position)
            assertArrayEquals(ByteArray(128), all)
        }
        val base = ByteArray(128).also { crypt.decodeInner(it, 7) }
        assertArrayEquals(base, ByteArray(128).also { crypt.decodeInner(it, 0x1_0000_0007L) })
    }

    @Test fun explicitNoKeySkipsInnerTransformButKeepsProAesOuterTransform() {
        for (version in listOf(100, 200, 1000, 1010, 1020)) {
            val key = if (version in setOf(1010, 1020))
                WolfArchiveKey.fromCarrier(Source(resource("carrier1010.bin")), version) else null
            WolfArchive.open(Source(resource("Data$version-no-key.wolf")), carrierKey = key).use { archive ->
                assertEquals(version, archive.cryptVersion)
                for ((path, digest) in expectedAssets) assertEquals("NO_KEY $version/$path", digest, sha(archive.read(path)))
                assertEquals("魔女の道", WolfFormats.parseGame(archive.read("BasicData/Game.dat")).title)
                assertEquals("191654efe5d0c661f2ab8e377898f18798db2085078912df53bd6d34faea2a47",
                    sha(archive.read("BasicData/CommonEvent.dat")))
                assertEquals(2 to 2, WolfImageHeader.dimensions(archive.read("Picture/魔女.png")))
            }
        }
    }

    @Test fun unknownProfilesInvalidCarrierBoundsAndWrongKeysFailAndClose() {
        fun bad(source: Source, version: Int? = null, max: Long = 256L * 1024 * 1024) {
            assertThrows(WolfFormatException::class.java) { WolfArchiveKey.fromCarrier(source, version, max) }
            assertTrue(source.closed)
        }
        for (length in listOf(0, 1, 19, 30)) bad(Source(ByteArray(length)))
        bad(Source(resource("carrier1000.bin").copyOf(200)), 1000)
        bad(Source(resource("carrier1000.bin").also { it[19] = 0 }), 1000)
        bad(Source(resource("carrier1010.bin").also { it[19] = 0 }), 1010)
        bad(Source(resource("carrier1000.bin")), 1010)
        bad(Source(resource("carrier1010.bin")), 1010, 32)
        val tooLarge = object : WolfArchiveSource {
            var closed = false
            override val size = 0x1_0000_0000L
            override fun read(offset: Long, length: Int): ByteArray = error("must reject size before read")
            override fun close() { closed = true }
        }
        assertThrows(WolfFormatException::class.java) { WolfArchiveKey.fromCarrier(tooLarge) }
        assertTrue(tooLarge.closed)
        for (version in listOf(1000, 1010, 1020, 200)) {
            val input = Source(resource("Data$version.wolf"))
            assertThrows(WolfFormatException::class.java) { WolfArchive.open(input) }
            assertTrue(input.closed)
        }
        val wrongKey = WolfArchiveKey.fromCarrier(Source(resource("carrier1000.bin")), 1000)
        val input = Source(resource("Data1010.wolf"))
        assertThrows(WolfFormatException::class.java) { WolfArchive.open(input, carrierKey = wrongKey) }
        assertTrue(input.closed)
        val alteredCarrier = resource("carrier1000.bin").also { it[30] = (it[30].toInt() xor 1).toByte() }
        val wrongPassword = WolfArchiveKey.fromCarrier(Source(alteredCarrier), 1000)
        val wrongPasswordInput = Source(resource("Data1000.wolf"))
        assertThrows(WolfFormatException::class.java) { WolfArchive.open(wrongPasswordInput, carrierKey = wrongPassword) }
        assertTrue(wrongPasswordInput.closed)
    }

    private fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/archive-pro/$name")) {
        "Missing independent C++ synthetic fixture $name"
    }.use { it.readBytes() }
    private fun vectors(): Map<String, String> = resource("vectors.txt").toString(Charsets.US_ASCII).lineSequence()
        .filter { it.isNotBlank() }.associate { val parts = it.split(' ', limit = 2); parts[0] to parts[1] }
    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Source(val bytes: ByteArray) : WolfArchiveSource {
        override val size = bytes.size.toLong()
        val reads = mutableListOf<Pair<Long, Int>>()
        var closed = false
        override fun read(offset: Long, length: Int): ByteArray {
            check(!closed)
            reads += offset to length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        override fun close() { closed = true }
    }
}
