package fr.astragames.app.translation

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.data.local.FixtureDocumentsProvider
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.documentDir
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GameTranslationTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val original = """{"displayName":"Village","events":[null,{"name":"event","pages":[{"list":[{"code":401,"indent":0,"parameters":["Hello, traveler! \\N[1]"]},{"code":401,"indent":0,"parameters":["Hello, traveler! \\N[1]"]},{"code":401,"indent":0,"parameters":["Open the treasure chest."]}]}]}]}""".toByteArray()

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "translation-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        }
    }
    @After fun cleanup() { root.deleteRecursively() }

    private fun game(uri: String) = GameEntity(
        id = "translation-test", title = "Translation test", documentUri = uri, physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "test",
        dateAdded = 1, lastModified = 1, fingerprint = "fixture"
    )
    private class FakeTranslator : TextTranslator {
        var calls = 0
        var failAfter = Int.MAX_VALUE
        override suspend fun prepare(wifiOnly: Boolean) = Unit
        override suspend fun translate(text: String): String {
            calls++
            if (calls > failAfter) error("Interrupted translation")
            return when (text) {
                "Village" -> "Bourg"
                "Hello, traveler!" -> "Bonjour, voyageur !"
                else -> "Ouvrez le coffre."
            }
        }
        override fun close() = Unit
    }

    @Test fun interruptedTranslationResumesFromCacheAndRestoresExactBytes() = runBlocking {
        val dir = File(root, "game/www/data").apply { mkdirs() }
        val target = File(dir, "Map001.json").apply { writeBytes(original) }
        val game = game(Uri.fromFile(File(root, "game")).toString())
        val fake = FakeTranslator().apply { failAfter = 1 }
        val manager = GameTranslationManager(context) { _, _ -> fake }
        assertEquals(3, manager.analyze(game).texts)
        try { manager.translate(game, "en", "fr", false) {}; fail("Expected interruption") } catch (_: IllegalStateException) { }
        assertArrayEquals(original, target.readBytes())
        fake.calls = 0; fake.failAfter = Int.MAX_VALUE
        manager.translate(game, "en", "fr", false) {}
        assertEquals(2, fake.calls)
        assertTrue(manager.hasBackup(game))
        assertTrue(target.readText().contains("Bonjour"))
        assertTrue(manager.isLaunchSafe(game))
        manager.restore(game)
        assertArrayEquals(original, target.readBytes())
        assertFalse(manager.hasBackup(game))
        fake.calls = 0
        manager.translate(game, "en", "fr", false) {}
        assertEquals(0, fake.calls)
    }

    @Test fun nestedSafFolderSupportsTranslationAndRestoration() = runBlocking {
        val id = UUID.randomUUID().toString()
        val provider = Uri.parse("content://${FixtureDocumentsProvider.AUTHORITY}")
        val uri = Uri.parse(context.contentResolver.call(provider, "fixture", id, null)!!.getString("uri"))
        try {
            val gameFolder = documentDir(context, uri)!!.findFile("game")!!
            val data = gameFolder.createDirectory("data")!!
            val file = data.createFile("application/json", "Map001.json")!!
            context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(original) }
            val game = game(gameFolder.uri.toString()).copy(engine = "RPG_MAKER_MZ")
            val fake = FakeTranslator()
            val manager = GameTranslationManager(context) { _, _ -> fake }
            manager.translate(game, "en", "fr", false) {}
            assertEquals(3, fake.calls)
            assertTrue(manager.hasBackup(game))
            manager.restore(game)
            assertArrayEquals(original, context.contentResolver.openInputStream(file.uri)!!.use { it.readBytes() })
        } finally { context.contentResolver.call(provider, "removeFixture", id, null) }
    }

    @Test fun realModelTranslatesSyntheticGameAndReusesCache() = runBlocking<Unit> {
        Assume.assumeTrue("Run explicitly with -e liveTranslation true", InstrumentationRegistry.getArguments().getString("liveTranslation") == "true")
        val dir = File(root, "real/data").apply { mkdirs() }
        val file = File(dir, "Map001.json").apply { writeBytes(original) }
        val game = game(Uri.fromFile(File(root, "real")).toString())
        val manager = GameTranslationManager(context)
        val start = SystemClock.elapsedRealtime()
        val phases = JSONObject()
        withTimeout(240_000) {
            manager.translate(game, "en", "fr", false) { phases.put(it.phase, SystemClock.elapsedRealtime() - start) }
        }
        val translated = file.readText()
        assertNotEquals(original.toString(Charsets.UTF_8), translated)
        assertTrue(translated.contains("\\\\N[1]"))
        assertTrue(translated.lowercase().contains("bonjour"))
        val elapsed = SystemClock.elapsedRealtime() - start
        manager.restore(game)
        assertArrayEquals(original, file.readBytes())
        val cacheStart = SystemClock.elapsedRealtime()
        manager.translate(game, "en", "fr", false) {}
        val cacheElapsed = SystemClock.elapsedRealtime() - cacheStart
        val report = JSONObject().put("firstMs", elapsed).put("cachedMs", cacheElapsed).put("phases", phases).put("result", JSONObject(translated))
        File(ApplicationProvider.getApplicationContext<Context>().filesDir, "translation-live-result.json").writeText(report.toString(2))
        android.util.Log.i("AstraTranslationTest", "firstMs=$elapsed cachedMs=$cacheElapsed")
    }
}
