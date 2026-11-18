package fr.astragames.app.data.repository

import fr.astragames.app.core.metadata.hasGameUpdate
import fr.astragames.app.core.runCatchingCancellable
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.settings.GameUpdatePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class GameUpdateCheck(val checked: Int, val failures: Int, val available: Int)

internal class GameUpdateChecker(
    private val games: Flow<List<GameEntity>>,
    private val preferences: GameUpdatePreferences,
    private val f95Version: suspend (String) -> String?,
    private val ryuugamesVersion: suspend (String) -> String?,
    private val intervalMs: Long = 1_550L
) {
    private val checking = Mutex()

    private suspend fun linkedGames() = games.first().filter { !it.f95Url.isNullOrBlank() || !it.ryuugamesUrl.isNullOrBlank() }

    private suspend fun version(url: String?, fetch: suspend (String) -> String?): String? {
        if (url.isNullOrBlank()) return null
        return runCatchingCancellable { fetch(url)?.trim()?.takeIf(String::isNotEmpty) }.getOrNull()
    }

    suspend fun check(includeUnversioned: Boolean, notify: ((List<Pair<String, String>>) -> Boolean)? = null): GameUpdateCheck = checking.withLock {
        val candidates = linkedGames().filter { includeUnversioned || !it.version.isNullOrBlank() }
        val detected = mutableMapOf<String, String>()
        var failures = 0
        candidates.forEachIndexed { index, game ->
            if (index > 0) delay(intervalMs)
            val latest = version(game.f95Url, f95Version) ?: version(game.ryuugamesUrl, ryuugamesVersion)
            if (latest == null) failures++ else detected[game.id] = latest
        }
        // A scan, an edit or a deletion may have changed the library during the network requests.
        val current = linkedGames().associateBy { it.id }
        val changedLinks = candidates.filter { game ->
            val now = current[game.id]
            now == null || now.f95Url != game.f95Url || now.ryuugamesUrl != game.ryuugamesUrl
        }.mapTo(hashSetOf()) { it.id }
        changedLinks.forEach(detected::remove)
        val saved = preferences.saveCheck(current.keys, detected, changedLinks)
        val available = saved.filter { (id, latest) -> hasGameUpdate(current[id]?.version, latest) }
        if (notify != null) {
            preferences.notifyAvailable(available.filterKeys(detected::containsKey)) { ids ->
                notify(ids.map { id -> current.getValue(id).title to available.getValue(id) })
            }
        }
        GameUpdateCheck(candidates.size, failures, available.size)
    }
}
