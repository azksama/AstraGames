package fr.astragames.wolf

/** Inspect every decoded mode; an opcode alone never establishes compatibility. */
internal object WolfCapabilityInspector {
    private const val MAX_ISSUES = 512
    fun inspect(game: WolfGameData, currentMapId: Int, currentMap: WolfMap, source: WolfAssetSource, mapLoader: (Int) -> WolfMap, allMaps: Boolean): WolfCapabilities {
        val issues = linkedMapOf<List<Any>, WolfCapabilityIssue>()
        var truncated = false
        var inspectedCommand: WolfCommand? = null
        var inspectedPage: WolfPage? = null
        fun issue(file: String, event: Int, opcode: Int, offset: Int, reason: String?, detail: String? = null) {
            if (reason == null) return
            val key = listOf(file, event, opcode, reason)
            if (key in issues) return
            if (issues.size >= MAX_ISSUES) { truncated = true; return }
            // Format only retained diagnostics; no dialogue or whole command lists are kept.
            val operands = detail ?: inspectedCommand?.let(WolfDiagnosticOperands::command)
                ?: inspectedPage?.let(WolfDiagnosticOperands::page)
            issues[key] = WolfCapabilityIssue(file, event, opcode, offset, reason, operands)
        }
        fun value(file: String, event: Int, opcode: Int, offset: Int, code: Int, write: Boolean = false, string: Boolean = false) {
            issue(file, event, opcode, offset, variableProblem(code, write, string))
            if (code in 1_000_000_000..1_199_999_999 || code in 1_300_000_000..1_399_999_999) {
                val base = code / 100_000_000 * 100_000_000
                val kind = when (base) { 1_000_000_000 -> WolfDatabaseKind.USER; 1_100_000_000 -> WolfDatabaseKind.MUTABLE; else -> WolfDatabaseKind.SYSTEM }
                val relative = code - base
                val type = game.databases[kind]?.types?.getOrNull(relative / 1_000_000)
                val field = type?.fields?.getOrNull(relative % 100)
                if (field?.valueType != if (string) WolfValueType.STRING else WolfValueType.NUMBER)
                    issue(file, event, opcode, offset, "Champ DB ${if (string) "texte" else "numérique"} absent ou incompatible : $code")
                if (kind != WolfDatabaseKind.MUTABLE && relative % 1_000_000 / 100 >= (type?.rows?.size ?: 0))
                    issue(file, event, opcode, offset, "Rangée DB absente : $code")
            }
            if (code == 9_000_119 && (game.config.engineCompatibilityVersion ?: 0) < 300)
                issue(file, event, opcode, offset, "Variable système119 absente de ce profil Wolf")
            if (code == 9_000_140 && (game.config.engineCompatibilityVersion ?: 0) < 355)
                issue(file, event, opcode, offset, "Filtre d'affichage140 absent de ce profil Wolf")
            if (code == 9_000_148 && (game.config.engineCompatibilityVersion ?: 0) < 330)
                issue(file, event, opcode, offset, "Échelle du prochain lancement absente de ce profil Wolf")
            if (code == 9_000_150 && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, opcode, offset, "Conversion de texte150 absente de ce profil Wolf")
            if (string && code in 9_900_062..9_900_067 && (game.config.engineCompatibilityVersion ?: 0) < 300)
                issue(file, event, opcode, offset, "Police système absente de ce profil Wolf")
        }
        fun inspectCommand(file: String, event: Int, c: WolfCommand) {
            issue(file, event, c.opcode, c.offset, WolfRuntime.capabilityProblem(c))
            val a = c.arguments
            if (c.opcode == 123 && a.getOrElse(1) { 0 } and 0x70000 != 0 && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Mode de bord récent absent de ce profil Wolf")
            if (c.opcode == 124 && a.getOrElse(1) { 0 } ushr 12 and 15 == 3) {
                if (a.getOrElse(2) { -1 } in setOf(13, 30) && (game.config.engineCompatibilityVersion ?: 0) < 340)
                    issue(file, event, c.opcode, c.offset, "Recherche d'événement écran avant Wolf3.40 non qualifiée")
                if (a.getOrElse(2) { -1 } == 24 && game.config.byteSettings.getOrNull(17)?.let(WolfStateReadSemantics::language) == null)
                    issue(file, event, c.opcode, c.offset, "Langue de Game.dat inconnue")
            }
            if (c.opcode == 124 && a.getOrElse(1) { 0 } ushr 12 and 15 == 2 &&
                (a[1] ushr 16 and 255 in 22..23 || a[1] ushr 16 and 255 >= 150) && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Conversion écran/carte absente de ce profil Wolf")
            if (c.opcode == 130 && a.size >= 6 && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Direction après transfert absente de ce profil Wolf")
            if (c.opcode == 122 && a.getOrElse(1) { 0 } and 0x40000 != 0 && (game.config.engineCompatibilityVersion ?: 0) < 300)
                issue(file, event, c.opcode, c.offset, "Codes spéciaux de ce profil Wolf non établis")
            if (c.opcode == 122 && a.getOrElse(1) { 0 } and 0x40000 != 0 && c.strings.any { it.startsWith("<<FIND_PATH_DIR") } && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Recherche de chemin absente de ce profil Wolf")
            if (c.opcode == 122 && a.getOrElse(1) { 0 } and 0x40000 != 0 && c.strings.any { it.startsWith("<<MS_VOICE_PLAY") || it.startsWith("<<GET_MS_VOICE_") } && (game.config.engineCompatibilityVersion ?: 0) < 360)
                issue(file, event, c.opcode, c.offset, "Synthèse vocale absente de ce profil Wolf")
            if (c.opcode == 290 && a.getOrElse(0) { 0 } and 15 == 0 && a[0] ushr 4 > 10 && a[0] ushr 4 !in 18..20 && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Effet d'image récent absent de ce profil Wolf")
            if (c.opcode == 290 && a.getOrElse(0) { 0 } and 15 == 1 && a[0] ushr 4 in setOf(7, 8, 12) && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Effet de personnage récent absent de ce profil Wolf")
            if (c.opcode == 290 && a.getOrElse(0) { 0 } and 15 == 3 && (game.config.engineCompatibilityVersion ?: 0) < 350)
                issue(file, event, c.opcode, c.offset, "Masque de visibilité absent de ce profil Wolf")
            fun v(i: Int, write: Boolean = false, string: Boolean = false) { a.getOrNull(i)?.let { value(file, event, c.opcode, c.offset, it, write, string) } }
            fun isString(code: Int): Boolean = when (code) {
                in 1_600_005..1_600_009, in 3_000_000..3_999_999, in 9_900_000..9_999_999 -> true
                in 15_000_000..15_999_999 -> code % 100 in 5..9
                in 1_000_000_000..1_199_999_999, in 1_300_000_000..1_399_999_999 -> {
                    val base = code / 100_000_000 * 100_000_000
                    val kind = when (base) { 1_000_000_000 -> WolfDatabaseKind.USER; 1_100_000_000 -> WolfDatabaseKind.MUTABLE; else -> WolfDatabaseKind.SYSTEM }
                    game.databases[kind]?.types?.getOrNull((code - base) / 1_000_000)?.fields?.getOrNull(code % 100)?.valueType == WolfValueType.STRING
                }
                else -> false
            }
            fun display(s: String) { issue(file, event, c.opcode, c.offset, WolfTextCodes.presentationProblem(s, allowVariables = true)) }
            when (c.opcode) {
                101, 102 -> c.strings.forEach(::display)
                111 -> if (a.isNotEmpty()) repeat(a[0] and 15) { index ->
                    v(1 + index * 3)
                    if (a.getOrElse(3 + index * 3) { 0 } and 16 == 0) v(2 + index * 3)
                }
                112 -> if (a.isNotEmpty()) repeat((a[0] and 15).coerceAtMost(4)) { index ->
                    a.getOrNull(index + 1)?.let { packed ->
                        value(file, event, c.opcode, c.offset, packed and 0x00ffffff, string = true)
                        if (packed and 0x0f000000 != 0) v((a[0] and 15) + 1 + index, string = true)
                    }
                }
                121 -> if (a.size >= 4) {
                    val f = a[3]
                    v(0, write = true)
                    if (f and 4 == 0) v(1)
                    if (f and 8 == 0) v(2)
                }
                122 -> if (a.size >= 2) {
                    val f = a[1]; val sourceMode = f and 15
                    val stringTarget = a[0] !in 1_000_000..1_599_999 && a[0] !in 2_000_000..2_999_999 && a[0] !in 9_000_000..9_199_999
                    v(0, write = f and 16 == 0 && f ushr 8 and 15 != 6, string = f and 16 == 0 && stringTarget)
                    if (sourceMode == 1) v(2, string = true) else if (sourceMode in 2..3) v(2)
                }
                123 -> { v(0, write = true); if (a.getOrElse(1) { 0 } and 0x1000 != 0) v(2) }
                124 -> { v(0, write = true); (2 until a.size).forEach { v(it) } }
                125 -> if (a.getOrElse(0) { 0 } ushr 28 != 0) (1 until a.size).forEach { v(it) }
                126 -> if (a.getOrElse(0) { 0 } ushr 28 == 1 && a[0] and 15 == 0 && a.getOrNull(1) != -1) v(1)
                130 -> {
                    repeat(minOf(a.size, 4)) { v(it) }
                    if (a.size >= 6) v(5)
                    if (a.getOrNull(4) !in setOf(0, 1, 16, 17, 32, 33)) issue(file, event, c.opcode, c.offset, "Mode de téléportation non implémenté")
                }
                140 -> {
                    (1 until a.size).forEach { v(it) }
                }
                151 -> (1 until a.size).forEach { v(it) }
                280, 281 -> (1 until a.size).forEach { v(it) }
                290 -> (1 until a.size).forEach { v(it) }
                150 -> {
                    val f = a.getOrNull(0) ?: 0
                    val action = f and 15; val kind = f ushr 4 and 15
                    (1 until minOf(11, a.size)).forEach { v(it) }
                    if (action == 0 && kind == 2) c.strings.forEach { text ->
                        display(text)
                        // Expanded values are checked at SHOW. Their lexical placeholders do
                        // not establish any timing for ruby, sound escapes or input release.
                        val expanded = WolfTextCodes.staticExpansion(text).replace("\r", "")
                        runCatching { WolfTextPictureTiming.compile(expanded,
                            if (game.config.encoding == WolfEncoding.UTF8) Charsets.UTF_8 else java.nio.charset.Charset.forName("windows-31j"),
                            game.config.engineCompatibilityVersion ?: 0) }.exceptionOrNull()?.let { failure ->
                            issue(file, event, c.opcode, c.offset, failure.message ?: "Progression de texte non établie")
                        }
                    }
                    if (action == 0 && kind in setOf(0, 3)) c.strings.firstOrNull()?.let { path ->
                        issue(file, event, c.opcode, c.offset, pictureResourceProblem(path, source))
                    }
                }
                179, 180 -> v(0)
                201 -> {
                    v(0)
                    issue(file, event, c.opcode, c.offset, routeProblem(c.route))
                    c.route?.commands?.forEach { route -> if (route.opcode !in 29..31) route.arguments.forEach { value(file, event, c.opcode, c.offset, it) } }
                }
                210, 211, 300 -> {
                    if (c.opcode != 300) v(0)
                    if (a.size >= 2 && (c.opcode == 300 || a[0] in 500_000..999_999)) {
                        val f = a[1]; val n = f and 15; val s = f ushr 4 and 15
                        repeat(minOf(n, 10)) { v(2 + it) }
                        repeat(minOf(s, 10)) { if (f and (1 shl (12 + it)) == 0) v(2 + n + it, string = true) }
                        if (f and 0x01000000 != 0) a.lastOrNull()?.let { value(file, event, c.opcode, c.offset, it, write = true) }
                    }
                }
                220 -> v(1)
                240 -> if (a.getOrElse(0) { 0 } != -1_000_000) v(0)
                241 -> v(0)
                242 -> a.indices.forEach { v(it) }
                221, 222 -> {
                    v(0, write = c.opcode == 221, string = isString(a.getOrElse(0) { 0 })); v(1)
                    if (a.getOrElse(3) { 0 } and 1 != 0) v(2)
                    else {
                        val stored = a.getOrElse(2) { 0 }
                        if (stored !in 2_000_000..3_999_999 && stored !in 9_000_000..9_099_999 && stored !in 9_900_000..9_999_999 && stored !in 1_100_000_000..1_199_999_999)
                            issue(file, event, c.opcode, c.offset, "Variable exclue des opérations isolées de sauvegarde : $stored")
                        value(file, event, c.opcode, c.offset, stored, write = c.opcode == 222, string = isString(stored))
                        issue(file, event, c.opcode, c.offset, WolfSystemSupport.savedReferenceProblem(stored, write = c.opcode == 222))
                    }
                }
                250 -> {
                    val flags = a.getOrElse(3) { 0 }
                    repeat(minOf(3, a.size)) { if (flags and (0x10000 shl it) == 0) v(it) }
                    if (flags and 0x70000 != 0 && c.strings.size < 4) issue(file, event, c.opcode, c.offset, "Noms de DB incomplets")
                    if (a.size >= 5 && (flags and 0xf000 == 0x1000 || a[1] != -2 && a[2] != -2)) {
                        if (flags and 15 == 1) v(4)
                        else if (flags and 15 != 2 || flags and 0xf000 == 0x1000) {
                            val kind = when (flags ushr 8 and 15) { 0 -> WolfDatabaseKind.MUTABLE; 1 -> WolfDatabaseKind.SYSTEM; else -> WolfDatabaseKind.USER }
                            val types = game.databases[kind]?.types.orEmpty()
                            val type = if (flags and 0x10000 != 0) types.firstOrNull { it.name == c.strings.getOrNull(1) }
                                else a[0].takeIf { it in 0..999_999 }?.let(types::getOrNull)
                            val descriptor = if (flags and 0x40000 != 0) type?.fields?.firstOrNull { it.name == c.strings.getOrNull(3) }
                                else a[2].takeIf { it in 0..999_999 }?.let { type?.fields?.getOrNull(it) }
                            val textTarget = when {
                                a[1] == -3 || a[2] == -3 -> true
                                a[1] == -1 || a[2] == -1 -> false
                                descriptor != null -> descriptor.valueType == WolfValueType.STRING
                                else -> isString(a[4])
                            }
                            v(4, write = flags and 0xf000 == 0x1000, string = textTarget)
                        }
                    }
                }
                251 -> {
                    if (a.getOrElse(3) { 0 } and 0x10000 == 0) v(0)
                    if (a.getOrElse(3) { 0 } and 0x20000 == 0) v(1)
                    v(4)
                    if (a.getOrElse(3) { 0 } and 0xf000 != 0x1000) {
                        c.strings.firstOrNull()?.takeUnless { it.contains('\\') &&
                            Regex("\\\\(?:v|s|self|cself|sys|sysS)\\[", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.let { literal ->
                            val path = WolfFileText.path(literal, writing = false)
                            if (path.endsWith(".wolfx", true) || source.exists("$path.wolfx"))
                                issue(file, event, c.opcode, c.offset, "CSV WOLFX non pris en charge : $path")
                        }
                    }
                }
                260 -> {
                    if (a.getOrElse(2) { 0 } != 0) v(2, write = true, string = true)
                    if (a.getOrElse(3) { 0 } != 0 && (game.config.engineCompatibilityVersion ?: 0) < 300)
                        issue(file, event, c.opcode, c.offset, "Extensions de téléchargement260 absentes du profil W2")
                }
            }
            c.strings.forEach { s ->
                WolfTextCodes.variableToken.findAll(s).forEach { match ->
                    if (WolfTextCodes.quotedEscape(s, match.range.first)) return@forEach
                    val family = match.groupValues[1]
                    val argument = match.groupValues[2]
                    // Nested/dynamic indices and database names resolve at execution. Static
                    // indices use exactly the same lexer and namespace as the interpreter.
                    if (!argument.matches(Regex("[+-]?[0-9]+"))) return@forEach
                    val id = argument.toIntOrNull()
                    if (id == null) { issue(file, event, c.opcode, c.offset, "Référence de texte hors capacité"); return@forEach }
                    val code = runCatching { when (family) {
                        "sys" -> WolfTextCodes.systemReference(id, string = false)
                        "sysS" -> WolfTextCodes.systemReference(id, string = true)
                        "self" -> Math.addExact(1_100_000, id)
                        "cself" -> Math.addExact(1_600_000, id)
                        "s" -> Math.addExact(3_000_000, id)
                        "udb", "cdb", "sdb" -> return@forEach
                        else -> if (id >= 1_000_000) id else Math.addExact(2_000_000 + (family.drop(1).toIntOrNull() ?: 0) * 100_000, id)
                    } }.getOrNull()
                    if (code == null || id < 0) issue(file, event, c.opcode, c.offset, "Référence de texte hors capacité : ${match.value}")
                    else value(file, event, c.opcode, c.offset, code, string = family in setOf("sysS", "s", "cself"))
                }
            }
        }
        fun command(file: String, event: Int, c: WolfCommand) {
            val previous = inspectedCommand
            inspectedCommand = c
            try { inspectCommand(file, event, c) }
            finally { inspectedCommand = previous }
        }
        val config = game.config
        if (config.byteSettings.getOrNull(8)?.toInt() !in 0..1 || config.byteSettings.getOrNull(9)?.toInt() !in 0..1)
            issue("Game.dat", -1, -1, 0, "Réglage de demi-pas/collision inconnu")
        if (config.characterDirectionsMove !in setOf(4, 8) || config.characterDirectionsImage !in setOf(4, 8) || config.animationPatterns !in setOf(3, 5))
            issue("Game.dat", -1, -1, 0, "Disposition des sprites non prise en charge")
        if (config.fps !in setOf(30, 60)) issue("Game.dat", -1, -1, 0, "Cadence moteur non prise en charge")
        game.commonEvents.forEach { common ->
            val run = common.conditionWord and 15
            if (run !in 0..3) issue("CommonEvent.dat", common.id, -1, common.offset, "Déclencheur de commun inconnu")
            if (run in 1..2 && common.argumentSettings.size == 7) {
                val b = common.argumentSettings
                val code = (common.conditionWord ushr 8) or ((b[0].toInt() and 255) shl 24)
                value("CommonEvent.dat", common.id, -1, common.offset, code)
                if (common.conditionWord ushr 4 and 15 !in 0..6) issue("CommonEvent.dat", common.id, -1, common.offset, "Comparaison de commun inconnue")
            }
            common.returnValue?.let { if (it !in -1..99) issue("CommonEvent.dat", common.id, -1, common.offset, "Index de retour de commun invalide : $it") }
            common.commands.forEach { command("CommonEvent.dat", common.id, it) }
        }
        fun inspectMap(map: WolfMap) {
            val tileset = game.tilesets.getOrNull(map.tilesetId)
            if (tileset == null) issue(map.name, -1, -1, 0, "Tileset absent")
            map.events.forEach { event -> event.pages.forEach { page ->
                val previous = inspectedPage
                inspectedPage = page
                try {
                    if (page.trigger !in 0..4) issue(map.name, event.id, -1, page.offset, "Déclencheur de page inconnu")
                    if (page.conditions.size != 37) issue(map.name, event.id, -1, page.offset, "Conditions de page incomplètes")
                    else repeat(4) { index ->
                        val f = page.conditions[index + 1].toInt() and 255
                        if (f and 15 !in 0..1 || f ushr 4 !in 0..6) issue(map.name, event.id, -1, page.offset, "Mode de condition de page inconnu")
                        if (f and 15 != 0) value(map.name, event.id, -1, page.offset, intAt(page.conditions, 5 + index * 4))
                    }
                    // Animation and speed index the engine's cadence tables. Movement frequency
                    // is an unsigned page byte, not a 0..6 table index. Modes2/3 are the native
                    // random/towards-hero strategies; runtime executes them at the same cadence.
                    if (page.movement.size != 4 || page.movement[0].toInt().and(255) !in 0..6 ||
                        page.movement[1].toInt().and(255) !in 0..6 || page.movement[3].toInt().and(255) !in 0..3)
                        issue(map.name, event.id, -1, page.offset, "Mode autonome de page non implémenté")
                    if (page.graphicBlend !in 0..3) issue(map.name, event.id, -1, page.offset, "Mélange du graphique de page inconnu")
                    if (page.featureCount !in 0..4 || page.features.size < minOf(page.featureCount, 3))
                        issue(map.name, event.id, -1, page.offset, "Fonction avancée de page ou transfert non implémenté")
                    if (page.route.commands.isNotEmpty()) issue(map.name, event.id, 201, page.offset, routeProblem(page.route))
                    page.commands.forEach { command(map.name, event.id, it) }
                } finally { inspectedPage = previous }
            } }
        }
        fun result(mapCount: Int, complete: Boolean): WolfCapabilities {
            val result = issues.values.toMutableList()
            if (truncated) result += WolfCapabilityIssue("Jeu", -1, -1, 0, "Rapport limité à $MAX_ISSUES incompatibilités distinctes ; d'autres existent")
            return WolfCapabilities(result, mapCount, complete)
        }
        inspectMap(currentMap)
        // A full audit aggregates every map's references, including failures. Keep the
        // bounded issue budget and stream maps rather than retaining the whole game.
        if (!allMaps) return result(1, false)
        var mapCount = 1
        for (reference in game.maps) {
            if (reference.id == currentMapId) continue
            try { inspectMap(mapLoader(reference.id)) }
            catch (error: Exception) { issue(reference.file, -1, -1, 0, "Carte non inspectable : ${error.message ?: error.javaClass.simpleName}") }
            mapCount++
        }
        // Limit retained diagnostics, never the number of maps actually inspected.
        // A truncated report must not advertise complete compatibility coverage.
        return result(mapCount, !truncated)
    }

    private fun pictureResourceProblem(input: String, source: WolfAssetSource): String? {
        val path = input.replace("\r", "") // Runtime.text removes CR before resource expansion.
        if (path.isBlank() || path.startsWith('<')) return null
        // Mutable strings, common values and nested DB expressions are expanded by the
        // interpreter at SHOW. A lexical placeholder is never a filename to look up.
        if (WolfTextCodes.variableToken.findAll(path).any { !WolfTextCodes.quotedEscape(path, it.range.first) }) return null
        val normalized = runCatching { WolfParser.rootAssetPath(WolfParser.imageAssetPath(path)) }
            .getOrElse { return "Chemin de pic invalide" }
        return runCatching {
            // Media readers prefer foo.png.wolfx over foo.png and accept either spelling.
            // Presence alone does not certify the encryption key, pixels or media format;
            // those remain checked by the resource worker when the image is required.
            val present = !normalized.endsWith(".wolfx", true) && source.exists("$normalized.wolfx") || source.exists(normalized)
            if (present) null else "Ressource de pic absente : $normalized"
        }.getOrElse { "Ressource de pic non vérifiable : $normalized (${it.message ?: it.javaClass.simpleName})" }
    }

    fun variableProblem(code: Int, write: Boolean = false, string: Boolean = false): String? {
        val supported = when {
            string -> when (code) {
                in 1_600_000..1_699_999, in 3_000_000..3_999_999, in 15_000_000..15_999_999, in 1_100_000_000..1_199_999_999 -> true
                in 1_000_000_000..1_099_999_999, in 1_300_000_000..1_399_999_999 -> !write
                in 9_900_000..9_999_999 -> code % 100_000 in if (write) WolfVariables.SUPPORTED_SYSTEM_STRING_WRITES
                    else WolfVariables.SUPPORTED_SYSTEM_STRING_READS
                else -> false
            }
            code <= 999_999 -> !write
            code in 1_000_000..1_199_999 || code in 1_600_000..1_699_999 || code in 2_000_000..3_999_999 || code in 15_000_000..15_999_999 -> !write || code !in 3_000_000..3_999_999
            code in 8_000_000..8_999_999 -> !write
            code in 9_000_000..9_099_999 -> code % 100_000 in if (write) WolfVariables.SUPPORTED_SYSTEM_WRITES else WolfVariables.SUPPORTED_SYSTEM_READS
            code in 9_100_000..9_199_999 -> !write && code % 10 in setOf(0, 1, 2, 3, 6) && ((code - 9_100_000) / 10 !in 8001..8999) && ((code - 9_100_000) / 10 !in 9001..9999)
            code in 1_100_000_000..1_199_999_999 -> true
            code in 1_000_000_000..1_099_999_999 || code in 1_300_000_000..1_399_999_999 -> !write
            else -> false
        }
        if (supported) return null
        val feature = when (code) {
            in 9_000_000..9_099_999 -> WolfSystemSupport.unsupportedNumbers[code % 100_000]
            in 9_900_000..9_999_999 -> WolfSystemSupport.unsupportedStrings[code % 100_000]
            else -> null
        }
        return "${if (write) "Destination" else "Référence"} ${if (string) "texte" else "numérique"} non certifiée : $code" +
            (feature?.let { " ($it)" } ?: "")
    }

    private fun intAt(b: ByteArray, i: Int): Int = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8) or ((b[i + 2].toInt() and 255) shl 16) or (b[i + 3].toInt() shl 24)
    private fun routeProblem(route: WolfRoute?): String? {
        if (route == null) return "Route absente"
        if (route.flags and 7.inv() != 0) return "Options de route inconnues"
        // These five bytes are route-record metadata, not an instruction to change traits.
        if (route.header.isNotEmpty() && route.header.size != 5) return "En-tête de route incomplet"
        val known = (0..23).toSet() + setOf(25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,159,160,161)
        if (route.commands.any { it.opcode in 29..31 && it.args.size != 1 }) return "Opérande de cadence de route absent"
        if (route.commands.any { it.opcode in setOf(29,31) && it.args[0].toShort().toInt() !in 0..6 }) return "Cadence de route hors table"
        return if (route.commands.any { it.opcode !in known }) "Commande de route non implémentée" else null
    }
}

/** Shared lexer: formatting stays intact for the renderer and timing is executed by the VM. */
internal object WolfTextCodes {
    fun systemReference(id: Int, string: Boolean): Int {
        require(id in 0..99_999) { "Identifiant de variable système texte hors capacité : $id" }
        return (if (string) 9_900_000 else 9_000_000) + id
    }
    val escape = Regex("""\\(imgS|img|isize|iS|space|font|cself|self|sysS|sys|[ucs]db|v[0-9]?|s|sp|se|wE|cE|cS|cR|mx|my|bx|ax|ay|f\+|fR|[cfimr-])\[([^]]*)]|\\([EN!.^><\\]|A[+-])""")
    /** 948960 consumes pairs of backslashes before expanding variable references. */
    fun quotedEscape(text: String, offset: Int): Boolean {
        var preceding = offset - 1
        while (preceding >= 0 && text[preceding] == '\\') preceding--
        return (offset - preceding - 1) and 1 != 0
    }
    /** Validate the surrounding syntax after replacing only variable tokens, including nested ones.
     * Actual values are expanded and checked again when the command executes. */
    val variableToken = Regex("""\\(cself|self|sysS|sys|[ucs]db|v[0-9]?|s)\[([^\[\]]*)]""")
    fun staticExpansion(input: String): String = WolfTextExpansion.staticShape(input)
    fun presentationProblem(input: String, allowVariables: Boolean = false): String? {
        var problem: String? = null
        val expanded = if (allowVariables) staticExpansion(input) else input
        val stripped = escape.replace(expanded) { match ->
            val code = match.groupValues[1]
            val argument = match.groupValues[2]
            val variable = code in setOf("v", "s", "self", "cself", "sys", "sysS", "udb", "cdb", "sdb") || code.matches(Regex("v[0-9]"))
            if (variable && !allowVariables) problem = "Référence de texte non développée : ${match.value}"
            when {
                code == "r" -> if (',' !in argument) problem = "Ruby incomplet : ${match.value}"
                code in setOf("img", "imgS", "se") -> if (argument.isBlank()) problem = "Ressource de texte vide"
                code.isNotBlank() && !variable -> if (argument.toIntOrNull() == null) problem = "Paramètre de texte non numérique : ${match.value}"
            }
            ""
        }
        return problem ?: if ('\\' in stripped) "Code de texte non pris en charge dans : ${input.take(120)}" else null
    }
}

/** Message timing measured in engine ticks; no host thread or blocking sleep. */
internal class WolfTextProgram(text: String, initialSpeed: Int, initialDelay: Int, private val fps: Int, convertSpecial: Boolean = true) {
    private data class Token(val count: Int = 0, val code: String = "", val argument: String = "")
    private val tokens = buildList {
        var offset = 0
        while (offset < text.length) {
            val match = if (convertSpecial) WolfTextCodes.escape.matchAt(text, offset) else null
            if (match != null) {
                val code = match.groupValues[1].ifBlank { match.groupValues[3] }
                val arg = match.groupValues[2]
                val count = when (code) { "r" -> arg.substringBefore(',').codePointCount(0, arg.substringBefore(',').length); "i", "iS", "img", "imgS", "\\" -> 1; else -> 0 }
                add(Token(count, code, arg)); offset += match.value.length
            } else if (convertSpecial && (text.startsWith("<L>", offset) || text.startsWith("<C>", offset) || text.startsWith("<R>", offset))) offset += 3
            else {
                val cp = text.codePointAt(offset)
                add(Token(if (cp == '\n'.code || cp == '\r'.code) 0 else 1)); offset += Character.charCount(cp)
            }
        }
    }
    private var cursor = 0
    private var consumed = 0
    private var speed = initialSpeed
    private var waitTicks = initialDelay.coerceAtLeast(0)
    private var fraction = 0.0
    private var instant = false
    private var fast = false
    var visible = 0; private set
    var waiting = false; private set
    var autoClose = false; private set
    val complete: Boolean get() = cursor >= tokens.size
    fun advance() { if (waiting) waiting = false else fast = true; waitTicks = 0 }
    fun tick(audio: (String, Int, Int) -> Unit) {
        if (waiting || complete) return
        if (waitTicks > 0) { waitTicks--; return }
        fraction += if (speed <= 0 || instant || fast) tokens.size + 1.0 else speed.toDouble() / fps
        var budget = 100_000
        while (cursor < tokens.size && --budget > 0) {
            val token = tokens[cursor]
            if (consumed == 0) when (token.code) {
                "!" -> { cursor++; waiting = true; fast = false; return }
                "." -> if (!fast) { cursor++; waitTicks = (fps / 4).coerceAtLeast(1); return }
                "^" -> autoClose = true
                ">" -> instant = true
                "<" -> instant = false
                "sp" -> speed = token.argument.toInt()
                "se" -> {
                    val options = Regex("^<VOL=([0-9]+)/FREQ=([0-9]+)>(.*)$").matchEntire(token.argument)
                    audio(options?.groupValues?.get(3) ?: token.argument, options?.groupValues?.get(1)?.toInt() ?: 100, options?.groupValues?.get(2)?.toInt() ?: 100)
                }
            }
            if (token.count > consumed) {
                if (!instant && !fast && speed > 0 && fraction < 1) return
                visible++; consumed++; fraction--
                if (consumed < token.count) continue
            }
            cursor++; consumed = 0
        }
        require(budget > 0) { "Message natif trop long sans interruption" }
    }
}
