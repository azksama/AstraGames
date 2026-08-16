package fr.astragames.app.settings

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Search providers used for web, thread and image lookups. */
enum class SearchEngine(
    val code: String,
    val displayName: String
) {
    YANDEX("yandex", "Yandex"),
    GOOGLE("google", "Google"),
    QWANT("qwant", "Qwant"),
    BING("bing", "Bing"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo"),
    ECOSIA("ecosia", "Ecosia");

    fun webSearchUrl(query: String): String {
        val encoded = encode(query)
        return when (this) {
            YANDEX -> "https://yandex.com/search/?text=$encoded"
            GOOGLE -> "https://www.google.com/search?q=$encoded"
            QWANT -> "https://www.qwant.com/?q=$encoded&t=web"
            BING -> "https://www.bing.com/search?q=$encoded"
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded"
            ECOSIA -> "https://www.ecosia.org/search?q=$encoded"
        }
    }

    fun imageSearchUrl(query: String): String {
        val encoded = encode(query)
        return when (this) {
            YANDEX -> "https://yandex.com/images/search?text=$encoded"
            GOOGLE -> "https://www.google.com/search?tbm=isch&q=$encoded"
            QWANT -> "https://www.qwant.com/?q=$encoded&t=images"
            BING -> "https://www.bing.com/images/search?q=$encoded"
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded&iax=images&ia=images"
            ECOSIA -> "https://www.ecosia.org/images?q=$encoded"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.toString())

    companion object {
        fun fromCode(code: String?): SearchEngine =
            entries.firstOrNull { it.code == code } ?: YANDEX
    }
}
