package fr.astragames.wolf

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.floor

/**
 * A data interpreter running directly on the Android/JVM host. Game.exe is never executed.
 * The host calls tick on one worker at [fps]; rendering may run at a different refresh rate.
 */
class WolfRuntime private constructor(
    private val source: WolfAssetSource,
    val game: WolfGameData,
    private val mapLoader: (Int) -> WolfMap,
) {
    val fps: Int get() = game.config.fps
    var ticks: Long = 0L; private set
    var mapId: Int = game.start.mapId; private set
    private var map = mapLoader(mapId)
    private var mapEpoch = 0
    private var terminated = false
    private var failed: WolfRuntimeException? = null
    private val effects = ArrayDeque<NativeEffect>()
    private val activeAudio = linkedMapOf<Int, NativeEffect.Audio>()
    private val keys = linkedSetOf<NativeKey>()
    private val newKeys = linkedSetOf<NativeKey>()
    private val bannedKeys = linkedSetOf<NativeKey>()
    private val pictures = linkedMapOf<Int, NativePicture>()
    private val tweens = linkedMapOf<Int, PictureTween>()
    private val erasedEvents = linkedSetOf<Int>()
    private val parallel = linkedMapOf<String, Execution>()
    private var foreground: Execution? = null
    private val reserved = ArrayDeque<CallRequest>()
    private var dialog: NativeDialog? = null
    private var dialogOwner: Invocation? = null
    private var allowMovement = false
    private var freezeWorld = false
    private var pointerX = -1f
    private var pointerY = -1f
    private var pointerPressed = false
    private var targetTile: Pair<Int, Int>? = null
    private var randomState = 0x13579bdf
    private var instructionBudget = 50_000
    private val player = Actor(-2, game.start.x.toFloat(), game.start.y.toFloat(), game.config.defaultHeroGraphic)
    private val actors = linkedMapOf<Int, Actor>()
    private val variables = WolfVariables(game.databases, ::systemValue)
    private var screenOpacity = 255
    private var screenColor = 0

    private data class Actor(
        val id: Int, var x: Float, var y: Float, var path: String,
        var direction: Int = 2, var pattern: Int = 1, var opacity: Int = 255,
        var flags: Int = 3, var speed: Int = 3, var tileId: Int = -1,
        var targetX: Float = x, var targetY: Float = y, var moveTicks: Int = 0,
        var route: WolfRoute? = null, var routeIndex: Int = 0, var routeWait: Int = 0,
        var moving: Boolean = false,
    )
    private data class Loop(val start: Int, val end: Int, var remaining: Int)
    private class Invocation(
        val commands: List<WolfCommand>, val address: EventAddress,
        val returnDestination: Int? = null,
    ) {
        var pc = 0
        var waitTicks = 0
        var waitMovement = false
        var waitKeyDestination: Int? = null
        var waitKeyFlags = 0
        var waitHost = false
        val branches = mutableMapOf<Int, Int>()
        val loops = ArrayDeque<Loop>()
        var command: WolfCommand? = null
    }
    private class Execution(val origin: String, val parallel: Boolean, frame: Invocation) {
        val stack = ArrayDeque<Invocation>().apply { addLast(frame) }
    }
    private data class CallRequest(val id: Int, val numbers: List<Int>, val strings: List<String>, val address: EventAddress)
    private data class PictureTween(val from: NativePicture, val to: NativePicture, val length: Int, var elapsed: Int = 0, val erase: Boolean = false)

    fun key(key: NativeKey, pressed: Boolean) {
        val logical = when (key) { NativeKey.Z, NativeKey.SPACE -> NativeKey.ACCEPT; NativeKey.X -> NativeKey.BACK; else -> key }
        if (pressed) { if (keys.add(logical)) newKeys.add(logical) } else keys.remove(logical)
    }

    fun releaseInput() { keys.clear(); newKeys.clear(); targetTile = null; pointerPressed = false; pointerX = -1f; pointerY = -1f }

    /** Coordinates are inverse-transformed by the host from the visible viewport. */
    fun pointer(x: Float, y: Float, pressed: Boolean = true) {
        val firstPress = pressed && !pointerPressed
        pointerX = x; pointerY = y; pointerPressed = pressed
        if (!firstPress) return
        val currentDialog = dialog
        if (currentDialog != null) {
            // Choice hit testing belongs to the view; tap elsewhere advances a message.
            if (variables.system[73] == 1 && currentDialog.choices.isEmpty()) advanceDialog()
            return
        }
        val tile = game.config.tileSize
        targetTile = floor((x + cameraX()) / tile).toInt() to floor((y + cameraY()) / tile).toInt()
    }

    fun choose(index: Int) {
        val current = dialog ?: return
        if (index !in current.choices.indices) return
        val owner = dialogOwner ?: return
        val original = owner.command ?: return
        owner.branches[original.indent] = index + 2 // Choices use labels 2,3,...; label 1 belongs to a condition.
        variables.system[7] = 0
        dialog = null; dialogOwner = null
    }

    fun tick() {
        failed?.let { throw it }
        if (terminated) return
        ticks++
        instructionBudget = 50_000
        try {
            updateDialogInput()
            updatePictures()
            refreshActors()
            if (!freezeWorld) updateWorld()
            schedule()
            // Parallel common initializers run before the map's automatic title event.
            parallel.values.toList().forEach { execute(it) }
            parallel.entries.removeAll { it.value.stack.isEmpty() }
            foreground?.let { execute(it); if (it.stack.isEmpty()) foreground = null }
        } catch (failure: WolfRuntimeException) {
            failed = failure; releaseInput(); effects.add(NativeEffect.Diagnostic(failure.message.orEmpty())); throw failure
        } catch (failure: Exception) {
            val current = foreground?.stack?.lastOrNull() ?: parallel.values.firstOrNull()?.stack?.lastOrNull()
            val c = current?.command
            val address = current?.address ?: EventAddress(mapId)
            val wrapped = WolfRuntimeException(address.map, address.event, address.common, c?.opcode ?: -1, c?.offset ?: 0, failure.message ?: failure.javaClass.simpleName)
            failed = wrapped; releaseInput(); effects.add(NativeEffect.Diagnostic(wrapped.message.orEmpty())); throw wrapped
        } finally { newKeys.clear() }
    }

    fun frame(): NativeFrame {
        val tile = game.config.tileSize.toFloat()
        val characters = (listOf(player) + actors.values).filter { it.opacity > 0 && (it.path.isNotBlank() || it.tileId >= 0) }.map {
            NativeCharacter(it.id, asset(it.path), (it.x + .5f) * tile, (it.y + 1f) * tile,
                0f, 0f, it.direction, it.pattern, it.opacity, it.flags and 16 != 0, it.tileId, it.moving)
        }
        val tileset = game.tilesets.getOrNull(map.tilesetId)
            ?: WolfTileset(map.tilesetId, "", "", emptyList(), byteArrayOf(), intArrayOf())
        return NativeFrame(game.config.width, game.config.height,
            NativeMapScene(map, tileset, characters, cameraX(), cameraY(), game.config.tileSize,
                game.config.characterDirectionsImage, game.config.animationPatterns),
            pictures.values.sortedWith(compareBy<NativePicture> { it.id / 100_000 }.thenBy { it.z }.thenBy { it.id }),
            emptyList(), dialog, ticks, failed?.message, screenOpacity, screenColor)
    }

    fun drainEffects(): List<NativeEffect> = buildList { while (effects.isNotEmpty()) add(effects.removeFirst()) }
    fun completeHostRequest(success: Boolean) {
        variables.system[24] = if (success) 1 else 0
        (parallel.values + listOfNotNull(foreground)).forEach { execution -> execution.stack.forEach { it.waitHost = false } }
    }

    fun inspectCapabilities(includeAllMaps: Boolean = false): WolfCapabilities {
        return WolfCapabilityInspector.inspect(game, mapId, map, source, mapLoader, includeAllMaps)
    }

    private fun schedule() {
        game.commonEvents.forEach { common ->
            val run = common.conditionWord and 15
            require(run in 0..3) { "Déclencheur de commun non pris en charge : $run" }
            if (run == 0 || !commonActive(common)) return@forEach
            val origin = "common:${common.id}"
            if (run == 2 || run == 3) {
                if (origin !in parallel) parallel[origin] = Execution(origin, true, invocation(common, EventAddress(mapId, -1, common.id)))
            } else if (foreground == null) foreground = Execution(origin, false, invocation(common, EventAddress(mapId, -1, common.id)))
        }
        actors.values.forEach { actor ->
            val event = map.events.firstOrNull { it.id == actor.id } ?: return@forEach
            val page = activePage(event) ?: return@forEach
            val origin = "map:$mapEpoch:${event.id}:${page.index}"
            if (page.trigger == 2 && origin !in parallel) parallel[origin] = Execution(origin, true, Invocation(page.commands, EventAddress(mapId, event.id)))
            else if (page.trigger == 1 && foreground == null) foreground = Execution(origin, false, Invocation(page.commands, EventAddress(mapId, event.id)))
        }
        if (foreground == null && reserved.isNotEmpty()) {
            val request = reserved.removeFirst()
            val common = common(request.id)
            foreground = Execution("reserved:${request.id}", false, invocation(common, request.address.copy(common = common.id), request.numbers, request.strings))
        }
    }

    private fun commonActive(common: WolfCommon): Boolean {
        val run = common.conditionWord and 15
        if (run == 3) return true
        val settings = common.argumentSettings
        require(settings.size == 7) { "Conditions de commun invalides" }
        val code = (common.conditionWord ushr 8) or ((settings[0].toInt() and 255) shl 24)
        val value = intAt(settings, 1)
        return WolfVariables.compare(variables.read(code, EventAddress(mapId, -1, common.id)), value, (common.conditionWord ushr 4) and 15)
    }

    private fun activePage(event: WolfEvent): WolfPage? {
        if (event.id in erasedEvents) return null
        return event.pages.asReversed().firstOrNull { page ->
            try {
                require(page.conditions.size == 37) { "Conditions de page invalides" }
                (0 until 4).all { index ->
                    val flags = page.conditions[index + 1].toInt() and 255
                    require(flags and 15 in 0..1 && flags ushr 4 in 0..6) { "Mode de condition de page non pris en charge" }
                    flags and 15 == 0 || WolfVariables.compare(
                        variables.read(intAt(page.conditions, 5 + index * 4), EventAddress(mapId, event.id)),
                        intAt(page.conditions, 21 + index * 4), flags ushr 4)
                }
            } catch (failure: Exception) {
                throw WolfRuntimeException(mapId, event.id, -1, -1, page.offset, failure.message ?: "Condition de page invalide")
            }
        }
    }

    private fun refreshActors() {
        map.events.forEach { event ->
            val page = activePage(event)
            if (page == null) { actors.remove(event.id); return@forEach }
            val actor = actors.getOrPut(event.id) { Actor(event.id, event.x.toFloat(), event.y.toFloat(), page.graphicFile) }
            val marker = variables.system[-1_000_000 - event.id]
            if (marker != page.index) {
                variables.system[-1_000_000 - event.id] = page.index
                actor.path = page.graphicFile; actor.direction = page.graphicDirection
                actor.pattern = page.graphicFrame; actor.opacity = page.graphicOpacity
                actor.tileId = page.graphicTile; actor.flags = page.flags
                actor.speed = page.movement.getOrNull(1)?.toInt()?.and(255) ?: 3
                if (page.graphicBlend != 0 || page.flags and 0xe0 != 0 || page.pageTransfer != null || page.features.firstOrNull()?.toInt() != 0)
                    throw WolfRuntimeException(mapId, event.id, -1, -1, page.offset, "Graphique, collision ou fonctionnalité avancée de page non portés")
                val mode = page.movement.getOrNull(3)?.toInt()?.and(255) ?: 0
                if (mode !in 0..1) throw WolfRuntimeException(mapId, event.id, -1, 201, page.offset, "Mouvement autonome $mode non porté")
                actor.route = page.route.takeIf { mode == 1 && it.commands.isNotEmpty() }; actor.routeIndex = 0
                parallel.entries.removeAll { it.key.startsWith("map:$mapEpoch:${event.id}:") }
            }
        }
    }

    private fun execute(execution: Execution) {
        while (execution.stack.isNotEmpty()) {
            val frame = execution.stack.last()
            if (frame.waitHost) return
            if (frame.waitTicks > 0) { frame.waitTicks--; return }
            if (frame.waitMovement) {
                if (player.moveTicks > 0 || player.route != null || actors.values.any { it.moveTicks > 0 || it.route != null }) return
                frame.waitMovement = false
            }
            frame.waitKeyDestination?.let { destination ->
                val value = inputValue(frame.waitKeyFlags)
                if (value == 0) return
                variables.write(destination, value, frame.address); frame.waitKeyDestination = null
            }
            if (dialogOwner === frame) return
            if (frame.pc >= frame.commands.size) {
                finishInvocation(execution); continue
            }
            val command = frame.commands[frame.pc++]
            frame.command = command
            if (--instructionBudget <= 0) fail(frame, command, "Budget de 50 000 instructions dépassé sans attente; boucle potentiellement infinie")
            try {
                capabilityProblem(command)?.let { fail(frame, command, it) }
                if (dispatch(execution, frame, command)) return
            } catch (failure: WolfRuntimeException) { throw failure }
            catch (failure: Exception) { fail(frame, command, failure.message ?: failure.javaClass.simpleName) }
        }
    }

    private fun dispatch(execution: Execution, frame: Invocation, c: WolfCommand): Boolean {
        fun raw(index: Int, default: Int = 0) = c.arguments.getOrElse(index) { default }
        fun value(index: Int, default: Int = 0) = variables.read(raw(index, default), frame.address)
        when (c.opcode) {
            0, 99, 103 -> Unit // Blank, editor checkpoint, ordinary comment have no game state effect.
            101 -> { dialog = NativeDialog(displayText(c.strings.firstOrNull().orEmpty(), frame.address), fontSize = (variables.system[8] ?: 16).toFloat()); dialogOwner = frame; return true }
            102 -> {
                val choices = c.strings.map { displayText(it, frame.address) }
                require(choices.isNotEmpty()) { "Choix sans options" }
                dialog = NativeDialog("", choices, (variables.system[7] ?: 0).coerceIn(choices.indices), fontSize = (variables.system[8] ?: 16).toFloat()); dialogOwner = frame; return true
            }
            105 -> { dialog = null; dialogOwner = null }
            106 -> effects.add(NativeEffect.Diagnostic(text(c.strings.firstOrNull().orEmpty(), frame.address)))
            107 -> Unit // Clear debug-text state; diagnostics remain in the host report.
            111 -> {
                val count = raw(0) and 15
                require(count in 1..15 && c.arguments.size >= 1 + count * 3) { "Conditions numériques incomplètes" }
                val selected = (0 until count).firstOrNull {
                    WolfVariables.compare(value(1 + it * 3), value(2 + it * 3), raw(3 + it * 3))
                }?.plus(1) ?: 0
                frame.branches[c.indent] = selected
                if (selected == 0) skipToBranch(frame, c.indent, 0)
            }
            112 -> {
                val count = raw(0) and 15
                require(count in 1..4 && c.arguments.size >= count + 1 && c.strings.size >= count) { "Conditions de chaîne incomplètes" }
                val mode = raw(0) ushr 4 and 15
                require(mode in 0..1) { "Comparaison de chaîne non prise en charge : $mode" }
                val selected = (0 until count).firstOrNull {
                    val same = variables.readString(raw(it + 1), frame.address) == text(c.strings[it], frame.address)
                    if (mode == 0) same else !same
                }?.plus(1) ?: 0
                frame.branches[c.indent] = selected
                if (selected == 0) skipToBranch(frame, c.indent, 0)
            }
            121 -> setVariable(frame, c)
            122 -> {
                val mode = raw(1)
                val target = raw(0)
                val rhs = if (c.strings.isNotEmpty()) text(c.strings[0], frame.address) else variables.readString(raw(2), frame.address)
                val previous = variables.readString(target, frame.address)
                variables.writeString(target, if (mode and 15 == 1) previous + rhs else rhs, frame.address)
            }
            123 -> {
                val destination = raw(0)
                val flags = raw(1)
                val input = inputValue(flags)
                variables.write(destination, input, frame.address)
            }
            126 -> {
                bannedKeys.clear()
                val bits = raw(0)
                if (bits and 16 != 0) bannedKeys += listOf(NativeKey.UP, NativeKey.DOWN, NativeKey.LEFT, NativeKey.RIGHT)
                if (bits and 32 != 0) bannedKeys += NativeKey.ACCEPT
                if (bits and 64 != 0) bannedKeys += NativeKey.BACK
                if (bits and 128 != 0) bannedKeys += NativeKey.SHIFT
            }
            130 -> teleport(frame, c)
            140 -> sound(frame, c)
            150 -> picture(frame, c)
            170, 176, 179 -> {
                val tail = findLoopEnd(frame.commands, frame.pc - 1)
                val count = if (c.opcode == 179) maxOf(0, value(0)) else -1
                if (count == 0) frame.pc = tail + 1 else frame.loops.addLast(Loop(frame.pc, tail, count))
            }
            171 -> { val loop = frame.loops.pollLast() ?: error("Sortie de boucle sans boucle"); frame.pc = loop.end + 1 }
            172 -> finishInvocation(execution)
            173 -> { if (frame.address.map == mapId) { erasedEvents += frame.address.event; actors.remove(frame.address.event) }; finishInvocation(execution) }
            174 -> { resetToTitle(); return true }
            175 -> { terminated = true; releaseInput(); effects.add(NativeEffect.Quit); return true }
            177 -> freezeWorld = true
            178 -> freezeWorld = false
            180 -> { frame.waitTicks = maxOf(0, value(0)); return true }
            201 -> {
                val actor = actor(value(0), frame.address)
                actor.route = c.route ?: error("Commande de mouvement sans route")
                val header = actor.route!!.header
                if (header.isNotEmpty()) {
                    require(header.size == 5 && header[0].toInt() == 3 && header[2].toInt() == 3 && header[3].toInt() in 0..1 && header[4].toInt() and 0xe0 == 0) { "Fréquence ou collision avancée de route non portée" }
                    actor.speed = header[1].toInt() and 255; actor.flags = header[4].toInt() and 255
                }
                actor.routeIndex = 0; actor.routeWait = 0
                if (actor.route!!.flags and 4 != 0) { frame.waitMovement = true; return true }
            }
            202 -> { frame.waitMovement = true; return true }
            210, 211, 300 -> call(execution, frame, c)
            212 -> Unit // A label is a target, not an executable mutation.
            213 -> {
                val label = c.strings.firstOrNull().orEmpty()
                val target = frame.commands.indexOfFirst { it.opcode == 212 && it.strings.firstOrNull() == label }
                require(target >= 0) { "Étiquette absente : $label" }; frame.pc = target + 1
            }
            220 -> {
                val mode = raw(0); val slot = value(1)
                require(slot in 0..9999) { "Slot de sauvegarde non pris en charge : $slot" }
                if (mode == 0) effects.add(NativeEffect.Save(slot)) else effects.add(NativeEffect.Load(slot))
                frame.waitHost = true; return true
            }
            230 -> allowMovement = true
            231 -> allowMovement = false
            250 -> database(frame, c)
            401, 402 -> { if (frame.branches[c.indent] != raw(0)) skipToBranch(frame, c.indent, frame.branches[c.indent] ?: 0) }
            420, 421 -> { if ((frame.branches[c.indent] ?: 0) != 0) skipToBranch(frame, c.indent, -1) }
            498 -> {
                val loop = frame.loops.lastOrNull() ?: error("Fin de boucle sans début")
                require(loop.end == frame.pc - 1) { "Fin de boucle imbriquée incohérente" }
                if (loop.remaining == -1 || --loop.remaining > 0) frame.pc = loop.start else frame.loops.removeLast()
            }
            499 -> frame.branches.remove(c.indent)
            else -> fail(frame, c, "Commande non implémentée")
        }
        return false
    }

    private fun setVariable(frame: Invocation, c: WolfCommand) {
        val flags = c.args[3]
        val count = if (flags and 0x10000 != 0) maxOf(0, c.args.getOrElse(4) { 0 }) else 0
        require(count <= 100_000) { "Affectation de plage trop grande" }
        var destination = c.args[0]
        if (flags and 16 != 0) destination = variables.read(destination, frame.address)
        fun operand(code: Int, literalFlag: Int, indirectFlag: Int): Int {
            var result = if (flags and literalFlag != 0) code else variables.read(code, frame.address)
            if (flags and indirectFlag != 0) result = variables.read(result, frame.address)
            return result
        }
        val left = operand(c.args[1], 4, 32)
        val right = operand(c.args[2], 8, 64)
        val operation = flags ushr 12 and 15
        val assign = flags ushr 8 and 15
        val floating = flags and 2 != 0
        val rhs = if (floating && operation == 3) left.toDouble() / (if (right == 0) 1 else right)
            else WolfVariables.calculate(left.toLong(), right.toLong(), operation, ::random).toDouble()
        for (index in 0..count) {
            val previous = if (assign in 1..7) variables.read(destination + index, frame.address).toLong() else 0L
            val result = WolfVariables.assign(previous, rhs, assign, right)
            val simpleCommon = frame.address.common >= 0 && flags and 0x00ff == 0 && destination in 1_000_000..2_999_999
            val limit = if (flags and 1 != 0) 999_999.0 else 2_000_000_000.0
            val integer = if (simpleCommon) result.toLong().toInt() else result.coerceIn(-limit, limit).toInt()
            variables.write(destination + index, integer, frame.address)
        }
    }

    private fun call(execution: Execution, frame: Invocation, c: WolfCommand) {
        val target = if (c.opcode == 300) {
            val name = text(c.strings.firstOrNull().orEmpty(), frame.address)
            game.commonEvents.firstOrNull { it.name == name }?.id?.plus(500_000) ?: error("Commun nommé absent : $name")
        } else variables.read(c.args[0], frame.address)
        if (target !in 500_000..599_999) {
            val mapEvent = map.events.firstOrNull { it.id == if (target == -1) frame.address.event else target }
                ?: error("Événement appelé absent : $target")
            val pageIndex = c.args.getOrElse(1) { -1 }
            val page = if (pageIndex == -1) activePage(mapEvent) else mapEvent.pages.getOrNull(pageIndex)
            require(page != null) { "Page appelée absente : $pageIndex" }
            require(execution.stack.size < 256) { "Profondeur d'appel dépassée" }
            execution.stack.addLast(Invocation(page.commands, frame.address.copy(event = mapEvent.id, common = -1)))
            return
        }
        val common = common(target - 500_000)
        val flags = c.args.getOrElse(1) { 0 }
        val numberCount = flags and 15
        val stringCount = flags ushr 4 and 15
        require(numberCount <= 10 && stringCount <= 10 && c.args.size >= 2 + numberCount + stringCount) { "Arguments de commun incomplets" }
        val numbers = (0 until numberCount).map { variables.read(c.args[it + 2], frame.address) }
        val stringArgs = (0 until stringCount).map { index ->
            if (flags and (1 shl (index + 12)) != 0) text(c.strings.getOrElse(index + if (c.opcode == 300) 1 else 0) { "" }, frame.address)
            else variables.readString(c.args[2 + numberCount + index], frame.address)
        }
        if (c.opcode == 211) { reserved.add(CallRequest(common.id, numbers, stringArgs, frame.address)); return }
        val returnTarget = if (flags and 0x01000000 != 0) c.args.last() else null
        require(execution.stack.size < 256) { "Profondeur d'appel dépassée" }
        execution.stack.addLast(invocation(common, frame.address.copy(common = common.id), numbers, stringArgs, returnTarget))
    }

    private fun common(id: Int) = game.commonEvents.firstOrNull { it.id == id } ?: error("Commun absent : $id")

    private fun database(frame: Invocation, c: WolfCommand) {
        val flags = c.args[3]
        val kind = when (flags ushr 8 and 15) {
            0 -> WolfDatabaseKind.MUTABLE; 1 -> WolfDatabaseKind.SYSTEM; 2 -> WolfDatabaseKind.USER
            else -> error("Type de DB non pris en charge")
        }
        val database = game.databases[kind] ?: error("Base de données absente : $kind")
        val type = if (flags and 0x10000 != 0) {
            val name = text(c.strings.getOrElse(1) { "" }, frame.address)
            database.types.firstOrNull { it.name == name } ?: error("Type DB nommé absent : $name")
        } else database.types.getOrNull(variables.read(c.args[0], frame.address)) ?: error("Type DB absent")
        val row = if (flags and 0x20000 != 0) {
            val name = text(c.strings.getOrElse(2) { "" }, frame.address)
            type.rows.indexOfFirst { it.name == name }.also { require(it >= 0) { "Rangée DB nommée absente : $name" } }
        } else variables.read(c.args[1], frame.address)
        val field = if (flags and 0x40000 != 0) {
            val name = text(c.strings.getOrElse(3) { "" }, frame.address)
            type.fields.indexOfFirst { it.name == name }.also { require(it >= 0) { "Champ DB nommé absent : $name" } }
        } else variables.read(c.args[2], frame.address)
        val read = flags and 0x1000 != 0
        val assignment = flags ushr 4 and 15
        val target = c.args.getOrElse(4) { 0 }
        if (row == -1 && read) {
            val result = WolfVariables.assign(variables.read(target, frame.address).toLong(), type.rows.size.toDouble(), assignment).toInt()
            variables.write(target, result, frame.address)
            return
        }
        val descriptor = type.fields.getOrNull(field) ?: error("Champ DB absent : ${type.id}/$field")
        require(row in type.rows.indices) { "Rangée DB absente : ${type.id}/$row" }
        if (descriptor.valueType == WolfValueType.STRING) {
            require(assignment in 0..1) { "Affectation de texte DB non prise en charge" }
            val value = if (read) variables.databaseString(kind, type.id, row, field)
                else if (flags and 2 != 0) text(c.strings.firstOrNull().orEmpty(), frame.address)
                else variables.readString(target, frame.address)
            if (read) variables.writeString(target, (if (assignment == 1) variables.readString(target, frame.address) else "") + value, frame.address)
            else {
                require(kind == WolfDatabaseKind.MUTABLE) { "Écriture interdite sur DB immuable" }
                variables.setDatabaseString(type.id, row, field, (if (assignment == 1) variables.databaseString(kind, type.id, row, field) else "") + value)
            }
        } else {
            val value = if (read) variables.databaseNumber(kind, type.id, row, field) else variables.read(target, frame.address)
            val previous = if (read) variables.read(target, frame.address) else variables.databaseNumber(kind, type.id, row, field)
            val result = WolfVariables.assign(previous.toLong(), value.toDouble(), assignment).coerceIn(-2_000_000_000.0, 2_000_000_000.0).toInt()
            if (read) variables.write(target, result, frame.address)
            else {
                require(kind == WolfDatabaseKind.MUTABLE) { "Écriture interdite sur DB immuable" }
                variables.setDatabaseNumber(type.id, row, field, result)
            }
        }
    }
    private fun invocation(common: WolfCommon, address: EventAddress, numbers: List<Int> = emptyList(), strings: List<String> = emptyList(), returnTarget: Int? = null): Invocation {
        // Common-self state persists by common ID; only the supplied inputs are replaced.
        numbers.forEachIndexed { index, value -> variables.common[common.id to index] = value }
        strings.forEachIndexed { index, value -> variables.commonStrings[common.id to (5 + index)] = value }
        return Invocation(common.commands, address, returnTarget)
    }
    private fun finishInvocation(execution: Execution) {
        val finished = execution.stack.removeLast()
        if (dialogOwner === finished) { dialogOwner = null; dialog = null }
        if (finished.returnDestination != null && execution.stack.isNotEmpty()) {
            val common = common(finished.address.common)
            val result = variables.read(common.returnValue ?: 1_600_000, finished.address)
            variables.write(finished.returnDestination, result, execution.stack.last().address)
        }
    }

    private fun teleport(frame: Invocation, c: WolfCommand) {
        require(c.args.size >= 5) { "Téléportation incomplète" }
        val target = variables.read(c.args[0], frame.address)
        val x = variables.read(c.args[1], frame.address)
        val y = variables.read(c.args[2], frame.address)
        val destination = variables.read(c.args[3], frame.address)
        require(c.args[4] == 32 && target in setOf(-1, -2)) { "Mode de téléportation non pris en charge" }
        if (target == -2) {
            if (destination != mapId) {
                val loaded = mapLoader(destination)
                require(x in 0 until loaded.width && y in 0 until loaded.height) { "Départ hors carte" }
                mapId = destination; map = loaded; mapEpoch++; actors.clear(); erasedEvents.clear()
                parallel.entries.removeAll { it.key.startsWith("map:") }
                variables.system.keys.removeAll { it <= -1_000_000 }
                refreshActors()
            }
            player.x = x.toFloat(); player.y = y.toFloat(); player.targetX = player.x; player.targetY = player.y
            player.moveTicks = 0; targetTile = null
        } else {
            require(destination == mapId) { "Transfert de carte d'un événement non pris en charge" }
            val actor = actor(target, frame.address); actor.x = x.toFloat(); actor.y = y.toFloat()
            actor.targetX = actor.x; actor.targetY = actor.y; actor.moveTicks = 0
        }
    }

    private fun sound(frame: Invocation, c: WolfCommand) {
        val flags = c.args[0]
        val channel = flags ushr 4 and 15
        val stop = flags and 15 == 1 || (c.strings.isEmpty() && c.args.getOrElse(1) { -1 } == 0 && channel == 2)
        val path = if (stop) null else {
            if (flags and 0x02000000 != 0) asset(text(c.strings.firstOrNull().orEmpty(), frame.address))
            else {
                val id = variables.read(c.args.getOrElse(1) { -1 }, frame.address)
                val dbType = when (channel) { 0 -> 1; 1 -> 2; 2 -> 3; else -> error("Canal audio non pris en charge") }
                asset(game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(dbType)?.string(id, 0) ?: error("Son DB absent : $dbType/$id"))
            }
        }
        val request = NativeEffect.Audio(channel, path, variables.read(c.args.getOrElse(4) { 100 }, frame.address),
            variables.read(c.args.getOrElse(5) { 100 }, frame.address), channel != 2,
            variables.read(c.args.getOrElse(3) { 0 }, frame.address), stop)
        if (channel in 0..1) { if (stop) activeAudio.remove(channel) else activeAudio[channel] = request }
        effects.add(request)
    }

    private fun picture(frame: Invocation, c: WolfCommand) {
        fun value(i: Int, default: Int = 0) = variables.read(c.args.getOrElse(i) { default }, frame.address)
        val flags = c.args[0]; val action = flags and 15; val id = value(1)
        val duration = maxOf(0, value(2))
        if (action == 2) {
            val prior = pictures[id]
            if (duration > 0 && prior != null) tweens[id] = PictureTween(prior, prior.copy(opacity = 0), duration, erase = true)
            else { pictures.remove(id); tweens.remove(id) }
        } else {
            val kind = flags ushr 4 and 15
            val prior = pictures[id]
            val content = c.strings.firstOrNull()?.let { text(it, frame.address) }
            val scaleMode = flags ushr 20 and 15
            val sx = if (scaleMode == 2) 1f else value(9, 100) / 100f
            val sy = if (scaleMode == 1) 1f else if (scaleMode == 2) value(9, 100) / 100f else sx
            val picture = NativePicture(id,
                path = if (kind == 0) content?.let(::asset) ?: prior?.path else null,
                text = if (kind == 2) content?.also { textPresentationProblem(it)?.let { issue -> error(issue) } } ?: prior?.text else null,
                x = value(3).toFloat(), y = value(4).toFloat(),
                width = if (kind == 3) value(7).toFloat() else prior?.width ?: 0f,
                height = if (kind == 3) value(8).toFloat() else prior?.height ?: 0f,
                scaleX = sx, scaleY = sy, opacity = value(6, 255).coerceIn(0, 255),
                rotation = value(7).toFloat() / 10, blend = flags ushr 8 and 15,
                z = prior?.z ?: id, color = -1,
                anchor = flags ushr 12 and 15, fontSize = (variables.system[8] ?: 16).toFloat(),
                screenRelative = flags and 0x10000 == 0)
            if (action == 1 && prior == null) error("Déplacement de pic absent : $id")
            if (duration > 0 && prior != null) tweens[id] = PictureTween(prior, picture, duration)
            else { pictures[id] = picture; tweens.remove(id) }
        }
        if (flags and 0x80000 != 0) frame.waitTicks = duration
    }

    private fun updatePictures() {
        val finished = mutableListOf<Int>()
        tweens.forEach { (id, tween) ->
            val progress = (++tween.elapsed).toFloat() / tween.length
            fun lerp(a: Float, b: Float) = a + (b - a) * progress
            pictures[id] = tween.to.copy(x = lerp(tween.from.x, tween.to.x), y = lerp(tween.from.y, tween.to.y),
                scaleX = lerp(tween.from.scaleX, tween.to.scaleX), scaleY = lerp(tween.from.scaleY, tween.to.scaleY),
                opacity = lerp(tween.from.opacity.toFloat(), tween.to.opacity.toFloat()).toInt(),
                rotation = lerp(tween.from.rotation, tween.to.rotation))
            if (tween.elapsed >= tween.length) { if (tween.erase) pictures.remove(id); finished += id }
        }
        finished.forEach { tweens.remove(it) }
    }

    private fun updateDialogInput() {
        val current = dialog ?: return
        if (current.choices.isEmpty()) { if (pressed(NativeKey.ACCEPT) || pressed(NativeKey.BACK)) advanceDialog(); return }
        var selected = current.selectedChoice
        if (pressed(NativeKey.UP)) selected = (selected - 1 + current.choices.size) % current.choices.size
        if (pressed(NativeKey.DOWN)) selected = (selected + 1) % current.choices.size
        dialog = current.copy(selectedChoice = selected)
        if (pressed(NativeKey.ACCEPT)) choose(selected)
        if (pressed(NativeKey.BACK)) {
            val owner = dialogOwner ?: return
            val original = owner.command ?: return
            if (original.args.getOrElse(0) { 0 } and 16 != 0) { owner.branches[original.indent] = 0; dialog = null; dialogOwner = null }
        }
    }
    private fun advanceDialog() { dialog = null; dialogOwner = null }

    private fun updateWorld() {
        (listOf(player) + actors.values).forEach { actor ->
            if (actor.moveTicks > 0) {
                actor.x += (actor.targetX - actor.x) / actor.moveTicks
                actor.y += (actor.targetY - actor.y) / actor.moveTicks
                actor.moveTicks--; actor.moving = true
                if (actor.moveTicks == 0) { actor.x = actor.targetX; actor.y = actor.targetY; if (actor.id == -2) triggerTouch() }
            } else { actor.moving = false; updateRoute(actor) }
        }
        if (dialog != null || player.moveTicks > 0 || (foreground != null && !allowMovement)) return
        val dx = if (down(NativeKey.LEFT)) -1 else if (down(NativeKey.RIGHT)) 1 else 0
        val dy = if (game.config.characterDirectionsMove == 4 && dx != 0) 0 else if (down(NativeKey.UP)) -1 else if (down(NativeKey.DOWN)) 1 else 0
        if (dx != 0 || dy != 0) { targetTile = null; move(player, dx, dy) }
        else targetTile?.let { target ->
            val path = nextPathStep(player.x.toInt(), player.y.toInt(), target.first, target.second)
            if (path == null) targetTile = null else move(player, path.first, path.second)
        }
        if (pressed(NativeKey.ACCEPT)) triggerAction()
    }

    private fun nextPathStep(x: Int, y: Int, targetX: Int, targetY: Int): Pair<Int, Int>? {
        if (x == targetX && y == targetY || targetX !in 0 until map.width || targetY !in 0 until map.height) return null
        // Bounded BFS follows the same collision test as keys and routes; it cannot tunnel through events.
        val queue = ArrayDeque<Pair<Int, Int>>()
        val previous = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        queue.add(x to y); previous[x to y] = x to y
        while (queue.isNotEmpty() && previous.size < 4096) {
            val point = queue.removeFirst()
            if (point.first == targetX && point.second == targetY) {
                var step = point
                while (previous[step] != (x to y)) step = previous[step] ?: return null
                return step.first - x to step.second - y
            }
            listOf(0 to 1, -1 to 0, 1 to 0, 0 to -1).forEach { delta ->
                val next = point.first + delta.first to point.second + delta.second
                if (next !in previous && passable(point.first, point.second, delta.first, delta.second, player)) { previous[next] = point; queue.add(next) }
            }
        }
        return null
    }

    private fun move(actor: Actor, dx: Int, dy: Int, force: Boolean = false): Boolean {
        if (actor.flags and 4 == 0) actor.direction = direction(dx, dy)
        if (!force && !passable(actor.x.toInt(), actor.y.toInt(), dx, dy, actor)) {
            if (actor.id == -2) triggerTouch(actor.x.toInt() + dx, actor.y.toInt() + dy)
            return false
        }
        actor.targetX = actor.x + dx; actor.targetY = actor.y + dy
        actor.moveTicks = maxOf(1, fps / (4 + actor.speed * 2))
        actor.moving = true
        return true
    }

    private fun passable(x: Int, y: Int, dx: Int, dy: Int, actor: Actor): Boolean {
        if (dx != 0 && dy != 0) return passable(x, y, dx, 0, actor) && passable(x, y, 0, dy, actor) && passable(x + dx, y, 0, dy, actor) && passable(x, y + dy, dx, 0, actor)
        val nx = x + dx; val ny = y + dy
        if (nx !in 0 until map.width || ny !in 0 until map.height) return false
        if (actor.flags and 8 != 0) return true
        if (actors.values.any { it.id != actor.id && it.flags and 8 == 0 && it.x.toInt() == nx && it.y.toInt() == ny && (it.path.isNotBlank() || it.tileId >= 0) }) return false
        if (actor.id != -2 && player.x.toInt() == nx && player.y.toInt() == ny) return false
        val tileset = game.tilesets.getOrNull(map.tilesetId) ?: return false
        val outMask = when { dy > 0 -> 1; dx < 0 -> 2; dx > 0 -> 4; else -> 8 }
        val inMask = when { dy > 0 -> 8; dx < 0 -> 4; dx > 0 -> 2; else -> 1 }
        fun open(tx: Int, ty: Int, mask: Int): Boolean {
            for (layer in 0 until map.layerCount) {
                val raw = map.tile(tx, ty, layer)
                if (raw <= 0) continue
                val flags = tileset.passageForTile(raw)
                require(flags and 0x1e0 == 0) { "Collision par quart, eau, comptoir ou triangle non portée" }
                // Passage bits 0..3 are down/left/right/up; bit4 is star (draw above, not a barrier).
                if (flags and (16 or 0x200) != 0) continue
                if (flags and mask != 0) return false
                return true
            }
            return true
        }
        return open(x, y, outMask) && open(nx, ny, inMask)
    }

    private fun triggerAction() {
        if (foreground != null) return
        val d = delta(player.direction)
        val x = player.x.toInt() + d.first; val y = player.y.toInt() + d.second
        triggerAt(x, y, setOf(0))
    }
    private fun triggerTouch(x: Int = player.x.toInt(), y: Int = player.y.toInt()) { if (foreground == null) triggerAt(x, y, setOf(3, 4)) }
    private fun triggerAt(x: Int, y: Int, triggers: Set<Int>) {
        map.events.sortedBy { it.id }.firstOrNull { event ->
            val a = actors[event.id] ?: return@firstOrNull false
            val page = activePage(event) ?: return@firstOrNull false
            page.trigger in triggers && abs(a.x.toInt() - x) <= (page.features.getOrNull(1)?.toInt()?.and(255) ?: 0) &&
                abs(a.y.toInt() - y) <= (page.features.getOrNull(2)?.toInt()?.and(255) ?: 0)
        }?.let { event ->
            val page = activePage(event)!!
            val actor = actors[event.id]!!
            if (actor.flags and 4 == 0) actor.direction = direction(player.x.toInt() - actor.x.toInt(), player.y.toInt() - actor.y.toInt())
            foreground = Execution("touch:${event.id}", false, Invocation(page.commands, EventAddress(mapId, event.id)))
        }
    }

    private fun updateRoute(actor: Actor) {
        val route = actor.route ?: return
        if (actor.routeWait > 0) { actor.routeWait--; return }
        if (actor.routeIndex >= route.commands.size) {
            if (route.flags and 1 != 0) actor.routeIndex = 0 else { actor.route = null; return }
        }
        val command = route.commands.getOrNull(actor.routeIndex) ?: return
        val address = EventAddress(mapId, if (actor.id == -2) -1 else actor.id)
        fun value(index: Int, default: Int = 0) = variables.read(command.args.getOrElse(index) { default }, address)
        var done = true
        when (command.opcode) {
            in 0..7 -> { val directions = listOf(0 to 1, -1 to 0, 1 to 0, 0 to -1, -1 to 1, 1 to 1, -1 to -1, 1 to -1); val d = directions[command.opcode]; done = move(actor, d.first, d.second) }
            in 8..15 -> actor.direction = listOf(2, 4, 6, 8, 1, 3, 7, 9)[command.opcode - 8]
            16 -> { val d = delta(listOf(2, 4, 6, 8)[random(0, 3)]); done = move(actor, d.first, d.second) }
            17, 18 -> { var dx = player.x.compareTo(actor.x); var dy = player.y.compareTo(actor.y); if (command.opcode == 18) { dx = -dx; dy = -dy }; done = if (abs(player.x - actor.x) > abs(player.y - actor.y)) move(actor, dx, 0) else move(actor, 0, dy) }
            19, 20 -> { val d = delta(actor.direction); val factor = if (command.opcode == 19) 1 else -1; done = move(actor, d.first * factor, d.second * factor) }
            21 -> { actor.x += value(0); actor.y += value(1); actor.targetX = actor.x; actor.targetY = actor.y }
            22, 23 -> { val order = if (game.config.characterDirectionsMove == 8) listOf(2, 1, 4, 7, 8, 9, 6, 3) else listOf(2, 4, 8, 6); val i = order.indexOf(actor.direction).coerceAtLeast(0); actor.direction = order[(i + if (command.opcode == 22) 1 else order.size - 1) % order.size] }
            25 -> { val directions = if (game.config.characterDirectionsMove == 8) listOf(2, 4, 6, 8, 1, 3, 7, 9) else listOf(2, 4, 6, 8); actor.direction = directions[random(0, directions.size - 1)] }
            26, 27 -> { val factor = if (command.opcode == 26) 1 else -1; actor.direction = direction((player.x - actor.x).toInt() * factor, (player.y - actor.y).toInt() * factor) }
            28 -> variables.write(command.args[0], value(1), address)
            29 -> actor.speed = value(0).coerceIn(0, 6)
            32 -> actor.flags = actor.flags or 1
            33 -> actor.flags = actor.flags and 1.inv()
            34 -> actor.flags = actor.flags or 2
            35 -> actor.flags = actor.flags and 2.inv()
            36 -> actor.flags = actor.flags or 4
            37 -> actor.flags = actor.flags and 4.inv()
            38 -> actor.flags = actor.flags or 8
            39 -> actor.flags = actor.flags and 8.inv()
            40 -> actor.flags = actor.flags or 16
            41 -> actor.flags = actor.flags and 16.inv()
            44 -> actor.path = game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(8)?.string(value(0), 0) ?: error("Graphique de route absent")
            45 -> actor.opacity = value(0).coerceIn(0, 255)
            47 -> actor.routeWait = maxOf(0, value(0))
            50, 51, 52, 56, 57 -> actor.pattern = when (command.opcode) { 50 -> 0; 51 -> 1; 52 -> 2; 56 -> 3; else -> 4 }
            55 -> variables.write(command.args[0], variables.read(command.args[0], address) + value(1), address)
            else -> throw WolfRuntimeException(mapId, actor.id, -1, 201, 0, "Route ${command.opcode} non prise en charge")
        }
        if (done || route.flags and 2 != 0) actor.routeIndex++
    }

    private fun actor(id: Int, address: EventAddress): Actor = when (id) {
        -2 -> player; -1 -> actors[address.event] ?: error("Événement courant absent : ${address.event}"); else -> actors[id] ?: error("Personnage absent : $id")
    }
    private fun cameraX() = ((player.x + .5f) * game.config.tileSize - game.config.width / 2f).coerceIn(0f, maxOf(0, map.width * game.config.tileSize - game.config.width).toFloat())
    private fun cameraY() = ((player.y + .5f) * game.config.tileSize - game.config.height / 2f).coerceIn(0f, maxOf(0, map.height * game.config.tileSize - game.config.height).toFloat())
    private fun pressed(key: NativeKey) = key in newKeys && key !in bannedKeys
    private fun down(key: NativeKey) = key in keys && key !in bannedKeys
    private fun inputValue(flags: Int): Int {
        val state = if (flags and 128 != 0) keys else newKeys
        fun held(key: NativeKey) = key in state && key !in bannedKeys
        if (flags and 16 != 0) {
            val dx = if (held(NativeKey.LEFT)) -1 else if (held(NativeKey.RIGHT)) 1 else 0
            val dy = if (held(NativeKey.UP)) -1 else if (held(NativeKey.DOWN)) 1 else 0
            if (dx != 0 || dy != 0) return direction(dx, dy)
        }
        if (flags and 32 != 0 && held(NativeKey.ACCEPT)) return 10
        if (flags and 64 != 0 && held(NativeKey.BACK)) return 11
        if (flags and 256 != 0 && held(NativeKey.SHIFT)) return 12
        return 0
    }

    private fun systemValue(code: Int, address: EventAddress): Int? = when (code) {
        12 -> if (dialog != null) 1 else 0
        13 -> if (foreground != null) 1 else 0
        24 -> variables.system[24] ?: 0
        71 -> pointerX.toInt(); 72 -> pointerY.toInt()
        in 9_100_000..9_199_999 -> {
            val relative = code - 9_100_000
            val event = relative / 10
            val actor = when (event) { 8000 -> player; 9000 -> actors[address.event]; else -> actors[event] }
                ?: return -1
            when (relative % 10) { 0 -> actor.x.toInt(); 1 -> actor.y.toInt(); 2 -> (actor.x * 2).toInt(); 3 -> (actor.y * 2).toInt(); 6 -> actor.direction; else -> null }
        }
        else -> null
    }

    private fun text(input: String, address: EventAddress): String {
        var result = input.replace("\r\n", "\n").replace("\r", "\n")
        result = Regex("\\\\cself\\[([0-9]+)]", RegexOption.IGNORE_CASE).replace(result) {
            val id = it.groupValues[1].toInt()
            variables.commonStrings[address.common to id] ?: (variables.common[address.common to id] ?: 0).toString()
        }
        result = Regex("\\\\self\\[([0-9]+)]", RegexOption.IGNORE_CASE).replace(result) { variables.read(1_100_000 + it.groupValues[1].toInt(), address).toString() }
        result = Regex("\\\\v\\[([0-9]+)]", RegexOption.IGNORE_CASE).replace(result) { val id = it.groupValues[1].toInt(); variables.read(if (id >= 1_000_000) id else 2_000_000 + id, address).toString() }
        result = Regex("\\\\s\\[([0-9]+)]", RegexOption.IGNORE_CASE).replace(result) { variables.readString(3_000_000 + it.groupValues[1].toInt(), address) }
        return result
    }

    private fun displayText(input: String, address: EventAddress): String = text(input, address).also {
        textPresentationProblem(it)?.let { issue -> error(issue) }
    }

    private fun asset(path: String): String {
        if (path.isEmpty()) return ""
        return WolfParser.dataPath(path)
    }
    private fun random(low: Int, high: Int): Int {
        randomState = randomState xor (randomState shl 13); randomState = randomState xor (randomState ushr 17); randomState = randomState xor (randomState shl 5)
        val width = high.toLong() - low + 1
        return (low + (randomState.toLong() and 0xffffffffL) % width).toInt()
    }
    private fun resetToTitle() {
        activeAudio.clear(); (0..2).forEach { effects.add(NativeEffect.Audio(it, stop = true)) }
        variables.clear(); pictures.clear(); tweens.clear(); parallel.clear(); foreground = null; bannedKeys.clear()
        reserved.clear(); erasedEvents.clear(); actors.clear(); dialog = null; dialogOwner = null
        mapId = game.start.mapId; map = mapLoader(mapId); mapEpoch++
        player.x = game.start.x.toFloat(); player.y = game.start.y.toFloat(); player.targetX = player.x; player.targetY = player.y
        player.moveTicks = 0; player.route = null; player.path = game.config.defaultHeroGraphic
        allowMovement = false; freezeWorld = false; releaseInput(); refreshActors()
    }
    private fun skipToBranch(frame: Invocation, depth: Int, desired: Int) {
        var index = frame.pc
        while (index < frame.commands.size) {
            val c = frame.commands[index]
            if (c.indent < depth) error("Branche non terminée")
            if (c.indent == depth) {
                if (c.opcode == 499) { frame.pc = index; return }
                if (desired == 0 && c.opcode in setOf(420, 421)) { frame.pc = index; return }
                if (desired > 0 && c.opcode in setOf(401, 402) && c.args.firstOrNull() == desired) { frame.pc = index; return }
            }
            index++
        }
        error("Branche sans fin")
    }
    private fun findLoopEnd(commands: List<WolfCommand>, start: Int): Int {
        val depth = commands[start].indent
        return (start + 1 until commands.size).firstOrNull { commands[it].opcode == 498 && commands[it].indent == depth } ?: error("Boucle sans fin")
    }
    private fun fail(frame: Invocation, command: WolfCommand, reason: String): Nothing = throw WolfRuntimeException(frame.address.map, frame.address.event, frame.address.common, command.opcode, command.offset, reason)

    /** Native save codec. It deliberately does not decode or replace Windows .sav files. */
    fun snapshot(): ByteArray {
        require(tweens.isEmpty() && player.route == null && actors.values.none { it.route != null } && activeAudio.isEmpty()) { "Sauvegarde pendant une route, une animation de pic ou un son continu non portée ; réessayez à l'arrêt" }
        return WolfSnapshot.encode(snapshotState())
    }
    fun restore(bytes: ByteArray) {
        val state = WolfSnapshot.decode(bytes, game.config.title)
        restoreState(state)
    }

    internal fun snapshotState(): WolfSnapshot.State = WolfSnapshot.State(game.config.title, ticks, mapId, player.x, player.y, player.direction, player.path, randomState,
        variables.numbers.toMap(), variables.strings.toMap(), variables.self.toMap(), variables.common.toMap(), variables.commonStrings.toMap(), variables.system.toMap(), variables.systemStrings.toMap(), variables.mutableNumbers.toMap(), variables.mutableStrings.toMap(),
        characterState(player), actors.values.map(::characterState), erasedEvents.toSet(), pictures.values.toList(), allowMovement, freezeWorld, bannedKeys.toSet())
    private fun characterState(a: Actor) = WolfSnapshot.CharacterState(a.id, a.x, a.y, a.path, a.direction, a.pattern, a.opacity, a.flags, a.speed, a.tileId, a.targetX, a.targetY, a.moveTicks)
    private fun restoreCharacter(a: Actor, s: WolfSnapshot.CharacterState) {
        a.x = s.x; a.y = s.y; a.path = s.path; a.direction = s.direction; a.pattern = s.pattern; a.opacity = s.opacity
        a.flags = s.flags; a.speed = s.speed; a.tileId = s.tileId; a.targetX = s.targetX; a.targetY = s.targetY; a.moveTicks = s.moveTicks
        a.route = null; a.routeIndex = 0; a.routeWait = 0; a.moving = s.moveTicks > 0
    }
    internal fun restoreState(state: WolfSnapshot.State) {
        val loaded = mapLoader(state.mapId)
        require(state.x >= 0 && state.y >= 0 && state.x < loaded.width && state.y < loaded.height) { "Sauvegarde hors carte" }
        (state.actors + listOfNotNull(state.hero)).forEach { c ->
            require(c.x >= 0 && c.y >= 0 && c.x < loaded.width && c.y < loaded.height && c.targetX >= 0 && c.targetY >= 0 && c.targetX < loaded.width && c.targetY < loaded.height) { "Personnage de sauvegarde hors carte" }
            require(c.id == -2 || loaded.events.any { it.id == c.id }) { "Événement de sauvegarde absent" }
            asset(c.path)
        }
        require(state.hero == null || state.hero.id == -2) { "Héros de sauvegarde invalide" }
        state.pictures.forEach { p -> p.path?.let(::asset); p.text?.let { require(textPresentationProblem(it) == null) { "Code d'image de sauvegarde invalide" } } }
        require(state.erasedEvents.all { id -> loaded.events.any { it.id == id } }) { "Événement effacé de sauvegarde absent" }
        require(state.system.keys.all { it <= -1_000_000 || it in WolfVariables.SUPPORTED_SYSTEM_READS }) { "Champ système de sauvegarde non pris en charge" }
        resetToTitle(); mapId = state.mapId; map = loaded; mapEpoch++; actors.clear()
        ticks = state.ticks; player.x = state.x; player.y = state.y; player.targetX = state.x; player.targetY = state.y
        player.direction = state.direction; player.path = state.path; randomState = state.randomState
        variables.numbers.putAll(state.numbers); variables.strings.putAll(state.strings); variables.self.putAll(state.self)
        variables.common.putAll(state.common); variables.commonStrings.putAll(state.commonStrings)
        variables.system.putAll(state.system.filterKeys { it > -1_000_000 }); variables.systemStrings.putAll(state.systemStrings)
        variables.mutableNumbers.putAll(state.mutableNumbers); variables.mutableStrings.putAll(state.mutableStrings)
        // Wolf resumes without the event's in-progress stack, waits or dialog.
        parallel.clear(); foreground = null; dialog = null; dialogOwner = null; failed = null; terminated = false
        erasedEvents.addAll(state.erasedEvents); refreshActors()
        state.hero?.let { restoreCharacter(player, it) }
        state.actors.forEach { saved -> actors[saved.id]?.let { restoreCharacter(it, saved) } }
        pictures.putAll(state.pictures.associateBy { it.id }); allowMovement = state.allowMovement; freezeWorld = state.freezeWorld
        bannedKeys.clear(); bannedKeys.addAll(state.bannedKeys); releaseInput()
    }

    companion object {
        fun load(source: WolfAssetSource, progress: (String) -> Unit = {}): WolfRuntime {
            progress("Lecture des données Wolf")
            val parser = WolfParser(source)
            val game = parser.loadGame()
            progress("Lecture de la carte initiale")
            return WolfRuntime(source, game, parser::loadMap).also { progress("Moteur natif prêt") }
        }
        /** Allows deterministic, authored fixtures without pretending they prove arbitrary game compatibility. */
        fun fromData(source: WolfAssetSource, game: WolfGameData, mapLoader: (Int) -> WolfMap): WolfRuntime = WolfRuntime(source, game, mapLoader)

        private val SUPPORTED = setOf(0, 99, 101, 102, 103, 105, 106, 107, 111, 112, 121, 122, 123, 126, 130, 140, 150,
            170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 201, 202, 210, 211, 212, 213, 220, 230, 231, 250, 300, 401, 402, 420, 421, 498, 499)
        private val SUPPORTED_ROUTE = (0..20).toSet() + setOf(22, 23, 25, 26, 27, 28, 29, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 44, 45, 47, 50, 51, 52, 55, 56, 57)
        private fun textPresentationProblem(text: String): String? = WolfTextCodes.presentationProblem(text)
        fun capabilityProblem(command: WolfCommand): String? {
            if (command.opcode !in SUPPORTED) return "Commande Wolf non implémentée dans le moteur natif"
            if (command.extension.isNotEmpty()) return "Extension de commande non interprétée"
            val a = command.arguments
            return when (command.opcode) {
                103 -> if (command.strings.any { it.startsWith("<<") && it.contains("PerformanceMonitor") }) "Directive de commentaire spéciale" else null
                121 -> if (a.size < 4) "Affectation incomplète" else if (a[3] and 0xfffe0000.toInt() != 0 || a[3] and 0x80 != 0 || (a[3] ushr 12 and 15) > 6 || (a[3] ushr 8 and 15) > 12) "Mode d'affectation non pris en charge" else null
                122 -> if (a.size < 2 || a[1] !in 0..1) "Mode de chaîne non pris en charge" else null
                123 -> if (a.size < 2 || a[1] and 0xffffff8f.toInt() != 0) "Mode d'entrée non pris en charge : attente/lecteur souris ou clavier non porté" else null
                126 -> if (a.size != 1 || a[0] and 0xffffff0f.toInt() != 0) "Mode d'interdiction de touches non pris en charge" else null
                130 -> if (a.size != 5 || a[4] != 32) "Mode de téléportation non pris en charge" else null
                140 -> if (a.isEmpty() || a[0] and 0xfdffffc0.toInt() != 0 || (a[0] and 15) > 1) "Mode audio non pris en charge" else null
                150 -> when {
                    a.size < 3 || a[0] !in setOf(0, 2, 32) -> "Mode de pic non pris en charge (mouvement, fenêtre, ancrage, mélange ou option avancée)"
                    a[0] == 2 && a.size != 3 -> "Effacement de pic avec options non portées"
                    a[0] != 2 && (a.size !in 10..11 || a[7] != 0 || a[8] != 0 || a.getOrElse(10) { 0 } != 0 || command.strings.size != 1) -> "Paramètre de pic non porté (rotation, motif, teinte RGB ou police)"
                    a[1] < 0 -> "Pic sous la carte non porté"
                    else -> null
                }
                201 -> if (command.route == null || command.route.commands.any { it.opcode !in SUPPORTED_ROUTE }) "Mode de route non pris en charge" else null
                220 -> if (a.size < 2 || a[0] !in 0..1) "Opération de sauvegarde partielle non prise en charge" else null
                250 -> if (a.size < 4 || a[3] and 0xfff8ec0d.toInt() != 0 || (a[3] ushr 4 and 15) > 8 || (a[3] ushr 8 and 15) > 2) "Mode DB non pris en charge" else null
                else -> null
            }
        }
        private fun intAt(bytes: ByteArray, index: Int): Int = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8) or ((bytes[index + 2].toInt() and 255) shl 16) or (bytes[index + 3].toInt() shl 24)
        private fun direction(dx: Int, dy: Int): Int = when { dy > 0 -> if (dx < 0) 1 else if (dx > 0) 3 else 2; dy < 0 -> if (dx < 0) 7 else if (dx > 0) 9 else 8; dx < 0 -> 4; dx > 0 -> 6; else -> 2 }
        private fun delta(direction: Int): Pair<Int, Int> = when (direction) { 1 -> -1 to 1; 2 -> 0 to 1; 3 -> 1 to 1; 4 -> -1 to 0; 6 -> 1 to 0; 7 -> -1 to -1; 8 -> 0 to -1; 9 -> 1 to -1; else -> 0 to 0 }
    }
}
