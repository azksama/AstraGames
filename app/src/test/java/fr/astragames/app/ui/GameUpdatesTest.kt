package fr.astragames.app.ui

import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameUpdatesTest {
    @Test fun backgroundVersionChangesRefreshUpdatesWithoutManualChecking() = runTest {
        val games = MutableStateFlow(listOf(game("game", "1")))
        val versions = MutableStateFlow<Map<String, String>>(emptyMap())
        val results = mutableListOf<List<GameUpdateInfo>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeGameUpdates(games, versions).collect(results::add)
        }

        versions.value = mapOf("game" to "2")
        runCurrent()
        assertEquals("2", results.last().single().latestVersion)
        versions.value = emptyMap()
        runCurrent()
        assertTrue(results.last().isEmpty())
    }

    @Test fun editingAnInstalledVersionOrDeletingAGameRemovesItsUpdate() = runTest {
        val games = MutableStateFlow(listOf(game("edited", "1"), game("deleted", "1")))
        val versions = MutableStateFlow(mapOf("edited" to "2", "deleted" to "2"))
        val results = mutableListOf<List<GameUpdateInfo>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeGameUpdates(games, versions).collect(results::add)
        }
        runCurrent()
        assertEquals(2, results.last().size)

        games.value = listOf(game("edited", "2"))
        runCurrent()
        assertTrue(results.last().isEmpty())
    }

    @Test fun unknownInstalledVersionsAreNotAdvertisedAsUpdates() = runTest {
        val games = MutableStateFlow(listOf(game("unknown", null), game("current", "2")))
        val versions = MutableStateFlow(mapOf("unknown" to "2", "current" to "2"))
        val results = mutableListOf<List<GameUpdateInfo>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeGameUpdates(games, versions).collect(results::add)
        }
        runCurrent()

        assertTrue(results.last().isEmpty())
    }

    private fun game(id: String, version: String?) = GameEntity(
        id = id, title = id, version = version, documentUri = "content://games/$id", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "source",
        dateAdded = 1, lastModified = 1, fingerprint = id
    )
}
