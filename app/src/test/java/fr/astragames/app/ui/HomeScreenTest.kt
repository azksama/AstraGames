package fr.astragames.app.ui

import fr.astragames.app.data.local.GameEntity
import org.junit.Assert.*
import org.junit.Test

class HomeScreenTest {
    private fun game(id: String, lastPlayed: Long?) = GameEntity(
        id = id, title = id, documentUri = "file:///$id", physicalPath = null, executableName = "Game.exe",
        engine = "RPG_MAKER_MV", launcher = "JOIPLAY", sourceId = "test", dateAdded = 1,
        lastModified = 1, fingerprint = id, lastPlayedAt = lastPlayed
    )

    @Test fun homeUsesTheLastTenVisibleGamesRegardlessOfLibraryFilters() {
        val games = listOf(game("second", 20), game("never", null), game("hidden", 50).copy(hidden = true), game("fourth", 1), game("first", 30), game("third", 10))
        val state = AstraUiState(games = games, filteredGames = listOf(games[1]))
        assertEquals(listOf("first", "second", "third", "fourth"), homeRecentGames(state).map { it.id })
    }

    @Test fun recentGamesAreCappedAtTen() {
        val recent = homeRecentGames(AstraUiState(games = (1..20).map { game(it.toString(), it.toLong()) }))
        assertEquals((20 downTo 11).map(Int::toString), recent.map { it.id })
    }

    @Test fun aFreshLibraryDoesNotInventARecentSession() {
        assertTrue(homeRecentGames(AstraUiState(games = listOf(game("unplayed", null)))).isEmpty())
    }
}
