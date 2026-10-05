package fr.astragames.app.translation

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranslationAnalysisCacheTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(app.cacheDir, "analysis-test-${UUID.randomUUID()}").apply { mkdirs() }
    private val context = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() } }
    private val data = File(root, "game/data").apply { mkdirs() }
    private val map = File(data, "Map001.json")
    private val game = GameEntity(id = "analysis-test", title = "Example", documentUri = Uri.fromFile(data.parentFile).toString(), physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MZ", launcher = "JOIPLAY", sourceId = "test", dateAdded = 1, lastModified = 1, fingerprint = "test")
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun openingDoesNotParseGameFilesAndCompletedAnalysisSurvivesManagerRecreation() = runBlocking {
        map.writeText("invalid JSON")
        val manager = GameTranslationManager(context)
        assertNull(manager.savedAnalysis(game))
        map.writeText("""{"displayName":"Village","events":[]}""")
        val first = manager.analyze(game)
        assertEquals(1, first.texts)
        // A screen opened later reads only the saved summary, even if game JSON became invalid.
        map.writeText("invalid JSON")
        val recreated = GameTranslationManager(context)
        assertEquals(first, recreated.savedAnalysis(game))
        assertTrue(runCatching { recreated.analyze(game) }.isFailure)
        assertEquals(first, recreated.savedAnalysis(game)) // no incomplete analysis overwrites the cache
        assertNull(recreated.savedAnalysis(game.copy(id = "other-game")))
    }

    @Test fun changedGameFilesInvalidateExtractedEntriesBeforeExport() = runBlocking {
        map.writeText("""{"displayName":"Village","events":[]}""")
        GameTranslationManager(context).analyze(game)
        map.writeText("""{"displayName":"New town","events":[]}""")
        val output = File(root, "exchange.json").apply { createNewFile() }
        GameTranslationManager(context).exportManual(game, "en", "fr", Uri.fromFile(output))
        val bytes = output.readText()
        assertTrue(bytes.contains("New town"))
        assertFalse(bytes.contains("Village"))
    }
}
