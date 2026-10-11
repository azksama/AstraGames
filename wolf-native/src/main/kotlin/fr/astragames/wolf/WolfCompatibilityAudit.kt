package fr.astragames.wolf

/** A static inspection never claims that a complete playthrough or media decoding succeeded. */
enum class WolfAuditVerdict { NO_KNOWN_BLOCKER, RUNTIME_CHECKS_REQUIRED, BLOCKED, INCOMPLETE }

data class WolfAuditProgress(val stage: String, val completedMaps: Int = 0, val totalMaps: Int = 0,
    val commands: Long = 0, val currentFile: String = "")

data class WolfAuditFeature(val key: String, val label: String, val occurrences: Long,
    val blocked: Long, val runtimeChecks: Long = 0)

data class WolfAuditFinding(val issue: WolfCapabilityIssue, val mapId: Int? = null,
    val pageIndex: Int? = null, val occurrences: Long = 1)

data class WolfCompatibilityAudit(
    val title: String, val declaredEngineVersion: Int?, val width: Int, val height: Int, val fps: Int,
    val totalMaps: Int, val visitedMaps: Int, val readableMaps: Int, val commonEvents: Int, val pages: Long,
    val commands: Long, val features: List<WolfAuditFeature>, val findings: List<WolfAuditFinding>,
    val problemOccurrences: Long, val detailsTruncated: Boolean, val inventoryTruncated: Boolean,
    val finished: Boolean, val elapsedMs: Long,
) {
    val complete: Boolean get() = finished && visitedMaps == totalMaps && readableMaps == totalMaps &&
        !detailsTruncated && !inventoryTruncated
    val verdict: WolfAuditVerdict get() = when {
        !complete -> WolfAuditVerdict.INCOMPLETE
        findings.isNotEmpty() -> WolfAuditVerdict.BLOCKED
        features.any { it.runtimeChecks > 0 } -> WolfAuditVerdict.RUNTIME_CHECKS_REQUIRED
        else -> WolfAuditVerdict.NO_KNOWN_BLOCKER
    }
    val verdictLabel: String get() = when (verdict) {
        WolfAuditVerdict.NO_KNOWN_BLOCKER -> "Aucun blocage connu au contrôle statique"
        WolfAuditVerdict.RUNTIME_CHECKS_REQUIRED -> "Vérifications à l’exécution nécessaires"
        WolfAuditVerdict.BLOCKED -> "Incompatibilités natives détectées"
        WolfAuditVerdict.INCOMPLETE -> "Analyse incomplète"
    }
    fun textReport(): String = buildString {
        appendLine("ASTRA — COMPATIBILITÉ WOLF RPG")
        appendLine("Jeu : $title")
        appendLine("Bilan : $verdictLabel")
        appendLine("Profil Wolf déclaré : ${declaredEngineVersion ?: "inconnu"} ; ${width}×${height} ; $fps FPS")
        appendLine("Cartes : $visitedMaps/$totalMaps inspectées, $readableMaps lisibles")
        appendLine("Événements communs : $commonEvents ; pages : $pages ; commandes : $commands")
        appendLine("Incompatibilités : ${findings.size} détails distincts ; $problemOccurrences occurrences")
        appendLine("Analyse complète : $complete ; durée : $elapsedMs ms")
        appendLine("Détails limités : $detailsTruncated ; inventaire limité : $inventoryTruncated")
        appendLine()
        appendLine("PORTÉE")
        appendLine("Contrôle des réglages, des bases de données, de tous les événements communs et de toutes les pages des cartes déclarées, même inactives. Aucune commande du jeu n’est exécutée.")
        appendLine("Le contrôle partage les règles du lancement natif. Il vérifie les variantes décodées, références de variables/DB, routes et chemins d’image littéraux.")
        appendLine("Un opcode reconnu ne suffit pas à certifier toutes ses variantes. Les chemins calculés, valeurs dynamiques, décodages de médias, pilotes, services Android et performances nécessitent une vérification pendant le jeu.")
        appendLine("Les cartes absentes du registre SysDB et les fichiers externes générés/téléchargés ne font pas partie de ce scan. Les dialogues et contenus des chaînes ne sont pas exportés.")
        appendLine("Winlator reste disponible en secours ; ce bilan concerne uniquement le moteur natif d’Astra.")
        appendLine()
        appendLine("FONCTIONS UTILISÉES")
        features.forEach { appendLine("${it.key} | ${it.label} | ${it.occurrences} utilisations ; ${it.blocked} bloquées ; ${it.runtimeChecks} à vérifier à l’exécution") }
        appendLine()
        appendLine("INCOMPATIBILITÉS")
        if (findings.isEmpty()) appendLine("Aucune incompatibilité retenue.")
        findings.forEach { finding ->
            val i = finding.issue
            appendLine("${i.file} / carte ${finding.mapId ?: "—"} / événement ${i.eventId} / page ${finding.pageIndex ?: "—"} / commande ${i.opcode} @0x${i.offset.toString(16)} : ${i.reason}")
            appendLine("  Occurrences : ${finding.occurrences}")
            i.operands?.let { appendLine("  $it") }
        }
    }
}

internal interface WolfCapabilityObserver {
    fun checkpoint()
    fun problem(issue: WolfCapabilityIssue, mapId: Int?, pageIndex: Int?)
    fun reference(code: Int, write: Boolean, string: Boolean, blocked: Boolean)
    fun command(command: WolfCommand, blocked: Boolean)
    fun page(page: WolfPage, blocked: Boolean)
    fun beforeMap(reference: WolfMapReference, completed: Int, total: Int)
    fun afterMap(reference: WolfMapReference, completed: Int, total: Int, loaded: Boolean)
}

object WolfCompatibilityAuditor {
    const val MAX_DETAILS = 20_000
    const val MAX_FEATURES = 20_000

    fun inspect(source: WolfAssetSource, progress: (WolfAuditProgress) -> Unit = {}, checkpoint: () -> Unit = {}): WolfCompatibilityAudit {
        val begin = System.nanoTime()
        val checkedSource = object : WolfAssetSource {
            override fun read(path: String): ByteArray { checkpoint(); return source.read(path) }
            override fun exists(path: String): Boolean { checkpoint(); return source.exists(path) }
            override fun list(path: String): List<String> { checkpoint(); return source.list(path) }
        }
        val parser = WolfParser(checkedSource) { checkpoint(); progress(WolfAuditProgress(it)) }
        val game = try { parser.loadGame() } catch (error: Exception) {
            checkpoint()
            return WolfCompatibilityAudit("Wolf RPG", null, 0, 0, 0, 0, 0, 0, 0, 0, 0, emptyList(),
                listOf(WolfAuditFinding(WolfCapabilityIssue("Métadonnées Wolf", -1, -1, 0,
                    "Données non inspectables : ${error.message ?: error.javaClass.simpleName}"))), 1, false, false, false,
                (System.nanoTime() - begin) / 1_000_000)
        }
        return inspectData(checkedSource, game, parser::loadMap, progress, checkpoint, begin)
    }

    /** The same scan can use decoded, authored fixtures without creating or ticking a VM. */
    fun inspectData(source: WolfAssetSource, game: WolfGameData, mapLoader: (Int) -> WolfMap,
        progress: (WolfAuditProgress) -> Unit = {}, checkpoint: () -> Unit = {},
        beginNanos: Long = System.nanoTime()): WolfCompatibilityAudit {
        val observer = Inventory(game, progress, checkpoint)
        checkpoint()
        progress(WolfAuditProgress("Analyse des événements communs", totalMaps = game.maps.size))
        WolfCapabilityInspector.inspect(game, game.start.mapId, null, source, mapLoader, true, observer, MAX_DETAILS)
        checkpoint()
        progress(WolfAuditProgress("Analyse terminée", observer.visited, game.maps.size, observer.commands))
        return WolfCompatibilityAudit(game.config.title, game.config.engineCompatibilityVersion, game.config.width,
            game.config.height, game.config.fps, game.maps.size, observer.visited, observer.readable,
            game.commonEvents.size, observer.pages, observer.commands, observer.features.values.map { it.snapshot() }.sortedBy { it.key },
            observer.findings.values.toList(), observer.problems, observer.detailsTruncated, observer.inventoryTruncated,
            true, (System.nanoTime() - beginNanos) / 1_000_000)
    }

    private class Count(val key: String, val label: String) {
        var occurrences = 0L; var blocked = 0L; var dynamic = 0L
        fun snapshot() = WolfAuditFeature(key, label, occurrences, blocked, dynamic)
    }
    private class Inventory(val game: WolfGameData, val progress: (WolfAuditProgress) -> Unit,
        val check: () -> Unit) : WolfCapabilityObserver {
        val features = linkedMapOf<String, Count>()
        val findings = linkedMapOf<List<Any?>, WolfAuditFinding>()
        var commands = 0L; var pages = 0L; var problems = 0L
        var visited = 0; var readable = 0
        var detailsTruncated = false; var inventoryTruncated = false
        private var lastProgress = 0L
        override fun checkpoint() = check()
        private fun feature(key: String, label: String, blocked: Boolean, dynamic: Boolean = false) {
            if (key !in features && features.size >= MAX_FEATURES) { inventoryTruncated = true; return }
            features.getOrPut(key) { Count(key, label) }.apply { occurrences++; if (blocked) this.blocked++; if (dynamic) this.dynamic++ }
        }
        override fun problem(issue: WolfCapabilityIssue, mapId: Int?, pageIndex: Int?) {
            problems++
            // Offset/page distinguish independent bad instructions; identical repeats are counted.
            val key = listOf(issue.file, mapId, issue.eventId, pageIndex, issue.opcode, issue.offset, issue.reason)
            val previous = findings[key]
            if (previous != null) findings[key] = previous.copy(occurrences = previous.occurrences + 1)
            else if (findings.size < MAX_DETAILS) findings[key] = WolfAuditFinding(issue, mapId, pageIndex)
            else detailsTruncated = true
        }
        override fun reference(code: Int, write: Boolean, string: Boolean, blocked: Boolean) {
            if (code < 1_000_000 && !write && !string) return // Numeric literals are not variables.
            val kind = if (string) "texte" else "nombre"
            val operation = if (write) "écriture" else "lecture"
            val label = when (code) {
                in 9_000_000..9_099_999 -> "Sys[${code % 100_000}]"
                in 9_900_000..9_999_999 -> "SysS[${code % 100_000}]"
                else -> "Référence $code"
            }
            feature("variable:$kind:$operation:$code", "$label · $kind · $operation", blocked)
        }
        override fun command(command: WolfCommand, blocked: Boolean) {
            commands++
            val code = command.opcode
            val dynamicText = command.strings.any { text -> WolfTextCodes.variableToken.findAll(text).any {
                !WolfTextCodes.quotedEscape(text, it.range.first)
            } }
            // Host/media operations cannot be established by lexical checks alone.
            val dynamic = dynamicText || code in setOf(140, 150, 160, 161, 162, 170, 171, 172, 173, 174, 175, 221, 222, 251, 260, 270)
            feature("commande:$code", commandLabel(code), blocked, dynamic)
            command.route?.let { route(it) }
            if (code == 122 && command.arguments.getOrElse(1) { 0 } and 0x40000 != 0) {
                val literal = command.strings.firstOrNull()
                if (literal?.startsWith("<<") == true) {
                    val name = literal.removePrefix("<<").substringBefore('=').substringBefore('/').substringBefore('>').take(80)
                    feature("macro:$name", "Macro $name", blocked, dynamicText)
                } else feature("macro:calculée", "Macro calculée à l’exécution", blocked, true)
            }
            val now = System.nanoTime()
            if (now - lastProgress >= 100_000_000L) {
                lastProgress = now
                progress(WolfAuditProgress("Analyse des commandes", visited, game.maps.size, commands))
            }
        }
        private fun route(route: WolfRoute) {
            val blocked = WolfCapabilityInspector.routeProblem(route) != null
            route.commands.forEach {
                check()
                feature("route:${it.opcode}", "Route ${it.opcode}", blocked)
            }
        }
        override fun page(page: WolfPage, blocked: Boolean) {
            pages++; feature("pages", "Pages d’événement et conditions", blocked)
            if (page.route.commands.isNotEmpty()) route(page.route)
        }
        override fun beforeMap(reference: WolfMapReference, completed: Int, total: Int) {
            progress(WolfAuditProgress("Analyse des cartes", completed, total, commands, reference.file))
        }
        override fun afterMap(reference: WolfMapReference, completed: Int, total: Int, loaded: Boolean) {
            visited = completed; if (loaded) readable++
            progress(WolfAuditProgress("Analyse des cartes", completed, total, commands, reference.file))
        }
    }

    fun commandLabel(code: Int): String = when (code) {
        0, 4, 99, 498, 499 -> "Structure d’événement"
        101 -> "Message"; 102 -> "Choix"; 103 -> "Commentaire"; 104, 105, 106, 107 -> "Options de texte"
        111, 112 -> "Conditions"; 121 -> "Variables numériques"; 122 -> "Chaînes et macros"
        123 -> "Clavier et souris"; 124 -> "Lecture d’état"; 125, 126 -> "Contrôle des entrées"
        130 -> "Transfert de carte"; 140 -> "Audio"; 150, 151 -> "Images et textes"
        160, 161, 162 -> "Écran et caméra"; in 170..180 -> "Événements et personnages"
        201, 202 -> "Déplacements"; in 210..213, 300 -> "Événements communs"
        220, 221, 222 -> "Sauvegardes"; 230, 231 -> "Attente"; 240, 241, 242 -> "Boucles et appels"
        250 -> "Bases de données"; 251 -> "CSV"; 260 -> "Téléchargement"; 270 -> "Services externes"
        280, 281 -> "Transition d’écran"; 290 -> "Effets"; 401, 402, 420, 421, 1001 -> "Branche d’événement"
        else -> "Commande Wolf $code"
    }
}
