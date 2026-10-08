package fr.astragames.app.windows

internal fun wolfRuntimeFailureHint(log: String): String? =
    if (log.contains("a wine server seems to be running, but I cannot connect to it"))
        "Wine ne peut pas joindre son serveur encore actif. Le jeu n’a pas pu démarrer."
    else null

/** A bounded excerpt per stream, preserving the result, loader context and final errors. */
internal fun wolfReportPreview(report: String): String {
    if (report.length <= 48_000) return report
    val sections = report.split(Regex("(?m)(?=^===== [a-z]+ =====$)"))
    return sections.joinToString("") { section ->
        val budget = when {
            section.startsWith("===== runtime") -> 18_000
            section.startsWith("===== startup") -> 10_000
            section.startsWith("===== events") -> 6_000
            else -> 3_000
        }
        if (section.length <= budget) section else section.take(budget / 4) +
            "\n[… Flux abrégé ; partagez le rapport complet …]\n" + section.takeLast(budget * 3 / 4)
    }.take(48_000)
}
