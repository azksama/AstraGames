package fr.astragames.app.core.metadata

import fr.astragames.app.updates.releaseVersion

private val numericVersion = Regex("[0-9]+(?:\\.[0-9]+)*")
private val shortCore = Regex("([0-9]+(?:\\.[0-9]+){0,2})([-+].*)?")

private fun orderedRelease(value: String) = shortCore.matchEntire(value)?.let { match ->
    val parts = match.groupValues[1].split('.')
    val core = (0..2).joinToString(".") { parts.getOrNull(it).orEmpty().trimStart('0').ifEmpty { "0" } }
    releaseVersion(core + match.groupValues[2])
}

/** Providers also use free-form edition and chapter names. Order only versions with a known format. */
internal fun hasGameUpdate(installed: String?, published: String?): Boolean {
    fun clean(value: String?) = value.orEmpty().trim().let {
        if (it.length > 1 && it.startsWith("v", ignoreCase = true) && it[1] in '0'..'9') it.drop(1) else it
    }
    val old = clean(installed)
    val new = clean(published)
    if (old.isEmpty() || new.isEmpty() || new.equals(old, ignoreCase = true)) return false
    if (numericVersion.matches(old) && numericVersion.matches(new)) {
        val left = old.split('.')
        val right = new.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrNull(i).orEmpty().trimStart('0')
            val b = right.getOrNull(i).orEmpty().trimStart('0')
            if (a.length != b.length) return b.length > a.length
            if (a != b) return b > a
        }
        return false
    }
    val oldRelease = orderedRelease(old)
    val newRelease = orderedRelease(new)
    return oldRelease == null || newRelease == null || newRelease > oldRelease
}
