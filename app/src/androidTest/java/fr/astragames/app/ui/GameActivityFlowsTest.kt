package fr.astragames.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.AstraApplication
import fr.astragames.app.data.local.AstraDatabase
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.PlaySessionEntity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.settings.dataStore
import fr.astragames.app.settings.latestGameVersionsKey
import fr.astragames.app.settings.notifiedGameVersionsKey
import fr.astragames.app.settings.seenGameVersionsKey
import fr.astragames.app.ui.theme.AstraTheme
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameActivityFlowsTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val store = ViewModelStore()
    private val vm by lazy {
        ViewModelProvider.create(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AstraViewModel::class]
    }
    private val language = AppLocalizer.language
    private var savedPreferences: Preferences? = null

    private fun seedVersions(raw: String) = runBlocking {
        if (savedPreferences == null) savedPreferences = app.dataStore.data.first()
        app.dataStore.edit { it[latestGameVersionsKey] = raw }
    }

    @After fun finish() {
        compose.runOnUiThread { store.clear() }
        savedPreferences?.let { previous -> runBlocking {
            app.dataStore.edit { current ->
                for (key in listOf(latestGameVersionsKey, notifiedGameVersionsKey, seenGameVersionsKey)) {
                    previous[key]?.let { current[key] = it } ?: current.remove(key)
                }
            }
        } }
        AppLocalizer.language = language
    }

    private fun game(id: String, version: String?) = GameEntity(id, id, version = version, documentUri = "content://activity/$id",
        physicalPath = null, executableName = "index.html", engine = "HTML5", launcher = "EXTERNAL", sourceId = "activity",
        dateAdded = 1, lastModified = 1, fingerprint = id)

    @Test fun updateListExcludesOlderEquivalentAndUnknownVersions() = runBlocking {
        val cases = listOf("older" to ("2.10" to "2.9"), "equal" to ("v1.4" to "1.4.0"),
            "prerelease" to ("1.2" to "1.2.0-beta.3"), "newer" to ("1.9" to "1.10"),
            "named" to ("Chapter A" to "Chapter B"), "empty" to ("" to "1"))
        val games = cases.map { (id, labels) -> game(id, labels.first) }
        val remote = cases.associate { (id, labels) -> id to labels.second }
        assertEquals(listOf("newer", "named"), observeGameUpdates(flowOf(games), flowOf(remote)).first().map { it.game.id })
    }

    @Test fun confirmingTheDisplayedVersionKeepsANewerAnnouncement() = runBlocking {
        val game = game(UUID.randomUUID().toString(), "1.0")
        val settings = app.container.settings
        try {
            app.container.dao.upsertGame(game)
            seedVersions("${game.id}:3.0")
            vm.confirmGameUpdate(game.id, "2.0", "1.0")
            assertEquals("2.0", app.container.dao.getGame(game.id)?.version)
            assertEquals("3.0", settings.gameUpdates.latestVersions.first()[game.id])
        } finally {
            app.container.dao.deleteGameCompletely(game.id)
        }
    }

    @Test fun gameDetailsKeepTheVersionShownInTheConfirmationAfterARefreshAndRecreation() {
        val restore = StateRestorationTester(compose)
        val game = game(UUID.randomUUID().toString(), "1.0")
        val dao = app.container.dao
        val banner = "Nouvelle version disponible : "
        val vertical = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        try {
            runBlocking { dao.upsertGame(game) }
            seedVersions("${game.id}:2.0")
            AppLocalizer.language = AppLanguage.FRENCH
            restore.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { GameDetailScreen(game.id, AstraUiState(games = listOf(game)), vm, {}, {}, {}) }
            } }
            compose.waitUntil(5_000) { compose.onAllNodes(vertical).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(vertical).performScrollToNode(hasText(banner + "2.0"))
            compose.onNodeWithText(banner + "2.0").performClick()
            seedVersions("${game.id}:3.0")
            compose.waitUntil(5_000) { vm.latestGameVersions.value[game.id] == "3.0" }
            restore.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Avez-vous effectué la mise à jour vers la version 2.0 ?").assertIsDisplayed()
            compose.onNodeWithText("Oui").performClick()
            compose.waitUntil(5_000) { runBlocking { dao.getGame(game.id)?.version == "2.0" } }
            compose.onNode(vertical).performScrollToNode(hasText(banner + "3.0"))
            compose.onNodeWithText(banner + "3.0").assertIsDisplayed()
            assertEquals("3.0", runBlocking { app.container.settings.gameUpdates.latestVersions.first()[game.id] })
        } finally { runBlocking { dao.deleteGameCompletely(game.id) } }
    }

    @Test fun aConcurrentLibraryEditCannotBeOverwrittenByAnOldConfirmation() = runBlocking {
        val game = game(UUID.randomUUID().toString(), "2.5")
        try {
            app.container.dao.upsertGame(game)
            seedVersions("${game.id}:3.0")
            assertTrue(runCatching { vm.confirmGameUpdate(game.id, "2.0", "1.0") }.isFailure)
            assertEquals("2.5", app.container.dao.getGame(game.id)?.version)
            assertEquals("3.0", app.container.settings.gameUpdates.latestVersions.first()[game.id])
        } finally { app.container.dao.deleteGameCompletely(game.id) }
    }

    @Test fun failedHistoryClearPreservesTheRowsAndCanBeRetriedOnce() {
        val db = Room.inMemoryDatabaseBuilder(app, AstraDatabase::class.java).build()
        val game = game("history-game", null)
        val ended = PlaySessionEntity("ended", game.id, 1_000, 66_000, durationMs = null)
        val running = PlaySessionEntity("running", game.id, 70_000)
        val gate = CompletableDeferred<Unit>()
        val dao = db.dao()
        var submits = 0
        try {
            runBlocking { dao.upsertGame(game); dao.insertPlaySession(ended); dao.insertPlaySession(running) }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER refuse_clear BEFORE DELETE ON play_sessions BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            val history = dao.observePlayHistory()
            compose.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.ENGLISH) {
                val rows by history.collectAsState(initial = emptyList())
                AstraTheme { HistoryContent(listOf(game), rows, {}, {
                    submits++
                    gate.await()
                    dao.clearEndedPlaySessions()
                }, {}, {}) }
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(game.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("1 min", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Clear").performClick()
            val confirm = compose.onNode(hasText("Clear") and hasAnyAncestor(isDialog()))
            confirm.performClick().assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, submits); gate.complete(Unit) }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Could not save changes.").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(ended), runBlocking { dao.getPlayHistory() })
            assertEquals(65_000L, runBlocking { dao.observePlayStats().first().single().totalDurationMs })
            sql.execSQL("DROP TRIGGER refuse_clear")
            confirm.performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No play sessions recorded").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals(2, submits) }
            assertEquals(70_000L, runBlocking { dao.firstOpenSessionStart() })
        } finally { db.close() }
    }

    @Test fun aHistoryDeletionCannotRemoveASessionStillBeingTimed() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AstraDatabase::class.java).build()
        try {
            val dao = db.dao()
            dao.insertPlaySession(PlaySessionEntity("running", "game", 123L))
            dao.deletePlaySession("running")
            assertEquals(123L, dao.firstOpenSessionStart())
        } finally { db.close() }
    }

    @Test fun missingDurationsRespectTheTrackingLimitAndCompletedTiesUseInsertionOrder() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AstraDatabase::class.java).build()
        try {
            val dao = db.dao()
            val game = game("legacy", null)
            dao.upsertGame(game)
            dao.insertPlaySession(PlaySessionEntity("overnight", game.id, 1_000, 24 * 3_600_000L + 1_000))
            dao.insertPlaySession(PlaySessionEntity("backward", game.id, 1_000, 500))
            dao.insertPlaySession(PlaySessionEntity("measured", game.id, 1_000, 5_000, 1_500))
            dao.insertPlaySession(PlaySessionEntity("active", game.id, 5_000))
            assertEquals(listOf("measured", "backward", "overnight"), dao.observePlayHistory().first().map { it.id })
            assertEquals(12 * 3_600_000L + 1_500, dao.observePlayStats().first().single().totalDurationMs)
        } finally { db.close() }
    }

    @Test fun historyClearConfirmationSurvivesScreenRecreation() {
        val restore = StateRestorationTester(compose)
        val game = game(UUID.randomUUID().toString(), null)
        val dao = app.container.dao
        try {
            runBlocking {
                dao.upsertGame(game)
                dao.insertPlaySession(PlaySessionEntity(game.id, game.id, System.currentTimeMillis(), System.currentTimeMillis(), 0))
            }
            AppLocalizer.language = AppLanguage.FRENCH
            restore.setContent { CompositionLocalProvider(LocalAppLanguage provides AppLanguage.FRENCH) {
                AstraTheme { HistoryScreen(AstraUiState(games = listOf(game)), vm, {}) {} }
            } }
            compose.waitUntil(5_000) { compose.onAllNodesWithText(game.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Effacer").performClick()
            val cancel = compose.onNodeWithText("Annuler")
            cancel.assertIsDisplayed()
            restore.emulateSavedInstanceStateRestore()
            cancel.assertIsDisplayed().performClick()
            assertTrue(runBlocking { dao.getPlayHistory().any { it.id == game.id } })
        } finally { runBlocking { dao.deleteGameCompletely(game.id) } }
    }
}
