package fr.astragames.app.ui

import fr.astragames.app.core.search.SearchParser
import fr.astragames.app.data.local.GameEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal data class LibrarySearchResult(
    val query: String,
    val gameIds: Set<String>? = null,
    val loading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeLibrarySearch(
    queries: Flow<String>,
    search: (String) -> Flow<List<GameEntity>>,
    debounceMillis: Long = 180L
): Flow<LibrarySearchResult> = queries.map(String::trim).distinctUntilChanged().flatMapLatest { query ->
    flow {
        if (SearchParser.terms(query).isEmpty()) {
            emit(LibrarySearchResult(query))
        } else {
            emit(LibrarySearchResult(query, loading = true))
            delay(debounceMillis)
            emitAll(search(query).map { games -> LibrarySearchResult(query, games.mapTo(hashSetOf()) { it.id }) })
        }
    }
}
