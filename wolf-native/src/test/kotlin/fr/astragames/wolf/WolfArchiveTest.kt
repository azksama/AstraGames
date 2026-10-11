package fr.astragames.wolf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WolfArchiveTest {
    @Test fun independentAndroidArchiveFixtureReadsStructuredMetadataAndIntrinsicImage() {
        val file = File("../build/wolf-native-archive-fixture/Data.wolf").let {
            if (it.isFile) it else File("build/wolf-native-archive-fixture/Data.wolf")
        }
        assumeTrue("Local synthetic Android/SAF archive fixture is absent", file.isFile)
        val original = sha(file.readBytes())
        val input = RandomAccessFile(file,"r")
        var readBytes = 0L
        val source = object : WolfArchiveSource {
            override val size = input.length()
            override fun read(offset: Long, length: Int): ByteArray = ByteArray(length).also {
                input.seek(offset); input.readFully(it); readBytes += length
            }
            override fun close() = input.close()
        }
        WolfArchive.open(source).use { archive ->
            assertTrue(readBytes < source.size)
            assertEquals(setOf("BasicData","MapData","Picture","Audio","Root.txt"), archive.list("").toSet())
            assertEquals("魔女の道",WolfFormats.parseGame(archive.read("BasicData/Game.dat")).title)
            assertEquals("魔女",WolfFormats.parseCommonEvents(archive.read("BasicData/CommonEvent.dat")).single().name)
            assertEquals("属性",WolfFormats.parseDatabase(archive.read("BasicData/DataBase.project"),archive.read("BasicData/DataBase.dat")).types.single().name)
            assertEquals("森",WolfFormats.parseTilesets(archive.read("BasicData/TileSetData.dat")).single().name)
            assertEquals("小さな地図",WolfFormats.parseMap(archive.read("MapData/start.mps")).title)
            assertEquals(2 to 2,WolfImageHeader.dimensions(archive.read("Picture/魔女.png")))
            assertArrayEquals("RIFF".toByteArray(),archive.read("Audio/歩く.wav").copyOf(4))
        }
        assertEquals(original,sha(file.readBytes()))
        assertThrows(java.io.IOException::class.java) { input.read() }
    }

    @Test fun dx8ReadsOnlyIndexAndRequestedFileAndListsImmediateChildren() {
        val fixture = fixture(8, noKey = true)
        val source = BytesSource(fixture.bytes)
        val archive = WolfArchive.open(source)
        assertEquals(8, archive.version)
        assertEquals(listOf("Root.txt", "Picture"), archive.list(""))
        assertEquals(listOf("ソ.png"), archive.list("picture"))
        assertTrue(archive.exists("PICTURE/ソ.PNG"))
        assertFalse(archive.exists("missing"))
        assertEquals(listOf(0L to 64, fixture.nameStart.toLong() to fixture.indexSize), source.reads)
        assertArrayEquals(fixture.image, archive.read("Picture\\ソ.png"))
        assertEquals(fixture.assetStart.toLong() to fixture.image.size, source.reads.last())
        assertArrayEquals("root".toByteArray(), archive.read("ROOT.TXT"))
        assertFalse(source.closed)
        archive.close()
        assertTrue(source.closed)
        assertThrows(IllegalStateException::class.java) { archive.exists("Root.txt") }
    }

    @Test fun dx5AndDx6KnownFixedKeysReadNamesAndCompressedAssets() {
        for (version in listOf(5, 6)) {
            val fixture = fixture(version, lzImage = true)
            WolfArchive.open(BytesSource(fixture.bytes)).use { archive ->
                assertEquals(version, archive.version)
                assertArrayEquals(fixture.image, archive.read("Picture/ソ.png"))
                assertArrayEquals("root".toByteArray(), archive.read("Root.txt"))
            }
        }
    }

    @Test fun dx8PasswordKeysUseUppercaseFilenameAndReverseDirectoryAncestry() {
        for (version in listOf(0, 300, 314)) {
            val fixture = fixture(8, cryptVersion = version, lzImage = true)
            WolfArchive.open(BytesSource(fixture.bytes)).use { archive ->
                assertEquals(version, archive.cryptVersion)
                assertArrayEquals(fixture.image, archive.read("picture/ソ.png"))
                assertArrayEquals("root".toByteArray(), archive.read("Root.txt"))
            }
        }
        assertArrayEquals(hex("b379878337609f"), key7("wolf-custom-password".toByteArray()))
    }

    @Test fun dx8CompressedIndexAndWholeOrPartialHuffmanAssetsAreExact() {
        for (noKey in listOf(false, true)) for (partial in listOf(false, true)) for (lz in listOf(false, true)) {
            val fixture = fixture(8, noKey = noKey, compressedIndex = true, huffmanImage = true, partialImage = partial, lzImage = lz)
            val source = BytesSource(fixture.bytes)
            WolfArchive.open(source).use { archive ->
                assertArrayEquals(fixture.image, archive.read("Picture/ソ.png"))
                assertTrue(source.reads.none { (offset, length) -> offset == 0L && length == source.size.toInt() })
            }
        }
    }

    @Test fun dx8OffsetsRemainLongAboveFourGiBWithoutAllocatingTheContainer() {
        val fixture = fixture(8, noKey = true)
        val base = 0x1_0000_0000L
        val header = fixture.bytes.copyOf(64)
        putLong(header, 8, base + 64)
        putLong(header, 16, base + fixture.nameStart)
        val source = object : WolfArchiveSource {
            override val size = base + fixture.bytes.size
            override fun read(offset: Long, length: Int): ByteArray = if (offset == 0L) header.copyOf(length)
                else fixture.bytes.copyOfRange((offset - base).toInt(), (offset - base).toInt() + length)
        }
        WolfArchive.open(source).use { assertArrayEquals(fixture.image, it.read("Picture/ソ.png")) }
    }

    @Test fun dxLzEscapesAndOverlappingBackreferencesAndRejectsBadTokens() {
        val repeated = "abababababab".toByteArray()
        assertArrayEquals(repeated, WolfArchiveCodecs.lz(lzBlock(12, byteArrayOf(97, 98, 0xfe.toByte(), 0x30, 1)), 12, 100))
        val escaped = byteArrayOf(0xfe.toByte(), 0, 0xfe.toByte())
        assertArrayEquals(escaped, WolfArchiveCodecs.lz(literalLz(escaped), 3, 100))
        rejects { WolfArchiveCodecs.lz(lzBlock(4, byteArrayOf(0xfe.toByte(), 0, 0)), 4, 100) }
        rejects { WolfArchiveCodecs.lz(lzBlock(4, byteArrayOf(97, 0xfe.toByte(), 3, 0, 0, 0, 0)), 4, 100) }
        rejects { WolfArchiveCodecs.lz(literalLz(byteArrayOf(1)), 2, 100) }
        rejects { WolfArchiveCodecs.lz(literalLz(ByteArray(101)), 101, 100) }
        val valid = literalLz(byteArrayOf(0, 1, 0xfe.toByte(), 5))
        for (size in valid.indices) rejects { WolfArchiveCodecs.lz(valid.copyOf(size), 4, 100) }
    }

    @Test fun dxHuffmanHandlesAllSymbolsBothBitOrdersAndTruncatedHeaders() {
        for (input in listOf(byteArrayOf(), byteArrayOf(1, 0, 2, 255.toByte()), ByteArray(256) { it.toByte() })) {
            val encoded = uniformHuffman(input)
            assertArrayEquals(input, WolfArchiveCodecs.huffman(encoded, input.size, 512))
            for (size in encoded.indices) rejects { WolfArchiveCodecs.huffman(encoded.copyOf(size), input.size, 512) }
            rejects { WolfArchiveCodecs.huffman(encoded + byteArrayOf(0), input.size, 512) }
            rejects { WolfArchiveCodecs.huffman(encoded, input.size + 1, 512) }
        }
        rejects { WolfArchiveCodecs.huffman(uniformHuffman(ByteArray(100)), 100, 99) }
        rejects { WolfArchiveCodecs.huffman(byteArrayOf(0xfc.toByte()), null, 100) }
    }

    @Test fun archiveRejectsUnknownCryptTraversalCyclesOffsetsDuplicatesAndQuotasAndClosesSource() {
        val fixture = fixture(8, noKey = true)
        fun bad(change: (ByteArray) -> Unit, limits: WolfArchiveLimits = WolfArchiveLimits()) {
            val source = BytesSource(fixture.bytes.clone().also(change))
            rejects { WolfArchive.open(source, limits = limits) }
            assertTrue(source.closed)
        }
        bad({ putInt(it, 44, (1020 shl 16) or 3) })
        bad({ putInt(it, 44, 7) })
        bad({ putLong(it, 16, Long.MAX_VALUE) })
        bad({ putLong(it, 32, Int.MAX_VALUE.toLong()) })
        bad({ putLong(it, fixture.nameStart + fixture.dirBase + 16, Int.MAX_VALUE.toLong()) })
        bad({ putLong(it, fixture.nameStart + fixture.fileBase + 40, Long.MAX_VALUE) })
        bad({ putLong(it, fixture.nameStart + fixture.fileBase + 72 + 40, 0) })
        bad({ putLong(it, fixture.nameStart + fixture.dirBase + 32 + 8, 32) })
        bad({ putLong(it, fixture.nameStart + fixture.fileBase + 72, 0) })
        bad({ it[fixture.nameStart + 4] = '.'.code.toByte(); it[fixture.nameStart + 5] = '.'.code.toByte(); it[fixture.nameStart + 6] = 0
            val original = fixture.nameStart + 4 + 12; it[original] = '.'.code.toByte(); it[original + 1] = '.'.code.toByte(); it[original + 2] = 0 })
        bad({}, WolfArchiveLimits(maxIndexBytes = fixture.indexSize - 1))
        bad({}, WolfArchiveLimits(maxEntries = 2))
        bad({}, WolfArchiveLimits(maxAssetBytes = fixture.image.size - 1))
        bad({}, WolfArchiveLimits(maxIndexAllocationBytes = fixture.indexSize.toLong()))
        WolfArchive.open(BytesSource(fixture.bytes)).use { archive ->
            for (path in listOf("../Root.txt", "/Root.txt", "C:/Root.txt", "Picture/../Root.txt", "Picture//ソ.png", "./Root.txt"))
                rejects { archive.exists(path) }
        }
        for (size in listOf(0, 1, 27, 28, 63, 64, fixture.nameStart, fixture.bytes.size - 1)) {
            val source = BytesSource(fixture.bytes.copyOf(size))
            rejects { WolfArchive.open(source) }
            assertTrue(source.closed)
        }
        val short = object : WolfArchiveSource {
            override val size = fixture.bytes.size.toLong()
            override fun read(offset: Long, length: Int) = fixture.bytes.copyOfRange(offset.toInt(), offset.toInt() + length - 1)
        }
        rejects { WolfArchive.open(short) }
    }

    @Test fun wolf331And350StreamsMatchPinnedCppAndSeekingPreservesEveryBoundary() {
        val pw = ByteArray(15) { (3 + 7 * it).toByte() }
        val salt = fixedPassword(331)
        val hashes = mapOf(331 to "003357c8dee232623ba5a23e648a6bb6bc5cb6b9b25686379c879a3b39398dd1",
            350 to "62090cdc13221435474a31f933835bc1710b6c79ecd8198f230ff3f6c4d2447a")
        val addresses = mapOf(331 to "623fb6519b14e5396049ff1fc76c5521ac785a5fa5471339f26568459b77594b",
            350 to "825066740436113e3d2eaa27900497186d221ab8390d00006e00f4064d42ef30")
        val streams = mapOf(
            331 to listOf("ad95c52b22cbb90da33198d922b3563bad207898c0e5c436f6aad6ffc09263f68cced11b5f923372989024c0534f0099f7bd3b0f99eea0ae3e9b7b92b5d35c54",
                "ab88b0e00e07ee9c288614bdfc0796731e88055dbde5c0e113d38ff3dae5b746d3a9ebf43e7ab71657bdb501e5766a25bcd2981e2abccb858b1bbe5eb790f679",
                "d2162e7e90997002b6188a23629908ed80169bc3237b5e7f8d4d116d447b29d84d37756aa0e42988c9232b9f7be8f4bb224c0680b422551b158520c0290e68e7"),
            350 to listOf("0093d39d80df5a1ddf128552f1a9fe8bfdda430a7253539ca3cba424639248cf96c0a30f32e4f7c23263c85a47a0d185aaef5d6ceb503e26b66459ad24854846",
                "739300400e134cc98e4c8116c1623a6d186e49d099e1c0c00f305837b7f001db5c0553309ca1776451a1f05bc9d4334216397cceff78c3adb525f7ca3eb716db",
                "000093d39d80df5a1ddf128552f1a9fe8bfdda430a7253539ca3cba424639248cf96c0a30f32e4f7c23263c85a47a0d185aaef5d6ceb503e26b66459ad248548"),
        )
        for (version in listOf(331, 350)) {
            val crypto = WolfArchiveCrypto(pw, salt, version, 1600)
            crypto.configureIndex(1152)
            val all = ByteArray(1600).also { crypto.decodeOuter(it, 0) }
            assertEquals(hashes.getValue(version), sha(all))
            val header = ByteArray(64).also(crypto::decodeAddresses)
            assertArrayEquals(hex(addresses.getValue(version)), header.copyOfRange(8, 40))
            for ((i, position) in listOf(0L, 255L, 65535L).withIndex()) {
                val stream = ByteArray(64).also { crypto.decodeInner(it, position) }
                assertArrayEquals(hex(streams.getValue(version)[i]), stream)
            }
            val reconstructed = ByteArray(1600)
            for (start in 0 until 1600 step 17) {
                val slice = ByteArray(minOf(17, 1600 - start)).also { crypto.decodeOuter(it, start.toLong()) }
                slice.copyInto(reconstructed, start)
            }
            assertArrayEquals(all, reconstructed)
        }
    }

    @Test fun official331ArchiveOpensWithoutExtractionAndReadsCompressedMetadataAndPng() {
        val file = File(System.getProperty("wolf.fixtureRoot", "../build/wolf-research"), "runtime/public/Data.wolf")
        assumeTrue("Official archive research fixture is local only", file.isFile)
        val reads = mutableListOf<Pair<Long, Int>>()
        val source = object : WolfArchiveSource {
            private val access = RandomAccessFile(file, "r")
            override val size = access.length()
            @Synchronized override fun read(offset: Long, length: Int) = ByteArray(length).also { access.seek(offset); access.readFully(it); reads += offset to length }
            override fun close() = access.close()
        }
        val originalHash = sha(file.readBytes())
        WolfArchive.open(source).use { archive ->
            assertEquals(8, archive.version)
            assertEquals(331, archive.cryptVersion)
            assertTrue(archive.list("").containsAll(listOf("BasicData", "CharaChip", "MapData", "SystemFile")))
            val firstReads = reads.toList()
            assertEquals(2, firstReads.size)
            assertTrue(firstReads.sumOf { it.second } < 20_000)
            val png = archive.read("CharaChip/Chara_Hero1_USM.png")
            assertArrayEquals(hex("89504e470d0a1a0a"), png.copyOf(8))
            assertEquals(1958, png.size)
            val common = archive.read("BasicData/CommonEvent.dat")
            assertEquals(1_259_484, common.size)
            assertEquals("284538f0d03c925ab863111bc9fceefd74cf7ccfba9498f2322154430bcd094a", sha(common))
            assertTrue(WolfFormats.parseCommonEvents(common).isNotEmpty())
            val config = WolfFormats.parseGame(archive.read("BasicData/Game.dat"))
            assertTrue(config.title.isNotBlank())
            val map = WolfFormats.parseMap(archive.read("MapData/TitleMap.mps"))
            assertTrue(map.width > 0 && map.height > 0)
            val audio = archive.read("SystemFile/SE_Enter.ogg")
            assertEquals("eb97f3fa1087e0e45e453d89e00daf688c49d6273c2f5791b64de778a9c428d2", sha(audio))
            val tiles = WolfFormats.parseTilesets(archive.read("BasicData/TileSetData.dat"))
            assertTrue(tiles.isNotEmpty())
            val database = WolfFormats.parseDatabase(archive.read("BasicData/SysDatabase.project"), archive.read("BasicData/SysDatabase.dat"))
            assertTrue(database.types.isNotEmpty())
            assertTrue(reads.none { it.first == 0L && it.second == source.size.toInt() })
        }
        assertEquals(originalHash, sha(file.readBytes()))
    }

    private class BytesSource(private val bytes: ByteArray) : WolfArchiveSource {
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

    private data class Fixture(val bytes: ByteArray, val image: ByteArray, val nameStart: Int, val indexSize: Int,
        val fileBase: Int, val dirBase: Int, val assetStart: Int)

    /** Independent wire assembler; synthetic data contains no game or engine assets. */
    private fun fixture(version: Int, noKey: Boolean = false, cryptVersion: Int = 0, compressedIndex: Boolean = false,
        lzImage: Boolean = false, huffmanImage: Boolean = false, partialImage: Boolean = false): Fixture {
        val word = if (version == 5) 4 else 8
        val record = if (version == 5) 44 else if (version == 6) 64 else 72
        val headerSize = if (version == 5) 28 else if (version == 6) 48 else 64
        val charset = Charset.forName("windows-31j")
        val nameTable = ByteArrayOutputStream()
        fun name(value: String): Int {
            val address = nameTable.size()
            val bytes = value.toByteArray(charset)
            val upper = bytes.map { if ((it.toInt() and 255) in 97..122) (it - 32).toByte() else it }.toByteArray()
            val padded = (bytes.size + 1 + 3) / 4 * 4
            nameTable.write(byteArrayOf((padded / 4).toByte(), 0, 0, 0))
            nameTable.write(upper); repeat(padded - upper.size) { nameTable.write(0) }
            nameTable.write(bytes); nameTable.write(0)
            while (nameTable.size() % 4 != 0) nameTable.write(0)
            return address
        }
        val rootName = name("Root.txt")
        val folderName = name("Picture")
        val imageName = name("ソ.png")
        val root = "root".toByteArray()
        val image = if (partialImage) ByteArray(3500) { (it * 19 + 7).toByte() } else byteArrayOf(0, 1, 2, 0xfe.toByte(), 9, 10)
        val lz = if (lzImage) literalLz(image) else image
        val edge = 1024
        val huffmanKB = if (partialImage) 1 else 255
        val huff = if (!huffmanImage) null else uniformHuffman(if (partialImage) lz.copyOfRange(0, edge) + lz.copyOfRange(lz.size - edge, lz.size) else lz)
        val encodedImage = if (huff == null) lz else if (partialImage) huff + lz.copyOfRange(edge, lz.size - edge) else huff
        val fileBase = nameTable.size()
        val dirBase = fileBase + record * 3
        val index = ByteArray(dirBase + word * 8)
        nameTable.toByteArray().copyInto(index)
        fun scalar(offset: Int, value: Long) { if (word == 4) putInt(index, offset, value.toInt()) else putLong(index, offset, value) }
        fun file(i: Int, filename: Int, attributes: Int, dataOffset: Int, size: Int, lzSize: Int = -1, huffSize: Int = -1) {
            val start = fileBase + record * i
            scalar(start, filename.toLong()); scalar(start + word, attributes.toLong())
            scalar(start + word * 2 + 24, dataOffset.toLong()); scalar(start + word * 3 + 24, size.toLong())
            scalar(start + word * 4 + 24, lzSize.toLong())
            if (version == 8) scalar(start + word * 5 + 24, huffSize.toLong())
        }
        file(0, rootName, 0, 0, root.size)
        file(1, folderName, 16, word * 4, 0)
        file(2, imageName, 0, root.size, image.size, if (lzImage) lz.size else -1, huff?.size ?: -1)
        scalar(dirBase, -1); scalar(dirBase + word, -1); scalar(dirBase + word * 2, 2); scalar(dirBase + word * 3, 0)
        scalar(dirBase + word * 4, record.toLong()); scalar(dirBase + word * 5, 0)
        scalar(dirBase + word * 6, 1); scalar(dirBase + word * 7, (record * 2).toLong())
        val indexStored = if (compressedIndex) uniformHuffman(literalLz(index)) else index
        val header = ByteArray(headerSize)
        header[0] = 'D'.code.toByte(); header[1] = 'X'.code.toByte(); header[2] = version.toByte()
        putInt(header, 4, index.size)
        val nameStart = headerSize + root.size + encodedImage.size
        if (version == 5) {
            putInt(header, 8, headerSize); putInt(header, 12, nameStart); putInt(header, 16, fileBase)
            putInt(header, 20, dirBase); putInt(header, 24, 932)
        } else {
            putLong(header, 8, headerSize.toLong()); putLong(header, 16, nameStart.toLong())
            putLong(header, 24, fileBase.toLong()); putLong(header, 32, dirBase.toLong())
            if (version == 6) putLong(header, 40, 932) else {
                putInt(header, 40, 932); putInt(header, 44, (cryptVersion shl 16) or (if (noKey) 1 else 0) or (if (compressedIndex) 0 else 2))
                header[48] = huffmanKB.toByte()
            }
        }
        val rootStored = root.clone()
        val imageStored = encodedImage.clone()
        val indexEncrypted = indexStored.clone()
        if (version != 8) {
            val k = if (version == 5) hex("f0356b1cfb9bed1d0370e32d") else hex("c705ca7d8de3def1d90c85f4")
            // These transformed keys are fixed reference values, not the production generator.
            xorIndependent(header, k, 0); xorIndependent(indexEncrypted, k, 0)
            xorIndependent(rootStored, k, root.size); xorIndependent(imageStored, k, image.size)
        } else if (!noKey) {
            val password = fixedPassword(cryptVersion)
            xorIndependent(indexEncrypted, keyIndependent(password), 0)
            xorIndependent(rootStored, keyIndependent(password + "ROOT.TXT".toByteArray()), root.size)
            xorIndependent(imageStored, keyIndependent(password + "ソ.PNGPICTURE".toByteArray(charset)), image.size)
        }
        return Fixture(header + rootStored + imageStored + indexEncrypted, image, nameStart, index.size, fileBase, dirBase, headerSize + root.size)
    }

    private fun fixedPassword(version: Int): ByteArray = when (version) {
        0 -> "WLFRPrO!p(;s5((8P@((UFWlu$#5(=".toByteArray()
        300 -> hex("0f53e13e8eb54191521655ae34c98f79592f596b95199b1b359a2fdec97c1296c314b50f53e13e8e")
        314 -> hex("31f90136a3e38d3c7bc37d25ad6328191bf78e6cc4e5e27682ea4fed61dae0445bb6463b06d5ceb67858d07c82")
        331 -> hex("ca084c5d170ddaa1d727c8415438823254b7f9468e136bcad05c9595e2dc0353609b4a3817f36959a4c79a4363e654afdbbb4358")
        else -> error("Unsupported synthetic profile")
    }

    private fun keyIndependent(source: ByteArray): ByteArray {
        val a = CRC32().also { it.update(source.filterIndexed { i, _ -> i % 2 == 0 }.toByteArray()) }.value
        val b = CRC32().also { it.update(source.filterIndexed { i, _ -> i % 2 == 1 }.toByteArray()) }.value
        return ByteArray(7) { if (it < 4) (a ushr (it * 8)).toByte() else (b ushr ((it - 4) * 8)).toByte() }
    }
    private fun xorIndependent(output: ByteArray, key: ByteArray, position: Int) {
        for (i in output.indices) output[i] = (output[i].toInt() xor key[(position + i) % key.size].toInt()).toByte()
    }
    private fun literalLz(data: ByteArray): ByteArray {
        val encoded = ByteArrayOutputStream()
        for (b in data) { encoded.write(b.toInt() and 255); if (b == 0xfe.toByte()) encoded.write(0xfe) }
        return lzBlock(data.size, encoded.toByteArray())
    }
    private fun lzBlock(size: Int, data: ByteArray): ByteArray = ByteArray(9).also {
        putInt(it, 0, size); putInt(it, 4, data.size + 9); it[8] = 0xfe.toByte()
    } + data
    private fun uniformHuffman(input: ByteArray): ByteArray {
        val header = BitWriter()
        fun size(number: Int) { val width = maxOf(1, 32 - Integer.numberOfLeadingZeros(number)); header.write(6, width - 1); header.write(width, number) }
        size(input.size); size(maxOf(1, input.size))
        repeat(256) { header.write(3, 0); header.write(1, 0); header.write(2, if (it == 0) 1 else 0) }
        val payload = if (input.isEmpty()) byteArrayOf(0) else input.map { (Integer.reverse(it.toInt() and 255) ushr 24).toByte() }.toByteArray()
        return header.bytes() + payload
    }
    private class BitWriter {
        private val bits = mutableListOf<Int>()
        fun write(width: Int, value: Int) { for (i in width - 1 downTo 0) bits += value ushr i and 1 }
        fun bytes() = ByteArray((bits.size + 7) / 8).also { data -> bits.forEachIndexed { i, b -> data[i / 8] = (data[i / 8].toInt() or (b shl (7 - i % 8))).toByte() } }
    }
    private fun putInt(data: ByteArray, offset: Int, value: Int) { for (i in 0..3) data[offset + i] = (value ushr (i * 8)).toByte() }
    private fun putLong(data: ByteArray, offset: Int, value: Long) { for (i in 0..7) data[offset + i] = (value ushr (i * 8)).toByte() }
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun rejects(block: () -> Unit) { assertThrows(WolfFormatException::class.java) { block() } }
}
