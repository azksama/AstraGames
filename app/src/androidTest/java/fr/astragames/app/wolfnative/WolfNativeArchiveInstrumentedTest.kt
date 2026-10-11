package fr.astragames.app.wolfnative

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.wolf.WolfFormats
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class WolfNativeArchiveInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val payload = byteArrayOf(0, 4, 9, 127)

    @Test fun cppProContainersDiscoverAdjacentCarriersWithoutCopyingTheGame() {
        val fixtures = proFixtures()
        val root = File(context.cacheDir, "native-pro-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            for (profile in listOf(100, 200, 1000, 1010, 1020)) for (noKey in listOf(false, true)) {
                val game = File(root, "$profile-$noKey").apply { mkdirs() }
                val archive = File(fixtures, "Data$profile${if (noKey) "-no-key" else ""}.wolf").copyTo(File(game, "Data.pak"))
                val inputs = mutableListOf(archive)
                if (profile in listOf(1010, 1020) || (profile != 100 && !noKey)) {
                    inputs += File(fixtures, "carrier${if (profile == 1020) 1010 else profile}.bin")
                        .copyTo(File(game, "gAmEfIlE.dAtA"))
                }
                val before = inputs.associate { it.name to WolfNativeSaveStore.digest(it.readBytes()) }
                AndroidWolfAssetSource(context, Uri.fromFile(game)).use(::verifyProAssets)
                assertEquals(before.keys, game.list()!!.toSet())
                before.forEach { (name, digest) -> assertEquals(digest, WolfNativeSaveStore.digest(File(game, name).readBytes())) }
            }
        } finally { check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun cppProCarriersWorkInsideTheSelectedSafSubdirectory() {
        val fixtures = proFixtures()
        val id = UUID.randomUUID().toString()
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        try {
            val fixture = context.contentResolver.call(provider, "fixture", id, null)!!
            val parent = DocumentFile.fromTreeUri(context, Uri.parse(fixture.getString("uri")!!))!!
            parent.createFile("text/plain", "parent-only.txt")!!
            for (profile in listOf(100, 200, 1000, 1010, 1020)) for (noKey in listOf(false, true)) {
                val selected = parent.createDirectory("Selected$profile-$noKey")!!
                val inputs = mutableListOf("Data$profile${if (noKey) "-no-key" else ""}.wolf" to "Data.wolf")
                if (profile in listOf(1010, 1020) || (profile != 100 && !noKey))
                    inputs += "carrier${if (profile == 1020) 1010 else profile}.bin" to "GameFile.data"
                for ((input, output) in inputs) {
                    val target = selected.createFile("application/octet-stream", output)!!
                    context.contentResolver.openOutputStream(target.uri)!!.use { it.write(File(fixtures, input).readBytes()) }
                }
                AndroidWolfAssetSource(context, selected.uri).use { source ->
                    verifyProAssets(source)
                    assertFalse(source.exists("parent-only.txt"))
                }
                assertEquals(inputs.map { it.second }.toSet(), selected.listFiles().map { it.name }.toSet())
            }
        } finally { context.contentResolver.call(provider, "removeFixture", id, null) }
    }

    private fun proFixtures(): File {
        val supplied = InstrumentationRegistry.getArguments().getString("nativeArchiveProRoot")
        assumeTrue("Independent C++ Pro fixtures must be provisioned explicitly", !supplied.isNullOrBlank())
        return File(requireNotNull(supplied)).also { assertTrue(File(it, "Data1000.wolf").isFile) }
    }

    private fun verifyProAssets(source: AndroidWolfAssetSource) {
        assertEquals("魔女の道", WolfFormats.parseGame(source.read("data/basicdata/game.DAT")).title)
        assertEquals("魔女", WolfFormats.parseCommonEvents(source.read("Data/BasicData/CommonEvent.dat")).single().name)
        assertEquals("小さな地図", WolfFormats.parseMap(source.read("Data/MapData/start.mps")).title)
        val image = source.read("Data/Picture/魔女.png")
        assertEquals("e6d66889131220f931fddfb05730d647a0992456c63ae0a8154b4ae32ff219ef", WolfNativeSaveStore.digest(image))
        val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(image, 0, image.size))
        assertEquals(2, bitmap.width); assertEquals(2, bitmap.height); bitmap.recycle()
        assertEquals("69cc381009586842928aa4d0807c054368f8182498d799616380d73f11a1c705",
            WolfNativeSaveStore.digest(source.read("Data/Audio/歩く.wav")))
        assertEquals(5, source.filesRead)
    }

    @Test fun official331ArchiveReadsItsRealCompressedGameDataOnAndroidWithoutExtraction() {
        val supplied = InstrumentationRegistry.getArguments().getString("nativeOfficialRoot")
        assumeTrue("Official archive must be provisioned explicitly", !supplied.isNullOrBlank())
        val root = File(requireNotNull(supplied), "archive331")
        val container = File(root, "Data.wolf")
        assertTrue("Missing official archive", container.isFile)
        val original = WolfNativeSaveStore.digest(container.readBytes())
        AndroidWolfAssetSource(context, Uri.fromFile(root)).use { source ->
            assertTrue(WolfFormats.parseGame(source.read("Data/BasicData/Game.dat")).title.isNotBlank())
            val common = source.read("data/basicdata/commonevent.dat")
            assertEquals("284538f0d03c925ab863111bc9fceefd74cf7ccfba9498f2322154430bcd094a",
                WolfNativeSaveStore.digest(common))
            assertTrue(WolfFormats.parseCommonEvents(common).isNotEmpty())
            assertTrue(WolfFormats.parseMap(source.read("Data/MapData/TitleMap.mps")).width > 0)
            val png = source.read("Data/CharaChip/Chara_Hero1_USM.png")
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size))
            assertTrue(bitmap.width > 0 && bitmap.height > 0); bitmap.recycle()
            assertEquals("eb97f3fa1087e0e45e453d89e00daf688c49d6273c2f5791b64de778a9c428d2",
                WolfNativeSaveStore.digest(source.read("Data/SystemFile/SE_Enter.ogg")))
            assertEquals(5, source.filesRead)
            assertTrue(source.list("Data").containsAll(listOf("BasicData", "CharaChip", "MapData", "SystemFile")))
            assertEquals(listOf("Data.wolf"), root.list()!!.toList())
        }
        assertEquals(original, WolfNativeSaveStore.digest(container.readBytes()))
    }

    @Test fun directArchiveIsMountedLazilyAndLooseFilesTakePriority() {
        val root = File(context.cacheDir, "native-archive-${UUID.randomUUID()}").apply { mkdirs() }
        val archive = File(root, "Data.wolf").apply { writeBytes(archive()) }
        val before = WolfNativeSaveStore.digest(archive.readBytes())
        try {
            AndroidWolfAssetSource(context, Uri.fromFile(root)).use { source ->
                assertArrayEquals(payload, source.read("data/picture/ソ.PNG"))
                assertEquals(listOf("Root.txt", "Picture"), source.list("Data"))
                assertEquals(listOf("ソ.png"), source.list("Data/Picture"))
                assertEquals("root", source.read("Data/Root.txt").toString(Charsets.UTF_8))
                assertEquals(2, source.filesRead)
                assertEquals(payload.size + 4L, source.bytesRead)
                assertFalse(source.exists("Data/Picture/missing.png"))
                assertEquals(listOf("Data.wolf"), root.list()!!.toList())
            }
            File(root, "Data/Picture").mkdirs()
            File(root, "Data/Picture/ソ.png").writeBytes(byteArrayOf(42))
            AndroidWolfAssetSource(context, Uri.fromFile(root)).use { source ->
                assertArrayEquals(byteArrayOf(42), source.read("Data/Picture/ソ.png"))
                assertEquals("root", source.read("Data/Root.txt").toString(Charsets.UTF_8))
            }
            assertEquals(before, WolfNativeSaveStore.digest(archive.readBytes()))
        } finally { check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun safArchiveKeepsTheSelectedDirectoryAndUsesPositionalReads() {
        val id = UUID.randomUUID().toString()
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        try {
            val fixture = context.contentResolver.call(provider, "fixture", id, null)!!
            val root = DocumentFile.fromTreeUri(context, Uri.parse(fixture.getString("uri")!!))!!
            val selected = root.createDirectory("Selected")!!
            val file = selected.createFile("application/octet-stream", "Data.wolf")!!
            context.contentResolver.openOutputStream(file.uri)!!.use { it.write(archive()) }
            root.createFile("text/plain", "parent-only.txt")!!
            AndroidWolfAssetSource(context, selected.uri).use { source ->
                assertArrayEquals(payload, source.read("Data/Picture/ソ.png"))
                assertEquals("root", source.read("Data/Root.txt").toString(Charsets.UTF_8))
                assertFalse(source.exists("parent-only.txt"))
                assertEquals(2, source.filesRead)
                assertEquals(listOf("ソ.png"), source.list("Data/Picture"))
            }
        } finally { context.contentResolver.call(provider, "removeFixture", id, null) }
    }

    /** Independent unencrypted DX8 wire fixture, with one nested directory. */
    private fun archive(): ByteArray {
        val names = ByteArrayOutputStream()
        fun name(value: String): Long {
            val offset = names.size().toLong()
            val encoded = value.toByteArray(Charsets.UTF_8)
            val upper = value.uppercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8)
            val padded = (maxOf(encoded.size, upper.size) + 4) / 4 * 4
            names.write(byteArrayOf((padded / 4).toByte(), 0, 0, 0))
            names.write(upper); repeat(padded - upper.size) { names.write(0) }
            names.write(encoded); names.write(0)
            while (names.size() % 4 != 0) names.write(0)
            return offset
        }
        val rootName = name("Root.txt"); val pictureName = name("Picture"); val imageName = name("ソ.png")
        val fileBase = names.size(); val dirBase = fileBase + 72 * 3
        val index = ByteArray(dirBase + 64).also { names.toByteArray().copyInto(it) }
        val writer = ByteBuffer.wrap(index).order(ByteOrder.LITTLE_ENDIAN)
        fun record(number: Int, filename: Long, directory: Boolean, offset: Long, size: Int) {
            val start = fileBase + number * 72
            writer.putLong(start, filename); writer.putLong(start + 8, if (directory) 16 else 0)
            writer.putLong(start + 40, offset); writer.putLong(start + 48, size.toLong())
            writer.putLong(start + 56, -1); writer.putLong(start + 64, -1)
        }
        record(0, rootName, false, 0, 4); record(1, pictureName, true, 32, 0)
        record(2, imageName, false, 4, payload.size)
        writer.putLong(dirBase, -1); writer.putLong(dirBase + 8, -1)
        writer.putLong(dirBase + 16, 2); writer.putLong(dirBase + 24, 0)
        writer.putLong(dirBase + 32, 72); writer.putLong(dirBase + 40, 0)
        writer.putLong(dirBase + 48, 1); writer.putLong(dirBase + 56, 144)
        val header = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        header.put(0, 'D'.code.toByte()); header.put(1, 'X'.code.toByte()); header.putShort(2, 8)
        header.putInt(4, index.size); header.putLong(8, 64); header.putLong(16, 64 + 4L + payload.size)
        header.putLong(24, fileBase.toLong()); header.putLong(32, dirBase.toLong())
        header.putInt(40, 65001); header.putInt(44, 3); header.put(48, 255.toByte())
        return header.array() + "root".toByteArray() + payload + index
    }
}
