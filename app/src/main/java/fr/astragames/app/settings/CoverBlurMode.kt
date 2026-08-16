package fr.astragames.app.settings

enum class CoverBlurMode(
    val code: String
) {
    OFF("off"),
    STARTUP("startup"),
    MANUAL("manual");

    companion object {
        fun fromCode(code: String?): CoverBlurMode =
            entries.firstOrNull { it.code == code } ?: OFF
    }
}
