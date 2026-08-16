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
            YANDEX -> "https://yandex.com/search/?text=$encoded&filter=0"
            GOOGLE -> "https://www.google.com/search?q=$encoded&safe=off&pws=0"
            QWANT -> "https://www.qwant.com/?q=$encoded&t=web&safesearch=off"
            BING -> "https://www.bing.com/search?q=$encoded&adlt_set=off&safeSearch=off"
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded&kp=-2"
            ECOSIA -> "https://www.ecosia.org/search?q=$encoded&safesearch=off"
        }
    }

    fun imageSearchUrl(query: String): String {
        val encoded = encode(query)
        return when (this) {
            YANDEX -> "https://yandex.com/images/search?text=$encoded&family_mode=0"
            GOOGLE -> "https://www.google.com/search?tbm=isch&safe=off&q=$encoded"
            QWANT -> "https://www.qwant.com/?q=$encoded&t=images&safesearch=off"
            BING -> "https://www.bing.com/images/search?q=$encoded&safeSearch=off&adlt_set=off"
            DUCKDUCKGO -> "https://duckduckgo.com/?q=$encoded&iax=images&ia=images&kp=-2"
            ECOSIA -> "https://www.ecosia.org/images?q=$encoded&safesearch=off"
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.toString())

    companion object {
        fun fromCode(code: String?): SearchEngine =
            entries.firstOrNull { it.code == code } ?: YANDEX
    }
}
