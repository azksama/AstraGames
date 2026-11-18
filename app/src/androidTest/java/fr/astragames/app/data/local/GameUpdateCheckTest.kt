package fr.astragames.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.data.repository.GameUpdateCheck
import fr.astragames.app.data.repository.GameUpdateChecker
import fr.astragames.app.settings.GameUpdatePreferences
import fr.astragames.app.settings.latestGameVersionsKey
import fr.astragames.app.settings.notifiedGameVersionsKey
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameUpdateCheckTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file = File(context.cacheDir, "${UUID.randomUUID()}.preferences_pb")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    private val prefs = GameUpdatePreferences(store)
    private val catalog = MutableStateFlow(emptyList<GameEntity>())

    private fun game(id: String, installed: String? = "1") = GameEntity(id, "Jeu $id", version = installed,
        documentUri = "content://versions/$id", physicalPath = null, executableName = "index.html", engine = "HTML5",
        launcher = "EXTERNAL", sourceId = "test", f95Url = "primary/$id", ryuugamesUrl = "secondary/$id",
        dateAdded = 1, lastModified = 1, fingerprint = id)

    private fun checker(primary: suspend (String) -> String?, secondary: suspend (String) -> String? = { null }) =
        GameUpdateChecker(catalog, prefs, primary, secondary, intervalMs = 0)

    @After fun cleanup() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        file.delete()
    }

    @Test fun providerFailureFallsBackAndPreservesVersionsContainingPunctuation() = runBlocking {
        val calls = mutableListOf<String>()
        val label = "Chapter 2 | Edition: \"Été\""
        val game = game("library:id|part")
        catalog.value = listOf(game)
        val check = checker({ url -> calls.add(url); throw IOException("unavailable") },
            { url -> calls.add(url); label })
        assertEquals(GameUpdateCheck(1, 0, 1), check.check(true))
        assertEquals(listOf(game.f95Url, game.ryuugamesUrl), calls)
        assertEquals(mapOf(game.id to label), GameUpdatePreferences(store).latestVersions.first())
        prefs.acknowledge(game.id, label)
        assertEquals(0, check.check(true).available)
    }

    @Test fun failureKeepsKnownVersionsAndUnlinkedGamesDisappearEvenWithAnEmptyLibrary() = runBlocking {
        val a = game("a")
        val b = game("b", installed = null)
        catalog.value = listOf(a, b)
        store.edit { it[latestGameVersionsKey] = "a:2|b:édition:été|bonus|removed:3" }
        val check = checker({ null }, { null })
        assertEquals(GameUpdateCheck(1, 1, 1), check.check(false))
        assertEquals(mapOf("a" to "2", "b" to "édition:été|bonus"), prefs.latestVersions.first())
        catalog.value = listOf(a.copy(f95Url = null, ryuugamesUrl = null))
        assertEquals(GameUpdateCheck(0, 0, 0), check.check(true))
        assertTrue(prefs.latestVersions.first().isEmpty())
    }

    @Test fun markingAReleaseSeenDuringACheckKeepsItHiddenAndANewerVersionStillAppears() = runBlocking {
        val games = listOf(game("a"), game("b"))
        catalog.value = games
        store.edit { it[latestGameVersionsKey] = "a:2|b:2" }
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val pending = async {
            checker({ entered.complete(Unit); resume.await(); "2" }).check(true)
        }
        withTimeout(5_000) { entered.await() }
        coroutineScope { games.forEach { launch { prefs.acknowledge(it.id, "2") } } }
        resume.complete(Unit)
        assertEquals(0, pending.await().available)
        assertTrue(prefs.latestVersions.first().isEmpty())
        assertEquals(1, checker({ if (it.endsWith("/b")) "3" else "2" }).check(true).available)
        assertEquals(mapOf("b" to "3"), prefs.latestVersions.first())
        prefs.acknowledge("b", "2")
        assertEquals(mapOf("b" to "3"), prefs.latestVersions.first())
    }

    @Test fun notificationsRetryAfterDenialAndDeduplicateEachVersionIndependently() = runBlocking {
        catalog.value = listOf(game("a"), game("b"), game("c"))
        store.edit {
            it[latestGameVersionsKey] = "a:2"
            it[notifiedGameVersionsKey] = "a:2|c:2"
        }
        val check = checker({ "2" })
        val posted = mutableListOf<List<Pair<String, String>>>()
        suspend fun run(allow: Boolean) = check.check(false) { items -> posted += items; allow }
        // A notice for A is still visible. C had already been removed from the old cache.
        assertEquals(2, run(false).available)
        assertEquals(setOf("a", "b"), prefs.latestVersions.first().keys)
        run(true)
        run(true)
        assertEquals(listOf(listOf("Jeu b" to "2"), listOf("Jeu b" to "2")), posted)
        checker({ "3" }).check(false) { items -> posted += items; true }
        assertEquals(catalog.value.map { it.title to "3" }, posted.last())
        assertEquals(3, posted.size)
    }

    @Test fun aChangedSourceAndARemovedGameCannotRepublishTheirOldResults() = runBlocking {
        val a = game("a")
        val b = game("b")
        catalog.value = listOf(a, b)
        store.edit { it[latestGameVersionsKey] = "a:2|b:2" }
        val check = checker({
            if (it.endsWith("/b")) catalog.value = listOf(a.copy(f95Url = "primary/other"))
            "3"
        })
        assertEquals(0, check.check(false) { fail("Stale notification"); true }.available)
        assertTrue(prefs.latestVersions.first().isEmpty())
    }

    @Test fun cancellationKeepsTheLastCompleteCacheWithoutStartingTheFallback() = runBlocking {
        catalog.value = listOf(game("a"), game("b"))
        store.edit { it[latestGameVersionsKey] = "a:2|b:2" }
        val waiting = CompletableDeferred<Unit>()
        var fallbacks = 0
        val check = checker({ if (it.endsWith("/a")) "3" else { waiting.complete(Unit); awaitCancellation() } },
            { fallbacks++; null })
        val work = launch { check.check(true) }
        withTimeout(5_000) { waiting.await() }
        work.cancelAndJoin()
        assertEquals(0, fallbacks)
        assertEquals(mapOf("a" to "2", "b" to "2"), prefs.latestVersions.first())
    }
}
