package fr.astragames.app.ui

import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.core.metadata.hasGameUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun observeGameUpdates(
    games: Flow<List<GameEntity>>,
    latestVersions: Flow<Map<String, String>>
): Flow<List<GameUpdateInfo>> = combine(games, latestVersions) { library, latest ->
    library.mapNotNull { game ->
        val version = latest[game.id]
        if (version != null && hasGameUpdate(game.version, version)) {
            GameUpdateInfo(game, game.version, version)
        } else null
    }
}.distinctUntilChanged()
