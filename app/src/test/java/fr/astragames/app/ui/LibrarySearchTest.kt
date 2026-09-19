package fr.astragames.app.ui

import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySearchTest {
    @Test fun rapidTypingOnlyQueriesTheLatestText() = runTest {
        val queries = MutableStateFlow("")
        val requested = mutableListOf<String>()
        val results = mutableListOf<LibrarySearchResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLibrarySearch(queries, { query -> requested += query; flowOf(emptyList()) }).collect(results::add)
        }

        queries.value = "a"
        runCurrent()
        advanceTimeBy(100)
        queries.value = "astra"
        runCurrent()
        advanceTimeBy(180)
        runCurrent()

        assertEquals(listOf("astra"), requested)
        assertEquals(LibrarySearchResult("astra", emptySet()), results.last())
    }

    @Test fun changingTheQueryCancelsAnOlderSearchBeforeItCanPublish() = runTest {
        val queries = MutableStateFlow("old")
        var oldCancelled = false
        val results = mutableListOf<LibrarySearchResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLibrarySearch(queries, { query ->
                flow {
                    try {
                        if (query == "old") delay(1_000)
                        emit(listOf(game(query)))
                    } finally {
                        if (query == "old") oldCancelled = true
                    }
                }
            }).collect(results::add)
        }
        advanceTimeBy(180)
        runCurrent()
        queries.value = "new"
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(oldCancelled)
        assertFalse(results.any { it.query == "old" && !it.loading })
        assertEquals(setOf("new"), results.last().gameIds)
    }

    @Test fun clearingAndPunctuationImmediatelyRestoreTheLibrary() = runTest {
        val queries = MutableStateFlow("game")
        var searches = 0
        val results = mutableListOf<LibrarySearchResult>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLibrarySearch(queries, { searches++; flowOf(emptyList()) }).collect(results::add)
        }

        queries.value = "  "
        runCurrent()
        assertEquals(LibrarySearchResult(""), results.last())
        queries.value = "???"
        runCurrent()
        assertEquals(LibrarySearchResult("???"), results.last())
        advanceTimeBy(1_000)
        assertEquals(0, searches)
    }

    @Test fun trimmingTheQueryDoesNotRestartAnActiveSearch() = runTest {
        val queries = MutableStateFlow("astra")
        var searches = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeLibrarySearch(queries, { searches++; flowOf(emptyList()) }).collect()
        }
        advanceTimeBy(180)
        runCurrent()
        queries.value = " astra "
        runCurrent()
        advanceTimeBy(180)
        runCurrent()

        assertEquals(1, searches)
    }

    private fun game(id: String) = GameEntity(
        id = id, title = id, documentUri = "content://games/$id", physicalPath = null,
        executableName = "Game.exe", engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "source",
        dateAdded = 1, lastModified = 1, fingerprint = id
    )
}
