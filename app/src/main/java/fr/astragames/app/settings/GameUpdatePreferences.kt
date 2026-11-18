package fr.astragames.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException

internal val latestGameVersionsKey = stringPreferencesKey("f95_latest_versions")
internal val notifiedGameVersionsKey = stringPreferencesKey("f95_notified_updates")
internal val seenGameVersionsKey = stringPreferencesKey("game_versions_marked_seen")

private fun entries(raw: String?): List<Pair<String, String>> {
    if (raw.isNullOrEmpty()) return emptyList()
    if (raw.startsWith('[')) {
        val rows = try { JSONArray(raw) } catch (_: JSONException) { null }
        if (rows != null) return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.optJSONArray(i) ?: continue
                val id = row.opt(0) as? String ?: continue
                val version = row.opt(1) as? String ?: continue
                if (id.isNotBlank() && version.isNotBlank()) add(id to version)
            }
        }
    }
    // Previous releases wrote id:version entries separated by pipes.
    return buildList {
        for (entry in raw.split('|')) {
            if (entry.isBlank()) continue
            val separator = entry.indexOf(':')
            if (separator < 0 && isNotEmpty()) {
                val (id, version) = removeAt(lastIndex)
                add(id to "$version|$entry")
            } else if (separator > 0) {
                val id = entry.substring(0, separator)
                val version = entry.substring(separator + 1)
                if (id.isNotBlank() && version.isNotBlank()) add(id to version)
            }
        }
    }
}

private fun Preferences.seenVersions(): Set<Pair<String, String>> {
    this[seenGameVersionsKey]?.let { return entries(it).toSet() }
    // In the old format a delivered notice stayed in the list. Marking it seen removed it.
    return entries(this[notifiedGameVersionsKey]).toSet() - entries(this[latestGameVersionsKey]).toSet()
}

private fun encode(entries: Collection<Pair<String, String>>): String {
    if (entries.isEmpty()) return ""
    return JSONArray().apply {
        for ((id, version) in entries) put(JSONArray().put(id).put(version))
    }.toString()
}

internal class GameUpdatePreferences(private val store: DataStore<Preferences>) {
    val latestVersions = store.data.map { it[latestGameVersionsKey] }.distinctUntilChanged()
        .map { entries(it).toMap() }

    suspend fun saveCheck(linked: Set<String>, detected: Map<String, String>, changedLinks: Set<String>): Map<String, String> {
        val saved = store.edit { prefs ->
            fun retained(key: Preferences.Key<String>) = entries(prefs[key]).filter { (id, _) -> id in linked && id !in changedLinks }
            val latest = retained(latestGameVersionsKey).toMap().toMutableMap()
            val seen = prefs.seenVersions().filterTo(mutableSetOf()) { (id, _) -> id in linked && id !in changedLinks }
            for ((id, version) in detected) {
                if ((id to version) in seen) latest.remove(id) else latest[id] = version
            }
            prefs[latestGameVersionsKey] = encode(latest.toList())
            prefs[seenGameVersionsKey] = encode(seen)
            prefs[notifiedGameVersionsKey] = encode(retained(notifiedGameVersionsKey))
        }
        return entries(saved[latestGameVersionsKey]).toMap()
    }

    suspend fun acknowledge(id: String, version: String) {
        store.edit { prefs ->
            val latest = entries(prefs[latestGameVersionsKey]).toMap().toMutableMap()
            val seen = prefs.seenVersions()
            if (latest[id] == version) {
                latest.remove(id)
                prefs[latestGameVersionsKey] = encode(latest.toList())
            }
            prefs[seenGameVersionsKey] = encode(seen + (id to version))
        }
    }

    suspend fun notifyAvailable(versions: Map<String, String>, post: (List<String>) -> Boolean) {
        if (versions.isEmpty()) return
        store.edit { prefs ->
            val latest = entries(prefs[latestGameVersionsKey]).toMap()
            val notified = entries(prefs[notifiedGameVersionsKey]).toSet()
            val pending = versions.toList().filter { (id, version) -> latest[id] == version && (id to version) !in notified }
            if (pending.isNotEmpty() && post(pending.map { it.first })) {
                prefs[notifiedGameVersionsKey] = encode(notified + pending)
            }
        }
    }
}
