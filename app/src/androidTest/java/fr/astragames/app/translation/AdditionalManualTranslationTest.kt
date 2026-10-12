package fr.astragames.app.translation

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.documentDir
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises nested provider paths, v2 exchanges and exact rollback through Android's resolver. */
class AdditionalManualTranslationTest {
    @Test fun renpyNestedSourcesKeepCodeAndSavesAndRejectLostInterpolation() = runBlocking {
        val source = "define e = Character(\"Original\")\r\nlabel start:\r\n    e \"Hello [name]! {b}World{/b}\" # keep\r\n"
        roundTrip("RENPY", "chapters/story.rpy", source.toByteArray(), true) { bytes ->
            val changed = bytes.toString(Charsets.UTF_8)
            assertTrue(changed.contains("Bonjour Hello [name]! {b}World{/b}"))
            assertTrue(changed.contains("define e = Character(\"Original\")"))
            assertTrue(changed.endsWith(" # keep\r\n"))
        }
    }

    @Test fun rgssMarshalRoundTripKeepsOpaqueResourceFields() = runBlocking {
        val fields = linkedMapOf<MarshalValue.SymbolValue, MarshalValue>(
            MarshalValue.SymbolValue("@id") to MarshalValue.IntValue(1),
            MarshalValue.SymbolValue("@name") to MarshalValue.StringValue("Alice".toByteArray()),
            MarshalValue.SymbolValue("@character_name") to MarshalValue.StringValue("Character.png".toByteArray())
        )
        val original = Marshal.dump(MarshalValue.ArrayValue(mutableListOf(MarshalValue.NilValue,
            MarshalValue.ObjectValue(MarshalValue.SymbolValue("RPG::Actor"), fields))))
        roundTrip("RPG_MAKER_VX_ACE", "Actors.rvdata2", original) { bytes ->
            val actor = (Marshal.load(bytes) as MarshalValue.ArrayValue).items[1] as MarshalValue.ObjectValue
            assertEquals("Bonjour Alice", (actor.ivars[MarshalValue.SymbolValue("@name")] as MarshalValue.StringValue).text)
            assertEquals("Character.png", (actor.ivars[MarshalValue.SymbolValue("@character_name")] as MarshalValue.StringValue).text)
        }
    }

    @Test fun actualWolfMapUsesNestedBackupAndRestoresOriginalBytes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfTextCorpus") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = File(context.filesDir, "wolf-probe/save-fixtures/game22961/Data/MapData/SampleMapA.mps")
        check(original.isFile) { "Push the separately obtained official Wolf sample first." }
        val bytes = original.readBytes()
        roundTrip("WOLF_RPG", "MapData/SampleMapA.mps", bytes) { changed ->
            val before = WolfTextDocument("MapData/SampleMapA.mps", bytes).entries
            val after = WolfTextDocument("MapData/SampleMapA.mps", changed).entries
            assertEquals(before.map { it.path }, after.map { it.path })
            assertEquals("Bonjour " + before.first().text, after.first().text)
            assertEquals(before.drop(1), after.drop(1))
        }
    }

    private suspend fun roundTrip(engine: String, path: String, original: ByteArray,
        rejectMissingToken: Boolean = false, verify: (ByteArray) -> Unit) = withTimeout(40_000) {
        val application = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val privateRoot = File(application.cacheDir, "additional-translation-$id").apply { check(mkdirs()) }
        val context = object : ContextWrapper(application) {
            override fun getNoBackupFilesDir() = File(privateRoot, "private").apply { mkdirs() }
        }
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        try {
            val tree = Uri.parse(context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri"))
            val root = documentDir(context, tree)!!
            val gameRoot = root.findFile("game")!!
            val textRoot = gameRoot.createDirectory(if (engine == "RENPY") "game" else "Data")!!
            var folder = textRoot
            path.split('/').dropLast(1).forEach { name -> folder = folder.createDirectory(name)!! }
            val source = folder.createFile("application/octet-stream", path.substringAfterLast('/'))!!
            write(context, source, original)
            val save = gameRoot.findFile("save")!!.findFile("file1.rpgsave")!!
            val saveBytes = read(context, save)
            val exchange = root.createFile("application/json", "exchange.json")!!
            val game = GameEntity(id = "additional-$id", title = "Translation fixture", documentUri = gameRoot.uri.toString(),
                physicalPath = null, executableName = "Game.exe", engine = engine, launcher = "JOIPLAY", sourceId = "test",
                dateAdded = 1, lastModified = 1, fingerprint = "fixture")
            val manager = GameTranslationManager(context) { _, _ -> error("Manual import never needs ML Kit") }
            val report = manager.exportManual(game, "en", "fr", exchange.uri)
            assertEquals(1, report.files)
            val bundle = JSONObject(read(context, exchange).toString(Charsets.UTF_8))
            assertEquals("astra-game-translation", bundle.getString("format"))
            assertEquals(2, bundle.getInt("version"))
            val row = bundle.getJSONArray("entries").getJSONObject(0)
            assertEquals(path, row.getString("file"))
            val masked = row.getString("source")
            if (rejectMissingToken) {
                row.put("translation", "Bonjour")
                write(context, exchange, bundle.toString().toByteArray())
                assertNotNull(runCatching { manager.importManual(game, exchange.uri) }.exceptionOrNull())
                assertArrayEquals(original, read(context, source))
                assertFalse(manager.hasBackup(game))
            }
            row.put("translation", "Bonjour $masked")
            write(context, exchange, bundle.toString().toByteArray())
            assertTrue(manager.importManual(game, exchange.uri).installed)
            assertTrue(manager.hasBackup(game))
            verify(read(context, source))
            assertArrayEquals(saveBytes, read(context, save))
            manager.restore(game)
            assertArrayEquals(original, read(context, source))
            assertArrayEquals(saveBytes, read(context, save))
            assertFalse(manager.hasBackup(game))
        } finally {
            context.contentResolver.call(provider, "removeFixture", id, null)
            privateRoot.deleteRecursively()
        }
    }

    private fun read(context: Context, file: DocumentFile) = context.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() }
    private fun write(context: Context, file: DocumentFile, bytes: ByteArray) = context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(bytes) }
}
