package fr.astragames.wolf

/** Positive admission is deliberately narrower than the experimental VM handlers. */
internal object WolfCapabilityInspector {
    private const val MAX_ISSUES = 512
    fun inspect(game: WolfGameData, currentMapId: Int, currentMap: WolfMap, source: WolfAssetSource, mapLoader: (Int) -> WolfMap, allMaps: Boolean): WolfCapabilities {
        val issues = linkedMapOf<List<Any>, WolfCapabilityIssue>()
        var truncated = false
        fun issue(file: String, event: Int, opcode: Int, offset: Int, reason: String?) {
            if (reason == null) return
            val key = listOf(file, event, opcode, reason)
            if (key in issues) return
            if (issues.size >= MAX_ISSUES) { truncated = true; return }
            issues[key] = WolfCapabilityIssue(file, event, opcode, offset, reason)
        }
        fun value(file: String, event: Int, opcode: Int, offset: Int, code: Int, write: Boolean = false, string: Boolean = false) {
            issue(file, event, opcode, offset, variableProblem(code, write, string))
        }
        fun command(file: String, event: Int, c: WolfCommand) {
            issue(file, event, c.opcode, c.offset, WolfRuntime.capabilityProblem(c))
            val a = c.arguments
            fun v(i: Int, write: Boolean = false, string: Boolean = false) { a.getOrNull(i)?.let { value(file, event, c.opcode, c.offset, it, write, string) } }
            fun display(s: String) { issue(file, event, c.opcode, c.offset, WolfTextCodes.presentationProblem(s, allowVariables = true)) }
            when (c.opcode) {
                101, 102 -> c.strings.forEach(::display)
                111 -> if (a.isNotEmpty()) repeat((a[0] and 15).coerceAtMost(15)) { index -> v(1 + index * 3); v(2 + index * 3) }
                112 -> if (a.isNotEmpty()) repeat((a[0] and 15).coerceAtMost(4)) { v(it + 1, string = true) }
                121 -> if (a.size >= 4) {
                    val f = a[3]
                    v(0, write = true)
                    if (f and 4 == 0) v(1)
                    if (f and 8 == 0) v(2)
                    if (f and (16 or 32 or 64) != 0) issue(file, event, c.opcode, c.offset, "Référence indirecte dynamique non certifiée pour le mode automatique")
                    if (f and 0x10000 != 0 && a.getOrElse(4) { 0 } != 0) issue(file, event, c.opcode, c.offset, "Affectation de plage non certifiée pour le mode automatique")
                }
                122 -> { v(0, write = true, string = true); if (c.strings.isEmpty()) v(2, string = true) }
                123 -> { v(0, write = true); issue(file, event, c.opcode, c.offset, "Lecteur d'entrée et modes attente/souris non certifiés") }
                126 -> issue(file, event, c.opcode, c.offset, "Masques d'interdiction de touches non certifiés")
                130 -> {
                    repeat(minOf(a.size, 4)) { v(it) }
                    // Only the full tile, immediate player/map transfer is fixture-tested.
                    if (a.getOrNull(4) != 32 || a.getOrNull(0) != -2) issue(file, event, c.opcode, c.offset, "Mode de téléportation non certifié pour le mode automatique")
                }
                140 -> {
                    issue(file, event, c.opcode, c.offset, "Résolution DB, format, boucles et fondus audio Wolf non certifiés")
                    c.strings.firstOrNull()?.let { path ->
                        if (path.endsWith(".mid", true) || path.endsWith(".midi", true)) issue(file, event, c.opcode, c.offset, "Synthèse MIDI Wolf absente")
                    }
                }
                150 -> {
                    val f = a.getOrNull(0) ?: 0
                    val action = f and 15; val kind = f ushr 4 and 15
                    (1 until minOf(11, a.size)).forEach { v(it) }
                    if (f !in setOf(0, 2) || action == 0 && (kind != 0 || a.size !in 10..11 || c.strings.size != 1 || a.getOrNull(7) != 0 || a.getOrNull(8) != 0 || a.getOrNull(9) != 100 || a.getOrElse(2) { 0 } != 0))
                        issue(file, event, c.opcode, c.offset, "Variante de pic (texte, ancrage, couleur, rotation, motif, animation ou déplacement) non certifiée")
                    if (kind == 2) c.strings.forEach(::display)
                    if (action == 0 && kind == 0) c.strings.firstOrNull()?.let { path ->
                        runCatching { WolfParser.dataPath(path) }.fold({ normalized -> if (!source.exists(normalized)) issue(file, event, c.opcode, c.offset, "Ressource de pic absente : $normalized") }, { issue(file, event, c.opcode, c.offset, "Chemin de pic invalide") })
                    }
                }
                179, 180 -> v(0)
                201 -> {
                    v(0)
                    issue(file, event, c.opcode, c.offset, "Mouvement scripté et en-tête de route non certifiés pour le mode automatique")
                    c.route?.commands?.forEach { route -> route.arguments.forEach { value(file, event, c.opcode, c.offset, it) } }
                }
                210, 211, 300 -> {
                    if (c.opcode != 300) v(0)
                    if (a.size >= 2 && (c.opcode == 300 || a[0] in 500_000..599_999)) {
                        val f = a[1]; val n = f and 15; val s = f ushr 4 and 15
                        repeat(minOf(n, 10)) { v(2 + it) }
                        repeat(minOf(s, 10)) { if (f and (1 shl (12 + it)) == 0) v(2 + n + it, string = true) }
                        if (f and 0x01000000 != 0) a.lastOrNull()?.let { value(file, event, c.opcode, c.offset, it, write = true) }
                    } else if (c.opcode != 300) issue(file, event, c.opcode, c.offset, "Appel de page ou cible de commun dynamique non certifié")
                }
                220 -> { v(1); issue(file, event, c.opcode, c.offset, "Le codec natif privé ne certifie pas les règles du fichier de sauvegarde Windows") }
                250 -> { issue(file, event, c.opcode, c.offset, "Masques de commande DB expérimentaux, pas encore validés différentiellement"); repeat(minOf(3, a.size)) { v(it) } }
            }
            c.strings.forEach { s ->
                Regex("\\\\(?:v|self|cself)\\[([0-9]+)]", RegexOption.IGNORE_CASE).findAll(s).forEach { match ->
                    val id = match.groupValues[1].toIntOrNull()
                    if (id == null) issue(file, event, c.opcode, c.offset, "Référence de texte hors capacité")
                    else if (match.value.startsWith("\\v", true)) value(file, event, c.opcode, c.offset, if (id >= 1_000_000) id else 2_000_000 + id)
                }
            }
        }
        val config = game.config
        if (config.byteSettings.getOrNull(8)?.toInt() != 1 || config.byteSettings.getOrNull(9)?.toInt() != 1)
            issue("Game.dat", -1, -1, 0, "Déplacement/détection par demi-tuile ou réglage non établi ; seule la collision pleine tuile est portée")
        if (config.characterDirectionsMove !in setOf(4, 8) || config.characterDirectionsImage !in setOf(4, 8) || config.animationPatterns !in setOf(3, 5))
            issue("Game.dat", -1, -1, 0, "Disposition des sprites non prise en charge")
        if (config.defaultHeroGraphic.isNotBlank()) issue("Game.dat", -1, -1, 0, "Cadence exacte d'animation et physique des personnages non certifiées")
        game.commonEvents.forEach { common ->
            val run = common.conditionWord and 15
            if (run !in 0..3) issue("CommonEvent.dat", common.id, -1, common.offset, "Déclencheur de commun inconnu")
            if (run in 1..2 && common.argumentSettings.size == 7) {
                val b = common.argumentSettings
                val code = (common.conditionWord ushr 8) or ((b[0].toInt() and 255) shl 24)
                value("CommonEvent.dat", common.id, -1, common.offset, code)
                if (common.conditionWord ushr 4 and 15 !in 0..6) issue("CommonEvent.dat", common.id, -1, common.offset, "Comparaison de commun inconnue")
            }
            common.returnValue?.let { value("CommonEvent.dat", common.id, -1, common.offset, it) }
            common.commands.forEach { command("CommonEvent.dat", common.id, it) }
        }
        fun inspectMap(map: WolfMap) {
            val tileset = game.tilesets.getOrNull(map.tilesetId)
            if (tileset == null) issue(map.name, -1, -1, 0, "Tileset absent")
            else {
                val checked = hashSetOf<Int>()
                map.tiles.forEach { raw -> if (raw > 0 && checked.add(raw) && tileset.passageForTile(raw) and 0x1e0 != 0)
                    issue(map.name, -1, -1, 0, "Collision par quart, eau transparente, comptoir ou triangle non portée") }
            }
            map.events.forEach { event -> event.pages.forEach { page ->
                if (page.trigger !in 0..4) issue(map.name, event.id, -1, page.offset, "Déclencheur de page inconnu")
                if (page.conditions.size != 37) issue(map.name, event.id, -1, page.offset, "Conditions de page incomplètes")
                else repeat(4) { index ->
                    val f = page.conditions[index + 1].toInt() and 255
                    if (f and 15 !in 0..1 || f ushr 4 !in 0..6) issue(map.name, event.id, -1, page.offset, "Mode de condition de page inconnu")
                    if (f and 15 != 0) value(map.name, event.id, -1, page.offset, intAt(page.conditions, 5 + index * 4))
                }
                if (page.movement.size != 4 || !page.movement.contentEquals(byteArrayOf(3, 3, 3, 0))) issue(map.name, event.id, -1, page.offset, "Fréquences/vitesse/mode autonome de page non certifiés")
                if (page.flags and 0xe0 != 0) issue(map.name, event.id, -1, page.offset, "Demi-pas ou collision carrée de page non portés")
                if (page.graphicBlend != 0) issue(map.name, event.id, -1, page.offset, "Mélange du graphique de page non porté")
                if (page.graphicFile.isNotBlank() && page.flags and 3 != 0) issue(map.name, event.id, -1, page.offset, "Cadence d'animation de page non certifiée")
                if (page.pageTransfer != null || page.featureCount !in 0..3 || page.features.any { it.toInt() != 0 }) issue(map.name, event.id, -1, page.offset, "Fonction avancée de page, ombre, zone ou transfert non certifiée")
                if (page.route.commands.isNotEmpty()) issue(map.name, event.id, 201, page.offset, "Route autonome de page non certifiée")
                page.commands.forEach { command(map.name, event.id, it) }
            } }
        }
        fun result(mapCount: Int, complete: Boolean): WolfCapabilities {
            val result = issues.values.toMutableList()
            if (truncated) result += WolfCapabilityIssue("Jeu", -1, -1, 0, "Rapport limité à $MAX_ISSUES incompatibilités distinctes ; d'autres existent")
            return WolfCapabilities(result, mapCount, complete)
        }
        inspectMap(currentMap)
        // A definite refusal needs no enumeration of the rest of a large game. Positive admission
        // still requires every map; each is read sequentially and no list of maps is retained.
        if (!allMaps || issues.isNotEmpty()) return result(1, false)
        var mapCount = 1
        for (reference in game.maps) {
            if (reference.id == currentMapId) continue
            inspectMap(mapLoader(reference.id)); mapCount++
            if (issues.isNotEmpty()) return result(mapCount, false)
        }
        return result(mapCount, true)
    }

    fun variableProblem(code: Int, write: Boolean = false, string: Boolean = false): String? {
        val supported = when {
            string -> when (code) { in 1_600_000..1_699_999, in 3_000_000..3_999_999, in 15_000_000..15_999_999 -> true; else -> false }
            code <= 999_999 -> !write
            code in 1_000_000..1_199_999 || code in 1_600_000..1_699_999 || code in 2_000_000..3_999_999 || code in 15_000_000..15_999_999 -> !write || code !in 3_000_000..3_999_999
            code in 9_000_000..9_099_999 -> code % 100_000 in if (write) WolfVariables.SUPPORTED_SYSTEM_WRITES else WolfVariables.SUPPORTED_SYSTEM_READS - setOf(8, 24, 73)
            code in 9_100_000..9_199_999 -> !write && code % 10 in setOf(0, 1, 2, 3, 6) && ((code - 9_100_000) / 10 !in 8001..8999) && ((code - 9_100_000) / 10 !in 9001..9999)
            code in 1_000_000_000..1_099_999_999 || code in 1_100_000_000..1_199_999_999 || code in 1_300_000_000..1_399_999_999 -> false // Formula is supported experimentally; descriptor/type admission is not yet differential proof.
            else -> false
        }
        return if (supported) null else "${if (write) "Destination" else "Référence"} ${if (string) "texte" else "numérique"} non certifiée : $code"
    }

    private fun intAt(b: ByteArray, i: Int): Int = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8) or ((b[i + 2].toInt() and 255) shl 16) or (b[i + 3].toInt() shl 24)
}

/** Formatting is retained for the renderer, while unsupported timing semantics fail explicitly. */
internal object WolfTextCodes {
    fun presentationProblem(input: String, allowVariables: Boolean = false): String? {
        var stripped = input
        if (allowVariables) stripped = Regex("\\\\(?:v|s|self|cself)\\[[0-9]+]", RegexOption.IGNORE_CASE).replace(stripped, "")
        stripped = Regex("\\\\c\\[[0-9]+]|\\\\f(?:\\+)?\\[-?[0-9]+]|\\\\r\\[[^,]*,[^]]*]|\\\\(?:i|iS|icon)\\[[0-9]+]|\\\\isize\\[[0-9]+]|\\\\font\\[0]", RegexOption.IGNORE_CASE).replace(stripped, "")
        stripped = Regex("\\\\(?:img|imgS)\\[[^]]*]", RegexOption.IGNORE_CASE).replace(stripped) {
            val path = it.value.substringAfter('[').dropLast(1)
            if (',' in path || path.contains("DIV", true) || path.contains("CUT", true) || path.contains("ANIME", true)) it.value else ""
        }
        stripped = stripped.replace("\\\\", "")
        return if ('\\' in stripped) "Code de texte ou timing non pris en charge dans : ${input.take(120)}" else null
    }
}
