package fr.astragames.app.launcher

import android.content.Context
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity

interface GameLauncher {
    fun supports(game: GameEntity): Boolean
    suspend fun launch(context: Context, game: GameEntity): LaunchResult
}

class MToolLauncher : GameLauncher {
    override fun supports(game: GameEntity) = false
    override suspend fun launch(context: Context, game: GameEntity): LaunchResult =
        LaunchResult.Failure("MTool sera disponible dans une version ultérieure")
}
