package fr.astragames.app.translation

import kotlin.math.ceil

/** Estimate only inference work, excluding download time and fragments loaded from cache. */
internal class TranslationTiming(private val startedAtMs: Long, private val totalCharacters: Long) {
    private var characters = 0L
    private var samples = 0

    fun completed(characterCount: Int, nowMs: Long): Long? {
        characters += characterCount
        samples++
        if (characters >= totalCharacters) return 0
        if (samples < 3 || nowMs <= startedAtMs || characters == 0L) return null
        return ceil((nowMs - startedAtMs).toDouble() * (totalCharacters - characters) / characters / 1000).toLong().coerceAtLeast(1)
    }
}

internal object TranslationOutput {
    fun validated(value: String): String? = value
        .replace(Regex("[\\r\\n\\u0085\\u2028\\u2029]+"), " ").trim()
        .takeIf { it.isNotBlank() && ProtectedText.controls(it).isEmpty() }
}
