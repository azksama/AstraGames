package fr.astragames.app.settings

/** Languages exposed by Astra. English is the default for new installations. */
enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val flag: String
) {
    ENGLISH("en", "English", "🇬🇧"),
    FRENCH("fr", "Français", "🇫🇷"),
    SPANISH("es", "Español", "🇪🇸"),
    RUSSIAN("ru", "Русский", "🇷🇺"),
    GERMAN("de", "Deutsch", "🇩🇪"),
    CHINESE("zh", "中文", "🇨🇳"),
    JAPANESE("ja", "日本語", "🇯🇵");

    companion object {
        fun fromCode(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}
