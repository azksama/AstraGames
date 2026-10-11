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
    /** Host completions resume this instruction frame instead of scheduling another logical tick. */
    val hasPendingTick: Boolean get() = tickExecutions != null
    var ticks: Long = 0L; private set
    var mapId: Int = game.start.mapId; private set
    private var map = mapLoader(mapId)
    private var mapChanges = WolfMapChanges(map, game.tilesets)
    private var mapEpoch = 0
    private var terminated = false
    private var failed: WolfRuntimeException? = null
    private data class IsolatedSaveCache(val bytes: ByteArray, val state: WolfSnapshot.State)
    private var isolatedSaveCache: IsolatedSaveCache? = null
    private val effects = ArrayDeque<NativeEffect>()
    private val activeAudio = linkedMapOf<Pair<Int, Int>, NativeEffect.Audio>()
    private val bgsVolumes = linkedMapOf<Int, Int>()
    private val bgsPitches = linkedMapOf<Int, Int>()
    private var mapAudioPending = true
    private val keys = linkedSetOf<NativeKey>()
    private val newKeys = linkedSetOf<NativeKey>()
    private val releasedKeys = linkedSetOf<NativeKey>()
    // Host I/O suspends event execution after this frame's input update. Edges arriving
    // during that continuation belong to the next frame, even for a press+release pulse.
    private val queuedKeyPresses = linkedSetOf<NativeKey>()
    private val queuedKeyReleases = linkedSetOf<NativeKey>()
    private val queuedPhysicalPresses = linkedSetOf<Int>()
    private val queuedPadPresses = linkedSetOf<Int>()
    private var queuedPointerPress = false
    private var queuedPointerRelease = false
    private val keyRepeats = WolfKeyRepeats()
    private val physicalKeys = linkedSetOf<Int>()
    private val newPhysicalKeys = linkedSetOf<Int>()
    private val physicalPadButtons = linkedSetOf<Int>()
    private val newPhysicalPadButtons = linkedSetOf<Int>()
    private val nextAutomaticScanKeys = linkedSetOf<Int>()
    private val automaticScanKeys = linkedSetOf<Int>()
    private val bannedKeys = linkedSetOf<NativeKey>()
    private val movementBannedKeys = linkedSetOf<NativeKey>()
    private val nextAutomaticKeys = linkedSetOf<NativeKey>()
    private val nextAutomaticRawKeys = linkedSetOf<NativeKey>()
    private val automaticRawKeys = linkedSetOf<NativeKey>()
    private var mouseLeftBanned = false
    private var nextAutomaticPointer: Triple<Float, Float, Boolean>? = null
    private val pictures = linkedMapOf<Int, NativePicture>()
    private var nextVideoToken = 1L
    private val tweens = linkedMapOf<Int, PictureTween>()
    private val delayedPictures = mutableListOf<DelayedPicture>()
    private val erasedEvents = linkedSetOf<Int>()
    private val parallel = linkedMapOf<String, Execution>()
    private var foreground: Execution? = null
    private val reserved = ArrayDeque<CallRequest>()
    private var dialog: NativeDialog? = null
    private var dialogOwner: Invocation? = null
    private var allowMovement = false
    private var freezeWorld = false
    private var frozenFrame: NativeFrame? = null
    private var frozenMapFrame: NativeMapScene? = null
    private var transitionMode = -1
    private var transitionDuration = 10
    private var transitionWait = false
    private var transitionElapsed = 0
    private var transitionPrevious: NativeFrame? = null
    private var pointerX = -1f
    private var pointerY = -1f
    private var pointerPressed = false
    private var pointerNewPress = false
    private var pointerNewRelease = false
    private var targetTile: Pair<Int, Int>? = null
    private var randomState = 0x13579bdf
    private var timerFrameOrigin = 0L
    private var timerSecondOrigin = 0L
    private var instructionBudget = 50_000
    private var tickExecutions: List<Execution>? = null
    private var tickExecutionIndex = 0
    private var nextHostRequestId = 1L
    private val pendingSaveValues = linkedMapOf<Long, PendingSaveValue>()
    private data class PendingSaveValue(val invocation: Invocation, val destination: Int?, val stringValue: Boolean, val sourceVariableCode: Int)
    private val pendingPictureMeasures = linkedMapOf<Long, PendingPictureMeasure>()
    // Geometry and the reveal clock do not change the fully rasterized text's dimensions.
    private sealed interface PictureMeasureKey
    private data class TextMeasureKey(val text: String, val size: Float, val color: Int, val rubySize: Float,
        val shadowOffset: Int, val fonts: Map<Int, String>, val convertSpecial: Boolean, val fontWeight: Int) : PictureMeasureKey
    private data class BitmapMeasureKey(val picture: NativePicture, val fontWeight: Int) : PictureMeasureKey
    private val pictureMeasures = linkedMapOf<PictureMeasureKey, Pair<Int, Int>>()
    private data class TextProgramKey(val id: Int, val text: String, val encoding: WolfEncoding,
        val compatibilityVersion: Int, val convertSpecial: Boolean)
    private val textPicturePrograms = linkedMapOf<TextProgramKey, WolfTextPictureTiming.Program>()
    private val imageMeasures = linkedMapOf<String, Pair<Int, Int>>()
    private data class PendingPictureMeasure(val invocation: Invocation, val destination: Int, val flags: Int,
        val picture: NativePicture, val field: Int, val fontWeight: Int, val positionQuery: PicturePositionQuery? = null)
    private data class PicturePositionQuery(val invocation: Invocation, val destination: Int, val flags: Int,
        val x: Int, val y: Int, val maxZ: Boolean, val candidates: List<NativePicture>, var at: Int = 0,
        var selected: Int = -2_000_000_000, var selectedZ: Int = -Int.MAX_VALUE)
    private data class PendingFontOverride(val invocation: Invocation?, val index: Int, val name: String)
    private val pendingFontOverrides = linkedMapOf<Long, PendingFontOverride>()
    private var dispatchInvocation: Invocation? = null
    private var nextLaunchScale = 0
    private val pendingTextInputs = linkedMapOf<Long, PendingTextInput>()
    private val pendingSpeechVoices = linkedMapOf<Long, Pair<Invocation, Int>>()
    private val pendingStringFiles = linkedMapOf<Long, Pair<Invocation, Int?>>()
    private val pendingDownloads = linkedMapOf<Long, Pair<Invocation, WolfDownloadRequest>>()
    private var lastDownloadRequest = 0L
    private data class PendingDatabaseFile(val invocation: Invocation, val kind: WolfDatabaseKind, val type: Int,
        val start: Int, val count: Int, val writing: Boolean)
    private val pendingDatabaseFiles = linkedMapOf<Long, PendingDatabaseFile>()
    private var activeSpeechId: Long? = null
    private var activeSpeechPlaying = false
    private val pendingMaskImages = linkedMapOf<Long, Pair<Invocation, NativeEffect.MaskingImage>>()
    private data class PendingTextInput(val invocation: Invocation, val destination: Int, val maxLength: Int, val cancellable: Boolean, var confirmationRequested: Boolean = false)
    private val player = Actor(-2, game.start.x.toFloat(), game.start.y.toFloat(), game.config.defaultHeroGraphic,
        pattern = initialCharacterPattern(), flags = initialCharacterFlags())
    private val companions = (0 until 5).map { Actor(-3 - it, game.start.x.toFloat(), game.start.y.toFloat(), "",
        pattern = initialCharacterPattern(), flags = initialCharacterFlags()) }
    private var partyFollow = true
    private var partySync = false
    private var rememberedParty = emptyList<String>()
    private val partyTrail = ArrayDeque<Triple<Float, Float, Int>>()
    private val actors = linkedMapOf<Int, Actor>()
    private var macroTileGrid: Pair<WolfMap, WolfPathfinding.Grid>? = null
    private val variables = WolfVariables(game.databases, ::systemValue, ::systemString, { random(0, it) }, ::systemWrite,
        ::systemStringWrite, game.config.engineCompatibilityVersion ?: 0)
    private var screenOpacity = 255
    private var screenColor = 0
    private val screenEffects = WolfScreenEffects()
    private val pictureEffects = WolfPictureEffects(if ((game.config.engineCompatibilityVersion ?: 0) >= 350) WolfPictureEffectProfile.WOLF3 else WolfPictureEffectProfile.WOLF2)
    private val extendedPictureEffects = WolfExtendedPictureEffects()
    private val visibilityMask = WolfVisibilityMask(game.config.width, game.config.height)
    private val maskedPictures = linkedSetOf<Int>()
    private val maskedCharacters = linkedSetOf<Int>()
    private var mapMasked = false
    private val mapShake = WolfMapShake()
    private val mapCamera = WolfMapCamera().apply { reset(centredCameraX(), centredCameraY(), cameraMaxX(), cameraMaxY(), cameraLoopX(), cameraLoopY()) }
    private val characterEffects = WolfCharacterEffects(if ((game.config.engineCompatibilityVersion ?: 0) >= 350) WolfCharacterEffectProfile.WOLF3 else WolfCharacterEffectProfile.WOLF2)
    private var mapZoom = 1f
    private var mapZoomCenterX = 0f
    private var mapZoomCenterY = 0f
    private var mapZoomTween: MapZoomTween? = null
    private val mapTextures = WolfMapTextureController(source, game.config.engineCompatibilityVersion ?: 0,
        variables.system, variables.systemStrings, game.databases,
        { type, row, field -> variables.databaseNumber(WolfDatabaseKind.SYSTEM, type, row, field) },
        { type, row, field -> variables.databaseString(WolfDatabaseKind.SYSTEM, type, row, field) },
        { notice -> effects.add(NativeEffect.Diagnostic(notice)) })
    private var choiceFinishedTick = -1L
    private var choiceFinishedValue = -1
    /** The 104 command is a deferred request consumed by the next choice update (973b50). */
    private var forceChoiceFinish = false
    private var dialogText: WolfTextProgram? = null

    private data class Actor(
        val id: Int, var x: Float, var y: Float, var path: String,
        var direction: Int = 2, var pattern: Int = 1, var opacity: Int = 255,
        var flags: Int = 3, var speed: Int = 3, var tileId: Int = -1,
        var targetX: Float = x, var targetY: Float = y, var moveTicks: Int = 0,
        var route: WolfRoute? = null, var routeIndex: Int = 0, var routeWait: Int = 0,
        var moving: Boolean = false,
        var shadow: Int = 0, var blend: Int = 0,
        var stepSize: Int = 0, var elevation: Int = 0,
        var jumpPeak: Int = 0, var jumpRemaining: Int = 0,
        var routeForced: Boolean = false, var savedRoute: WolfRoute? = null, var savedRouteIndex: Int = 0,
        var routeCommon: Int = -1,
        var navigation: Navigation? = null,
        var animation: WolfCharacterAnimation? = null,
        var pixelMotion: WolfCharacterPixelMotion.State? = null,
        var motion: WolfCharacterMotion.State = WolfCharacterMotion.State(alternateSpeed = id in -7..-2),
    )
    private data class Navigation(val goalX: Int, val goalY: Int, val mode: Int, val waypoints: MutableList<Pair<Int, Int>>)
    private data class Loop(val start: Int, val end: Int, var remaining: Int)
    private class Invocation(
        val commands: List<WolfCommand>, val address: EventAddress,
        val returnDestination: Int? = null,
    ) {
        var pc = 0
        var waitTicks = 0
        var waitMovement = false
        var waitTransition = false
        var waitScroll = false
        var waitKeyDestination: Int? = null
        var waitKeyFlags = 0
        var waitKeyCode = 0
        var lastPhysicalKey = 0
        var lastPhysicalPadButton = 0
        var waitHost = false
        var waitInteractiveHost = false
        var waitFullHost = false
        val branches = mutableMapOf<Int, Int>()
        val loops = ArrayDeque<Loop>()
        val loopBounds: Map<Int, Int> by lazy {
            val ends = mutableMapOf<Int, Int>()
            buildMap { for (index in commands.indices.reversed()) {
                val c = commands[index]
                if (c.opcode == 498) ends[c.indent] = index
                else if (c.opcode in setOf(170, 179)) put(index, ends[c.indent] ?: error("Boucle sans fin"))
            } }
        }
        var command: WolfCommand? = null
    }
    private class Execution(val origin: String, val parallel: Boolean, frame: Invocation) {
        val stack = ArrayDeque<Invocation>().apply { addLast(frame) }
    }
    private data class CallRequest(val id: Int, val numbers: List<Int>, val strings: List<String>, val address: EventAddress)
    private data class PictureTween(val from: NativePicture, val to: NativePicture, val length: Int, var elapsed: Int = 0, val erase: Boolean = false)
    private data class DelayedPicture(val id: Int, val action: Int, val duration: Int, val target: NativePicture?, var remaining: Int,
        val effectOperation: Int = -1, val p1: Int = 0, val p2: Int = 0, val p3: Int = 0)
    private data class MapZoomTween(val from: Float, val to: Float, val length: Int, var elapsed: Int = 0,
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    fun key(key: NativeKey, pressed: Boolean) {
        scanForKey(key)?.let { physicalKey(it, pressed) }
        logicalKey(key, pressed)
    }

    /** Semantic controls from the native overlay, without inventing a physical keyboard scan. */
    fun logicalKey(key: NativeKey, pressed: Boolean) {
        val logical = when (key) { NativeKey.Z, NativeKey.SPACE -> NativeKey.ACCEPT; NativeKey.X -> NativeKey.BACK; else -> key }
        if (pressed) { if (keys.add(logical)) (if (tickExecutions == null) newKeys else queuedKeyPresses).add(logical) }
        else if (keys.remove(logical)) (if (tickExecutions == null) releasedKeys else queuedKeyReleases).add(logical)
    }

    /** DxLib/DirectInput scan code, before Wolf's +100 offset; independent of Android key codes. */
    fun physicalKey(scanCode: Int, pressed: Boolean) {
        require(scanCode in 0..255) { "Code physique de clavier invalide : $scanCode" }
        if (pressed) { if (physicalKeys.add(scanCode)) (if (tickExecutions == null) newPhysicalKeys else queuedPhysicalPresses).add(scanCode) }
        else physicalKeys.remove(scanCode)
    }

    /** Wolf numbers controller buttons 301..320; directional inputs use the semantic key API. */
    fun controllerButton(button: Int, pressed: Boolean) {
        require(button in 301..320) { "Bouton de manette non pris en charge : $button" }
        if (pressed) { if (physicalPadButtons.add(button)) (if (tickExecutions == null) newPhysicalPadButtons else queuedPadPresses).add(button) }
        else physicalPadButtons.remove(button)
    }

    fun releaseInput() { keys.clear(); newKeys.clear(); releasedKeys.clear(); queuedKeyPresses.clear(); queuedKeyReleases.clear(); queuedPhysicalPresses.clear(); queuedPadPresses.clear(); queuedPointerPress = false; queuedPointerRelease = false; physicalKeys.clear(); newPhysicalKeys.clear(); physicalPadButtons.clear(); newPhysicalPadButtons.clear(); keyRepeats.reset(); nextAutomaticScanKeys.clear(); automaticScanKeys.clear(); nextAutomaticKeys.clear(); nextAutomaticRawKeys.clear(); automaticRawKeys.clear(); nextAutomaticPointer = null; targetTile = null; pointerPressed = false; pointerNewPress = false; pointerNewRelease = false; pointerX = -1f; pointerY = -1f }

    /** Coordinates are inverse-transformed by the host from the visible viewport. */
    fun pointer(x: Float, y: Float, pressed: Boolean = true) {
        val firstPress = pressed && !pointerPressed
        if (firstPress) { if (tickExecutions == null) pointerNewPress = true else queuedPointerPress = true }
        if (!pressed && pointerPressed) { if (tickExecutions == null) pointerNewRelease = true else queuedPointerRelease = true }
        pointerX = x; pointerY = y; pointerPressed = pressed
        if (!firstPress) return
        val currentDialog = dialog
        if (currentDialog != null) {
            // Choice hit testing belongs to the view; tap elsewhere advances a message.
            if (!mouseLeftBanned && variables.system[73] == 1 && currentDialog.choices.isEmpty()) advanceDialog()
            return
        }
        val tile = game.config.tileSize
        targetTile = tileMapX(floor((cameraX() + mapZoomCenterX + (x - mapZoomCenterX) / mapZoom) / tile).toInt()) to
            tileMapY(floor((cameraY() + mapZoomCenterY + (y - mapZoomCenterY) / mapZoom) / tile).toInt())
    }

    fun choose(index: Int) {
        val current = dialog ?: return
        if (index !in current.choices.indices) return
        finishChoice(index, playSound = true)
    }

    private fun finishChoice(index: Int, playSound: Boolean) {
        val owner = dialogOwner ?: return
        val original = owner.command ?: return
        owner.branches[original.indent] = index + 2 // Choices use labels 2,3,...; label 1 belongs to a condition.
        variables.system[7] = 0
        choiceFinishedTick = ticks; choiceFinishedValue = index
        if (playSound) playSystemSound(17)
        forceChoiceFinish = false
        dialog = null; dialogOwner = null; dialogText = null
    }

    fun tick() {
        failed?.let { throw it }
        if (terminated) return
        if (pendingFontOverrides.isNotEmpty()) return
        var completed = false
        try {
          if (tickExecutions == null) {
            ticks++
            newKeys.addAll(queuedKeyPresses); queuedKeyPresses.clear()
            releasedKeys.addAll(queuedKeyReleases); queuedKeyReleases.clear()
            newPhysicalKeys.addAll(queuedPhysicalPresses); queuedPhysicalPresses.clear()
            newPhysicalPadButtons.addAll(queuedPadPresses); queuedPadPresses.clear()
            pointerNewPress = pointerNewPress || queuedPointerPress; queuedPointerPress = false
            pointerNewRelease = pointerNewRelease || queuedPointerRelease; queuedPointerRelease = false
            if (mapAudioPending) { mapAudioPending = false; applyMapAudio() }
            screenEffects.tick()
            mapShake.tick(fps)
            instructionBudget = 50_000
            newKeys.addAll(nextAutomaticKeys); nextAutomaticKeys.clear()
            automaticRawKeys.addAll(nextAutomaticRawKeys); nextAutomaticRawKeys.clear()
            newKeys.addAll(automaticRawKeys)
            automaticScanKeys.addAll(nextAutomaticScanKeys); nextAutomaticScanKeys.clear()
            automaticScanKeys.mapNotNull(::keyForScan).forEach(newKeys::add)
            if (28 in automaticScanKeys) pendingTextInputs.forEach { (id, pending) ->
                if (!pending.confirmationRequested) { pending.confirmationRequested = true; effects.add(NativeEffect.ConfirmTextInput(id)) }
            }
            nextAutomaticPointer?.let { (x, y, press) -> pointerX = x; pointerY = y; if (press) pointerNewPress = true }; nextAutomaticPointer = null
            val heldReaders = (keys + newKeys + logicalPhysicalState(false)).filterNot { it in bannedKeys }.toSet()
            val repeatDx = if (NativeKey.LEFT in heldReaders) -1 else if (NativeKey.RIGHT in heldReaders) 1 else 0
            val repeatDy = if (NativeKey.UP in heldReaders) -1 else if (NativeKey.DOWN in heldReaders) 1 else 0
            keyRepeats.tick(NativeKey.ACCEPT in heldReaders, NativeKey.BACK in heldReaders,
                if (repeatDx == 0 && repeatDy == 0) 0 else direction(repeatDx, repeatDy))
            updateDialogInput()
            updateDialogText()
            updatePictures()
            updateTextPictures()
            pictureEffects.tick().forEach { (id, pattern) ->
                pictures[id]?.let { pictures[id] = it.copy(pattern = pattern) }
                tweens[id]?.let { tweens[id] = it.copy(from = it.from.copy(pattern = pattern), to = it.to.copy(pattern = pattern)) }
            }
            extendedPictureEffects.tick()
            mapZoomTween?.let { tween ->
                tween.elapsed++
                val remaining = (tween.length - tween.elapsed).coerceAtLeast(0)
                // Original W2 stores percentages and centres as integer interpolation endpoints.
                fun interpolate(from: Int, to: Int) = to - (to.toLong() - from) * remaining / tween.length
                mapZoom = interpolate((tween.from * 100).toInt(), (tween.to * 100).toInt()) / 100f
                mapZoomCenterX = interpolate(tween.fromX.toInt(), tween.toX.toInt()).toFloat()
                mapZoomCenterY = interpolate(tween.fromY.toInt(), tween.toY.toInt()).toFloat()
                if (tween.elapsed >= tween.length) mapZoomTween = null
            }
            if (transitionPrevious != null && ++transitionElapsed >= transitionDuration) transitionPrevious = null
            refreshActors()
            mapCamera.tick(cameraMaxX(), cameraMaxY(), cameraLoopX(), cameraLoopY())
            val previousHeroX = heroQuarter(player.x); val previousHeroY = heroQuarter(player.y)
            updateWorld()
            mapCamera.follow(previousHeroX, previousHeroY, heroQuarter(player.x), heroQuarter(player.y),
                game.config.width, game.config.height, game.config.tileSize, cameraMaxX(), cameraMaxY(), cameraLoopX(), cameraLoopY())
            mapTextures.advance(::mapTextureParameters)
            schedule()
            // Parallel common initializers run before the map's automatic title event.
            tickExecutions = parallel.values.toList() + listOfNotNull(foreground)
            tickExecutionIndex = 0
          }
            val executions = tickExecutions ?: emptyList()
            while (tickExecutionIndex < executions.size) {
                val execution = executions[tickExecutionIndex]
                execute(execution)
                // Wolf's file I/O and picture measurement are synchronous instructions. The host
                // performs them asynchronously, but later events must remain in this same frame,
                // after the suspended instruction. This prevents Common63 racing Common48 init.
                if (execution.stack.lastOrNull()?.let { it.waitHost && !it.waitInteractiveHost } == true) return
                if (tickExecutions !== executions) break // A full restore or title reset abandons event stacks.
                tickExecutionIndex++
            }
            parallel.entries.removeAll { it.value.stack.isEmpty() }
            if (foreground?.stack?.isEmpty() == true) foreground = null
            completed = true
        } catch (failure: WolfRuntimeException) {
            failed = failure; releaseInput(); effects.add(NativeEffect.Diagnostic(failure.message.orEmpty())); throw failure
        } catch (failure: Exception) {
            val current = foreground?.stack?.lastOrNull() ?: parallel.values.firstOrNull()?.stack?.lastOrNull()
            val c = current?.command
            val address = current?.address ?: EventAddress(mapId)
            val wrapped = WolfRuntimeException(address.map, address.event, address.common, c?.opcode ?: -1, c?.offset ?: 0, failure.message ?: failure.javaClass.simpleName)
            failed = wrapped; releaseInput(); effects.add(NativeEffect.Diagnostic(wrapped.message.orEmpty())); throw wrapped
        } finally { if (completed || failed != null) { tickExecutions = null; tickExecutionIndex = 0; newKeys.clear(); releasedKeys.clear(); newPhysicalKeys.clear(); newPhysicalPadButtons.clear(); automaticRawKeys.clear(); automaticScanKeys.clear(); pointerNewPress = false; pointerNewRelease = false } }
    }

    fun frame(): NativeFrame {
        frozenFrame?.let { return it.copy(tick = ticks, diagnostic = failed?.message, windowSmooth = variables.system[140]?.let { filter -> filter != 0 }, eventRunning = foreground != null) }
        val tile = game.config.tileSize.toFloat()
        val characters = (listOf(player) + companions + actors.values).filter { it.opacity > 0 && (it.path.isNotBlank() || it.tileId >= 0) }.map {
            NativeCharacter(it.id, asset(it.path), (it.x + .5f) * tile, (it.y + 1f) * tile,
                0f, 0f, it.direction, it.pattern, it.opacity, it.flags and 16 != 0, it.tileId, it.moving, it.flags and 8 != 0,
                if (it.tileId >= 0 || game.config.byteSettings.getOrNull(5)?.toInt() == 1) null else game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(9)?.string(if (it.id == -2) variables.system[36] ?: it.shadow else it.shadow, 0)?.takeIf { path -> path.isNotBlank() }?.let(::asset), it.blend, characterHeight(it).toFloat(), characterBush(it), maskEnabled = it.id in maskedCharacters).let(characterEffects::character)
        }
        val tileset = currentTileset()
            ?: WolfTileset(map.tilesetId, "", "", emptyList(), byteArrayOf(), intArrayOf())
        val backdrop = mapTextures.frame(WolfMapTextures.Kind.BACKGROUND, mapTextureParameters(WolfMapTextures.Kind.BACKGROUND),
            (mapZoom * 100).toInt(), mapZoomCenterX.toInt(), mapZoomCenterY.toInt())
        // The desktop's separate DAT150bb44 opacity cap has no frame-transition
        // counterpart. Normal native rendering uses the stored Sys65 opacity.
        val fog = mapTextures.frame(WolfMapTextures.Kind.FOG, mapTextureParameters(WolfMapTextures.Kind.FOG),
            (mapZoom * 100).toInt(), mapZoomCenterX.toInt(), mapZoomCenterY.toInt(), (variables.system[65] ?: 255).coerceIn(0, 255))
        return NativeFrame(game.config.width, game.config.height,
            frozenMapFrame ?: NativeMapScene(map, tileset, characters, cameraX(), cameraY(), game.config.tileSize,
                game.config.characterDirectionsImage, game.config.animationPatterns, mapZoom, mapZoomCenterX, mapZoomCenterY, mapMasked,
                backdrop = backdrop, fog = fog),
            pictures.values.map(::renderPicture).sortedWith(compareBy<NativePicture> { Math.floorDiv(it.id, 100_000) }.thenBy { it.z }.thenBy { it.id }),
            emptyList(), dialog?.let { d -> d.copy(pauseX = variables.system[5]?.takeUnless { it == -1 }?.let { it + (d.bounds?.x ?: 0f) },
                pauseY = variables.system[6]?.takeUnless { it == -1 }?.let { it + (d.bounds?.y ?: 0f) }) }, ticks, failed?.message, screenOpacity, screenColor,
            transitionPrevious?.let { NativeTransition(it, if (transitionDuration == 0) 1f else (transitionElapsed.toFloat() / transitionDuration).coerceIn(0f, 1f), transitionMask(),
                if (transitionDuration == 0) 255 else 255 - ((transitionDuration - transitionElapsed).coerceAtLeast(0).toLong() * 255 / transitionDuration).toInt()) }, variables.system[125] ?: 5,
            screenEffects.state.tone, screenEffects.flashFrame(), visibilityMask.frame(), fontOverrides(), variables.system[140]?.let { it != 0 }, eventRunning = foreground != null)
    }

    fun drainEffects(): List<NativeEffect> = buildList { while (effects.isNotEmpty()) add(effects.removeFirst()) }
    /** Host configuration from its private per-game store, before the first instruction only. */
    fun initialNextLaunchScale(permille: Int) {
        require(ticks == 0L && tickExecutions == null) { "Échelle initiale définie après le lancement" }
        nextLaunchScale = permille
    }
    fun completeMaskImage(requestId: Long, image: NativeVisibilityMask) {
        val pending = pendingMaskImages[requestId] ?: return
        val request = pending.second
        visibilityMask.draw(image.width, image.height, image.pixels, request.x, request.y, request.anchor, request.mode)
        pendingMaskImages.remove(requestId)
        pending.first.waitHost = false
    }
    fun completeHostRequest(success: Boolean) {
        variables.system[24] = if (success) 1 else 0
        (parallel.values + listOfNotNull(foreground)).forEach { execution -> execution.stack.forEach {
            if (it.waitFullHost) { it.waitHost = false; it.waitFullHost = false }
        } }
    }

    fun completeSaveValue(requestId: Long, exists: Boolean, number: Int = 0, text: String = "", snapshot: ByteArray? = null) {
        val pending = pendingSaveValues.remove(requestId) ?: error("Demande de lecture de sauvegarde inconnue : $requestId")
        val destination = pending.destination ?: error("Demande de lecture sans destination")
        variables.system[24] = if (exists) 1 else 0
        // 465a30 chooses the reader from the destination. The readers do not
        // convert a numeric source into text, or parse a string source as a number.
        val sourceIsString = isStringValueCode(pending.sourceVariableCode)
        val database = pending.sourceVariableCode in 1_100_000_000..1_199_999_999
        if (pending.stringValue) {
            val value = when {
                !exists -> ""
                !sourceIsString -> if (database) "" else "<<ERROR>>"
                snapshot != null -> savedString(snapshot, pending.sourceVariableCode)
                else -> text
            }
            variables.writeString(destination, value, pending.invocation.address)
        } else {
            val value = when {
                !exists -> 0
                sourceIsString -> if (database) 0 else -1
                pending.sourceVariableCode == 9_000_024 -> 1
                snapshot != null -> savedNumber(snapshot, pending.sourceVariableCode)
                else -> number
            }
            variables.write(destination, value, pending.invocation.address)
        }
        pending.invocation.waitHost = false
    }

    fun completeSaveWrite(requestId: Long, success: Boolean) {
        val pending = pendingSaveValues.remove(requestId) ?: error("Demande d'écriture de sauvegarde inconnue : $requestId")
        pending.invocation.waitHost = false
        require(success) { "Écriture de sauvegarde native échouée" }
    }

    fun completePictureMeasure(requestId: Long, width: Int, height: Int) {
        val pending = pendingPictureMeasures.remove(requestId) ?: return // A restored/closed event abandons its host request.
        require(width in 0..32_768 && height in 0..32_768 && width.toLong() * height <= 64_000_000) { "Mesure d'image hors capacité" }
        val size = width to height
        pictureMeasures[pictureMeasureKey(pending.picture, pending.fontWeight)] = size
        while (pictureMeasures.size > 512) pictureMeasures.remove(pictureMeasures.keys.first())
        pending.invocation.waitHost = false
        if (pending.positionQuery != null) continuePicturePositionQuery(pending.positionQuery)
        else assignState(pending.invocation, pending.destination, pending.flags, pictureState(pending.picture, pending.field, size))
    }

    fun completeFontOverride(requestId: Long, available: Boolean) {
        val pending = pendingFontOverrides.remove(requestId) ?: return
        val index = pending.index; val name = pending.name
        variables.systemStrings[62 + index] = if (available || name.isEmpty()) name
            else if (index != 5 && (game.config.engineCompatibilityVersion ?: 0) >= 350) "<<?>>$name" else "<<NotFound>>"
        pictureMeasures.clear()
        pending.invocation?.waitHost = false
    }

    fun completeTextInput(requestId: Long, text: String?) {
        val pending = pendingTextInputs.remove(requestId) ?: return // Restoring a save abandons the former text input.
        require(text != null || pending.cancellable) { "Annulation d'une saisie obligatoire" }
        require(text == null || text.codePointCount(0, text.length) <= pending.maxLength) { "Saisie trop longue" }
        variables.system[39] = if (text == null) -1 else 0
        if (text != null) writeTextValue(pending.destination, text, pending.invocation.address)
        pending.invocation.waitHost = false
        pending.invocation.waitInteractiveHost = false
    }

    /** A former utterance finishing after its replacement cannot stop the newer one. */
    fun speechPlaying(requestId: Long, playing: Boolean) {
        if (activeSpeechId == requestId) activeSpeechPlaying = playing
    }

    fun completeSpeechVoices(requestId: Long, voices: List<String>) {
        val (invocation, destination) = pendingSpeechVoices.remove(requestId) ?: return
        require(voices.size <= 4096 && voices.all { it.length <= 4096 && '\n' !in it && '\r' !in it }) { "Liste de voix invalide" }
        writeTextValue(destination, voices.joinToString("\n"), invocation.address)
        invocation.waitHost = false
    }

    fun completeFileOperation(requestId: Long, result: String) {
        val (invocation, destination) = pendingStringFiles.remove(requestId) ?: run { completeDatabaseFile(requestId, result); return }
        require(result.length <= 16 * 1024 * 1024) { "Résultat de fichier Wolf trop volumineux" }
        if (destination != null) writeTextValue(destination, result, invocation.address)
        invocation.waitHost = false
    }

    /** A failed or abandoned download never overwrites an existing variable. */
    fun completeDownload(requestId: Long, response: Result<String>): Boolean {
        val (invocation, request) = pendingDownloads.remove(requestId) ?: return false
        val value = response.getOrNull()
        if (value != null && request.variable != null) {
            require(value.length <= WolfDownloadRequest.MAX_TEXT_CHARACTERS) { "Texte téléchargé hors capacité" }
            writeTextValue(request.variable, value, invocation.address)
        }
        if (requestId == lastDownloadRequest) variables.system[48] = if (response.isSuccess) 1 else -1
        if (request.waitForCompletion) invocation.waitHost = false
        response.exceptionOrNull()?.let { effects.add(NativeEffect.Diagnostic("Téléchargement260 échoué : ${it.message ?: it.javaClass.simpleName}")) }
        return true
    }

    /** Returns false for an abandoned request, e.g. a callback arriving after restoration. */
    fun completeDatabaseFile(requestId: Long, result: String): Boolean {
        val pending = pendingDatabaseFiles.remove(requestId) ?: return false
        if (!pending.writing) {
            // 941640 returns0 before the parser on an open failure. a25130 then leaves
            // this DB unchanged and returns from the instruction; an absent optional CSV
            // is not a process failure (the third game's first language import uses it).
            if (result != "<NoFile>") {
                val type = game.databases[pending.kind]?.types?.getOrNull(pending.type) ?: error("Type CSV DB absent")
                val rows = WolfDatabaseCsv.read(result, type.fields.size, pending.count, modernDatabaseCsv())
                variables.importCsv(pending.kind, pending.type, pending.start, rows)
            }
        }
        pending.invocation.waitHost = false
        return true
    }

    /** Decode callbacks cannot overwrite controls or revive a replaced/erased movie picture. */
    fun videoState(id: Int, measured: NativeVideoState) {
        val current = pictures[id]?.video ?: return
        if (measured.token != current.token || measured.seekRevision != current.seekRevision) return
        WolfVideo.validate(measured)
        val state = current.copy(positionMs = measured.positionMs, durationMs = measured.durationMs,
            playing = measured.playing && current.speedPercent > 0, width = measured.width, height = measured.height)
        updatePictureConversion(id) { if (it.video?.token == state.token) it.copy(video = state) else it }
    }

    private fun stopSpeech() {
        if (activeSpeechId != null) effects.add(NativeEffect.StopSpeech)
        activeSpeechId = null; activeSpeechPlaying = false; pendingSpeechVoices.clear()
    }

    fun savedNumber(snapshot: ByteArray, code: Int): Int {
        val saved = isolatedSaveState(snapshot)
        return when (code) {
            in 2_000_000..2_999_999 -> saved.numbers[code - 2_000_000] ?: 0
            in 3_000_000..3_999_999, in 9_900_000..9_999_999 -> -1
            in 9_000_000..9_099_999 -> when (val id = code % 100_000) {
                24 -> 1
                28 -> ((saved.ticks - saved.timerFrameOrigin + (saved.system[28] ?: 0)) % 10_000_000).toInt()
                29 -> ((saved.ticks - saved.timerSecondOrigin) / fps + (saved.system[29] ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                35 -> if ((saved.hero?.moveTicks ?: 0) > 0) 1 else 0
                38 -> saved.system[38] ?: 0
                61, 66 -> saved.system[id] ?: -1
                62, 63, 64, 67, 68 -> saved.system[id] ?: 0
                65 -> saved.system[id] ?: 255
                95, 97 -> saved.system[id] ?: saved.audio.firstOrNull { it.channel == (id - 95) / 2 }?.volume ?: 0
                96, 98 -> saved.system[id] ?: saved.audio.firstOrNull { it.channel == (id - 96) / 2 }?.pitch ?: 100
                115 -> game.config.engineCompatibilityVersion ?: error("Version de compatibilité absente de Game.dat")
                116 -> game.config.width
                117 -> game.config.height
                118 -> game.config.tileSize
                119 -> logicalScreenScale()
                155 -> game.config.characterDirectionsMove
                else -> saved.system[id] ?: error("Variable système absente de la sauvegarde : $code")
            }
            in 1_100_000_000..1_199_999_999 -> {
                if (isStringValueCode(code)) return 0 // 4772c0 rejects a non-numeric field before reading its row.
                val cell = mutableCell(code, WolfValueType.NUMBER)
                val type = game.databases.getValue(WolfDatabaseKind.MUTABLE).types[cell.first]
                require(cell.second < (saved.mutableRows[cell.first] ?: type.rows.size)) { "Rangée DB absente de la sauvegarde : $code" }
                saved.mutableNumbers[cell] ?: type.number(cell.second, cell.third) ?: type.fields[cell.third].defaultValue
            }
            else -> error("Lecture isolée de cette variable non prise en charge : $code")
        }
    }

    fun savedString(snapshot: ByteArray, code: Int): String {
        val saved = isolatedSaveState(snapshot)
        return when (code) {
            in 3_000_000..3_999_999 -> saved.strings[code - 3_000_000] ?: ""
            in 2_000_000..2_999_999, in 9_000_000..9_099_999 -> "<<ERROR>>"
            in 9_900_000..9_999_999 -> when (val id = code % 100_000) {
                in 1..6 -> WolfControlBindings.name(saved.system, id, game.config.engineCompatibilityVersion ?: 0)
                10 -> saved.hero?.path ?: saved.path
                in 11..15 -> saved.companions.getOrNull(id - 11)?.path ?: ""
                30, 31 -> saved.systemStrings[id].orEmpty()
                33, 34 -> saved.audio.firstOrNull { it.channel == id - 33 }?.path.orEmpty()
                else -> saved.systemStrings[id] ?: error("Chaîne système absente de la sauvegarde : $code")
            }
            in 1_100_000_000..1_199_999_999 -> {
                if (!isStringValueCode(code)) return "" // 477a40 rejects a non-string field.
                val cell = mutableCell(code, WolfValueType.STRING)
                val type = game.databases.getValue(WolfDatabaseKind.MUTABLE).types[cell.first]
                require(cell.second < (saved.mutableRows[cell.first] ?: type.rows.size)) { "Rangée DB texte absente de la sauvegarde : $code" }
                saved.mutableStrings[cell] ?: type.string(cell.second, cell.third) ?: ""
            }
            else -> error("Lecture isolée de cette chaîne non prise en charge : $code")
        }
    }

    /** One private decoded payload for repeated isolated221 reads; never used for restore.
     * Caller arrays are mutable: compare their content against an owned copy every time. */
    private fun isolatedSaveState(snapshot: ByteArray): WolfSnapshot.State {
        isolatedSaveCache?.takeIf { it.bytes.contentEquals(snapshot) }?.let { return it.state }
        val owned = snapshot.copyOf()
        val state = WolfSnapshot.decode(owned, game.config.title)
        isolatedSaveCache = if (owned.size <= 4 * 1024 * 1024) IsolatedSaveCache(owned, state) else null
        return state
    }

    /** REA43c9b0 distinguishes common slots5..9 and database fields, not one untyped dictionary. */
    private fun isStringValueCode(code: Int): Boolean = when (code) {
        in 1_600_005..1_600_009, in 3_000_000..3_999_999, in 9_900_000..9_999_999 -> true
        in 15_000_000..15_999_999 -> code % 100 in 5..9
        in 1_000_000_000..1_099_999_999, in 1_100_000_000..1_199_999_999, in 1_300_000_000..1_399_999_999 -> {
            val base = code / 100_000_000 * 100_000_000
            val kind = when (base) { 1_000_000_000 -> WolfDatabaseKind.USER; 1_100_000_000 -> WolfDatabaseKind.MUTABLE; else -> WolfDatabaseKind.SYSTEM }
            game.databases[kind]?.types?.getOrNull((code - base) / 1_000_000)?.fields?.getOrNull(code % 100)?.valueType == WolfValueType.STRING
        }
        else -> false
    }

    private fun mutableCell(code: Int, expected: WolfValueType): Triple<Int, Int, Int> {
        require(code in 1_100_000_000..1_199_999_999) { "Adresse DB mutable invalide" }
        val relative = code - 1_100_000_000
        val cell = Triple(relative / 1_000_000, relative % 1_000_000 / 100, relative % 100)
        require(game.databases[WolfDatabaseKind.MUTABLE]?.types?.getOrNull(cell.first)?.fields?.getOrNull(cell.third)?.valueType == expected) { "Champ DB de sauvegarde incompatible : $code" }
        return cell
    }

    /** W3 reads the Windows window multiplier; Android keeps game coordinates at their logical size. */
    private fun logicalScreenScale(): Int {
        require((game.config.engineCompatibilityVersion ?: 0) >= 300) { "Variable système119 absente de ce profil Wolf" }
        return 1
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
            val actor = actors.getOrPut(event.id) { Actor(event.id, event.x - if (page.flags and 128 != 0) .5f else 0f, event.y - if (page.flags and 64 != 0) .5f else 0f, page.graphicFile) }
            val marker = variables.system[-1_000_000 - event.id]
            if (marker != page.index) {
                variables.system[-1_000_000 - event.id] = page.index
                val keepFacing = marker != null && page.pageTransfer?.let { it != 0 } == true
                actor.path = page.graphicFile
                if (!keepFacing) { actor.direction = page.graphicDirection; actor.pattern = page.graphicFrame }
                actor.opacity = page.graphicOpacity
                actor.tileId = page.graphicTile; actor.flags = page.flags
                actor.shadow = page.features.getOrNull(0)?.toInt()?.and(255) ?: 0; actor.blend = page.graphicBlend
                actor.speed = page.movement.getOrNull(1)?.toInt()?.and(255) ?: 3
                actor.animation = newCharacterAnimation(page.movement.getOrNull(0)?.toInt()?.and(255) ?: 3,
                    if (keepFacing && (game.config.engineCompatibilityVersion ?: 0) >= 317) actor.pattern else page.graphicFrame)
                if (page.graphicBlend !in 0..3)
                    throw WolfRuntimeException(mapId, event.id, -1, -1, page.offset, "Graphique, collision ou fonctionnalité avancée de page non portés")
                val mode = page.movement.getOrNull(3)?.toInt()?.and(255) ?: 0
                if (mode !in 0..1) throw WolfRuntimeException(mapId, event.id, -1, 201, page.offset, "Mouvement autonome $mode non porté")
                actor.motion = WolfCharacterMotion.State(page.movement.getOrNull(2)?.toInt()?.and(255) ?: 6, mode)
                actor.route = page.route.takeIf { mode == 1 && it.commands.isNotEmpty() }; actor.routeIndex = 0
                actor.routeForced = false; actor.savedRoute = null; actor.savedRouteIndex = 0; actor.routeCommon = -1
                parallel.entries.removeAll { it.key.startsWith("map:$mapEpoch:${event.id}:") }
            }
        }
    }

    private fun execute(execution: Execution) {
        while (execution.stack.isNotEmpty()) {
            val frame = execution.stack.last()
            if (frame.waitHost) return
            if (frame.waitTransition) { if (transitionPrevious != null) return; frame.waitTransition = false }
            if (frame.waitScroll) { if (mapCamera.scrolling) return; frame.waitScroll = false }
            if (frame.waitTicks > 0) { frame.waitTicks--; return }
            if (frame.waitMovement) {
                if ((listOf(player) + companions + actors.values).any { it.routeForced && (it.moveTicks > 0 || it.jumpRemaining > 0 || it.route != null) }) return
                frame.waitMovement = false
            }
            frame.waitKeyDestination?.let { destination ->
                val value = inputValue(frame.waitKeyFlags, true, frame, frame.waitKeyCode)
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
                dispatchInvocation = frame
                if (dispatch(execution, frame, command)) return
            } catch (failure: WolfRuntimeException) { throw failure }
            catch (failure: Exception) { fail(frame, command, failure.message ?: failure.javaClass.simpleName) }
            finally { dispatchInvocation = null }
        }
    }

    private fun dispatch(execution: Execution, frame: Invocation, c: WolfCommand): Boolean {
        fun raw(index: Int, default: Int = 0) = c.arguments.getOrElse(index) { default }
        fun value(index: Int, default: Int = 0) = variables.read(raw(index, default), frame.address)
        when (c.opcode) {
            // a082c0 explicitly continues through the historic comment4 and internal marker1001.
            0, 4, 99, 103, 1001 -> Unit
            101 -> {
                var message = displayText(variables.systemStrings[26].orEmpty() + c.strings.firstOrNull().orEmpty() + variables.systemStrings[27].orEmpty(), frame.address)
                Regex("^@(-?[0-9]+)\\n").find(message)?.let { variables.system[0] = it.groupValues[1].toInt(); message = message.substring(it.value.length) }
                val size = (variables.system[8] ?: 16).toFloat()
                val x = variables.system[1]?.takeUnless { it == -1 }?.toFloat() ?: 0f
                val y = variables.system[2]?.takeUnless { it == -1 }?.toFloat() ?: 0f
                variables.systemStrings[25] = message
                dialogText = WolfTextProgram(message, variables.system[9] ?: 0, variables.system[10] ?: 0, fps, variables.system[150] != 1)
                dialog = NativeDialog(message, fontSize = size, bounds = NativeRect(x, y, game.config.width - x, game.config.height - y), visibleCharacters = 0,
                    pausePath = windowAsset(0), pauseFrames = windowFrames(0), rubySize = (variables.system[23] ?: size.toInt() / 2).toFloat(), shadowOffset = variables.system[21] ?: 0,
                    fontOverrides = fontOverrides(), convertSpecial = variables.system[150] != 1)
                dialogOwner = frame; updateDialogText(); return true
            }
            102 -> {
                val choices = c.strings.map { displayText(it, frame.address) }
                require(choices.isNotEmpty()) { "Choix sans options" }
                forceChoiceFinish = false
                val size = (variables.system[8] ?: 16).toFloat()
                dialog = NativeDialog("", choices, (variables.system[7] ?: 0).coerceIn(choices.indices), fontSize = size,
                    choiceSkinPath = windowAsset(1), choiceCursorPath = windowAsset(2), choiceCursorFrames = windowFrames(2),
                    choiceX = variables.system[3]?.takeUnless { it == -1 }?.toFloat(), choiceY = variables.system[4]?.takeUnless { it == -1 }?.toFloat(),
                    marginX = (variables.system[26] ?: 0).toFloat(), marginY = (variables.system[27] ?: 0).toFloat(),
                    cursorBlend = variables.system[22] ?: 0, rubySize = (variables.system[23] ?: size.toInt() / 2).toFloat(), shadowOffset = variables.system[21] ?: 0,
                    drawChoiceWindow = variables.system[14] != 0, fontOverrides = fontOverrides(), convertSpecial = variables.system[150] != 1); dialogOwner = frame; return true
            }
            104 -> { forceChoiceFinish = true }
            105 -> { dialog = null; dialogOwner = null; dialogText = null; forceChoiceFinish = false }
            106 -> effects.add(NativeEffect.Diagnostic(text(c.strings.firstOrNull().orEmpty(), frame.address)))
            107 -> Unit // Clear debug-text state; diagnostics remain in the host report.
            111 -> {
                val count = raw(0) and 15
                require(c.arguments.size >= 1 + count * 3) { "Conditions numériques incomplètes" }
                val selected = (0 until count).firstOrNull {
                    // a0cbc0 resolves the left operand, but bit0x10 makes the right a
                    // literal even when its value falls inside a variable namespace.
                    val flags = raw(3 + it * 3)
                    val right = if (flags and 16 != 0) raw(2 + it * 3) else value(2 + it * 3)
                    WolfVariables.compare(value(1 + it * 3), right, flags and 15)
                }?.plus(1) ?: 0
                frame.branches[c.indent] = selected
                if (selected == 0) skipToBranch(frame, c.indent, 0)
            }
            112 -> {
                val count = raw(0) and 15
                require(count in 1..4 && c.arguments.size >= count + 1) { "Conditions de chaîne incomplètes" }
                val selected = (0 until count).firstOrNull {
                    val packed = raw(it + 1)
                    val mode = packed ushr 28
                    val left = variables.readString(packed and 0x00ffffff, frame.address)
                    val right = if (packed and 0x0f000000 == 0) text(c.strings.getOrElse(it) { error("Texte de condition absent") }, frame.address)
                        else variables.readString(raw(count + 1 + it), frame.address)
                    when (mode) {
                        0 -> left == right
                        1 -> left != right
                        2 -> right in left
                        3 -> right !in left
                        4 -> left.startsWith(right)
                        5 -> left.endsWith(right)
                        else -> error("Comparaison de chaîne non prise en charge : $mode")
                    }
                }?.plus(1) ?: 0
                frame.branches[c.indent] = selected
                if (selected == 0) skipToBranch(frame, c.indent, 0)
            }
            121 -> setVariable(frame, c)
            122 -> { stringOperation(frame, c); if (frame.waitHost) return true }
            123 -> {
                val destination = raw(0)
                val flags = raw(1)
                val specificCode = if (flags and 0x1000 != 0) value(2) else 0
                if (flags and 128 != 0) {
                    frame.waitKeyDestination = destination; frame.waitKeyFlags = flags; frame.waitKeyCode = specificCode
                    if (flags and 0xf00 == 0x100) frame.lastPhysicalKey = physicalKeyboard(frame, false, specificCode)
                    else if (flags and 0xf00 == 0x200) frame.lastPhysicalPadButton = physicalController(frame, false, specificCode)
                    // a14280/45dd40 suspends first, then the event update invokes the
                    // waiting reader. A label cannot reread the same edge in this tick.
                    return true
                }
                val input = inputValue(flags, false, frame, specificCode)
                variables.write(destination, input, frame.address)
            }
            124 -> { variablePlus(frame, c); if (frame.waitHost) return true }
            125 -> injectInput(frame, c)
            126 -> blockInput(frame, c)
            130 -> teleport(frame, c)
            140 -> sound(frame, c)
            150 -> picture(frame, c)
            151 -> {
                val packed = raw(0)
                val duration = value(1)
                val tone = if (c.arguments.size == 5) NativeTone(
                    WolfScreenEffects.percentage(value(4)), WolfScreenEffects.percentage(value(3)), WolfScreenEffects.percentage(value(2)))
                else NativeTone(WolfScreenEffects.percentage(packed ushr 16 and 255),
                    WolfScreenEffects.percentage(packed ushr 8 and 255), WolfScreenEffects.percentage(packed and 255))
                if (packed and 0x01000000 != 0) screenEffects.flash(tone, duration) else screenEffects.tone(tone, duration)
            }
            160 -> { if (raw(0) != -2) transitionMode = raw(0); transitionDuration = raw(1) and 65535; transitionWait = raw(1) and 65536 != 0; transitionMask() }
            161 -> { if (transitionPrevious != null) { frame.pc--; return true }; frozenFrame = frame().copy(transition = null) }
            162 -> {
                if (transitionPrevious != null) { frame.pc--; return true }
                transitionPrevious = frozenFrame ?: frame().copy(transition = null)
                frozenFrame = null; transitionElapsed = 0
                if (transitionDuration == 0) transitionPrevious = null
                if (transitionWait && transitionPrevious != null) { frame.waitTransition = true; return true }
            }
            170, 179 -> {
                val tail = frame.loopBounds[frame.pc - 1] ?: error("Boucle sans fin")
                val count = if (c.opcode == 179) maxOf(0, value(0)) else -1
                frame.loops.removeAll { it.start == frame.pc }
                if (count == 0) frame.pc = tail + 1 else frame.loops.addLast(Loop(frame.pc, tail, count))
            }
            171 -> { val loop = enclosingLoop(frame, frame.pc - 1); frame.loops.removeAll { it.start >= loop.start }; frame.pc = loop.end + 1 }
            176 -> { val loop = enclosingLoop(frame, frame.pc - 1); advanceLoop(frame, loop) }
            172 -> finishInvocation(execution)
            173 -> { if (frame.address.map == mapId) { erasedEvents += frame.address.event; actors.remove(frame.address.event); characterEffects.erase(frame.address.event); maskedCharacters.remove(frame.address.event) }; finishInvocation(execution) }
            174 -> { resetToTitle(); return true }
            175 -> { terminated = true; releaseInput(); effects.add(NativeEffect.Quit); return true }
            177 -> { freezeWorld = true; frozenMapFrame = frame().map }
            178 -> { freezeWorld = false; frozenMapFrame = null }
            180 -> { frame.waitTicks = maxOf(0, value(0)); return true }
            201 -> {
                val actor = actor(value(0), frame.address)
                if (!actor.routeForced) { actor.savedRoute = actor.route; actor.savedRouteIndex = actor.routeIndex }
                actor.route = c.route ?: error("Commande de mouvement sans route")
                actor.routeForced = true; actor.routeCommon = frame.address.common
                val header = actor.route!!.header
                if (header.isNotEmpty()) {
                    require(header.size == 5) { "En-tête de route incomplet" }
                    // 4adcb0→47bd60 /9e9a70→6d4fe0 copy route metadata only.
                    // The actor's page traits change through explicit route29/30/31 etc.
                }
                actor.routeIndex = 0; actor.routeWait = 0
                val waitRoute = actor.route!!.flags and 4 != 0
                if (actor.moveTicks == 0 && actor.jumpRemaining == 0) updateRoute(actor)
                if (waitRoute) { frame.waitMovement = true; return true }
            }
            202 -> { frame.waitMovement = true; return true }
            210, 211, 300 -> call(execution, frame, c)
            212 -> Unit // A label is a target, not an executable mutation.
            213 -> {
                val label = text(c.strings.firstOrNull().orEmpty(), frame.address)
                val target = frame.commands.indexOfFirst { it.opcode == 212 && text(it.strings.firstOrNull().orEmpty(), frame.address) == label }
                require(target >= 0) { "Étiquette absente : $label" }; frame.pc = target + 1
            }
            220 -> {
                val mode = raw(0); val (slot, key) = saveIdentity(raw(1), frame.address)
                require(mode != 1 || !execution.parallel) { "Chargement complet interdit depuis un événement parallèle" }
                variables.system[25] = slot
                if (mode == 0) effects.add(NativeEffect.Save(slot, key)) else effects.add(NativeEffect.Load(slot, key))
                frame.waitHost = true; frame.waitFullHost = true; return true
            }
            221, 222 -> {
                require(c.args.size >= 3) { "Opération de valeur sauvegardée incomplète" }
                val flags = raw(3)
                val (_, key) = saveIdentity(raw(1), frame.address)
                val storedCode = if (flags and 1 != 0) value(2) else raw(2)
                require(storedCode in 2_000_000..3_999_999 || storedCode in 9_000_000..9_099_999 || storedCode in 9_900_000..9_999_999 || storedCode in 1_100_000_000..1_199_999_999) { "Variable exclue des opérations isolées de sauvegarde : $storedCode" }
                val storedString = isStringValueCode(storedCode)
                val stringValue = if (c.opcode == 221) isStringValueCode(raw(0)) else storedString
                if (storedCode in 1_100_000_000..1_199_999_999) mutableCell(storedCode, if (storedString) WolfValueType.STRING else WolfValueType.NUMBER)
                val requestId = nextHostRequestId++
                pendingSaveValues[requestId] = PendingSaveValue(frame, if (c.opcode == 221) raw(0) else null, stringValue, storedCode)
                if (c.opcode == 221) effects.add(NativeEffect.ReadSaveValue(requestId, key, storedCode, stringValue))
                else if (stringValue) effects.add(NativeEffect.WriteSaveValue(requestId, key, storedCode,
                    text = if (isStringValueCode(raw(0))) variables.readString(raw(0), frame.address) else value(0).toString()))
                else effects.add(NativeEffect.WriteSaveValue(requestId, key, storedCode, number = value(0)))
                frame.waitHost = true; return true
            }
            230 -> allowMovement = true
            231 -> allowMovement = false
            240 -> changeChip(frame, c)
            241 -> { mapChanges.switchTileset(value(0)); map = mapChanges.map; macroTileGrid = null }
            242 -> overwriteChips(frame, c)
            250 -> database(frame, c)
            251 -> databaseFile(frame, c)
            260 -> {
                // Both original workers reject a second request before changing its wait pointer.
                if (pendingDownloads.isNotEmpty()) {
                    effects.add(NativeEffect.Diagnostic("Téléchargement260 ignoré : une réception est déjà en cours"))
                    return false
                }
                val request = WolfDownloadRequest.parse(c.arguments, c.strings.map { text(it, frame.address) },
                    if ((game.config.engineCompatibilityVersion ?: 0) >= 300) WolfDownloadProfile.W3 else WolfDownloadProfile.W2,
                    frame.address.common)
                val id = nextHostRequestId++
                lastDownloadRequest = id; variables.system[48] = 0
                pendingDownloads[id] = frame to request
                effects.add(NativeEffect.Download(id, request))
                if (request.waitForCompletion) { frame.waitHost = true; return true }
            }
            270 -> party(frame, c)
            280 -> {
                val flags = raw(0); val direction = flags ushr 8 and 15
                if (direction == 2) mapShake.stop()
                else mapShake.start(flags and 15, flags ushr 4 and 15, direction, value(1).coerceAtLeast(0))
            }
            281 -> {
                val flags = raw(0); val mode = flags and 15
                when (mode) {
                    0, 1 -> {
                        val dx: Int; val dy: Int
                        if (mode == 1) {
                            dx = centredCameraX() - mapCamera.state.x; dy = centredCameraY() - mapCamera.state.y
                        } else {
                            val scale = if (flags and 0x200 != 0) 4 else game.config.tileSize * 4
                            dx = Math.multiplyExact(value(1), scale); dy = Math.multiplyExact(value(2), scale)
                        }
                        mapCamera.start(dx, dy, flags ushr 4 and 15, game.config.tileSize)
                        if (flags and 0x100 != 0 && mapCamera.scrolling) { frame.waitScroll = true; return true }
                    }
                    2 -> mapCamera.followsHero(false)
                    3 -> mapCamera.followsHero(true)
                }
            }
            290 -> {
                val flags = raw(0); val duration = value(1).coerceAtLeast(0)
                require(duration <= 360_000) { "Durée d'effet hors limites" }
                if (flags == 2) {
                    val zoom = value(6) / 100f
                    require(zoom in .01f..100f) { "Zoom de carte hors limites" }
                    val x = value(4).toFloat(); val y = value(5).toFloat()
                    if (mapZoom == 1f || duration == 0) { mapZoomCenterX = x; mapZoomCenterY = y }
                    if (duration == 0) { mapZoom = zoom; mapZoomTween = null }
                    else mapZoomTween = MapZoomTween(mapZoom, zoom, duration, 0, mapZoomCenterX, mapZoomCenterY, x, y)
                } else if (flags and 15 == 3) {
                    require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Masque de visibilité absent de ce profil Wolf" }
                    val operation = flags ushr 4 and 255
                    when (operation) {
                        0 -> { maskedPictures.clear(); visibilityMask.reset() }
                        1, 2 -> {
                            val first = value(2); val last = value(3)
                            require(kotlin.math.abs(last.toLong() - first) <= 10_000) { "Plage du masque d'image hors limites" }
                            for (id in minOf(first, last)..maxOf(first, last)) if (id in pictures) {
                                if (operation == 1) maskedPictures.add(id) else maskedPictures.remove(id)
                            }
                        }
                        3, 4 -> visibilityMask.fill(operation == 4)
                        5, 6 -> {
                            val path = asset(text(c.strings.firstOrNull() ?: error("Image du masque absente"), frame.address))
                            val requestId = nextHostRequestId++
                            val request = NativeEffect.MaskingImage(requestId, path, value(4), value(5), value(6), operation - 5)
                            require(request.anchor in 0..1) { "Ancrage du masque inconnu" }
                            pendingMaskImages[requestId] = frame to request
                            frame.waitHost = true; effects.add(request); return true
                        }
                        7, 8 -> {
                            val first = value(2); val last = value(3)
                            require(kotlin.math.abs(last.toLong() - first) <= 10_000) { "Plage du masque de personnage hors limites" }
                            for (id in minOf(first, last)..maxOf(first, last)) {
                                val actual = if (id == -1) frame.address.event else id
                                if (actual in -7..-2 || actual in actors) {
                                    if (operation == 7) maskedCharacters.add(actual) else maskedCharacters.remove(actual)
                                }
                            }
                        }
                        9, 10 -> mapMasked = operation == 9
                        else -> error("Opération du masque de visibilité non portée : $operation")
                    }
                } else if (flags and 15 == 1) {
                    val id = value(2)
                    val target = when (id) { -2 -> player; -1 -> actors[frame.address.event]; in -7..-3 -> companions[-id - 3]; else -> actors[id] }
                    val operation = flags ushr 4 and 255
                    if (target != null) when (operation) {
                        7 -> {
                            require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Déplacement pixel absent de ce profil Wolf" }
                            pixelMove(target, value(4), value(5))
                        }
                        8, 12 -> {
                            require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Effet de personnage récent absent de ce profil Wolf" }
                            val enabled = value(4).coerceIn(0, 1) == 1
                            if (operation == 8) characterAnimation(target).animateOnStop(enabled)
                            else target.flags = if (enabled) target.flags or 4 else target.flags and 4.inv()
                        }
                        else -> characterEffects.apply(target.id, operation, value(4), value(5), value(6), duration)
                    }
                } else {
                    val operation = flags ushr 4
                    require(flags and 15 == 0 && (operation in 0..10 || operation in 18..20 || (game.config.engineCompatibilityVersion ?: 0) >= 350 && operation in setOf(11, 12, 13, 14, 17, 21, 22, 23, 24, 25, 26, 27, 28))) { "Effet Wolf non porté : catégorie=${flags and 15}, opération=$operation" }
                    val first = value(2); val last = value(3)
                    require(kotlin.math.abs(last.toLong() - first) <= 10_000) { "Plage d'effets d'image hors limites" }
                    val p1 = value(4); val p2 = value(5); val p3 = value(6)
                    val delay = value(7).coerceAtLeast(0)
                    require(delay <= 360_000 && (delay == 0 || delayedPictures.size + abs(last.toLong() - first) + 1 <= 10_000)) { "Délai d'effet d'image hors limites" }
                    for (id in minOf(first, last)..maxOf(first, last)) {
                        if (id in pictures) {
                            if (delay > 0) delayedPictures.add(DelayedPicture(id, 4, duration, null, delay, operation, p1, p2, p3))
                            else applyPictureEffect(id, operation, p1, p2, p3, duration)
                        }
                    }
                }
            }
            401, 402 -> {
                // a2dbf0 consumes a successful selection as -100. Keeping the old
                // selection would re-enter a later arm after a label jumps into it.
                val selected = frame.branches[c.indent] ?: 0
                if (selected == raw(0)) frame.branches[c.indent] = -100
                else skipToBranch(frame, c.indent, selected)
            }
            420, 421 -> {
                if ((frame.branches[c.indent] ?: 0) == 0) frame.branches[c.indent] = -100
                else skipToBranch(frame, c.indent, -1)
            }
            498 -> {
                val loop = enclosingLoop(frame, frame.pc - 1)
                advanceLoop(frame, loop)
            }
            499 -> frame.branches[c.indent] = -100
            else -> fail(frame, c, "Commande non implémentée")
        }
        return false
    }

    private fun updatePictureConversion(id: Int, update: (NativePicture) -> NativePicture) {
        pictures[id]?.let { pictures[id] = update(it) }
        tweens[id]?.let { tween -> tweens[id] = tween.copy(from = update(tween.from), to = update(tween.to)) }
    }

    private fun applyPictureEffect(id: Int, operation: Int, p1: Int, p2: Int, p3: Int, duration: Int) {
        if (id !in pictures) return
        when {
            operation in 18..20 -> updatePictureConversion(id) { picture ->
                val video = picture.video
                if (video == null) picture else {
                    val changed = when (operation) {
                        18 -> video.copy(speedPercent = p1, playing = p1 > 0 &&
                            (video.loop || video.durationMs == 0L || video.positionMs < video.durationMs))
                        19 -> video.copy(seekMs = p1.toLong(), seekRevision = Math.addExact(video.seekRevision, 1), positionMs = p1.toLong())
                        else -> video.copy(volumePercent = p1)
                    }
                    WolfVideo.validate(changed); picture.copy(video = changed)
                }
            }
            operation == 25 -> { pictureEffects.erase(id); extendedPictureEffects.erase(id) }
            operation == 28 -> updatePictureConversion(id) { it.copy(z = p1) }
            operation == 27 -> updatePictureConversion(id) { it.copy(imageFilters = emptyList()) }
            operation in setOf(21, 22, 23, 24, 26) -> updatePictureConversion(id) {
                if (it.kind !in setOf(0, 1, 3, 4) || it.path == null || it.video != null) it else {
                    require(it.imageFilters.size < 256) { "Trop de conversions cumulatives d'image" }
                    it.copy(imageFilters = it.imageFilters + NativeImageFilter(operation, p1, p2, p3, duration / maxOf(1, 60 / fps)))
                }
            }
            operation <= 10 -> pictureEffects.apply(id, operation, p1, p2, p3, duration)
            else -> extendedPictureEffects.apply(id, operation, p1, p2, p3, duration)
        }
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

    /** REA W2 0045b670: low nibble selects the source, bits8..11 the string operation. */
    private fun stringOperation(frame: Invocation, c: WolfCommand) {
        val flags = c.args[1]
        val destination = if (flags and 0x10 != 0) variables.read(c.args[0], frame.address) else c.args[0]
        val sourceMode = flags and 15
        val operation = flags ushr 8 and 15
        if (sourceMode == 3) {
            require(pendingTextInputs.isEmpty()) { "Saisies de texte simultanées non prises en charge" }
            val maximum = variables.read(c.args.getOrElse(2) { error("Longueur de saisie absente") }, frame.address).coerceIn(2, 500)
            val initial = if (flags and 0x2000 != 0) variables.readString(destination, frame.address) else ""
            val requestId = nextHostRequestId++
            pendingTextInputs[requestId] = PendingTextInput(frame, destination, maximum, flags and 0x1000 != 0)
            frame.waitHost = true
            frame.waitInteractiveHost = true
            effects.add(NativeEffect.TextInput(requestId, initial, maximum, flags and 0x1000 != 0, fontOverrides()[5]))
            return
        }
        val sourceCode = if (sourceMode == 1) c.args.getOrElse(2) { error("Source de chaîne absente") }
            else if (sourceMode == 2) variables.read(c.args.getOrElse(2) { error("Source indirecte de chaîne absente") }, frame.address) else null
        val rhs = if (sourceCode != null) variables.readString(sourceCode, frame.address)
            else text(c.strings.getOrElse(0) { "" }, frame.address)
        val fileProbe = when {
            rhs.startsWith("<<GET_FILE_EXIST>>") -> 8
            rhs.startsWith("<<GET_SOUNDTIME>>") -> 9
            else -> 0
        }
        if (operation in 5..7 || flags and 0x40000 != 0 && fileProbe != 0) {
            val hostOperation = if (operation == 5 && fileProbe != 0 || flags and 0x40000 != 0) fileProbe else operation
            val filePath = WolfFileText.path(if (hostOperation in 8..9) rhs.substringAfter(">>") else rhs, hostOperation == 6)
            val encoding = c.args.getOrElse(4) { 0 }
            require(encoding in 0..2) { "Encodage de fichier Wolf inconnu : $encoding" }
            val requestId = nextHostRequestId++
            pendingStringFiles[requestId] = frame to destination.takeUnless { hostOperation == 6 }
            frame.waitHost = true
            effects.add(NativeEffect.FileOperation(requestId, hostOperation, filePath,
                if (hostOperation == 6) variables.readString(destination, frame.address) else "", encoding,
                (game.config.engineCompatibilityVersion ?: 0) >= 300))
            return
        }
        if (flags and 0x40000 != 0) {
            require((game.config.engineCompatibilityVersion ?: 0) >= 300) { "Codes spéciaux de ce profil Wolf non établis" }
            val special = if (rhs.startsWith("<<") && rhs.contains(">>")) rhs.substringAfter("<<").substringBefore(">>").substringBefore('/') else ""
            if (special in setOf("MS_VOICE_PLAY", "GET_MS_VOICE_PLAYING", "GET_MS_VOICE_LIST")) {
                require((game.config.engineCompatibilityVersion ?: 0) >= 360) { "Synthèse vocale absente de ce profil Wolf" }
                when (special) {
                    "MS_VOICE_PLAY" -> {
                        val request = WolfStringMacros.speechRequest(rhs)
                        val corrected = if (request.absolute) request.volume.toLong() else request.volume.toLong() * (variables.system[102] ?: 100) / 100
                        require(corrected <= 100_000) { "Amplification de voix hors capacité" }
                        val id = nextHostRequestId++
                        activeSpeechId = id; activeSpeechPlaying = request.text.isNotEmpty()
                        effects.add(NativeEffect.Speech(id, request.text, request.speed, corrected.toInt(), request.voice))
                        writeTextValue(destination, "1", frame.address)
                    }
                    "GET_MS_VOICE_PLAYING" -> writeTextValue(destination, if (activeSpeechPlaying) "1" else "0", frame.address)
                    else -> {
                        val id = nextHostRequestId++; pendingSpeechVoices[id] = frame to destination
                        frame.waitHost = true; effects.add(NativeEffect.SpeechVoices(id))
                    }
                }
            } else writeTextValue(destination, WolfStringMacros.evaluate(rhs, game, map) { pathMacro(it, frame.address) }, frame.address)
            return
        }
        val previous = variables.readString(destination, frame.address)
        val result = when (operation) {
            0 -> rhs
            1 -> previous + rhs
            2, 3 -> {
                val newline = rhs.indexOf('\n')
                if (operation == 3 && sourceCode != null) writeTextValue(sourceCode, if (newline < 0) "" else rhs.substring(newline + 1), frame.address)
                (if (newline < 0) rhs else rhs.substring(0, newline)).removeSuffix("\r")
            }
            8 -> if (rhs.isEmpty()) previous else previous.replace(rhs, "")
            9 -> {
                val replacement = text(c.strings.getOrElse(1) { error("Texte de remplacement absent") }, frame.address)
                if (rhs.isEmpty()) previous else previous.replace(rhs, replacement)
            }
            10, 11 -> {
                val index = previous.indexOf(rhs)
                if (index < 0) "<<NotFound>>" else if (operation == 10) previous.substring(0, index) else previous.substring(index + rhs.length)
            }
            else -> error("Opération de chaîne non portée : $operation")
        }
        writeTextValue(destination, result, frame.address)
    }

    /** W3 hidden-code dispatcher a2e2d0; full-tile grid and snapped actor footprints. */
    private fun pathMacro(request: WolfPathfinding.Request, address: EventAddress): String {
        require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Recherche de chemin absente de ce profil Wolf" }
        require(map.width.toLong() * map.height <= 4_000_000) { "Carte de recherche hors capacité" }
        val began = System.nanoTime()
        val goal = WolfPathfinding.Point(Math.floorMod(request.goal.x, map.width), Math.floorMod(request.goal.y, map.height))
        // Non-v2 retains the original raw-coordinate goal passage check before search normalization.
        if (!request.version2 && !gridPassage(request.goal.x * 2, request.goal.y * 2, 10, 2))
            return WolfPathfinding.format(WolfPathfinding.Result(null, 0, false), request, map.width, map.height, map.loopY, map.loopX)
        val base = macroTileGrid?.takeIf { it.first === map }?.second ?: run {
            val cardinal = IntArray(map.width * map.height)
            for (x in 0 until map.width) for (y in 0 until map.height) {
                var mask = 0
                if (gridPassage(x * 2, y * 2, 8, 2)) mask = mask or 1
                if (gridPassage(x * 2, y * 2, 6, 2)) mask = mask or 4
                if (gridPassage(x * 2, y * 2, 2, 2)) mask = mask or 16
                if (gridPassage(x * 2, y * 2, 4, 2)) mask = mask or 64
                cardinal[x * map.height + y] = mask
            }
            WolfPathfinding.buildGrid(map.width, map.height, map.loopX, map.loopY, cardinal).also { macroTileGrid = map to it }
        }
        val grid = if (request.version2) base.openedGoal(goal) else base
        val movingId = if (request.movingEvent == -1) address.event else request.movingEvent
        val occupancy = if (movingId > -8) WolfPathfinding.occupancy(map.width, map.height,
            (listOf(player) + actors.values).map { a -> WolfPathfinding.Occupant(a.id,
                (logicalX(a) * 2).toInt(), (logicalY(a) * 2).toInt(), a.flags and 8 != 0,
                squareHitbox(a), a.path.length, a.tileId) }, movingId, goal) else null
        // The shipped dispatcher swaps map loop axes when binding its generic search helper.
        // Grid construction itself keeps the map's original X/Y axes.
        val result = WolfPathfinding.search(map.width, map.height, map.loopY, map.loopX, request,
            grid::allows, occupancy?.let { it::allows })
        val elapsed = ((System.nanoTime() - began) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return WolfPathfinding.format(result, request, map.width, map.height, map.loopY, map.loopX, elapsed)
    }

    private fun writeTextValue(code: Int, value: String, address: EventAddress) {
        // The original setter accepts numeric destinations and applies atol after its string operation.
        if (code in 1_000_000..1_599_999 || code in 2_000_000..2_999_999 || code in 9_000_000..9_199_999)
            variables.write(code, WolfVariables.numericPrefix(value), address)
        else variables.writeString(code, value, address)
    }

    /** REA W2 FUN_0045de60: source category in bits12..15, operation in bits8..11. */
    private fun variablePlus(frame: Invocation, c: WolfCommand) {
        require(c.args.size >= 3) { "Lecture d'état incomplète" }
        val flags = c.args[1]
        var destination = c.args[0]
        if (flags and 16 != 0) { destination = variables.read(destination, frame.address); if (destination < 1_000_000) destination += 2_000_000 }
        fun value(index: Int) = variables.read(c.args.getOrElse(index) { error("Argument d'état absent") }, frame.address)
        val result = when (flags ushr 12 and 15) {
            1 -> {
                val target = value(2)
                val actor = when (target) { -1 -> actors[frame.address.event]; -2 -> player; in -7..-3 -> companions[-target - 3]; else -> actors[target] }
                val field = c.args.getOrElse(3) { error("Champ de personnage absent") }
                if (actor == null) -1 else when (field) {
                    0 -> Math.floorMod(logicalX(actor).toInt(), map.width)
                    1 -> Math.floorMod(logicalY(actor).toInt(), map.height)
                    2 -> Math.floorMod((logicalX(actor) * 2).toInt(), map.width * 2)
                    3 -> Math.floorMod((logicalY(actor) * 2).toInt(), map.height * 2)
                    4 -> actor.elevation
                    5 -> actor.direction
                    6 -> ((actor.x + .5f) * game.config.tileSize - cameraX()).toInt()
                    7 -> ((actor.y + 1f) * game.config.tileSize - cameraY()).toInt()
                    9 -> tileTag(actor.x.toInt(), actor.y.toInt())
                    10 -> actor.id
                    11 -> { val x = (actor.x + .5f) * game.config.tileSize - cameraX(); val y = (actor.y + 1f) * game.config.tileSize - cameraY(); if (x > -game.config.tileSize && x < game.config.width + game.config.tileSize && y > -game.config.tileSize && y < game.config.height + game.config.tileSize * 2) 1 else 0 }
                    12 -> map.events.firstOrNull { it.id == actor.id }?.let(::activePage)?.index?.plus(1) ?: 0
                    13 -> map.events.firstOrNull { it.id == actor.id }?.let(::activePage)?.trigger ?: 0
                    14, 15 -> map.events.firstOrNull { it.id == actor.id }?.let(::activePage)?.features?.getOrNull(field - 13)?.toInt()?.and(255) ?: 0
                    16 -> actor.pattern + 1
                    17 -> if (actor.moveTicks > 0 || actor.pixelMotion?.moved == true) 1 else 0
                    else -> error("Champ de personnage non porté : $field")
                }
            }
            2 -> {
                val field = flags ushr 16 and 255
                if (field in setOf(22, 23)) {
                    require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Recherche d'image à une position absente de ce profil Wolf" }
                    continuePicturePositionQuery(PicturePositionQuery(frame, destination, flags, value(2), value(3), field == 23,
                        pictures.values.map(::renderPicture).sortedBy { it.id }))
                    return
                } else if (field in setOf(152, 153, 154, 155, 161, 162)) {
                    require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Conversion écran/carte absente de ce profil Wolf" }
                    // a16305..a16814: truncate the inverse zoom before adding the integer camera,
                    // then wrap map pixels even on a non-looping map. Field152/153 divide by a tile;
                    // field154/155 by a half-tile;161/162 return the actual pixel coordinates.
                    val px = Math.floorMod(cameraX().toInt() + ((value(2) - mapZoomCenterX) / mapZoom + mapZoomCenterX).toInt(), map.width * game.config.tileSize)
                    val py = Math.floorMod(cameraY().toInt() + ((value(3) - mapZoomCenterY) / mapZoom + mapZoomCenterY).toInt(), map.height * game.config.tileSize)
                    when (field) {
                        152 -> px / game.config.tileSize
                        153 -> py / game.config.tileSize
                        154 -> px / (game.config.tileSize / 2)
                        155 -> py / (game.config.tileSize / 2)
                        161 -> px
                        else -> py
                    }
                } else {
                val half = flags and 32 != 0
                val hx = value(2).let { if (half) it else it * 2 }; val hy = value(3).let { if (half) it else it * 2 }
                val x = tileMapX(Math.floorDiv(hx, 2)); val y = tileMapY(Math.floorDiv(hy, 2))
                when (field) {
                    0 -> tileTag(x, y)
                    1 -> actors.values.firstOrNull { tileMapX(logicalX(it).toInt()) == x && tileMapY(logicalY(it).toInt()) == y && it.id !in erasedEvents }?.id ?: -1
                    2 -> if (if (half) quarterPassable(hx, hy) else gridPassage(hx, hy, 0, 0) && (!squareHitbox(player) || gridPassage(hx, hy - 1, 0, 0))) 0 else 1
                    3 -> if (quarterPassable(hx, hy) && actors.values.none { it.flags and 8 == 0 && it.x.toInt() == x && it.y.toInt() == y && (it.path.isNotBlank() || it.tileId >= 0) }) 0 else 1
                    4 -> chipValue((map.layerCount - 1 downTo 0).map { chipAt(x, y, it) }.firstOrNull { it != 0 } ?: 0)
                    5, 6, 7 -> chipValue(chipAt(x, y, field - 5))
                    8, 9, 10 -> {
                        val tileset = currentTileset()
                        fun tag(hx: Int, hy: Int) = tileset?.tagForTile(map.tile(halfMapX(hx) / 2, halfMapY(hy) / 2, field - 8)) ?: 0
                        if (half) tag(hx, hy) else maxOf(tag(hx, hy + 1), tag(hx + 1, hy + 1))
                    }
                    else -> error("Champ de position non porté : $field")
                }
                }
            }
            3 -> when (val field = c.args[2]) {
                0 -> mapId
                1, 2 -> activeAudio[audioKey(field - 1)]?.path?.let { path -> game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(field)?.rows?.indexOfFirst { asset(it.strings.firstOrNull().orEmpty()) == path } } ?: -1
                7 -> if (pointerNewPress) 2 else if (pointerPressed) 1 else 0
                14 -> frame.address.event
                15 -> map.width
                16 -> map.height
                17 -> frame.address.common
                18 -> foreground?.stack?.firstOrNull()?.address?.let { if (it.common >= 0) it.common + 500_000 else it.event } ?: -1
                19 -> foreground?.stack?.lastOrNull()?.pc?.minus(1) ?: -1
                20 -> pointerX.toInt()
                21 -> pointerY.toInt()
                22 -> ((java.lang.Runtime.getRuntime().totalMemory() - java.lang.Runtime.getRuntime().freeMemory()) / 1_048_576).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else -> error("Champ d'état général non porté : $field")
            }
            4 -> {
                val id = value(2)
                val field = c.args.getOrElse(3) { error("Champ d'image absent") }
                val picture = pictures[id]
                if (picture == null) if (field == 9) 0 else -1
                else {
                    val size = if (field in setOf(2, 3, 8)) pictureDimensions(picture) else null
                    if (size == null && field in setOf(2, 3, 8)) {
                        val request = nextHostRequestId++
                        pendingPictureMeasures[request] = PendingPictureMeasure(frame, destination, flags, picture, field, variables.system[125] ?: 5)
                        frame.waitHost = true
                        effects.add(NativeEffect.MeasurePicture(request, picture, variables.system[125] ?: 5, picture.fontOverrides))
                        return
                    }
                    pictureState(picture, field, size)
                }
            }
            else -> error("Catégorie de lecture d'état inconnue : ${flags ushr 12 and 15}")
        }
        assignState(frame, destination, flags, result)
    }

    private fun assignState(frame: Invocation, destination: Int, flags: Int, result: Int) {
        val previous = if (flags ushr 8 and 15 in 1..7) variables.read(destination, frame.address).toLong() else 0L
        val operation = flags ushr 8 and 15
        val assigned = if (operation == 8) abs(result.toDouble()) else WolfVariables.assign(previous, result.toDouble(), operation)
        val limit = if (flags and 1 != 0) 999_999.0 else 2_000_000_000.0
        variables.write(destination, assigned.coerceIn(-limit, limit).toInt(), frame.address)
    }

    /** 9a0f80: last matching active picture, or highest Z with the last picture winning a tie. */
    private fun continuePicturePositionQuery(query: PicturePositionQuery) {
        while (query.at < query.candidates.size) {
            val p = query.candidates[query.at]
            val size = pictureDimensions(p)
            if (size == null) {
                val request = nextHostRequestId++
                pendingPictureMeasures[request] = PendingPictureMeasure(query.invocation, query.destination, query.flags,
                    p, -1, variables.system[125] ?: 5, query)
                query.invocation.waitHost = true
                effects.add(NativeEffect.MeasurePicture(request, p, variables.system[125] ?: 5, p.fontOverrides))
                return
            }
            if (WolfPictureGeometry.contains(p, size.first, size.second, query.x, query.y, cameraX(), cameraY(),
                    game.config.engineCompatibilityVersion ?: 0) && (!query.maxZ || query.selectedZ <= p.z)) {
                query.selected = p.id; query.selectedZ = p.z
            }
            query.at++
        }
        assignState(query.invocation, query.destination, query.flags, query.selected)
    }

    private fun currentTileset(): WolfTileset? = mapChanges.tileset

    private fun mapTextureParameters(kind: WolfMapTextures.Kind): WolfMapTextures.Parameters {
        val speedField = if (kind == WolfMapTextures.Kind.FOG) 62 else 67
        return WolfMapTextures.Parameters(variables.system[speedField] ?: 0, variables.system[speedField + 1] ?: 0,
            engineFrameStep(), mapCamera.state.x, mapCamera.state.y,
            mapShake.offsetX(game.config.tileSize), mapShake.offsetY(game.config.tileSize))
    }

    private fun chipAt(x: Int, y: Int, layer: Int): Int =
        currentTileset()?.passageIndex(map.tile(x, y, layer))?.coerceAtLeast(0) ?: 0

    private fun usesWolf3ChipLayout() = (currentTileset()?.autoImages?.size ?: 0) >= 31

    /** a2e270: profiles before2.30 map the two additional W3 autotile rows out of variable indices. */
    private fun chipValue(propertyIndex: Int): Int = if (usesWolf3ChipLayout() &&
        (game.config.engineCompatibilityVersion ?: 0) < 230) {
        when { propertyIndex > 31 -> propertyIndex - 16; propertyIndex >= 16 -> -1; else -> propertyIndex }
    } else propertyIndex

    private fun chipOperand(raw: Int, address: EventAddress): Int {
        val wolf3 = usesWolf3ChipLayout()
        if (raw < 1_000_000) return WolfMapChanges.literalIndex(variables.read(raw, address), wolf3)
        val index = variables.read(raw, address)
        return if (wolf3 && (game.config.engineCompatibilityVersion ?: 0) < 230 && index > 15) Math.addExact(index, 16) else index
    }

    private fun changeChip(frame: Invocation, c: WolfCommand) {
        if (c.arguments[0] == -1_000_000) mapChanges.resetPassage()
        else mapChanges.passage(chipOperand(c.arguments[0], frame.address), c.arguments[1])
        macroTileGrid = null
    }

    private fun overwriteChips(frame: Invocation, c: WolfCommand) {
        fun value(index: Int) = variables.read(c.arguments[index], frame.address)
        mapChanges.rectangle(value(0) - 1, value(1), value(2), value(3), value(4), chipOperand(c.arguments[5], frame.address))
        map = mapChanges.map; macroTileGrid = null
    }

    private fun pictureMeasureKey(picture: NativePicture, fontWeight: Int): PictureMeasureKey = picture.text?.let {
        TextMeasureKey(it, picture.fontSize, picture.fontColor, picture.rubySize, picture.shadowOffset,
            picture.fontOverrides, picture.textTiming?.convertSpecial ?: true, fontWeight)
    } ?: BitmapMeasureKey(picture, fontWeight)

    private fun textProgramKey(picture: NativePicture): TextProgramKey {
        val timing = requireNotNull(picture.textTiming) { "Horloge de texte absente" }
        return TextProgramKey(picture.id, requireNotNull(picture.text) { "Horloge sans texte" }, timing.encoding,
            timing.compatibilityVersion, timing.convertSpecial)
    }

    private fun textPictureProgram(picture: NativePicture): WolfTextPictureTiming.Program =
        textPicturePrograms.getOrPut(textProgramKey(picture)) { picture.textTiming!!.program(picture.text!!) }

    private fun forgetTextPicture(id: Int, keep: TextProgramKey? = null) {
        textPicturePrograms.keys.removeAll { it.id == id && it != keep }
    }

    private fun initialTextPicture(picture: NativePicture): NativePicture {
        if (picture.text == null) return picture
        val metadata = NativeTextPictureTiming(game.config.encoding,
            game.config.engineCompatibilityVersion ?: error("Profil de texte absent de Game.dat"),
            variables.system[150] != 1, WolfTextPictureTiming.State())
        val program = textPictureProgram(picture.copy(textTiming = metadata))
        val speed = variables.system[11] ?: 1_000_000 // Constructors4798c0/959290 initialize Sys11, before SHOW.
        val layout = WolfTextPictureTiming.layout(program, WolfTextPictureTiming.initial(program, speed), speed)
        return picture.copy(textTiming = metadata.copy(state = layout.state), visibleCharacters = layout.visibleGlyphCount)
    }

    /** Clock compilation happens at SHOW/restore. Geometry interpolation carries this clock unchanged. */
    private fun updateTextPictures() {
        val speed = variables.system[11] ?: 1_000_000
        pictures.entries.forEach { entry ->
            val picture = entry.value
            val timing = picture.textTiming ?: return@forEach
            val layout = WolfTextPictureTiming.advance(textPictureProgram(picture), timing.state, speed, engineFrameStep())
            val metadata = timing.copy(state = layout.state)
            entry.setValue(picture.copy(textTiming = metadata, visibleCharacters = layout.visibleGlyphCount))
            tweens[picture.id]?.let { tween -> tweens[picture.id] = tween.copy(
                to = tween.to.copy(textTiming = metadata, visibleCharacters = layout.visibleGlyphCount)) }
        }
    }

    private fun pictureDimensions(picture: NativePicture): Pair<Int, Int>? {
        picture.video?.let {
            if (it.width > 0 && it.height > 0) return it.width / picture.divisionsX to it.height / picture.divisionsY
            return null
        }
        if (picture.kind in setOf(3, 4)) return picture.width.toInt() to picture.height.toInt()
        if (picture.text != null) return pictureMeasures[pictureMeasureKey(picture, variables.system[125] ?: 5)]
        picture.sourceRect?.let { return it.width.toInt() to it.height.toInt() }
        val path = picture.path ?: error("Image sans contenu")
        if (path.startsWith('<')) return picture.width.toInt() to picture.height.toInt()
        val intrinsic = imageMeasures.getOrPut(path) {
            val root = WolfParser.rootAssetPath(path)
            val resource = if (!root.endsWith(".wolfx", true) && source.exists("$root.wolfx")) "$root.wolfx" else root
            val raw = source.read(resource)
            val bytes = if (WolfXResource.isEncrypted(raw)) WolfXResource.decode(raw) else raw
            WolfImageHeader.dimensions(bytes, path) ?: error("Format d'image non reconnu pour la mesure : $path")
        }
        require(picture.divisionsX > 0 && picture.divisionsY > 0) { "Division d'image invalide" }
        return intrinsic.first / picture.divisionsX to intrinsic.second / picture.divisionsY
    }

    /** W2 FUN_00491150: bitmap/text size at 100%; easy windows return their scaled dimensions. */
    private fun pictureState(p: NativePicture, field: Int, dimensions: Pair<Int, Int>?): Int {
        val width = dimensions?.first ?: 0
        val height = dimensions?.second ?: 0
        val sx = (p.scaleX * 100).toInt(); val sy = (p.scaleY * 100).toInt()
        val x = p.x.toInt() - if (p.screenRelative) 0 else cameraX().toInt()
        val y = p.y.toInt() - if (p.screenRelative) 0 else cameraY().toInt()
        return when (field) {
            0 -> x; 1 -> y
            2 -> if (p.kind in setOf(3, 4)) width * sx / 100 else width
            3 -> if (p.kind in setOf(3, 4)) height * sy / 100 else height
            4 -> p.pattern; 5 -> p.opacity; 6 -> (p.rotation * 10).toInt()
            7 -> if (sx == 100) sy else sx
            8 -> {
                require(dimensions != null) { "Dimensions requises pour tester l'image" }
                val w = width * sx / 100f; val h = height * sy / 100f
                val left = x - when (p.anchor) { 1 -> w / 2; 3, 4 -> w; 5, 6 -> w / 2; else -> 0f }
                val top = y - when (p.anchor) { 1 -> h / 2; 2, 4, 6 -> h; else -> 0f }
                val angle = -p.rotation * Math.PI / 180.0
                val dx = pointerX - x; val dy = pointerY - y
                val mx = x + dx * kotlin.math.cos(angle) - dy * kotlin.math.sin(angle)
                val my = y + dx * kotlin.math.sin(angle) + dy * kotlin.math.cos(angle)
                if (mx >= left && mx <= left + w && my >= top && my <= top + h) 1 else 0
            }
            9 -> 1 // Existence/display state is independent of opacity, including preloaded transparent pictures.
            10 -> if (p.text == null) -1 else p.textTiming?.let { timing ->
                WolfTextPictureTiming.status(textPictureProgram(p), timing.state, engineFrameStep())
            } ?: 1 // Older saves contain an already rendered bitmap, without a reveal clock.
            11 -> sx; 12 -> sy
            21 -> p.video?.let { if (!it.playing && it.durationMs > 0 && it.durationMs - it.positionMs < 25) it.durationMs else it.positionMs }?.toInt() ?: 0
            22 -> p.video?.durationMs?.toInt() ?: 0
            else -> error("Champ d'image non porté : $field")
        }
    }

    private fun tileTag(x: Int, y: Int): Int {
        val tileset = currentTileset() ?: return 0
        return (map.layerCount - 1 downTo 0).map { tileset.tagForTile(map.tile(tileMapX(x), tileMapY(y), it)) }.firstOrNull { it != 0 } ?: 0
    }

    private fun tilePassable(x: Int, y: Int): Boolean {
        if (x !in 0 until map.width || y !in 0 until map.height) return false
        val tileset = currentTileset() ?: return true
        for (layer in 0 until map.layerCount) {
            val tile = map.tile(x, y, layer)
            if (tile < 0) continue
            val passage = tileset.passageForTile(tile)
            if (passage and 0x200 != 0 || passage and 0x10 != 0) continue
            return passage and 15 != 15
        }
        return true
    }

    /** W2 FUN_0044f160. Low passage bits on a quarter tile select quadrants, not directions. */
    private fun quarterPassable(hx: Int, hy: Int): Boolean {
        if (!map.loopX && hx !in 0 until map.width * 2 || !map.loopY && hy !in 0 until map.height * 2) return false
        val x = halfMapX(hx); val y = halfMapY(hy)
        val tileset = currentTileset() ?: return false
        for (layer in map.layerCount - 1 downTo 0) {
            val raw = map.tile(x / 2, y / 2, layer)
            if (raw < 0 || tileset.passageIndex(raw) == 0) continue
            val f = tileset.passageForTile(raw)
            if (f and 15 == 15) return false
            if (f and 0x200 != 0) continue
            if (f and 0x20 != 0) {
                val quadrant = if (x and 1 == 0) if (y and 1 == 0) 8 else 2 else if (y and 1 == 0) 4 else 1
                return f and quadrant == 0
            }
            if (f and 0x10 == 0) return true
        }
        return true
    }

    /** Original FUN_0045fe40 changes only the selected keys, with independent controller/reader masks. */
    private fun blockInput(frame: Invocation, c: WolfCommand) {
        val flags = c.args[0]
        when (flags ushr 28) {
            0 -> basicKeyFlags.forEach { (bit, key) -> if (flags and bit != 0) {
                if (flags and 0x300 != 0) movementBannedKeys.add(key) else movementBannedKeys.remove(key)
                if (flags and 0x200 != 0) bannedKeys.add(key) else bannedKeys.remove(key)
            } }
            1 -> when (flags and 15) {
                0 -> {
                    val code = c.args.getOrElse(1) { error("Masque clavier sans touche") }
                    val scans = if (code == -1) 0..255 else {
                        val scan = (variables.read(code, frame.address) - 100).coerceIn(0, 255)
                        scan..scan
                    }
                    scans.forEach { variables.system[-2_000_000 - it] = if (flags and 0x100 != 0) 1 else 0 }
                }
                1 -> mouseLeftBanned = flags and 0x100 != 0
                2 -> variables.system[-2_100_000] = if (flags and 0x100 != 0) 1 else 0
                3 -> {
                    val blocked = if (flags and 0x100 != 0) 1 else 0
                    (0..255).forEach { variables.system[-2_000_000 - it] = blocked }
                    mouseLeftBanned = blocked == 1; variables.system[-2_100_000] = blocked
                }
                else -> error("Masque de périphérique Wolf non porté : $flags")
            }
        }
    }

    /** The original automatic input is applied on the NEXT frame, never held indefinitely. */
    private fun injectInput(frame: Invocation, c: WolfCommand) {
        val flags = c.args[0]
        when (flags ushr 28) {
            0 -> {
                basicKeyFlags.forEach { (bit, key) -> if (flags and bit != 0) nextAutomaticKeys += key }
            }
            1 -> {
                val code = variables.read(c.args[1], frame.address)
                require(code in 100..355) { "Code de clavier automatique invalide : $code" }
                nextAutomaticScanKeys += code - 100
            }
            2 -> {
                require(flags and 0x16 == 0) { "Entrée automatique bouton souris droit/milieu/molette non portée" }
                val x = if (flags and 8 != 0) variables.read(c.args[1], frame.address).toFloat() else pointerX
                val y = if (flags and 8 != 0) variables.read(c.args[2], frame.address).toFloat() else pointerY
                nextAutomaticPointer = Triple(x, y, flags and 1 != 0)
            }
            else -> error("Périphérique d'entrée automatique inconnu")
        }
    }

    private fun scanForKey(key: NativeKey): Int? = when (key) {
        NativeKey.ACCEPT -> 28; NativeKey.BACK -> 1; NativeKey.SHIFT -> 42; NativeKey.CONTROL -> 29
        NativeKey.SPACE -> 57; NativeKey.LEFT -> 203; NativeKey.UP -> 200; NativeKey.RIGHT -> 205; NativeKey.DOWN -> 208
        NativeKey.A -> 30; NativeKey.C -> 46; NativeKey.D -> 32; NativeKey.S -> 31; NativeKey.W -> 17; NativeKey.X -> 45; NativeKey.Z -> 44
        NativeKey.F1 -> 59; NativeKey.F5 -> 63; NativeKey.F12 -> 88
    }
    private fun keyForScan(scan: Int): NativeKey? {
        if (WolfControlBindings.keyboard(variables.system, 52) == scan + 100) return NativeKey.ACCEPT
        if (WolfControlBindings.keyboard(variables.system, 53) == scan + 100) return NativeKey.BACK
        if (WolfControlBindings.keyboard(variables.system, 54) == scan + 100) return NativeKey.SHIFT
        return when (scan) {
            28, 156, 57 -> NativeKey.ACCEPT; 1, 14, 211 -> NativeKey.BACK; 42, 54 -> NativeKey.SHIFT; 29, 157 -> NativeKey.CONTROL
            203 -> NativeKey.LEFT; 200 -> NativeKey.UP; 205 -> NativeKey.RIGHT; 208 -> NativeKey.DOWN
            30 -> NativeKey.A; 46 -> NativeKey.C; 32 -> NativeKey.D; 31 -> NativeKey.S; 17 -> NativeKey.W; 45 -> NativeKey.X; 44 -> NativeKey.Z
            59 -> NativeKey.F1; 63 -> NativeKey.F5; 88 -> NativeKey.F12
            else -> null
        }
    }

    private fun call(execution: Execution, frame: Invocation, c: WolfCommand) {
        val targetCode = if (c.opcode == 300) {
            val name = text(c.strings.firstOrNull().orEmpty(), frame.address)
            game.commonEvents.lastOrNull { it.name == name }?.id?.plus(500_000) ?: return // Defined missing-name behavior.
        } else variables.read(c.args[0], frame.address)
        val target = if (targetCode >= 600_000) 500_000 + (frame.address.common + targetCode - 600_100).coerceIn(0, game.commonEvents.lastIndex) else targetCode
        if (target !in 500_000..599_999) {
            val mapEvent = map.events.firstOrNull { it.id == if (target == -1) frame.address.event else target } ?: return
            val pageIndex = variables.read(c.args.getOrElse(1) { -1 }, frame.address)
            val page = if (pageIndex in -1..0) activePage(mapEvent) else mapEvent.pages.getOrNull(pageIndex - 1)
            if (page == null) return
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
        if (c.opcode == 211) { reserved.clear(); reserved.add(CallRequest(common.id, numbers, stringArgs, frame.address)); return }
        val returnTarget = if (flags and 0x01000000 != 0) c.args.last() else null
        require(execution.stack.size < 256) { "Profondeur d'appel dépassée" }
        execution.stack.addLast(invocation(common, frame.address.copy(common = common.id), numbers, stringArgs, returnTarget))
    }

    private fun common(id: Int) = game.commonEvents.firstOrNull { it.id == id } ?: error("Commun absent : $id")

    private fun party(frame: Invocation, c: WolfCommand) {
        val flags = c.args[0]
        val members = listOf(player) + companions
        val action = flags and 15
        fun path(): String {
            val raw = if (flags and 0x100 == 0) c.strings.firstOrNull().orEmpty() else {
                val code = c.args.getOrElse(2) { error("Chemin variable de partie absent") }
                val isText = code in 3_000_000..3_999_999 || code in 9_900_000..9_999_999 || (code in 1_600_000..1_699_999 && code % 100 in 5..9)
                variables.readString(if (isText) code else 3_000_000 + variables.read(code, frame.address), frame.address)
            }
            if (raw.isNotBlank()) asset(raw)
            return raw
        }
        when (action) {
            0, 1, 2 -> {
                val index = variables.read(c.args.getOrElse(1) { error("Index de partie absent") }, frame.address) - 1
                require(index in members.indices) { "Index de partie hors limite : ${index + 1}" }
                when (action) {
                    0 -> members[index].path = ""
                    1 -> { for (i in members.lastIndex downTo index + 1) members[i].path = members[i - 1].path; members[index].path = path() }
                    2 -> members[index].path = path()
                }
            }
            3 -> { val file = path(); members.filter { it.path == file }.forEach { it.path = "" } }
            4 -> when (flags ushr 4 and 15) {
                0 -> { val paths = members.map { it.path }.filter { it.isNotBlank() }; members.forEachIndexed { i, member -> member.path = paths.getOrElse(i) { "" } } }
                1 -> members.forEach { it.path = "" }
                2 -> { companions.forEach { it.x = player.x; it.y = player.y; it.targetX = player.x; it.targetY = player.y; it.moveTicks = 0 }; partyTrail.clear() }
                3 -> partySync = true
                4 -> partySync = false
                5 -> members.forEach { it.opacity = 0 }
                6 -> members.forEach { it.opacity = 255 }
                7 -> rememberedParty = members.map { it.path }
                8 -> members.forEachIndexed { i, member -> member.path = rememberedParty.getOrElse(i) { "" } }
                9 -> partyFollow = true
                10 -> partyFollow = false
                else -> error("Opération spéciale de partie inconnue : $flags")
            }
            else -> error("Opération de partie inconnue : $action")
        }
    }

    private fun saveIdentity(code: Int, address: EventAddress): Pair<Int, String> {
        val isString = code in 3_000_000..3_999_999 || code in 9_900_000..9_999_999 ||
            code in 1_600_000..1_699_999 && address.common to (code % 100) in variables.commonStrings ||
            code in 15_000_000..15_999_999 && ((code - 15_000_000) / 100) to (code % 100) in variables.commonStrings
        if (isString) {
            val key = variables.readString(code, address)
            require(key.isNotBlank() && key.length <= 1024 && !key.startsWith('/') && key.split('/').all { it.isNotBlank() && it !in setOf(".", "..") } && key.none { it in "\\%:*?\"<>|" || it.code < 32 }) { "Nom de sauvegarde invalide : $key" }
            return 0 to key
        }
        val slot = variables.read(code, address)
        require(slot in 0..9999) { "Slot de sauvegarde non pris en charge : $slot" }
        return slot to "slot:$slot"
    }

    private fun database(frame: Invocation, c: WolfCommand) {
        val flags = c.args[3]
        val kind = when (flags ushr 8 and 15) {
            0 -> WolfDatabaseKind.MUTABLE; 1 -> WolfDatabaseKind.SYSTEM; 2 -> WolfDatabaseKind.USER
            else -> error("Type de DB non pris en charge")
        }
        val database = game.databases[kind] ?: error("Base de données absente : $kind")
        require(flags and 0x70000 == 0 || c.strings.size >= 4) { "Noms DB incomplets" }
        val type = if (flags and 0x10000 != 0) {
            val name = text(c.strings.getOrElse(1) { "" }, frame.address)
            database.types.firstOrNull { it.name == name } ?: error("Type DB nommé absent : $name")
        } else database.types.getOrNull(variables.read(c.args[0], frame.address)) ?: error("Type DB absent")
        val row = if (flags and 0x20000 != 0) {
            val name = text(c.strings.getOrElse(2) { "" }, frame.address)
            variables.namedRow(kind, type.id, name).also { require(it >= 0) { "Rangée DB nommée absente : $name" } }
        } else variables.read(c.args[1], frame.address)
        val field = if (flags and 0x40000 != 0) {
            val name = text(c.strings.getOrElse(3) { "" }, frame.address)
            type.fields.indexOfFirst { it.name == name }.also { require(it >= 0) { "Champ DB nommé absent : $name" } }
        } else variables.read(c.args[2], frame.address)
        val read = flags and 0xf000 == 0x1000
        require(flags and 0xf000 in setOf(0, 0x1000)) { "Mode DB non porté : ${flags and 0xf000}" }
        val assignment = flags ushr 4 and 15
        val target = c.args.getOrElse(4) { 0 }.let { if (flags and 15 == 1) variables.read(it, frame.address) else it }
        if (read && (row == -3 || field == -3)) {
            require(assignment in 0..1) { "Affectation de nom DB non prise en charge" }
            val name = when {
                row == -3 && field == -3 -> type.name
                field == -3 -> variables.rowName(kind, type.id, row)
                else -> type.fields.getOrNull(field)?.name ?: error("Nom de champ DB absent")
            }
            variables.writeString(target, (if (assignment == 1) variables.readString(target, frame.address) else "") + name, frame.address)
            return
        }
        if ((row == -1 || field == -1) && read) {
            val count = if (field == -1) type.fields.size else variables.rowCount(kind, type.id) - if (kind == WolfDatabaseKind.SYSTEM && type.id in 1..3) 10 else 0
            val previous = if (assignment in 1..7) variables.read(target, frame.address).toLong() else 0L
            val result = WolfVariables.assign(previous, count.toDouble(), assignment).toInt()
            variables.write(target, result, frame.address)
            return
        }
        if (!read && (row == -2 || field == -2)) {
            require(kind == WolfDatabaseKind.MUTABLE) { "Réinitialisation de DB immuable interdite" }
            if (row >= 0) variables.grow(type.id, row + 1)
            val rows = if (row == -2) 0 until variables.rowCount(kind, type.id) else row..row
            require(rows.all { it >= 0 && it < variables.rowCount(kind, type.id) }) { "Rangée de réinitialisation DB absente" }
            rows.forEach { resetRow -> type.fields.forEachIndexed { index, descriptor ->
                if (descriptor.valueType == WolfValueType.NUMBER) variables.setDatabaseNumber(type.id, resetRow, index, descriptor.defaultValue)
                else variables.setDatabaseString(type.id, resetRow, index, "")
            } }
            return
        }
        val descriptor = type.fields.getOrNull(field) ?: error("Champ DB absent : ${type.id}/$field")
        if (kind == WolfDatabaseKind.MUTABLE) variables.grow(type.id, row + 1)
        require(row in 0 until variables.rowCount(kind, type.id)) { "Rangée DB absente : ${type.id}/$row" }
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
            val previous = if (assignment !in 1..7) 0 else if (read) variables.read(target, frame.address) else variables.databaseNumber(kind, type.id, row, field)
            val result = WolfVariables.assign(previous.toLong(), value.toDouble(), assignment).coerceIn(-2_000_000_000.0, 2_000_000_000.0).toInt()
            if (read) variables.write(target, result, frame.address)
            else {
                require(kind == WolfDatabaseKind.MUTABLE) { "Écriture interdite sur DB immuable" }
                variables.setDatabaseNumber(type.id, row, field, result)
            }
        }
    }
    private fun modernDatabaseCsv(): Boolean = (game.config.engineCompatibilityVersion ?: 0) >= 300

    private fun databaseFile(frame: Invocation, c: WolfCommand) {
        val flags = c.args[3]
        val kind = when (flags ushr 8 and 15) { 0 -> WolfDatabaseKind.MUTABLE; 1 -> WolfDatabaseKind.SYSTEM; 2 -> WolfDatabaseKind.USER; else -> error("Famille CSV DB inconnue") }
        val database = game.databases[kind] ?: error("DB CSV absente")
        val type = if (flags and 0x10000 != 0) database.types.firstOrNull { it.name == text(c.strings[1], frame.address) }
            else database.types.getOrNull(variables.read(c.args[0], frame.address))
        require(type != null) { "Type CSV DB absent" }
        val start = if (flags and 0x20000 != 0) variables.namedRow(kind, type.id, text(c.strings[2], frame.address))
            else variables.read(c.args[1], frame.address)
        val count = variables.read(c.args[4], frame.address)
        require(start in 0..10_000 && count in 0..10_000) { "Plage CSV DB hors capacité" }
        if (count == 0) return // Native 46b2f0/a25130 does not touch a file for an empty request.
        val writing = flags and 0xf000 == 0x1000
        val path = WolfFileText.path(text(c.strings[0], frame.address), writing)
        require(writing || !path.endsWith(".wolfx", true) && !source.exists("$path.wolfx")) {
            "CSV WOLFX non pris en charge : $path ; l'alias chiffré ne peut pas être remplacé par le fichier clair"
        }
        val amount = if (writing) minOf(count, maxOf(0, variables.rowCount(kind, type.id) - start)) else count
        val output = if (writing) WolfDatabaseCsv.write(type, start, amount, modernDatabaseCsv(), game.config.engineCompatibilityVersion ?: 0,
            { row, field -> variables.databaseNumber(kind, type.id, row, field) },
            { row, field -> variables.databaseString(kind, type.id, row, field) },
            { row -> variables.rowName(kind, type.id, row) }) else ""
        val id = nextHostRequestId++
        pendingDatabaseFiles[id] = PendingDatabaseFile(frame, kind, type.id, start, count, writing)
        frame.waitHost = true
        effects.add(NativeEffect.FileOperation(id, if (writing) 6 else 5, path, output, modernText = modernDatabaseCsv(), windowsTextMode = false))
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
            // CommonEvent's return field is a CommonSelf INDEX, not a variable value code.
            // A disabled return (-1) leaves the caller's destination alone.
            val index = common.returnValue ?: -1
            if (index >= 0) {
                require(index < 100) { "Index de retour de commun invalide : $index" }
                val result = variables.read(1_600_000 + index, finished.address)
                variables.write(finished.returnDestination, result, execution.stack.last().address)
            }
        }
    }

    private fun teleport(frame: Invocation, c: WolfCommand) {
        require(c.args.size >= 5) { "Téléportation incomplète" }
        // W2 FUN_004601c0 distinguishes historical literal targets from reference values.
        val rawTarget = c.args[0]
        val target = when (rawTarget) { -1, -10_001 -> -2; -2 -> -1; else -> variables.read(rawTarget, frame.address) }
        val flags = c.args[4]
        require(flags and 0xffffffce.toInt() == 0 && flags ushr 4 and 15 in 0..2) { "Mode de téléportation non pris en charge" }
        val position = if (rawTarget == -10_001) variables.read(c.args[1], frame.address) else null
        val factor = if (flags and 1 != 0) .5f else 1f
        val x = if (position == null) variables.read(c.args[1], frame.address) * factor else variables.databaseNumber(WolfDatabaseKind.SYSTEM, 7, position, 1).toFloat()
        val y = if (position == null) variables.read(c.args[2], frame.address) * factor else variables.databaseNumber(WolfDatabaseKind.SYSTEM, 7, position, 2).toFloat()
        val destination = if (position != null) variables.databaseNumber(WolfDatabaseKind.SYSTEM, 7, position, 0)
            else if (rawTarget !in setOf(-1, -2) || c.args[3] == -1) mapId else variables.read(c.args[3], frame.address)
        val facing = c.args.getOrNull(5)?.let {
            require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Direction après transfert absente de ce profil Wolf" }
            variables.read(it, frame.address).takeIf { d -> d in 1..9 && d != 5 }
        }
        if (target == -2) {
            val transferred = destination != mapId
            if (destination != mapId) {
                val loaded = mapLoader(destination)
                require(x >= 0 && y >= 0 && x < loaded.width && y < loaded.height) { "Départ hors carte" }
                mapId = destination; map = loaded; mapChanges = WolfMapChanges(loaded, game.tilesets); mapEpoch++; actors.clear(); erasedEvents.clear()
                parallel.entries.removeAll { it.key.startsWith("map:") }
                variables.system.keys.removeAll { it in -1_999_999..-1_000_000 }
                characterEffects.state().filter { it.id >= 0 }.forEach { characterEffects.erase(it.id) }
                maskedCharacters.removeAll { it >= 0 }
                refreshActors()
            }
            player.x = x; player.y = y; player.targetX = player.x; player.targetY = player.y
            player.moveTicks = 0; player.pixelMotion = null; targetTile = null
            if (transferred) {
                mapCamera.reset(centredCameraX(), centredCameraY(), cameraMaxX(), cameraMaxY(), cameraLoopX(), cameraLoopY())
                applyMapAudio()
            }
            companions.forEach { it.x = x; it.y = y; it.targetX = x; it.targetY = y; it.moveTicks = 0; it.pixelMotion = null }; partyTrail.clear()
            if (facing != null) player.direction = facing
        } else {
            val actor = actor(target, frame.address); actor.x = x; actor.y = y
            actor.targetX = actor.x; actor.targetY = actor.y; actor.moveTicks = 0; actor.pixelMotion = null
            if (facing != null) actor.direction = facing
        }
    }

    private fun sound(frame: Invocation, c: WolfCommand) {
        val flags = c.args[0]
        val channel = flags ushr 4 and 15
        val operation = flags and 15
        if (operation == 3) { effects.add(NativeEffect.AudioCache(3)); return }
        val sourceMode = flags ushr 24 and 15
        val id = if (sourceMode == 1) variables.read(c.args.getOrElse(2) { -1 }, frame.address)
            else if (sourceMode == 15) -1 else (flags ushr 8 and 65535).let { if (it == 65535) -1 else it }
        if (sourceMode != 2 && id == -2) return
        val description = if (sourceMode == 2) text(c.strings.firstOrNull().orEmpty(), frame.address)
            else if (id < 0) "" else variables.databaseString(WolfDatabaseKind.SYSTEM, channel + 1, id, 0)
        requestAudio(channel, description,
            if (sourceMode == 2) variables.read(c.args.getOrElse(4) { 100 }, frame.address) else if (id < 0) 100 else variables.databaseNumber(WolfDatabaseKind.SYSTEM, channel + 1, id, 1),
            if (sourceMode == 2) variables.read(c.args.getOrElse(5) { 100 }, frame.address) else if (id < 0) 100 else variables.databaseNumber(WolfDatabaseKind.SYSTEM, channel + 1, id, 2),
            variables.read(c.args.getOrElse(1) { 0 }, frame.address), operation,
            position = variables.read(c.args.getOrElse(3) { 0 }, frame.address),
            loopStart = if (sourceMode == 2 && channel < 2) variables.read(c.args.getOrElse(6) { 0 }, frame.address) else 0,
            voice = when {
                channel == 2 -> variables.read(c.args.getOrElse(6) { -1 }, frame.address)
                channel == 1 && (game.config.engineCompatibilityVersion ?: 0) >= 350 -> variables.read(c.args.getOrElse(8) { 0 }, frame.address)
                else -> 0
            },
            pan = if ((game.config.engineCompatibilityVersion ?: 0) >= 350) variables.read(c.args.getOrElse(7) { 0 }, frame.address) else 0)
    }

    /** Route46 uses the same SE table and mixer as event140; -2 keeps the current sound. */
    private fun routeSound(id: Int) {
        if (id == -2) return
        requestAudio(2, if (id < 0) "" else variables.databaseString(WolfDatabaseKind.SYSTEM, 3, id, 0),
            if (id < 0) 100 else variables.databaseNumber(WolfDatabaseKind.SYSTEM, 3, id, 1),
            if (id < 0) 100 else variables.databaseNumber(WolfDatabaseKind.SYSTEM, 3, id, 2),
            0, voice = -1)
    }

    /** The native map loader 44e490 applies SysDB0 fields1/2 and optional descriptions5/6. */
    private fun applyMapAudio() {
        val settings = game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(0) ?: return
        if (settings.fields.size < 5 || mapId !in settings.rows.indices) return
        activeAudio.values.filter { it.channel == 1 && it.voice != 0 }.toList().forEach {
            activeAudio.remove(it.channel to it.voice)
            effects.add(it.copy(path = null, stop = true, fadeTicks = 5, position = 0))
        }
        for (channel in 0..1) {
            val id = settings.number(mapId, channel + 1) ?: error("Réglage audio de carte absent : $mapId/${channel + 1}")
            if (id == -2) continue
            val sounds = game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(channel + 1)
            val description = when {
                id == -3 && settings.fields.size > channel + 5 -> settings.string(mapId, channel + 5).orEmpty()
                id == -3 -> continue
                id < 0 -> ""
                else -> sounds?.string(id, 0) ?: error("Son de carte absent : ${channel + 1}/$id")
            }
            requestAudio(channel, text(description, EventAddress(mapId)),
                if (id < 0) 100 else sounds?.number(id, 1) ?: 100,
                if (id < 0) 100 else sounds?.number(id, 2) ?: 100, 5)
        }
    }

    private fun requestAudio(channel: Int, description: String, defaultVolume: Int, defaultPitch: Int, fadeTicks: Int,
        operation: Int = 0, position: Int = 0, loopStart: Int = 0, voice: Int = 0, pan: Int = 0) {
        if (description.trim() == "INITIALIZE") { resetAudio(); return }
        val stop = description.isBlank()
        val parameters = description.replace("\r\n", "\n").split('\n')
        val path = parameters.firstOrNull()?.takeIf { it.isNotBlank() }?.let(::asset)
        val zeroMode = game.config.byteSettings.getOrNull(39)?.toInt() ?: 0
        val legacyZero = (game.config.engineCompatibilityVersion ?: 0) < 350 || if (channel == 2) zeroMode != 0 else zeroMode == 1
        val volume = (parameters.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: defaultVolume).let { if (it == 0 && legacyZero) 100 else it }
        val pitch = (parameters.getOrNull(2)?.takeIf { it.isNotBlank() }?.toInt() ?: defaultPitch).let { if (it == 0) 100 else it }
        val loop = parameters.getOrNull(3)?.takeIf { it.isNotBlank() }?.toInt() ?: loopStart
        if (operation != 0) { if (!stop) effects.add(NativeEffect.AudioCache(operation, path)); return }
        require(volume in 0..100 && pitch in 25..400 && fadeTicks in 0..360_000 && position >= 0 &&
            loop in -127..Int.MAX_VALUE && voice in -1..999_999 && pan in -255..255) { "Paramètre audio hors capacité" }
        val actualVoice = if (!stop && voice == -1) 0 else voice
        val request = NativeEffect.Audio(channel, path, volume, pitch, channel != 2,
            if (channel == 2 && !stop) 0 else fadeTicks, stop, if (channel == 2) 0 else position,
            loop, actualVoice, pan, if (channel == 2 && !stop) fadeTicks else 0)
        val key = channel to actualVoice
        val midi = path?.substringAfterLast('.')?.lowercase() in setOf("mid", "midi")
        val sameTrack = !stop && channel in 0..1 && activeAudio[key]?.path == path &&
            (!midi || activeAudio[key]?.loopStart == loop)
        if (channel in 0..1) {
            if (stop) activeAudio.entries.removeAll { it.key.first == channel && (voice == -1 || it.key == key) } else {
                activeAudio[key] = request
                if (channel == 1) selectBgsVoice(actualVoice)
                variables.system[95 + channel * 2] = volume
                variables.system[96 + channel * 2] = pitch
                if (channel == 1) { bgsVolumes[actualVoice] = volume; bgsPitches[actualVoice] = pitch }
            }
        }
        effects.add(if (sameTrack) request.copy(path = null) else request)
    }

    private fun audioKey(channel: Int): Pair<Int, Int> = channel to if (channel == 1) variables.system[99] ?: 0 else 0

    private fun selectBgsVoice(voice: Int) {
        variables.system[99] = voice
        variables.system[97] = bgsVolumes[voice] ?: 0
        variables.system[98] = bgsPitches[voice] ?: if (voice == 0) 100 else 0
    }

    private fun resetAudio() {
        activeAudio.clear(); bgsVolumes.clear(); bgsPitches.clear()
        (95..99).forEach { variables.system.remove(it) }
        (0..2).forEach { effects.add(NativeEffect.Audio(it, stop = true, voice = -1)); effects.add(NativeEffect.AudioGain(it, 100)) }
        effects.add(NativeEffect.AudioCache(3))
    }

    private fun picture(frame: Invocation, c: WolfCommand) {
        fun value(i: Int, default: Int = 0) = variables.read(c.args.getOrElse(i) { default }, frame.address)
        val flags = c.args[0]; val action = flags and 15
        val first = value(1)
        val rangeIndex = when (action) { 0, 1 -> 14; 2 -> 5; 3 -> 2; else -> error("Opération de pic non prise en charge") }
        val last = if (flags and 0x1000000 != 0) value(rangeIndex, first) else first
        require(kotlin.math.abs(last.toLong() - first) <= 10_000) { "Plage de pics hors capacité" }
        val ids = minOf(first, last)..maxOf(first, last)
        val duration = maxOf(0, value(2))
        if (action == 3) { delayedPictures.removeAll { it.id in ids }; return }
        val delay = maxOf(0, value(if (action == 2) 3 else 13))
        require(delay <= 360_000 && duration <= 360_000) { "Durée d'image hors capacité" }
        require(delay == 0 || delayedPictures.size + ids.count() <= 10_000) { "Trop d'opérations d'image différées" }
        for (id in ids) {
        if (action == 2) {
            if (delay > 0) delayedPictures.add(DelayedPicture(id, action, duration, null, delay))
            else applyPicture(id, action, duration, null)
        } else {
            val prior = pictures[id]
            // 004aa7d0 leaves the existing destination field untouched for the SAME sentinel.
            // Reading the interpolated bitmap here would cancel an in-progress fade to 255.
            val priorTarget = tweens[id]?.to ?: prior
            require(action in 0..1) { "Opération de pic non prise en charge : $action" }
            // The original ignores a move of a nonexistent picture; this is defined command behavior.
            if (action == 1 && prior == null) continue
            val kind = if (action == 1) prior!!.kind else flags ushr 4 and 15
            val content = if (action == 1) null else if (kind in setOf(1, 4)) variables.readString(c.args.getOrElse(11) { error("Variable du fichier de pic absente") }, frame.address)
                else c.strings.firstOrNull()?.let { if (kind == 2 && variables.system[150] == 1) it.replace("\r\n", "\n").replace("\r", "\n") else text(it, frame.address) }.orEmpty()
            val scaleMode = flags ushr 20 and 15
            val sx = when (scaleMode) { 0, 2, 3 -> value(9, 100) / 100f; 1 -> 1f; 4 -> priorTarget?.scaleX ?: 1f; else -> error("Mode de zoom non pris en charge") }
            val sy = when (scaleMode) { 0, 1 -> value(9, 100) / 100f; 2 -> 1f; 3 -> value(18, 100) / 100f; 4 -> priorTarget?.scaleY ?: 1f; else -> error("Mode de zoom non pris en charge") }
            fun same(i: Int, previous: Float, default: Int = 0) = value(i, default).let { if (it == -1_000_000) previous else it.toFloat() }
            fun coordinate(index: Int, previousTarget: Float, previousPosition: Float) = value(index).let {
                if (it == -1_000_000) previousTarget
                else it.toFloat() + if (flags and 0x02000000 != 0) previousPosition.toInt() else 0
            }
            val packedColor = c.args.getOrElse(12) { 0x646464 }
            val sameColor = packedColor and 0x1000000 != 0
            fun color(shift: Int, variableIndex: Int, previous: Float): Float = if (sameColor) previous else if (packedColor and 0x2000000 != 0) value(variableIndex, 100).coerceIn(0, 200) / 100f else (packedColor ushr shift and 255).coerceIn(0, 200) / 100f
            val blend = (flags ushr 8 and 15).let { if (it == 15) prior?.blend ?: 0 else it }
            val anchor = if (action == 1) prior!!.anchor else (flags ushr 12 and 15).let { if (it == 15) prior?.anchor ?: 0 else it }
            val path = if (kind == 2) null else if (action == 1) prior?.path else content!!.let { if (it.startsWith('<')) it else asset(it) }
            val picture = NativePicture(id,
                path = path,
                text = if (kind == 2) content?.also { if (variables.system[150] != 1) textPresentationProblem(it)?.let { issue -> error(issue) } } ?: prior?.text else null,
                x = coordinate(7, priorTarget?.x ?: 0f, prior?.x ?: 0f), y = coordinate(8, priorTarget?.y ?: 0f, prior?.y ?: 0f),
                width = if (kind in setOf(3, 4)) if (action == 1) prior!!.width else value(3).toFloat() else 0f,
                height = if (kind in setOf(3, 4)) if (action == 1) prior!!.height else value(4).toFloat() else 0f,
                scaleX = sx, scaleY = sy, opacity = same(6, (priorTarget?.opacity ?: 255).toFloat(), 255).toInt().coerceIn(0, 255),
                rotation = if (kind in setOf(3, 4)) 0f else same(10, (priorTarget?.rotation ?: 0f) * 10) / 10, blend = blend,
                z = prior?.z ?: 0, color = -1,
                anchor = anchor, fontSize = if (action == 1) prior!!.fontSize else (variables.system[8] ?: 16).toFloat(),
                screenRelative = flags and 0xf0000 != 0x10000,
                kind = kind, divisionsX = if (action == 1) prior!!.divisionsX else if (kind in 0..1) value(3, 1).coerceAtLeast(1) else 1,
                divisionsY = if (action == 1) prior!!.divisionsY else if (kind in 0..1) value(4, 1).coerceAtLeast(1) else 1,
                pattern = same(5, (priorTarget?.pattern ?: 1).toFloat(), 1).toInt(),
                colorR = color(16, 15, priorTarget?.colorR ?: 1f), colorG = color(8, 16, priorTarget?.colorG ?: 1f), colorB = color(0, 17, priorTarget?.colorB ?: 1f),
                rubySize = if (action == 1) prior!!.rubySize else (variables.system[23] ?: ((variables.system[8] ?: 16) / 2)).toFloat(),
                shadowOffset = if (action == 1) prior!!.shadowOffset else variables.system[21] ?: 0,
                fontOverrides = if (action == 1) prior!!.fontOverrides else fontOverrides(),
                textTiming = if (action == 1) prior!!.textTiming else null,
                visibleCharacters = if (action == 1) prior!!.visibleCharacters else Int.MAX_VALUE)
                .let { if (action == 0 && kind == 2) initialTextPicture(it) else it }
            if (delay > 0) delayedPictures.add(DelayedPicture(id, action, duration, picture, delay))
            else applyPicture(id, action, duration, picture)
        }
        }
    }

    private fun applyPicture(id: Int, action: Int, duration: Int, target: NativePicture?) {
        val prior = pictures[id]
        if (action == 2) {
            if (duration > 0 && prior != null) tweens[id] = PictureTween(prior, prior.copy(opacity = 0), duration, erase = true)
            else { pictures.remove(id); tweens.remove(id); pictureEffects.erase(id); extendedPictureEffects.erase(id); maskedPictures.remove(id); forgetTextPicture(id) }
            return
        }
        if (action == 1 && prior == null) return
        val video = if (action == 1) prior?.video else target?.path?.takeIf { target.kind in 0..1 && WolfVideo.isMovie(it) }
            ?.let { NativeVideoState(nextVideoToken++, loop = WolfVideo.loops(it)) }
        val to = requireNotNull(target).copy(imageFilters = if (action == 1) prior?.imageFilters.orEmpty() else emptyList(), video = video,
            textTiming = if (action == 1) prior?.textTiming else target.textTiming,
            visibleCharacters = if (action == 1) prior!!.visibleCharacters else target.visibleCharacters)
        if (action == 0) {
            val key = to.textTiming?.let { textProgramKey(to) }
            forgetTextPicture(id, key)
            if (key != null) textPictureProgram(to)
        }
        val from = if (action == 0) prior?.copy(imageFilters = emptyList(), video = video) else prior
        if (duration > 0 && from != null) {
            pictures[id] = to.copy(x = from.x, y = from.y, scaleX = from.scaleX, scaleY = from.scaleY,
                opacity = from.opacity, rotation = from.rotation, colorR = from.colorR, colorG = from.colorG, colorB = from.colorB)
            tweens[id] = PictureTween(from, to, duration)
        }
        else if (duration > 0) { pictures[id] = to.copy(opacity = 0); tweens[id] = PictureTween(to.copy(opacity = 0), to, duration) }
        else { pictures[id] = to; tweens.remove(id) }
    }

    private fun updatePictures() {
        val finished = mutableListOf<Int>()
        tweens.forEach { (id, tween) ->
            val progress = (++tween.elapsed).toFloat() / tween.length
            fun lerp(a: Float, b: Float) = a + (b - a) * progress
            pictures[id] = tween.to.copy(x = lerp(tween.from.x, tween.to.x), y = lerp(tween.from.y, tween.to.y),
                scaleX = lerp(tween.from.scaleX, tween.to.scaleX), scaleY = lerp(tween.from.scaleY, tween.to.scaleY),
                opacity = lerp(tween.from.opacity.toFloat(), tween.to.opacity.toFloat()).toInt(),
                rotation = lerp(tween.from.rotation, tween.to.rotation),
                colorR = lerp(tween.from.colorR, tween.to.colorR), colorG = lerp(tween.from.colorG, tween.to.colorG), colorB = lerp(tween.from.colorB, tween.to.colorB))
            if (tween.elapsed >= tween.length) { if (tween.erase) { pictures.remove(id); pictureEffects.erase(id); extendedPictureEffects.erase(id); maskedPictures.remove(id); forgetTextPicture(id) }; finished += id }
        }
        finished.forEach { tweens.remove(it) }
        val ready = delayedPictures.filter {
            if (it.action == 4) {
                if (it.remaining < 1) true else { it.remaining = (it.remaining - maxOf(1, 60 / fps)).coerceAtLeast(0); false }
            } else --it.remaining <= 0
        }
        delayedPictures.removeAll(ready.toSet())
        ready.forEach {
            if (it.action == 4) applyPictureEffect(it.id, it.effectOperation, it.p1, it.p2, it.p3, it.duration)
            else applyPicture(it.id, it.action, it.duration, it.target)
        }
    }

    /** W3 adjusts each picture150 endpoint before its interpolation, without altering saved base data. */
    private fun renderPicture(base: NativePicture): NativePicture {
        val tween = tweens[base.id]
        val adjusted = if (tween == null) extendedPictureEffects.endpoints(base) else {
            val from = extendedPictureEffects.endpoints(tween.from)
            val to = extendedPictureEffects.endpoints(tween.to)
            val progress = tween.elapsed.toFloat() / tween.length
            fun lerp(a: Float, b: Float) = a + (b - a) * progress
            base.copy(scaleX = lerp(from.scaleX, to.scaleX), scaleY = lerp(from.scaleY, to.scaleY),
                rotation = lerp(from.rotation, to.rotation), opacity = lerp(from.opacity.toFloat(), to.opacity.toFloat()).toInt())
        }
        return pictureEffects.picture(adjusted, extendedPictureEffects::flash, extendedPictureEffects::preTransform).copy(maskEnabled = base.id in maskedPictures)
    }

    private fun updateDialogInput() {
        val current = dialog ?: return
        fun readerPress(key: NativeKey) = key in newKeys + logicalPhysicalState(true) && key !in bannedKeys
        if (current.choices.isEmpty()) { if (readerPress(NativeKey.ACCEPT) || readerPress(NativeKey.BACK)) advanceDialog(); return }
        if (forceChoiceFinish) {
            // 973b50 closes the selected branch, unless packed option4 requests label102.
            val defaultBranch = (dialogOwner?.command?.args?.getOrElse(0) { 0 } ?: 0) and 0x400 != 0
            finishChoice(if (defaultBranch) 100 else current.selectedChoice, playSound = false)
            return
        }
        var selected = current.selectedChoice
        if (readerPress(NativeKey.UP)) selected = (selected - 1 + current.choices.size) % current.choices.size
        if (readerPress(NativeKey.DOWN)) selected = (selected + 1) % current.choices.size
        if (selected != current.selectedChoice) playSystemSound(18)
        dialog = current.copy(selectedChoice = selected)
        if (readerPress(NativeKey.ACCEPT)) choose(selected)
        if (readerPress(NativeKey.BACK)) {
            val owner = dialogOwner ?: return
            val original = owner.command ?: return
            if (original.args.getOrElse(0) { 0 } and 16 != 0) { owner.branches[original.indent] = 0; variables.system[7] = 0; choiceFinishedValue = -2; choiceFinishedTick = ticks; playSystemSound(19); dialog = null; dialogOwner = null }
        }
    }
    private fun advanceDialog() {
        val text = dialogText
        if (text != null && !text.complete) { text.advance(); return }
        dialog = null; dialogOwner = null; dialogText = null
    }

    private fun updateDialogText() {
        val text = dialogText ?: return
        text.tick { path, volume, pitch -> effects.add(NativeEffect.Audio(2, asset(path), volume, pitch)) }
        dialog = dialog?.copy(visibleCharacters = text.visible, waiting = text.waiting || text.complete)
        if (text.complete && text.autoClose) { dialog = null; dialogOwner = null; dialogText = null }
    }

    private fun playSystemSound(field: Int) {
        val id = variables.system[field] ?: return
        if (id < 0) return
        val type = game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(3) ?: error("Base des sons de choix absente")
        val path = type.string(id, 0) ?: error("Son de choix absent : $id")
        if (path.isNotBlank()) effects.add(NativeEffect.Audio(2, asset(path)))
    }

    private fun updateWorld() {
        characterEffects.tick()
        (listOf(player) + companions + actors.values).forEach { actor ->
            val jumping = actor.jumpRemaining > 0
            val pixelMoving = actor.pixelMotion?.moved == true
            val ordinaryBusy = !jumping && actor.moveTicks > 0
            val translating = jumping || ordinaryBusy || pixelMoving
            val wasWaiting = actor.routeWait > 0
            if (wasWaiting) actor.routeWait = maxOf(0, actor.routeWait - engineFrameStep())
            if (actor.jumpRemaining > 0) {
                actor.jumpRemaining = maxOf(0, actor.jumpRemaining - engineFrameStep())
                val remaining = actor.jumpRemaining
                val unit = game.config.tileSize * 4
                fun position(current: Float, target: Float) = ((current * unit).toLong() * remaining + (target * unit).toLong()) / (remaining + 1) / unit.toFloat()
                actor.x = position(actor.x, actor.targetX); actor.y = position(actor.y, actor.targetY)
                actor.moveTicks = (remaining + engineFrameStep() - 1) / engineFrameStep(); actor.moving = true
                if (remaining == 0) { actor.x = actor.targetX; actor.y = actor.targetY; actor.jumpPeak = 0; if (actor.id == -2) { updatePartyTrail(); triggerTouch() } }
            } else if (actor.moveTicks > 0) {
                val unit = game.config.tileSize * 4
                val result = WolfCharacterMotion.step((actor.x * unit).toInt(), (actor.y * unit).toInt(),
                    (actor.targetX * unit).toInt(), (actor.targetY * unit).toInt(),
                    WolfCharacterMotion.increment(game.config.wordSettings, actor.speed, actor.motion.alternateSpeed, engineFrameStep()),
                    (game.config.engineCompatibilityVersion ?: 0) >= 350,
                    variables.system[129] ?: 100, variables.system[130] ?: 100)
                actor.x = result.xQuarter / unit.toFloat(); actor.y = result.yQuarter / unit.toFloat()
                actor.moveTicks = if (result.arrived) 0 else 1; actor.moving = result.moved
                if (actor.moveTicks == 0) { actor.x = actor.targetX; actor.y = actor.targetY; if (actor.id == -2) { updatePartyTrail(); triggerTouch() } }
            } else actor.moving = pixelMoving
            actor.pattern = characterAnimation(actor).tick(actor.pattern, engineFrameStep(), translating, jumping,
                actor.flags and 2 != 0, actor.flags and 1 != 0,
                arrived = translating && actor.moveTicks == 0 && !jumping)
            actor.pixelMotion = actor.pixelMotion?.copy(moved = false, crossed = false)
            if (actor.moveTicks == 0 && actor.jumpRemaining == 0 && actor.routeWait == 0) {
                if (actor.routeForced) { if (!wasWaiting) updateRoute(actor) }
                else if (actor.motion.autonomousMode == 1 && WolfCharacterMotion.autonomousReady(actor.motion, characterAnimation(actor).state.idleTicks)) updateRoute(actor)
            }
        }
        if (dialog != null || player.moveTicks > 0 || (foreground != null && !allowMovement)) return
        val dx = if (down(NativeKey.LEFT)) -1 else if (down(NativeKey.RIGHT)) 1 else 0
        val dy = if (game.config.characterDirectionsMove == 4 && dx != 0) 0 else if (down(NativeKey.UP)) -1 else if (down(NativeKey.DOWN)) 1 else 0
        if (dx != 0 || dy != 0) { targetTile = null; move(player, dx, dy) }
        else targetTile?.let { target ->
            val path = gridPath(player, (logicalX(player) * 2).toInt(), (logicalY(player) * 2).toInt(), target.first * 2, target.second * 2,
                stepSize(player), includeEvents = true)?.firstOrNull()
            if (path == null) targetTile = null else move(player,
                mapDelta((logicalX(player) * 2).toInt(), path.first, map.width * 2, map.loopX).coerceIn(-1, 1),
                mapDelta((logicalY(player) * 2).toInt(), path.second, map.height * 2, map.loopY).coerceIn(-1, 1))
        }
        if (pressed(NativeKey.ACCEPT)) triggerAction()
    }

    private fun updatePartyTrail() {
        partyTrail.addFirst(Triple(player.x, player.y, player.direction))
        while (partyTrail.size > 4096) partyTrail.removeLast()
        if (!partyFollow || partySync) return
        val spacing = (variables.system[37] ?: 2).coerceIn(0, 512)
        companions.forEachIndexed { index, actor ->
            val position = partyTrail.elementAtOrNull((index + 1) * spacing) ?: return@forEachIndexed
            if (actor.path.isNotBlank() && actor.route == null) {
                actor.targetX = actor.x + mapDelta(actor.x, position.first, map.width, map.loopX)
                actor.targetY = actor.y + mapDelta(actor.y, position.second, map.height, map.loopY)
                actor.direction = position.third; actor.speed = player.speed
                actor.moveTicks = if (actor.x != actor.targetX || actor.y != actor.targetY) 1 else 0
            }
        }
    }

    private fun gridPath(actor: Actor, x: Int, y: Int, targetX: Int, targetY: Int, step: Int,
        includeEvents: Boolean, budget: Int = 4096, square: Boolean? = null): List<Pair<Int, Int>>? {
        if (!map.loopX && targetX !in 0 until map.width * 2 - 1 || !map.loopY && targetY !in 0 until map.height * 2) return null
        val origin = halfMapX(x) to halfMapY(y)
        val goal = halfMapX(targetX) to halfMapY(targetY)
        if (origin == goal) return emptyList()
        // Search nodes are half-grid positions; the same source/destination passage is used for movement.
        val queue = ArrayDeque<Pair<Int, Int>>()
        val previous = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
        queue.add(origin); previous[origin] = origin
        while (queue.isNotEmpty() && previous.size <= budget) {
            val point = queue.removeFirst()
            if (point == goal) {
                var cursor = point
                val result = mutableListOf<Pair<Int, Int>>()
                while (cursor != origin) { result += cursor; cursor = previous[cursor] ?: return null }
                result.reverse(); return result
            }
            val directions = if (game.config.characterDirectionsMove == 8) listOf(2, 4, 6, 8, 1, 3, 7, 9) else listOf(2, 4, 6, 8)
            directions.forEach { direction ->
                val delta = delta(direction)
                val next = halfMapX(point.first + delta.first * step) to halfMapY(point.second + delta.second * step)
                if (next !in previous && canMoveGrid(point.first, point.second, direction, actor, includeEvents, step, square)) { previous[next] = point; queue.add(next) }
            }
        }
        return null
    }

    /** W3 009fd7a0→009ef6a0 and the official route contract: map path, optional event detour, one-step variants. */
    private fun navigate(actor: Actor, opcode: Int, goalX: Int, goalY: Int): Boolean {
        val mode = opcode % 100 - 59
        val oneStep = opcode >= 100
        val hx = halfMapX((logicalX(actor) * 2).toInt()); val hy = halfMapY((logicalY(actor) * 2).toInt())
        if (hx == goalX * 2 && hy == goalY * 2) { actor.navigation = null; return true }
        if (goalX !in 0 until map.width || goalY !in 0 until map.height) { actor.navigation = null; return false }
        val budget = (500 + 4 * (abs(goalX - hx / 2) + abs(goalY - hy / 2)) + if (mode == 2) 15_000 else 0).coerceAtMost(65_536)
        var navigation = actor.navigation?.takeIf { it.goalX == goalX && it.goalY == goalY && it.mode == mode }
        if (navigation == null || navigation.waypoints.isEmpty()) {
            // Auto routes require full-square passage, even for a half-step character.
            val path = gridPath(actor, hx, hy, goalX * 2, goalY * 2, 2,
                includeEvents = mode == 2, budget = budget, square = true)
            if (path == null) { actor.navigation = null; return false }
            navigation = Navigation(goalX, goalY, mode, path.toMutableList()); actor.navigation = navigation
        }
        while (navigation.waypoints.firstOrNull() == (hx to hy)) navigation.waypoints.removeAt(0)
        var waypoint = navigation.waypoints.firstOrNull() ?: return true
        var dx = mapDelta(hx, waypoint.first, map.width * 2, map.loopX).coerceIn(-1, 1)
        var dy = mapDelta(hy, waypoint.second, map.height * 2, map.loopY).coerceIn(-1, 1)
        if (!canMoveGrid(hx, hy, direction(dx, dy), actor) && mode != 0) {
            val detourGoal = if (mode == 2) goalX * 2 to goalY * 2 else navigation.waypoints.getOrElse(minOf(3, navigation.waypoints.lastIndex)) { waypoint }
            val detour = gridPath(actor, hx, hy, detourGoal.first, detourGoal.second, stepSize(actor), true,
                if (mode == 2) budget else 128, square = true)
            if (detour.isNullOrEmpty()) return false
            val reconnect = navigation.waypoints.indexOf(detourGoal)
            if (reconnect >= 0) repeat(reconnect + 1) { navigation.waypoints.removeAt(0) }
            navigation.waypoints.addAll(0, detour)
            waypoint = detour.first()
            dx = mapDelta(hx, waypoint.first, map.width * 2, map.loopX).coerceIn(-1, 1)
            dy = mapDelta(hy, waypoint.second, map.height * 2, map.loopY).coerceIn(-1, 1)
        }
        if (!move(actor, dx, dy)) return false
        if (oneStep) { actor.navigation = null; return true }
        return false // Keep this route instruction active until its logical destination is reached.
    }

    /** W3 9ec3d0 and W2 48e590: one steering attempt, followed by the route dispatcher. */
    private fun approachPoint(actor: Actor, goalHalfX: Int, goalHalfY: Int): Boolean {
        val hx = halfMapX((logicalX(actor) * 2).toInt())
        val hy = halfMapY((logicalY(actor) * 2).toInt())
        val dx = WolfStraightRoute.delta(hx, goalHalfX, map.width * 2, map.loopX)
        val dy = WolfStraightRoute.delta(hy, goalHalfY, map.height * 2, map.loopY)
        // W3 advances a no-distance instruction directly; W2's dispatcher instead
        // waits for a movement or its explicit skip-blocked option (4519a0).
        if (dx == 0 && dy == 0) return (game.config.engineCompatibilityVersion ?: 0) >= 300
        if ((game.config.engineCompatibilityVersion ?: 0) > 357) actor.pixelMotion = actor.pixelMotion?.copy(
            offsetX = if (dx != 0) 0 else actor.pixelMotion!!.offsetX,
            offsetY = if (dy != 0) 0 else actor.pixelMotion!!.offsetY)
        val horizontalTie = if (abs(dx) == abs(dy) && game.config.characterDirectionsMove == 4) random(0, 1) == 0 else false
        for (candidate in WolfStraightRoute.candidates(dx, dy, game.config.characterDirectionsMove, horizontalTie)) {
            if (move(actor, candidate.first, candidate.second)) return true
        }
        return false
    }

    private fun approachActor(actor: Actor, targetId: Int): Boolean {
        if (targetId == -1) return (game.config.engineCompatibilityVersion ?: 0) >= 300
        val target = when (targetId) {
            -2 -> player
            in -7..-3 -> companions[-targetId - 3]
            else -> actors[targetId] ?: error("Cible de route53 absente : $targetId")
        }
        // 9ec3d0 intentionally tests the X residual for both target coordinates.
        val round = if ((target.pixelMotion?.offsetX ?: 0) >= game.config.tileSize / 4) 1 else 0
        return approachPoint(actor, (logicalX(target) * 2).toInt() + round, (logicalY(target) * 2).toInt() + round)
    }

    private fun engineFrameStep() = if (fps == 30) 2 else 1
    // W2 ctor444d20 and W3 ctor9e6740 both enable moving animation and disable idle animation.
    private fun initialCharacterFlags() = 2
    // W2 starts at pattern1 even for five patterns; its idle/base still use the centre.
    private fun initialCharacterPattern() = if ((game.config.engineCompatibilityVersion ?: 225) >= 350) game.config.animationPatterns / 2 else 1
    private fun newCharacterAnimation(frequency: Int = 2, idlePattern: Int = game.config.animationPatterns / 2,
        state: WolfCharacterAnimation.State? = null): WolfCharacterAnimation = WolfCharacterAnimation(
        game.config.animationPatterns,
        (game.config.engineCompatibilityVersion ?: 0) >= 350 && game.config.byteSettings.getOrNull(34)?.toInt()?.let { it != 0 } == true,
        game.config.engineCompatibilityVersion ?: 225,
        state ?: WolfCharacterAnimation.State(frequency, idlePattern, game.config.animationPatterns / 2))
    private fun characterAnimation(actor: Actor): WolfCharacterAnimation = actor.animation
        ?: newCharacterAnimation().also { actor.animation = it }
    private fun characterHeight(a: Actor): Int {
        val distance = abs(a.jumpRemaining - a.jumpPeak).toLong()
        val curve = if (a.jumpRemaining > 0) 3L * (a.jumpPeak.toLong() * a.jumpPeak - distance * distance) / maxOf(1, 160 / game.config.tileSize) else 0L
        return (a.elevation + curve).coerceIn(-1_000_000L, 1_000_000L).toInt()
    }

    /** W2 FUN_0048f400 sets logical destination now; FUN_00480e70 interpolates pixels on later ticks. */
    private fun jump(a: Actor, dx: Int, dy: Int): Boolean {
        val step = stepSize(a)
        val gx = (logicalX(a) * 2).toInt() + dx * step; val gy = (logicalY(a) * 2).toInt() + dy * step
        if (dx != 0 || dy != 0) {
            if (a.flags and 4 == 0) a.direction = if (abs(dx) > abs(dy)) if (dx < 0) 4 else 6 else if (dy < 0) 8 else 2
            if (!canMoveGrid(gx, gy, 0, a)) return false
        }
        val distance = kotlin.math.sqrt((dx.toLong() * dx + dy.toLong() * dy).toDouble()) * step
        a.jumpPeak = maxOf(1, (distance.toInt() + 15 - a.speed) / 2)
        require(a.jumpPeak <= 360_000) { "Saut hors capacité" }
        a.jumpRemaining = a.jumpPeak * 2; a.moveTicks = (a.jumpRemaining + engineFrameStep() - 1) / engineFrameStep()
        a.pixelMotion = a.pixelMotion?.copy(halfX = gx, halfY = gy)
        a.targetX = gx / 2f + (a.pixelMotion?.offsetX ?: 0) / game.config.tileSize.toFloat()
        a.targetY = gy / 2f + (a.pixelMotion?.offsetY ?: 0) / game.config.tileSize.toFloat(); a.moving = true
        return true
    }

    private fun move(actor: Actor, dx: Int, dy: Int, force: Boolean = false): Boolean {
        if (actor.flags and 4 == 0) actor.direction = direction(dx, dy)
        if (!force && !canMoveGrid((logicalX(actor) * 2).toInt(), (logicalY(actor) * 2).toInt(), direction(dx, dy), actor)) {
            if (actor.id == -2) triggerTouch(actor.x.toInt() + dx, actor.y.toInt() + dy)
            return false
        }
        val step = stepSize(actor) / 2f
        actor.pixelMotion = actor.pixelMotion?.let { it.copy(halfX = it.halfX + dx * stepSize(actor), halfY = it.halfY + dy * stepSize(actor)) }
        actor.targetX = actor.pixelMotion?.let { it.halfX / 2f + it.offsetX / game.config.tileSize.toFloat() } ?: (actor.x + dx * step)
        actor.targetY = actor.pixelMotion?.let { it.halfY / 2f + it.offsetY / game.config.tileSize.toFloat() } ?: (actor.y + dy * step)
        actor.moveTicks = if (actor.x != actor.targetX || actor.y != actor.targetY) 1 else 0
        actor.moving = true
        return true
    }

    private fun pixelMove(actor: Actor, dx: Int, dy: Int) {
        val tile = game.config.tileSize
        val before = actor.pixelMotion ?: WolfCharacterPixelMotion.State((logicalX(actor) * 2).toInt(), (logicalY(actor) * 2).toInt())
        val result = WolfCharacterPixelMotion.move(before, dx, dy, tile, (actor.x * tile * 4).toInt(), (actor.y * tile * 4).toInt()) { x, y, direction ->
            canMoveGrid(x, y, direction, actor, stepOverride = 1)
        }
        actor.pixelMotion = result.state
        actor.x = result.realXQuarter / (tile * 4f); actor.y = result.realYQuarter / (tile * 4f)
        actor.targetX = result.state.halfX / 2f + result.state.offsetX / tile.toFloat()
        actor.targetY = result.state.halfY / 2f + result.state.offsetY / tile.toFloat()
        if (actor.id == -2 && result.state.crossed) { updatePartyTrail(); triggerTouch(result.state.halfX / 2, result.state.halfY / 2) }
    }

    private fun stepSize(a: Actor) = if (a.stepSize in 1..2) a.stepSize else (game.config.byteSettings.getOrNull(8)?.toInt() ?: 1) + 1
    private fun squareHitbox(a: Actor) = if (a.id == -2) game.config.byteSettings.getOrNull(9)?.toInt() == 1 else a.flags and 32 != 0
    private fun characterBush(a: Actor): Boolean {
        if (a.jumpRemaining > 0 || a.elevation > 0) return false
        val hx = halfMapX((logicalX(a) * 2).toInt()); val hy = halfMapY((logicalY(a) * 2).toInt() + 1)
        val tileset = currentTileset() ?: return false
        return (0 until map.layerCount).any { layer ->
            tileset.passageForTile(map.tile(Math.floorDiv(hx, 2), Math.floorDiv(hy, 2), layer)) and 0x40 != 0 &&
                tileset.passageForTile(map.tile(Math.floorDiv(halfMapX(hx + 1), 2), Math.floorDiv(hy, 2), layer)) and 0x40 != 0
        }
    }
    private fun logicalX(a: Actor) = a.pixelMotion?.halfX?.div(2f) ?: if (a.moveTicks > 0) a.targetX else a.x
    private fun logicalY(a: Actor) = a.pixelMotion?.halfY?.div(2f) ?: if (a.moveTicks > 0) a.targetY else a.y
    private fun passable(x: Int, y: Int, dx: Int, dy: Int, actor: Actor) = canMoveGrid(x * 2, y * 2, direction(dx, dy), actor)

    /** W2 FUN_004469e0: source and destination passage, followed by event/hero rectangles. */
    private fun canMoveGrid(hx: Int, hy: Int, direction: Int, actor: Actor, includeEvents: Boolean = true, stepOverride: Int? = null, squareOverride: Boolean? = null): Boolean {
        val step = stepOverride ?: stepSize(actor)
        val d = if (direction == 0) 0 to 0 else delta(direction)
        val rawX = hx + d.first * step; val rawY = hy + d.second * step
        val square = squareOverride ?: squareHitbox(actor)
        if (!map.loopX && rawX !in 0 until map.width * 2 - 1 ||
            !map.loopY && (rawY + if (step == 1 && square) 1 else 0) !in 0 until map.height * 2) return false
        val nx = halfMapX(rawX)
        if (actor.flags and 8 != 0) return true
        val rows = if (step == 1 && square) listOf(hy, hy - 1) else listOf(hy)
        for (sourceY in rows) {
            val destinationY = halfMapY(sourceY + d.second * step)
            if (!gridPassage(hx, sourceY, direction, step) || !gridPassage(nx, destinationY, if (direction == 0) 0 else 10 - direction, step)) return false
            val blocked = includeEvents && (actors.values + if (actor.id == -2) emptyList() else listOf(player)).any { other ->
                if (other.id == actor.id || other.flags and 8 != 0) false else {
                    val ox = (logicalX(other) * 2).toInt(); val oy = (logicalY(other) * 2).toInt()
                    // 4469e0/9e9b20 compare the snapped footprints modulo the map periods,
                    // including the neighbouring horizontal half-cells on a wrapped seam.
                    val overlapX = (-1..1).any { Math.floorMod(ox, map.width * 2) == Math.floorMod(nx + it, map.width * 2) }
                    val overlapY = Math.floorMod(oy, map.height * 2) == Math.floorMod(destinationY, map.height * 2) ||
                        squareHitbox(other) && Math.floorMod(oy, map.height * 2) == Math.floorMod(destinationY + 1, map.height * 2)
                    overlapX && overlapY && (other.id == -2 || actor.id != -2 || other.path.isNotBlank() || other.tileId >= 0)
                }
            }
            if (blocked) return false
        }
        return true
    }

    /** W2 FUN_0044ebf0. A character covers two horizontal quarters, with directional boundaries. */
    private fun gridPassage(hx: Int, hy: Int, direction: Int, step: Int): Boolean {
        if (!map.loopX && hx !in 0 until map.width * 2 || !map.loopY && hy + 1 !in 0 until map.height * 2) return false
        val tileset = currentTileset() ?: return false
        val mask = if (direction == 0) 0 else (1 shl (direction / 2 - 1)) and 15
        for (column in 0..1) {
            val x = halfMapX(hx + column); val y = halfMapY(hy + 1)
            if (x !in 0 until map.width * 2) return false
            for (layer in map.layerCount - 1 downTo 0) {
                val raw = map.tile(x / 2, y / 2, layer)
                if (raw < 0 || tileset.passageIndex(raw) == 0) continue
                val f = tileset.passageForTile(raw)
                if (f and 0x20 != 0) {
                    if (step == 2 && f and 15 != 0) return false
                    val quadrant = if (x and 1 == 0) if (y and 1 == 0) 8 else 2 else if (y and 1 == 0) 4 else 1
                    if (f and quadrant != 0) return false
                } else {
                    if (direction == 6 && column == 0 && f and 4 != 0 && hx and 1 == 1) return false
                    if (direction == 4 && column == 1 && f and 2 != 0 && hx and 1 == 1) return false
                    if (step == 1 && f and mask != 0) {
                        if (direction == 6 && column == 0 && f and 4 != 0 && hx and 1 == 0) return false
                        if (direction == 4 && column == 0 && f and 2 != 0 && hx and 1 == 0) return false
                        if (direction == 2 && hy and 1 == 0 && f and 1 != 0) return false
                        if (direction == 8 && hy and 1 == 1 && f and 8 != 0) return false
                    }
                    if (step == 2 && f and mask != 0 || f and 15 == 15) return false
                }
                if (f and 0x200 == 0) break
            }
        }
        return true
    }

    private fun halfMapX(x: Int) = if (map.loopX) Math.floorMod(x, map.width * 2) else x
    private fun halfMapY(y: Int) = if (map.loopY) Math.floorMod(y, map.height * 2) else y
    private fun tileMapX(x: Int) = if (map.loopX) Math.floorMod(x, map.width) else x
    private fun tileMapY(y: Int) = if (map.loopY) Math.floorMod(y, map.height) else y
    private fun mapDelta(from: Int, to: Int, period: Int, loop: Boolean): Int {
        var delta = if (loop) Math.floorMod(to, period) - Math.floorMod(from, period) else to - from
        if (loop && abs(delta - period) < abs(delta)) delta -= period
        if (loop && abs(delta + period) < abs(delta)) delta += period
        return delta
    }
    private fun mapDelta(from: Float, to: Float, period: Int, loop: Boolean): Float {
        var delta = to - from
        if (loop) {
            delta %= period
            if (abs(delta - period) < abs(delta)) delta -= period
            if (abs(delta + period) < abs(delta)) delta += period
        }
        return delta
    }

    private fun triggerAction() {
        if (foreground != null) return
        val d = delta(player.direction)
        val halfX = (logicalX(player) * 2).toInt() + d.first * stepSize(player)
        val halfY = (logicalY(player) * 2).toInt() + d.second * if (d.second < 0 && squareHitbox(player)) 2 else stepSize(player)
        if (triggerActionAt(halfX, halfY)) return
        // 4492b0 extends one whole tile once; consecutive counters do not form a chain.
        if (WolfCounterInteraction.isCounter(map, currentTileset(), halfX, halfY, game.config.byteSettings.getOrNull(8)?.toInt() == 0))
            triggerActionAt(halfX + d.first * 2, halfY + d.second * 2)
    }
    private fun triggerActionAt(halfX: Int, halfY: Int): Boolean {
        val event = map.events.sortedBy { it.id }.firstOrNull { event ->
            val actor = actors[event.id] ?: return@firstOrNull false
            val page = activePage(event) ?: return@firstOrNull false
            if (page.trigger != 0) return@firstOrNull false
            val reachX = page.features.getOrNull(1)?.toInt()?.and(255) ?: 0
            val reachY = page.features.getOrNull(2)?.toInt()?.and(255) ?: 0
            val dx = mapDelta(halfX, (logicalX(actor) * 2).toInt(), map.width * 2, map.loopX)
            val dy = mapDelta(halfY, (logicalY(actor) * 2).toInt(), map.height * 2, map.loopY)
            // The range bytes are half-cells (481470), with an additional half-cell for width/square feet.
            WolfCounterInteraction.inRange(dx, dy, reachX, reachY, squareHitbox(actor))
        } ?: return false
        beginMapEvent(event)
        return true
    }
    private fun triggerTouch(x: Int = player.x.toInt(), y: Int = player.y.toInt()) { if (foreground == null) triggerAt(x, y, setOf(3, 4)) }
    private fun triggerAt(x: Int, y: Int, triggers: Set<Int>) {
        map.events.sortedBy { it.id }.firstOrNull { event ->
            val a = actors[event.id] ?: return@firstOrNull false
            val page = activePage(event) ?: return@firstOrNull false
            page.trigger in triggers && abs(mapDelta(logicalX(a).toInt(), x, map.width, map.loopX)) <= (page.features.getOrNull(1)?.toInt()?.and(255) ?: 0) &&
                abs(mapDelta(logicalY(a).toInt(), y, map.height, map.loopY)) <= (page.features.getOrNull(2)?.toInt()?.and(255) ?: 0)
        }?.let(::beginMapEvent)
    }
    private fun beginMapEvent(event: WolfEvent) {
        val page = activePage(event) ?: return
        val actor = actors[event.id] ?: return
        if (actor.flags and 4 == 0) actor.direction = direction(
            mapDelta((logicalX(actor) * 2).toInt(), (logicalX(player) * 2).toInt(), map.width * 2, map.loopX),
            mapDelta((logicalY(actor) * 2).toInt(), (logicalY(player) * 2).toInt(), map.height * 2, map.loopY))
        foreground = Execution("touch:${event.id}", false, Invocation(page.commands, EventAddress(mapId, event.id)))
    }

    private fun updateRoute(actor: Actor) {
        val route = actor.route ?: return
        if (actor.routeWait > 0) return
        // 4519a0/9fd7a0 and 9fbf40 execute instant route operations together.
        // A directional operation or a positive wait yields to the next world tick.
        while (true) {
        if (--instructionBudget < 0) throw WolfRuntimeException(mapId, actor.id, -1, 201, 0, "Budget de route dépassé")
        if (actor.routeIndex >= route.commands.size) {
            characterAnimation(actor).resetIdleTicks()
            if (route.flags and 1 != 0) { actor.routeIndex = 0; return } else {
                actor.route = actor.savedRoute; actor.routeIndex = actor.savedRouteIndex; actor.routeForced = false; actor.routeCommon = -1
                actor.savedRoute = null; actor.savedRouteIndex = 0
                return
            }
        }
        val command = route.commands.getOrNull(actor.routeIndex) ?: return
        val address = EventAddress(mapId, if (actor.id == -2) -1 else actor.id, actor.routeCommon)
        fun value(index: Int, default: Int = 0) = variables.read(command.args.getOrElse(index) { default }, address)
        var done = true
        when (command.opcode) {
            in 0..7 -> { val directions = listOf(0 to 1, -1 to 0, 1 to 0, 0 to -1, -1 to 1, 1 to 1, -1 to -1, 1 to -1); val d = directions[command.opcode]; done = move(actor, d.first, d.second) }
            in 8..15 -> if (actor.flags and 4 == 0) actor.direction = listOf(2, 4, 6, 8, 1, 3, 7, 9)[command.opcode - 8]
            16 -> { val d = delta(listOf(2, 4, 6, 8)[random(0, 3)]); done = move(actor, d.first, d.second) }
            17, 18 -> {
                val distanceX = mapDelta((logicalX(actor) * 2).toInt(), (logicalX(player) * 2).toInt(), map.width * 2, map.loopX)
                val distanceY = mapDelta((logicalY(actor) * 2).toInt(), (logicalY(player) * 2).toInt(), map.height * 2, map.loopY)
                val factor = if (command.opcode == 18) -1 else 1
                done = if (abs(distanceX) > abs(distanceY)) move(actor, distanceX.compareTo(0) * factor, 0) else move(actor, 0, distanceY.compareTo(0) * factor)
            }
            19, 20 -> { val d = delta(actor.direction); val factor = if (command.opcode == 19) 1 else -1; done = move(actor, d.first * factor, d.second * factor) }
            21 -> done = jump(actor, value(0), value(1))
            22, 23 -> { val order = if (game.config.characterDirectionsImage == 8) listOf(2, 1, 4, 7, 8, 9, 6, 3) else listOf(2, 4, 8, 6); val i = order.indexOf(actor.direction).coerceAtLeast(0); actor.direction = order[(i + if (command.opcode == 22) 1 else order.size - 1) % order.size] }
            25 -> { val directions = if (game.config.characterDirectionsImage == 8) listOf(2, 4, 6, 8, 1, 3, 7, 9) else listOf(2, 4, 6, 8); actor.direction = directions[random(0, directions.size - 1)] }
            26, 27 -> {
                val factor = if (command.opcode == 26) 1 else -1
                actor.direction = direction(mapDelta((logicalX(actor) * 2).toInt(), (logicalX(player) * 2).toInt(), map.width * 2, map.loopX) * factor,
                    mapDelta((logicalY(actor) * 2).toInt(), (logicalY(player) * 2).toInt(), map.height * 2, map.loopY) * factor)
            }
            28 -> variables.write(command.args[0], value(1), address)
            29 -> { val speed = command.args[0].toShort().toInt(); require(speed in 0..6) { "Vitesse de route hors table : $speed" }; actor.speed = speed }
            30 -> actor.motion = actor.motion.copy(frequency = command.args[0].toShort().toInt())
            31 -> characterAnimation(actor).frequency(command.args[0].toShort().toInt())
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
            46 -> routeSound(value(0))
            47 -> actor.routeWait = maxOf(0, value(0))
            48 -> actor.stepSize = 1
            49 -> actor.stepSize = 2
            58 -> actor.elevation = value(0)
            59, 60, 61, 159, 160, 161 -> done = navigate(actor, command.opcode, value(0), value(1))
            53 -> done = approachActor(actor, value(0))
            54 -> done = approachPoint(actor, value(0) * 2, value(1) * 2)
            50, 51, 52, 56, 57 -> {
                val pattern = when (command.opcode) { 50 -> 0; 51 -> 1; 52 -> 2; 56 -> 3; else -> 4 }
                characterAnimation(actor).idlePattern(pattern)
                if ((game.config.engineCompatibilityVersion ?: 0) > 305) actor.pattern = pattern
            }
            55 -> variables.write(command.args[0], variables.read(command.args[0], address) + value(1), address)
            else -> throw WolfRuntimeException(mapId, actor.id, -1, 201, 0, "Route ${command.opcode} non prise en charge")
        }
        if (done || route.flags and 2 != 0 && actor.moveTicks == 0 && actor.jumpRemaining == 0) { actor.routeIndex++; actor.navigation = null }
        if (command.opcode in 0..27 || command.opcode in 53..54 || command.opcode in setOf(59,60,61,159,160,161)) {
            characterAnimation(actor).resetIdleTicks()
            return
        }
        if (actor.routeWait > 0) return
        }
    }

    private fun actor(id: Int, address: EventAddress): Actor = when (id) {
        -2 -> player; in -7..-3 -> companions[-id - 3]; -1 -> actors[address.event] ?: error("Événement courant absent : ${address.event}"); else -> actors[id] ?: error("Personnage absent : $id")
    }
    private fun heroQuarter(value: Float) = (value * game.config.tileSize * 4).toInt()
    private fun cameraMaxX() = maxOf(0, map.width * game.config.tileSize - game.config.width) * 4
    private fun cameraMaxY() = maxOf(0, map.height * game.config.tileSize - game.config.height) * 4
    private fun cameraLoopX() = if (map.loopX) map.width * game.config.tileSize * 4 else 0
    private fun cameraLoopY() = if (map.loopY) map.height * game.config.tileSize * 4 else 0
    private fun centredCameraX() = (heroQuarter(player.x) - (game.config.width - game.config.tileSize) * 2).let {
        if (map.loopX) Math.floorMod(it, cameraLoopX()) else it.coerceIn(0, cameraMaxX()) }
    private fun centredCameraY() = (heroQuarter(player.y) - (game.config.height - game.config.tileSize) * 2).let {
        if (map.loopY) Math.floorMod(it, cameraLoopY()) else it.coerceIn(0, cameraMaxY()) }
    private fun cameraX() = mapCamera.state.x / 4f - mapShake.offsetX(game.config.tileSize)
    private fun cameraY() = mapCamera.state.y / 4f - mapShake.offsetY(game.config.tileSize)
    private fun logicalPhysicalState(newOnly: Boolean, includeKeyboard: Boolean = true, includePad: Boolean = true): Set<NativeKey> =
        (if (!includeKeyboard) emptySet() else if (newOnly) newPhysicalKeys + automaticScanKeys else physicalKeys + newPhysicalKeys + automaticScanKeys).filterNot { variables.system[-2_000_000 - it] == 1 }.mapNotNull(::keyForScan).toSet() +
            (if (!includePad) emptySet() else if (newOnly) newPhysicalPadButtons else physicalPadButtons + newPhysicalPadButtons).filter { variables.system[-2_100_000] != 1 }.mapNotNull { button ->
                when (WolfControlBindings.physicalButtonBits(button)) {
                    WolfControlBindings.controllerBits(variables.system, 55) -> NativeKey.ACCEPT
                    WolfControlBindings.controllerBits(variables.system, 56) -> NativeKey.BACK
                    WolfControlBindings.controllerBits(variables.system, 57) -> NativeKey.SHIFT
                    else -> null
                }
            }
    private fun pressed(key: NativeKey) = key in newKeys + logicalPhysicalState(true) && key !in movementBannedKeys
    private fun down(key: NativeKey) = key in keys + newKeys + logicalPhysicalState(false) && key !in movementBannedKeys
    private fun physicalKeyboard(invocation: Invocation, waiting: Boolean, specific: Int): Int {
        val held = (physicalKeys + newPhysicalKeys + automaticScanKeys).filterNot { variables.system[-2_000_000 - it] == 1 }.toSet()
        if (specific in 100..355) return if (specific - 100 in held) specific else 0
        // W2 0042bab0 scans indices 2..146 and reserves the engine's built-in control keys.
        val scan = held.filter { it in 2..146 && it !in setOf(28, 1, 42, 54, 14, 57, 58, 112) }.minOrNull()
        if (scan == null) { invocation.lastPhysicalKey = 0; return 0 }
        val code = scan + 100
        if (waiting && invocation.lastPhysicalKey == code) return 0
        invocation.lastPhysicalKey = if (waiting) code else 0
        return code
    }
    private fun physicalController(invocation: Invocation, waiting: Boolean, specific: Int): Int {
        val held = (physicalPadButtons + newPhysicalPadButtons).filter { variables.system[-2_100_000] != 1 }.toSet()
        val button = if (specific in 300..320) specific.takeIf { it in held } else held.minOrNull()
        if (button == null) { invocation.lastPhysicalPadButton = 0; return 0 }
        if (waiting && invocation.lastPhysicalPadButton == button) return 0
        invocation.lastPhysicalPadButton = if (waiting) button else 0
        return button
    }
    private fun inputValue(flags: Int, waiting: Boolean = flags and 128 != 0, invocation: Invocation? = null, specific: Int = 0): Int {
        val typing = pendingTextInputs.isNotEmpty()
        val typingPermissions = variables.system[38] ?: 0
        if (typing && flags and 0xf00 == 0x100) return 0
        if (typing && flags and 0xf00 == 0x200 && typingPermissions and 2 == 0) return 0
        if (flags and 0xf00 == 0x100) return physicalKeyboard(invocation ?: error("Contexte clavier absent"), waiting, specific)
        if (flags and 0xf00 == 0x200) return physicalController(invocation ?: error("Contexte manette absent"), waiting, specific)
        val state = if (typing) emptySet() else if (waiting) newKeys else keys + newKeys
        fun held(key: NativeKey) = key in state + logicalPhysicalState(waiting, !typing, !typing || typingPermissions and 2 != 0) && key !in bannedKeys
        if (flags and 0xf00 == 0x300) {
            val mouseMode = flags ushr 16 and 15
            if (mouseMode != 0) require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Mode souris récent absent de ce profil Wolf" }
            return when (flags and 15) {
                0 -> {
                    val active = when (mouseMode) { 0 -> if (waiting) pointerNewPress else pointerPressed || pointerNewPress; 2 -> pointerNewPress; 3 -> pointerNewRelease; else -> error("Mode de bord souris non porté : $mouseMode") }
                    if ((!typing || typingPermissions and 1 != 0) && !mouseLeftBanned && flags and 16 != 0 && active) 20 else 0
                }
                1 -> pointerX.toInt(); 2 -> pointerY.toInt()
                else -> error("Entrée souris non portée : $flags")
            }
        }
        var allowRepeat = true
        fun downReader(key: NativeKey) = key in keys + newKeys + logicalPhysicalState(false, !typing, !typing || typingPermissions and 2 != 0) && key !in bannedKeys
        fun repeatPulse(kind: Int): Boolean {
            val delay = variables.system[15] ?: error("Délai de répétition système15 non établi")
            val interval = variables.system[16] ?: error("Intervalle de répétition système16 non établi")
            return keyRepeats.pulse(kind, delay, interval, engineFrameStep())
        }
        if (flags and 16 != 0) {
            if (held(NativeKey.ACCEPT) || waiting && !typing && variables.system[59] == 1 && allowRepeat && downReader(NativeKey.ACCEPT) && repeatPulse(0)) return 10
            if (downReader(NativeKey.ACCEPT) && (variables.system[59] ?: 0) and 1 != 0) allowRepeat = false
        }
        if (flags and 32 != 0) {
            if (held(NativeKey.BACK) || waiting && !typing && variables.system[60] == 1 && allowRepeat && downReader(NativeKey.BACK) && repeatPulse(1)) return 11
            if (downReader(NativeKey.BACK) && (variables.system[60] ?: 0) and 1 != 0) allowRepeat = false
        }
        if (flags and 64 != 0 && held(NativeKey.SHIFT)) return 12
        val repeatDirection = waiting && !typing && allowRepeat && flags and 15 != 0 && keyRepeats.direction != 0 &&
            variables.system[15] != null && variables.system[16] != null && repeatPulse(2)
        fun directionHeld(key: NativeKey) = held(key) || repeatDirection && downReader(key)
        val dx = if (directionHeld(NativeKey.LEFT)) -1 else if (directionHeld(NativeKey.RIGHT)) 1 else 0
        val dy = if (directionHeld(NativeKey.UP)) -1 else if (directionHeld(NativeKey.DOWN)) 1 else 0
        return when (flags and 15) {
            0 -> 0
            1 -> if (dy != 0) direction(0, dy) else if (dx != 0) direction(dx, 0) else 0
            2 -> if (dx != 0 || dy != 0) direction(dx, dy) else 0
            3 -> if (directionHeld(NativeKey.UP)) 8 else 0
            4 -> if (directionHeld(NativeKey.DOWN)) 2 else 0
            5 -> if (directionHeld(NativeKey.LEFT)) 4 else 0
            6 -> if (directionHeld(NativeKey.RIGHT)) 6 else 0
            7 -> if (dy != 0) direction(0, dy) else 0
            8 -> if (dx != 0) direction(dx, 0) else 0
            else -> error("Direction d'entrée non portée : $flags")
        }
    }

    private fun transitionMask(): String? {
        if (transitionMode == -1) return null
        require(transitionMode >= 0) { "Mode de transition inconnu : $transitionMode" }
        val path = game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(11)?.string(transitionMode, 0)
            ?: error("Masque de transition absent : $transitionMode")
        return asset(path)
    }

    private fun windowAsset(row: Int): String? = game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(10)?.string(row, 0)?.takeIf { it.isNotBlank() }?.let(::asset)
    private fun windowFrames(row: Int): Int = maxOf(1, game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(10)?.number(row, 1) ?: 1)

    private fun systemValue(code: Int, address: EventAddress): Int? = when (code) {
        7 -> variables.system[7] ?: 0
        11 -> variables.system[11] ?: 1_000_000
        12 -> if (dialog?.choices?.isEmpty() == true) 1 else 0
        13 -> if (foreground != null) 1 else 0
        20 -> dialog?.takeIf { it.choices.isNotEmpty() }?.selectedChoice ?: if (choiceFinishedTick == ticks) choiceFinishedValue else -1
        24 -> variables.system[24] ?: 0
        25 -> variables.system[25] ?: 0
        28 -> ((ticks - timerFrameOrigin + (variables.system[28] ?: 0)) % 10_000_000).toInt()
        29 -> ((ticks - timerSecondOrigin) / fps + (variables.system[29] ?: 0)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        35 -> if (player.moveTicks > 0) 1 else 0
        37 -> variables.system[37] ?: 2 // Documented engine default: two steps.
        38 -> variables.system[38] ?: 0 // Documented: keyboard input initially blocks mouse/touch/pad reads.
        39 -> variables.system[39] ?: 0
        48 -> variables.system[48] ?: -1
        in 52..57 -> WolfControlBindings.read(variables.system, code, game.config.engineCompatibilityVersion ?: 0)
        in 61..68 -> mapTextures.number(code)
        100, 101, 102 -> variables.system[code] ?: 100
        104 -> mapCamera.state.x / 4
        105 -> mapCamera.state.y / 4
        125 -> variables.system[125] ?: 5
        129, 130 -> {
            require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Facteur diagonal absent de ce profil Wolf" }
            variables.system[code] ?: 100 //959ec0 initializes both numeric fields to100.
        }
        71 -> pointerX.toInt(); 72 -> pointerY.toInt()
        84 -> when (game.config.width to game.config.height) { 320 to 240 -> 0; 640 to 480 -> 1; 800 to 600 -> 2; else -> -1 }
        // W2 nominal volume aa6940 is zero in the initialized image; the independent
        // audio container 47a5a0 initializes pitch (this+8c, aab184) to100.
        95, 97 -> variables.system[code] ?: 0
        96 -> variables.system[code] ?: 100
        98 -> variables.system[code] ?: if ((variables.system[99] ?: 0) == 0) 100 else 0
        99 -> variables.system[99] ?: 0
        108 -> 50_000 - instructionBudget
        115 -> game.config.engineCompatibilityVersion ?: error("Version de compatibilité absente de Game.dat")
        116 -> game.config.width; 117 -> game.config.height; 118 -> game.config.tileSize
        119 -> logicalScreenScale()
        140 -> {
            require((game.config.engineCompatibilityVersion ?: 0) >= 355) { "Filtre d'affichage140 absent de ce profil Wolf" }
            variables.system[140]
        }
        148 -> {
            require((game.config.engineCompatibilityVersion ?: 0) >= 330) { "Échelle du prochain lancement absente de ce profil Wolf" }
            nextLaunchScale
        }
        150 -> {
            require((game.config.engineCompatibilityVersion ?: 0) >= 350) { "Conversion de texte150 absente de ce profil Wolf" }
            variables.system[150] ?: 0
        }
        155 -> game.config.characterDirectionsMove
        in 9_100_000..9_199_999 -> {
            val relative = code - 9_100_000
            val event = relative / 10
            val actor = when (event) { 8000 -> player; in 8001..8005 -> companions[event - 8001]; 9000 -> actors[address.event]; else -> actors[event] }
                ?: return -1
            when (relative % 10) { 0 -> logicalX(actor).toInt(); 1 -> logicalY(actor).toInt(); 2 -> (logicalX(actor) * 2).toInt(); 3 -> (logicalY(actor) * 2).toInt(); 6 -> actor.direction; else -> null }
        }
        else -> null
    }

    private fun systemString(code: Int, address: EventAddress): String? = when (code) {
        in 1..6 -> WolfControlBindings.name(variables.system, code, game.config.engineCompatibilityVersion ?: 0)
        10 -> player.path
        in 11..15 -> companions[code - 11].path
        19 -> "Astra Wolf Native"
        25, 26, 27 -> variables.systemStrings[code] ?: ""
        30, 31 -> mapTextures.string(code)
        33, 34 -> activeAudio[audioKey(code - 33)]?.path.orEmpty()
        in 62..65, 67 -> {
            require((game.config.engineCompatibilityVersion ?: 0) >= 300) { "Police système absente de ce profil Wolf" }
            variables.systemStrings[code].orEmpty()
        }
        else -> null
    }

    private fun systemWrite(code: Int, value: Int) {
        if (code in 61..68) mapTextures.writeNumber(code, value, ::mapTextureParameters)
        if (code in 95..98) {
            val channel = (code - 95) / 2
            val key = audioKey(channel)
            if (channel == 1) {
                if (code == 97) bgsVolumes[key.second] = value else bgsPitches[key.second] = value
            }
            activeAudio[key]?.let { playing ->
                val updated = if (code and 1 != 0) playing.copy(volume = value) else playing.copy(pitch = value)
                activeAudio[key] = updated
                effects.add(updated.copy(path = null, fadeTicks = 0, position = 0))
            }
        }
        if (code == 99) { require(value in 0..999_999) { "Canal BGS hors plage" }; selectBgsVoice(value) }
        if (code in 100..102) effects.add(NativeEffect.AudioGain(code - 100, value.coerceAtLeast(0)))
        if (code == 104) mapCamera.position(x = Math.multiplyExact(value, 4), maxX = cameraMaxX(), maxY = cameraMaxY(), loopX = cameraLoopX(), loopY = cameraLoopY())
        if (code == 105) mapCamera.position(y = Math.multiplyExact(value, 4), maxX = cameraMaxX(), maxY = cameraMaxY(), loopX = cameraLoopX(), loopY = cameraLoopY())
        if (code == 28) timerFrameOrigin = ticks
        if (code == 29) timerSecondOrigin = ticks
        if (code == 148) { nextLaunchScale = value; effects.add(NativeEffect.NextLaunchScale(value)) }
    }

    private fun systemStringWrite(code: Int, value: String): Boolean {
        if (code in 30..31) mapTextures.writeString(code)
        if (code in 10..15) {
            asset(value)
            if (code == 10) player.path = value else companions[code - 11].path = value
        }
        if (code in WolfVariables.FONT_SYSTEM_STRINGS) {
            require(value.length <= 4096 && value.none { it.code < 32 }) { "Nom de police hors capacité" }
            val owner = dispatchInvocation
            val id = nextHostRequestId++
            pendingFontOverrides[id] = PendingFontOverride(owner, code - 62, value.removePrefix("<<?>>"))
            owner?.waitHost = true
            effects.add(NativeEffect.FontOverride(id, code - 62, value.removePrefix("<<?>>")))
            return true
        }
        return false
    }

    private fun fontOverrides(): Map<Int, String> = buildMap {
        WolfVariables.FONT_SYSTEM_STRINGS.forEach { code ->
            variables.systemStrings[code]?.takeUnless { it.isEmpty() || it == "<<NotFound>>" }?.let { put(code - 62, it.removePrefix("<<?>>")) }
        }
    }

    private fun text(input: String, address: EventAddress): String {
        // 948960 removes CR before the conversion flag and before recursively expanding variables.
        var result = input.replace("\r", "")
        if (variables.system[150] == 1) return result
        val variableEscape = Regex("""\\(cself|self|sysS|sys|[ucs]db|v[0-9]?|s)\[([^\[\]]*)]""")
        repeat(100) {
            val source = result
            var expansions = 0
            result = variableEscape.replace(source) { match ->
                if (WolfTextCodes.quotedEscape(source, match.range.first)) return@replace match.value
                expansions++
                val code = match.groupValues[1]; val argument = match.groupValues[2]
                fun index() = argument.toIntOrNull() ?: error("Index de variable texte invalide : ${match.value}")
                when (code) {
                    "cself" -> { val id = index(); variables.commonStrings[address.common to id] ?: (variables.common[address.common to id] ?: 0).toString() }
                    "self" -> variables.read(1_100_000 + index(), address).toString()
                    "s" -> variables.readString(3_000_000 + index(), address)
                    "sys" -> variables.read(9_000_000 + index(), address).toString()
                    "sysS" -> variables.readString(9_900_000 + index(), address)
                    "udb", "cdb", "sdb" -> {
                        val kind = when (code) { "udb" -> WolfDatabaseKind.USER; "cdb" -> WolfDatabaseKind.MUTABLE; else -> WolfDatabaseKind.SYSTEM }
                        val parameters = argument.split(':'); require(parameters.size in 1..3) { "Référence DB texte invalide" }
                        val db = game.databases[kind] ?: error("DB texte absente : $kind")
                        val typeId = parameters[0].toIntOrNull() ?: db.types.indexOfFirst { it.name == parameters[0] }
                        val type = db.types.getOrNull(typeId) ?: error("Type DB texte absent : ${parameters[0]}")
                        if (parameters.size == 1) type.name else {
                            val row = parameters[1].toIntOrNull() ?: type.rows.indexOfFirst { it.name == parameters[1] }
                            if (parameters.size == 2) type.rows.getOrNull(row)?.name ?: error("Nom de rangée DB texte absent")
                            else {
                                val field = parameters[2].toIntOrNull() ?: type.fields.indexOfFirst { it.name == parameters[2] }
                                val descriptor = type.fields.getOrNull(field) ?: error("Champ DB texte absent")
                                if (descriptor.valueType == WolfValueType.STRING) variables.databaseString(kind, typeId, row, field)
                                else variables.databaseNumber(kind, typeId, row, field).toString()
                            }
                        }
                    }
                    else -> { val id = index(); val bank = code.drop(1).toIntOrNull() ?: 0; variables.read(if (id >= 1_000_000) id else 2_000_000 + bank * 100_000 + id, address).toString() }
                }
            }
            if (expansions == 0) return result
            require(result.length <= 1_000_000) { "Développement de texte supérieur à la capacité native" }
        }
        require(variableEscape.findAll(result).all { WolfTextCodes.quotedEscape(result, it.range.first) }) { "Références de texte récursives au-delà de 100 développements" }
        return result
    }

    private fun displayText(input: String, address: EventAddress): String = text(input, address).also {
        if (variables.system[150] != 1) textPresentationProblem(it)?.let { issue -> error(issue) }
    }

    private fun asset(path: String): String {
        if (path.isEmpty()) return ""
        return WolfParser.imageAssetPath(path)
    }
    private fun random(low: Int, high: Int): Int {
        randomState = randomState xor (randomState shl 13); randomState = randomState xor (randomState ushr 17); randomState = randomState xor (randomState shl 5)
        val width = high.toLong() - low + 1
        return (low + (randomState.toLong() and 0xffffffffL) % width).toInt()
    }
    private fun resetToTitle() {
        tickExecutions = null; tickExecutionIndex = 0
        pendingStringFiles.clear(); pendingDatabaseFiles.clear()
        pendingDownloads.keys.forEach { effects.add(NativeEffect.CancelDownload(it)) }
        pendingDownloads.clear(); lastDownloadRequest = 0L
        stopSpeech()
        resetAudio()
        variables.clear(); pictures.clear(); tweens.clear(); delayedPictures.clear(); parallel.clear(); foreground = null; bannedKeys.clear(); movementBannedKeys.clear(); mouseLeftBanned = false
        reserved.clear(); erasedEvents.clear(); actors.clear(); dialog = null; dialogOwner = null; forceChoiceFinish = false; pendingSaveValues.clear(); pendingPictureMeasures.clear(); pendingFontOverrides.clear(); pendingTextInputs.clear(); pendingMaskImages.clear(); pictureMeasures.clear(); textPicturePrograms.clear()
        mapId = game.start.mapId; map = mapLoader(mapId); mapChanges = WolfMapChanges(map, game.tilesets); mapEpoch++
        mapAudioPending = true
        restoreCharacter(player, characterState(Actor(player.id, game.start.x.toFloat(), game.start.y.toFloat(), game.config.defaultHeroGraphic,
            pattern = initialCharacterPattern(), flags = initialCharacterFlags())))
        companions.forEach { restoreCharacter(it, characterState(Actor(it.id, player.x, player.y, "",
            pattern = initialCharacterPattern(), flags = initialCharacterFlags()))) }
        partyFollow = true; partySync = false; rememberedParty = emptyList(); partyTrail.clear()
        frozenFrame = null; frozenMapFrame = null; transitionPrevious = null; transitionMode = -1; transitionDuration = 10; transitionElapsed = 0; transitionWait = false
        dialogText = null; choiceFinishedTick = -1; choiceFinishedValue = -1; screenOpacity = 255; screenColor = 0
        screenEffects.reset(); pictureEffects.reset(); extendedPictureEffects.reset(); mapShake.reset(); characterEffects.reset()
        visibilityMask.restore(null); maskedPictures.clear(); maskedCharacters.clear(); mapMasked = false
        mapCamera.reset(centredCameraX(), centredCameraY(), cameraMaxX(), cameraMaxY(), cameraLoopX(), cameraLoopY())
        mapZoom = 1f; mapZoomCenterX = 0f; mapZoomCenterY = 0f; mapZoomTween = null
        mapTextures.reset()
        timerFrameOrigin = ticks; timerSecondOrigin = ticks
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
    private fun enclosingLoop(frame: Invocation, pc: Int): Loop {
        val start = frame.loopBounds.keys.filter { it <= pc && frame.loopBounds.getValue(it) >= pc }.maxOrNull()
            ?: error("Contrôle de boucle sans début")
        return frame.loops.lastOrNull { it.start == start + 1 } ?: Loop(start + 1, frame.loopBounds.getValue(start),
            if (frame.commands[start].opcode == 179) variables.read(frame.commands[start].args[0], frame.address).coerceAtLeast(0) else -1).also { frame.loops.addLast(it) }
    }
    /** W2 00462a10 decrements the counter for both a tail and Continue; 00462ad0 exits it. */
    private fun advanceLoop(frame: Invocation, loop: Loop) {
        frame.loops.removeAll { it.start > loop.start }
        if (loop.remaining == -1 || --loop.remaining > 0) frame.pc = loop.start
        else { frame.loops.remove(loop); frame.pc = loop.end + 1 }
    }
    private fun fail(frame: Invocation, command: WolfCommand, reason: String): Nothing = throw WolfRuntimeException(frame.address.map, frame.address.event, frame.address.common, command.opcode, command.offset, reason)

    /** Native save codec. It deliberately does not decode or replace Windows .sav files. */
    fun snapshot(): ByteArray {
        return WolfSnapshot.encode(snapshotState())
    }
    fun restore(bytes: ByteArray) {
        val state = WolfSnapshot.decode(bytes, game.config.title)
        restoreState(state)
    }
    fun restoreSaveData(data: WolfSaveData) {
        val bytes = data.snapshot ?: error("Cette sauvegarde contient uniquement des valeurs isolées et aucun état de partie")
        val state = WolfSnapshot.decode(bytes, game.config.title)
        val numberOverrides = linkedMapOf<Int, Int>()
        val systemOverrides = linkedMapOf<Int, Int>()
        val stringOverrides = linkedMapOf<Int, String>()
        val systemStringOverrides = linkedMapOf<Int, String>()
        val mutableNumberOverrides = linkedMapOf<Triple<Int, Int, Int>, Int>()
        val mutableStringOverrides = linkedMapOf<Triple<Int, Int, Int>, String>()
        val mutableRows = state.mutableRows.toMutableMap()
        fun growSaved(cell: Triple<Int, Int, Int>) {
            val initialRows = game.databases.getValue(WolfDatabaseKind.MUTABLE).types[cell.first].rows.size
            mutableRows[cell.first] = maxOf(mutableRows[cell.first] ?: initialRows, cell.second + 1)
        }
        data.numbers.forEach { (code, value) -> when (code) {
            in 2_000_000..2_999_999 -> numberOverrides[code - 2_000_000] = value
            in 9_000_000..9_099_999 -> { require(code % 100_000 in WolfVariables.SUPPORTED_SYSTEM_WRITES); systemOverrides[code % 100_000] = value }
            in 1_100_000_000..1_199_999_999 -> { val cell = mutableCell(code, WolfValueType.NUMBER); mutableNumberOverrides[cell] = value; growSaved(cell) }
            else -> error("Destination de sauvegarde isolée invalide : $code")
        } }
        data.strings.forEach { (code, value) -> when (code) {
            in 3_000_000..3_999_999 -> stringOverrides[code - 3_000_000] = value
            in 9_900_000..9_999_999 -> { require(code % 100_000 in WolfVariables.SUPPORTED_SYSTEM_STRING_WRITES); systemStringOverrides[code % 100_000] = value }
            in 1_100_000_000..1_199_999_999 -> { val cell = mutableCell(code, WolfValueType.STRING); mutableStringOverrides[cell] = value; growSaved(cell) }
            else -> error("Destination de chaîne sauvegardée invalide : $code")
        } }
        var combined = state.copy(numbers = state.numbers + numberOverrides, system = state.system + systemOverrides,
            strings = state.strings + stringOverrides, systemStrings = state.systemStrings + systemStringOverrides,
            mutableNumbers = state.mutableNumbers + mutableNumberOverrides, mutableStrings = state.mutableStrings + mutableStringOverrides, mutableRows = mutableRows)
        val textureNotices = mutableListOf<String>()
        if (systemOverrides.keys.any { it == 61 || it == 66 } || systemStringOverrides.keys.any { it == 30 || it == 31 }) {
            // Isolated writes change the serialized variables independently of the
            // captured bitmap. Replay only their loaders on a private prospective
            // state so a bad path/header cannot partially restore this world.
            mapTextures.validate(state.mapTextures, state.system)
            val probe = WolfVariables(game.databases, { _, _ -> null })
            probe.imports.restore(combined.databaseChanges)
            val numbers = combined.system.toMutableMap()
            val strings = combined.systemStrings.toMutableMap()
            val textures = WolfMapTextureController(source, game.config.engineCompatibilityVersion ?: 0,
                numbers, strings, game.databases,
                { type, row, field -> probe.databaseNumber(WolfDatabaseKind.SYSTEM, type, row, field) },
                { type, row, field -> probe.databaseString(WolfDatabaseKind.SYSTEM, type, row, field) }, textureNotices::add)
            textures.restore(combined.mapTextures)
            val restoredMap = mapLoader(combined.mapId)
            fun camera(coordinate: Float, viewport: Int, extent: Int, looping: Boolean): Int {
                val centred = heroQuarter(coordinate) - (viewport - game.config.tileSize) * 2
                return if (looping) Math.floorMod(centred, extent * game.config.tileSize * 4)
                else centred.coerceIn(0, maxOf(0, extent * game.config.tileSize - viewport) * 4)
            }
            val cameraX = combined.camera?.x ?: camera(combined.x, game.config.width, restoredMap.width, restoredMap.loopX)
            val cameraY = combined.camera?.y ?: camera(combined.y, game.config.height, restoredMap.height, restoredMap.loopY)
            val shake = WolfMapShake().apply { restore(combined.mapShake) }
            fun parameters(kind: WolfMapTextures.Kind): WolfMapTextures.Parameters {
                val field = if (kind == WolfMapTextures.Kind.FOG) 62 else 67
                return WolfMapTextures.Parameters(numbers[field] ?: 0, numbers[field + 1] ?: 0, engineFrameStep(),
                    cameraX, cameraY, shake.offsetX(game.config.tileSize), shake.offsetY(game.config.tileSize))
            }
            systemOverrides.keys.filter { it == 61 || it == 66 }.forEach { textures.writeNumber(it, numbers.getValue(it), ::parameters) }
            systemStringOverrides.keys.filter { it == 30 || it == 31 }.forEach(textures::writeString)
            WolfMapTextures.Kind.entries.forEach { textures.frame(it, parameters(it), (combined.mapZoom * 100).toInt(),
                combined.mapZoomCenterX.toInt(), combined.mapZoomCenterY.toInt()) }
            combined = combined.copy(system = numbers, systemStrings = strings, mapTextures = textures.state)
        }
        restoreState(combined)
        textureNotices.forEach { effects.add(NativeEffect.Diagnostic(it)) }
    }

    internal fun snapshotState(): WolfSnapshot.State = WolfSnapshot.State(game.config.title, ticks, mapId, player.x, player.y, player.direction, player.path, randomState,
        variables.numbers.toMap(), variables.strings.toMap(), variables.self.toMap(), variables.common.toMap(), variables.commonStrings.toMap(), variables.system.toMap(), variables.systemStrings.toMap(), variables.mutableNumbers.toMap(), variables.mutableStrings.toMap(),
        characterState(player), actors.values.map(::characterState), erasedEvents.toSet(), pictures.values.toList(), allowMovement, freezeWorld, bannedKeys.toSet(),
        companions.map(::characterState), variables.mutableRows.toMap(), partyFollow, partySync, rememberedParty.toList(), partyTrail.toList(), activeAudio.values.toList(), screenOpacity, screenColor,
        transitionMode, transitionDuration, transitionWait, tweens.values.map { WolfSnapshot.TweenState(it.from, it.to, it.length, it.elapsed, it.erase) },
        mapZoom, mapZoomCenterX, mapZoomCenterY, mapZoomTween?.let { WolfSnapshot.MapZoomState(it.from, it.to, it.length, it.elapsed, it.fromX, it.fromY, it.toX, it.toY) },
        movementBannedKeys.toSet(), mouseLeftBanned, timerFrameOrigin, timerSecondOrigin, screenEffects.state, pictureEffects.state(), mapShake.state,
        delayedPictures.map { WolfSnapshot.DelayedPictureState(it.id, it.action, it.duration, it.target, it.remaining, it.effectOperation, it.p1, it.p2, it.p3) },
        characterEffects.state().filter { it.id in -7..-2 || it.id in actors }, mapCamera.state, extendedPictureEffects.state(),
        visibilityMask.state(), maskedPictures.filterTo(linkedSetOf()) { it in pictures },
        maskedCharacters.filterTo(linkedSetOf()) { it in -7..-2 || it in actors }, mapMasked,
        bgsVolumes.toMap(), bgsPitches.toMap(), mapChanges.state(), variables.imports.state(),
        mapTextures.state)
    private fun characterState(a: Actor) = WolfSnapshot.CharacterState(a.id, a.x, a.y, a.path, a.direction, a.pattern, a.opacity, a.flags, a.speed, a.tileId, a.targetX, a.targetY, a.moveTicks, a.route, a.routeIndex, a.routeWait, a.moving, a.shadow, a.blend, a.stepSize, a.elevation, a.jumpPeak, a.jumpRemaining, a.routeForced, a.savedRoute, a.savedRouteIndex, a.routeCommon,
        a.navigation?.let { WolfSnapshot.NavigationState(it.goalX, it.goalY, it.mode, it.waypoints.toList()) }, a.animation?.state, a.pixelMotion, a.motion)
    private fun restoreCharacter(a: Actor, s: WolfSnapshot.CharacterState) {
        a.x = s.x; a.y = s.y; a.path = s.path; a.direction = s.direction; a.pattern = s.pattern; a.opacity = s.opacity
        a.flags = s.flags; a.speed = s.speed; a.tileId = s.tileId; a.targetX = s.targetX; a.targetY = s.targetY; a.moveTicks = s.moveTicks
        a.route = s.route; a.routeIndex = s.routeIndex; a.routeWait = s.routeWait; a.moving = s.moving
        a.shadow = s.shadow; a.blend = s.blend
        a.stepSize = s.stepSize; a.elevation = s.elevation; a.jumpPeak = s.jumpPeak; a.jumpRemaining = s.jumpRemaining
        a.routeForced = s.routeForced; a.savedRoute = s.savedRoute; a.savedRouteIndex = s.savedRouteIndex; a.routeCommon = s.routeCommon
        a.navigation = s.navigation?.let { Navigation(it.goalX, it.goalY, it.mode, it.waypoints.toMutableList()) }
        a.animation = s.animation?.let { newCharacterAnimation(state = it) }
        a.pixelMotion = s.pixelMotion
        a.motion = s.motion ?: WolfCharacterMotion.State(alternateSpeed = a.id in -7..-2)
    }
    internal fun restoreState(state: WolfSnapshot.State) {
        mapTextures.validate(state.mapTextures, state.system)
        WolfScreenEffects().restore(state.screenEffects) // Validate before changing this runtime.
        WolfPictureEffects().restore(state.pictureEffects)
        WolfExtendedPictureEffects().restore(state.extendedPictureEffects)
        WolfVisibilityMask(game.config.width, game.config.height).restore(state.visibilityMask)
        WolfMapShake().restore(state.mapShake)
        WolfCharacterEffects().restore(state.characterEffects)
        state.camera?.let { WolfMapCamera().restore(it) }
        val loaded = mapLoader(state.mapId)
        val restoredMapChanges = WolfMapChanges(loaded, game.tilesets).apply { restore(state.mapChanges) }
        WolfDatabaseChanges(game.databases).restore(state.databaseChanges)
        state.camera?.let {
            require((loaded.loopX || it.x <= maxOf(0, loaded.width * game.config.tileSize - game.config.width) * 4) &&
                (loaded.loopY || it.y <= maxOf(0, loaded.height * game.config.tileSize - game.config.height) * 4)) { "Caméra de sauvegarde hors carte" }
        }
        // Cardinal moves and jumps retain continuous coordinates across a looping edge (481550/9eb130).
        // The bound is an interpreter quota; non-looping axes still require their actual map range.
        fun positionValid(value: Float, size: Int, looping: Boolean) = value.isFinite() &&
            if (looping) abs(value) <= 1_000_000f else value >= -.5f && value < size
        require(positionValid(state.x, loaded.width, loaded.loopX) && positionValid(state.y, loaded.height, loaded.loopY)) { "Sauvegarde hors carte" }
        (state.actors + state.companions + listOfNotNull(state.hero)).forEach { c ->
            require(positionValid(c.x, loaded.width, loaded.loopX) && positionValid(c.y, loaded.height, loaded.loopY) &&
                positionValid(c.targetX, loaded.width, loaded.loopX) && positionValid(c.targetY, loaded.height, loaded.loopY)) { "Personnage de sauvegarde hors carte" }
            require(c.id in -7..-2 || loaded.events.any { it.id == c.id }) { "Événement de sauvegarde absent" }
            asset(c.path)
            c.animation?.let { newCharacterAnimation(state = it) }
            c.motion?.let { WolfCharacterMotion.validate(it) }
            require(c.motion?.alternateSpeed == null || c.motion.alternateSpeed == (c.id in -7..-2)) { "Profil de déplacement sauvegardé incohérent" }
            WolfCharacterMotion.increment(game.config.wordSettings, c.speed, c.id in -7..-2, engineFrameStep())
            c.pixelMotion?.let { pixel ->
                require((game.config.engineCompatibilityVersion ?: 0) >= 350 && game.config.tileSize % 2 == 0 &&
                    positionValid(pixel.halfX / 2f, loaded.width, loaded.loopX) && positionValid(pixel.halfY / 2f, loaded.height, loaded.loopY) &&
                    pixel.offsetX in 0 until game.config.tileSize / 2 && pixel.offsetY in 0 until game.config.tileSize / 2) { "Déplacement pixel sauvegardé invalide" }
            }
            listOfNotNull(c.route, c.savedRoute).forEach { route -> route.commands.forEach { require(it.opcode in SUPPORTED_ROUTE) { "Route sauvegardée non prise en charge" } } }
            c.navigation?.let { path -> require(path.goalX in 0 until loaded.width && path.goalY in 0 until loaded.height && path.waypoints.all { (x, y) -> x in 0 until (loaded.width * 2 - if (loaded.loopX) 0 else 1) && y in 0 until loaded.height * 2 }) { "Trajet de sauvegarde hors carte" } }
        }
        require(state.hero == null || state.hero.id == -2) { "Héros de sauvegarde invalide" }
        val restoredTextPrograms = linkedMapOf<TextProgramKey, WolfTextPictureTiming.Program>()
        val savedPictures = state.pictures + state.tweens.flatMap { listOf(it.from, it.to) } + state.delayedPictures.mapNotNull { it.target }
        savedPictures.forEach { p ->
            p.path?.let(WolfParser::rootAssetPath)
            if (p.textTiming?.convertSpecial != false) p.text?.let { require(textPresentationProblem(it) == null) { "Code d'image de sauvegarde invalide" } }
            p.textTiming?.let { timing ->
                require(p.kind == 2 && p.text != null && timing.encoding == game.config.encoding &&
                    timing.compatibilityVersion == game.config.engineCompatibilityVersion) { "Profil de texte de sauvegarde incompatible" }
                val key = textProgramKey(p)
                val program = restoredTextPrograms.getOrPut(key) { timing.program(p.text) }
                require(WolfTextPictureTiming.validate(program, timing.state) && p.visibleCharacters in 0..program.glyphCount) { "Horloge de texte de sauvegarde invalide" }
            }
        }
        require(state.erasedEvents.all { id -> loaded.events.any { it.id == id } }) { "Événement effacé de sauvegarde absent" }
        require(state.system.keys.all { it <= -1_000_000 || it in WolfVariables.SUPPORTED_SYSTEM_READS }) { "Champ système de sauvegarde non pris en charge" }
        state.mutableRows.forEach { (type, count) -> require(game.databases[WolfDatabaseKind.MUTABLE]?.types?.getOrNull(type) != null && count in 0..10_000) { "Taille DB sauvegardée incompatible" } }
        state.mutableNumbers.forEach { (cell, _) -> require(cell.second >= 0 && cell.second < (state.mutableRows[cell.first] ?: 10_000) && game.databases[WolfDatabaseKind.MUTABLE]?.types?.getOrNull(cell.first)?.fields?.getOrNull(cell.third)?.valueType == WolfValueType.NUMBER) { "Cellule DB sauvegardée incompatible" } }
        state.mutableStrings.forEach { (cell, _) -> require(cell.second >= 0 && cell.second < (state.mutableRows[cell.first] ?: 10_000) && game.databases[WolfDatabaseKind.MUTABLE]?.types?.getOrNull(cell.first)?.fields?.getOrNull(cell.third)?.valueType == WolfValueType.STRING) { "Texte DB sauvegardé incompatible" } }
        state.rememberedParty.forEach(::asset); state.audio.forEach { it.path?.let(::asset) }
        require(state.bgsVolumes.all { (voice, value) -> voice in 0..999_999 && value in 0..100 } &&
            state.bgsPitches.all { (voice, value) -> voice in 0..999_999 && value in 0..400 }) { "Paramètres BGS sauvegardés invalides" }
        require(state.companions.all { it.id in -7..-3 } && state.companions.map { it.id }.distinct().size == state.companions.size) { "Partie sauvegardée invalide" }
        state.tweens.forEach { tween -> require(tween.from.id == tween.to.id && tween.length > 0 && tween.elapsed in 0..tween.length); tween.from.path?.let(WolfParser::rootAssetPath); tween.to.path?.let(WolfParser::rootAssetPath) }
        resetToTitle(); mapAudioPending = false; mapId = state.mapId; mapChanges = restoredMapChanges; map = restoredMapChanges.map; mapEpoch++; actors.clear()
        textPicturePrograms.putAll(restoredTextPrograms)
        ticks = state.ticks; player.x = state.x; player.y = state.y; player.targetX = state.x; player.targetY = state.y
        player.direction = state.direction; player.path = state.path; randomState = state.randomState
        variables.numbers.putAll(state.numbers); variables.strings.putAll(state.strings); variables.self.putAll(state.self)
        variables.common.putAll(state.common); variables.commonStrings.putAll(state.commonStrings)
        variables.system.putAll(state.system.filterKeys { it > -1_000_000 || it in -2_000_255..-2_000_000 || it == -2_100_000 }); variables.systemStrings.putAll(state.systemStrings)
        // The reception thread and its wait pointer are not serialized in a save.
        if (variables.system[48] == 0) variables.system[48] = -1
        variables.mutableNumbers.putAll(state.mutableNumbers); variables.mutableStrings.putAll(state.mutableStrings); variables.mutableRows.putAll(state.mutableRows)
        variables.imports.restore(state.databaseChanges)
        mapTextures.restore(state.mapTextures)
        // Wolf resumes without the event's in-progress stack, waits or dialog.
        parallel.clear(); foreground = null; dialog = null; dialogOwner = null; forceChoiceFinish = false; failed = null; terminated = false
        erasedEvents.addAll(state.erasedEvents); refreshActors()
        state.hero?.let { restoreCharacter(player, it) }
        state.actors.forEach { saved -> actors[saved.id]?.let { restoreCharacter(it, saved) } }
        state.companions.forEach { saved -> restoreCharacter(companions[-saved.id - 3], saved) }
        partyFollow = state.partyFollow; partySync = state.partySync; rememberedParty = state.rememberedParty; partyTrail.addAll(state.partyTrail)
        repeat(3) { channel -> effects.add(NativeEffect.AudioGain(channel, (variables.system[100 + channel] ?: 100).coerceAtLeast(0))) }
        bgsVolumes.putAll(state.bgsVolumes); bgsPitches.putAll(state.bgsPitches)
        if (state.bgsVolumes.isEmpty()) state.system[97]?.let { bgsVolumes[state.system[99] ?: 0] = it }
        if (state.bgsPitches.isEmpty()) state.system[98]?.let { bgsPitches[state.system[99] ?: 0] = it }
        state.audio.forEach { audio -> activeAudio[audio.channel to audio.voice] = audio; effects.add(audio) }
        screenOpacity = state.screenOpacity; screenColor = state.screenColor; transitionMode = state.transitionMode; transitionDuration = state.transitionDuration; transitionWait = state.transitionWait
        screenEffects.restore(state.screenEffects)
        pictureEffects.restore(state.pictureEffects)
        extendedPictureEffects.restore(state.extendedPictureEffects)
        visibilityMask.restore(state.visibilityMask)
        maskedPictures.addAll(state.maskedPictures); maskedCharacters.addAll(state.maskedCharacters); mapMasked = state.mapMasked
        mapShake.restore(state.mapShake)
        characterEffects.restore(state.characterEffects)
        if (state.camera != null) mapCamera.restore(state.camera)
        else mapCamera.reset(centredCameraX(), centredCameraY(), cameraMaxX(), cameraMaxY(), cameraLoopX(), cameraLoopY())
        delayedPictures.clear(); delayedPictures.addAll(state.delayedPictures.map { DelayedPicture(it.id, it.action, it.duration, it.target, it.remaining, it.effectOperation, it.p1, it.p2, it.p3) })
        mapZoom = state.mapZoom; mapZoomCenterX = state.mapZoomCenterX; mapZoomCenterY = state.mapZoomCenterY
        mapZoomTween = state.mapZoomTween?.let { MapZoomTween(it.from, it.to, it.length, it.elapsed, it.fromX, it.fromY, it.toX, it.toY) }
        val resumedVideos = linkedMapOf<Pair<Int, Long>, NativeVideoState>()
        fun resumeVideo(picture: NativePicture): NativePicture = picture.video?.let { saved ->
            val resumed = resumedVideos.getOrPut(picture.id to saved.token) {
                saved.copy(token = nextVideoToken++, seekMs = saved.positionMs, seekRevision = 0)
            }
            picture.copy(video = resumed)
        } ?: picture
        state.tweens.forEach { tween -> tweens[tween.from.id] = PictureTween(resumeVideo(tween.from), resumeVideo(tween.to), tween.length, tween.elapsed, tween.erase) }
        pictures.putAll(state.pictures.map(::resumeVideo).associateBy { it.id }); allowMovement = state.allowMovement; freezeWorld = state.freezeWorld
        bannedKeys.clear(); bannedKeys.addAll(state.bannedKeys); movementBannedKeys.clear(); movementBannedKeys.addAll(state.movementBannedKeys); mouseLeftBanned = state.mouseLeftBanned
        timerFrameOrigin = state.timerFrameOrigin; timerSecondOrigin = state.timerSecondOrigin; releaseInput()
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

        private val SUPPORTED = setOf(0, 4, 99, 101, 102, 103, 104, 105, 106, 107, 111, 112, 121, 122, 123, 124, 125, 126, 130, 140, 150, 151, 160, 161, 162, 1001,
            170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 201, 202, 210, 211, 212, 213, 220, 221, 222, 230, 231, 240, 241, 242, 250, 251, 260, 270, 280, 281, 290, 300, 401, 402, 420, 421, 498, 499)
        private val SUPPORTED_ROUTE = (0..23).toSet() + setOf(25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 159, 160, 161)
        private fun textPresentationProblem(text: String): String? = WolfTextCodes.presentationProblem(text)
        fun capabilityProblem(command: WolfCommand): String? {
            if (command.opcode !in SUPPORTED) return "Commande Wolf non implémentée dans le moteur natif"
            if (command.extension.isNotEmpty()) return "Extension de commande non interprétée"
            val a = command.arguments
            return when (command.opcode) {
                111 -> when {
                    a.isEmpty() || a.size < 1 + (a[0] and 15) * 3 -> "Conditions numériques incomplètes"
                    (0 until (a[0] and 15)).any { a[3 + it * 3] and 15 !in 0..6 || a[3 + it * 3] and 31.inv() != 0 } -> "Mode de condition numérique non pris en charge"
                    else -> null
                }
                103 -> if (command.strings.any { it.startsWith("<<") && it.contains("PerformanceMonitor") }) "Directive de commentaire spéciale" else null
                121 -> if (a.size < 4) "Affectation incomplète" else if (a[3] and 0xfffe0000.toInt() != 0 || a[3] and 0x80 != 0 || (a[3] ushr 12 and 15) > 6 || (a[3] ushr 8 and 15) > 12) "Mode d'affectation non pris en charge" else null
                122 -> when {
                    a.size < 2 -> "Opération de chaîne incomplète"
                    a[1] and 0x40000 != 0 -> when {
                        a[1] and 0xfffbfde0.toInt() != 0 || a[1] and 15 !in 0..2 || a[1] and 0xf00 != 0x200 -> "Options de code de chaîne spécial non prises en charge"
                        a[1] and 15 == 0 -> WolfStringMacros.problem(command.strings.firstOrNull().orEmpty())
                        a.size < 3 -> "Source de code de chaîne spécial absente"
                        else -> null
                    }
                    a[1] and 0xffffc0e0.toInt() != 0 || a[1] and 15 !in 0..3 || a[1] ushr 8 and 15 !in setOf(0, 1, 2, 3, 5, 6, 7, 8, 9, 10, 11) -> "Mode de chaîne non pris en charge"
                    a[1] ushr 8 and 15 in 5..7 && a.getOrElse(4) { 0 } !in 0..2 -> "Encodage de fichier Wolf inconnu"
                    a[1] and 15 == 3 && (a.size < 3 || a[1] and 0xf00 != 0) -> "Mode de saisie clavier non pris en charge"
                    a[1] and 15 != 3 && a[1] and 0x3000 != 0 -> "Options de saisie sur une opération de chaîne"
                    a[1] and 15 in 1..2 && a.size < 3 -> "Référence de chaîne absente"
                    a[1] and 0xf00 == 0x900 && command.strings.size < 2 -> "Remplacement de chaîne incomplet"
                    else -> null
                }
                123 -> when {
                    a.size < 2 || a[1] and 0xfffce000.toInt() != 0 || a[1] and 0xf00 !in setOf(0, 0x100, 0x200, 0x300) -> "Mode d'entrée non pris en charge"
                    a[1] and 0x30000 != 0 && !(a[1] and 0xf00 == 0 && a[1] ushr 16 and 15 == 1) &&
                        (a[1] and 0xf00 != 0x300 || a[1] ushr 16 and 15 !in setOf(2, 3) || a[1] and 0x80 != 0) -> "Mode de bord d'entrée non pris en charge"
                    a[1] and 0xf00 == 0 && (a[1] and 15 !in 0..8 || a[1] and 0x1000 != 0) -> "Mode d'entrée directionnelle non pris en charge"
                    a[1] and 0xf00 == 0x300 && (a[1] and 15 !in 0..2 || a[1] and 0x1060 != 0) -> "Bouton ou mode souris non porté"
                    a[1] and 0x1000 != 0 && a.size < 3 -> "Filtre physique sans code de touche"
                    else -> null
                }
                124 -> when {
                    a.size < 3 || a[1] and 0xff0000ce.toInt() != 0 || a[1] ushr 8 and 15 !in 0..8 -> "Mode de lecture d'état non pris en charge"
                    a[1] ushr 12 and 15 == 1 && (a.size != 4 || a[3] !in (0..7).toSet() + (9..17).toSet() || a[1] and 0xff0020 != 0) -> "Champ de personnage non porté"
                    a[1] ushr 12 and 15 == 2 && (a.size != 4 || a[1] ushr 16 and 255 !in (0..10).toSet() + setOf(22, 23, 152, 153, 154, 155, 161, 162)) -> "Champ de position non porté"
                    a[1] ushr 12 and 15 == 3 && (a.size != 3 || a[2] !in setOf(0, 1, 2, 7, 14, 15, 16, 17, 18, 19, 20, 21, 22) || a[1] and 0xff0020 != 0) -> "Champ d'état général non porté"
                    a[1] ushr 12 and 15 == 4 && (a.size != 4 || a[3] !in (0..12).toSet() + setOf(21, 22) || a[1] and 0xff0020 != 0) -> "Champ d'image non porté"
                    a[1] ushr 12 and 15 !in 1..4 -> "Catégorie de lecture d'état non portée"
                    else -> null
                }
                160 -> if (a.size != 2 || a[1] and -131072 != 0) "Transition incomplète ou options inconnues" else null
                126 -> when {
                    a.size == 1 && a[0] ushr 28 == 0 && a[0] and 0xfffffc08.toInt() == 0 -> null
                    a[0] ushr 28 == 1 && a[0] and 0x0ffffef0 == 0 && a[0] and 15 in 0..3 && (a[0] and 15 != 0 || a.size >= 2) -> null
                    else -> "Mode d'interdiction de touches non pris en charge"
                }
                130 -> if (a.size !in 5..6 || a[4] !in setOf(0, 1, 16, 17, 32, 33)) "Mode de téléportation non pris en charge" else null
                140 -> if (a.isEmpty() || a[0] ushr 24 !in (0..2).toSet() + setOf(255) || a[0] ushr 4 and 15 !in 0..2 || a[0] and 15 !in 0..3 ||
                    a[0] ushr 24 == 255 && (a[0] and 0x00ffff00 != 0x00ffff00 || a[0] and 15 != 0)) "Mode audio non pris en charge" else null
                151 -> if (a.size !in setOf(2, 5) || a[0] and 0xfe000000.toInt() != 0) "Mode de tonalité ou de flash non pris en charge" else null
                150 -> when {
                    a.size < 2 || a[0] and 15 !in 0..3 || a[0] and 0xfc000000.toInt() != 0 -> "Mode de pic non porté (quadrilatère ou option avancée)"
                    a[0] and 15 == 2 && a.size !in 3..6 -> "Effacement de pic avec options inconnues"
                    a[0] and 15 == 3 && a.size !in 2..3 -> "Annulation différée de pic incomplète"
                    a[0] and 15 < 2 && (a.size < 10 || a[0] ushr 4 and 15 !in 0..4 || a[0] ushr 8 and 15 !in setOf(0, 1, 2, 3, 15) || a[0] ushr 12 and 15 !in (0..6).toSet() + 15 || a[0] ushr 20 and 15 !in 0..4) -> "Paramètre de pic invalide"
                    else -> null
                }
                201 -> if (command.route == null || command.route.commands.any { it.opcode !in SUPPORTED_ROUTE }) "Mode de route non pris en charge" else null
                240 -> if (a.size != 2) "Changement de propriété de tuile incomplet" else if (a[0] != -1_000_000 && a[1] and 0x3ff.inv() != 0) "Propriété de tuile non portée" else null
                241 -> if (a.size != 1) "Changement de tileset incomplet" else null
                242 -> if (a.size != 6) "Rectangle de tuiles incomplet" else null
                220 -> if (a.size < 2 || a[0] !in 0..1) "Opération de sauvegarde partielle non prise en charge" else null
                221, 222 -> if (a.size !in 3..4 || a.getOrElse(3) { 0 } and 1.inv() != 0) "Mode de valeur sauvegardée non pris en charge" else null
                250 -> if (a.size < 4 || a[3] and 0xfff8ec00.toInt() != 0 || a[3] and 15 !in 0..2 || (a[3] ushr 4 and 15) > 7 || (a[3] ushr 8 and 15) > 2) "Mode DB non pris en charge" else null
                251 -> when {
                    a.size != 5 || a[2] != 0 || a[3] and 0x31300.inv() != 0 || a[3] ushr 8 and 15 !in 0..2 || a[3] and 0xf000 !in setOf(0, 0x1000) -> "Mode CSV DB non porté"
                    command.strings.isEmpty() || a[3] and 0x30000 != 0 && command.strings.size < 4 -> "Nom de fichier ou de DB CSV absent"
                    else -> null
                }
                260 -> when {
                    a.size !in 3..4 || command.strings.size !in 2..3 -> "Téléchargement260 incomplet"
                    a[1] and 3 != a[1] || a.getOrElse(3) { 0 } and 0x176.inv() != 0 -> "Options de téléchargement260 non portées"
                    a[2] < 0 -> "Alias navigateur ou Pro260 non porté : ${a[2]}"
                    a[1] and 1 != 0 && a[2] == 0 -> "Réception260 en chaîne sans variable"
                    a.getOrElse(3) { 0 } and 16 != 0 && command.strings.size != 3 -> "Corps POST260 absent"
                    else -> null
                }
                270 -> if (a.isEmpty() || a[0] and -512 != 0 || a[0] and 15 !in 0..4 || (a[0] and 15 == 4 && a[0] ushr 4 and 15 !in 0..10)) "Opération de partie non prise en charge" else null
                280 -> if (a.size != 2 || a[0] and 0xfffff000.toInt() != 0 || a[0] ushr 8 !in 0..2) "Mode de secousse inconnu" else null
                281 -> if (a.size != 3 || a[0] and 0xfffffc00.toInt() != 0 || a[0] and 15 !in 0..3) "Mode de défilement inconnu" else null
                290 -> if (a.size !in 7..8 || a.size == 8 && a[0] and 15 != 0 || !(a[0] == 2 || a[0] and 15 == 0 && (a[0] ushr 4 in 0..14 || a[0] ushr 4 in 17..28) || a[0] and 15 == 1 && a[0] ushr 4 in (0..3).toSet() + setOf(7, 8, 12) || a[0] and 15 == 3 && a[0] ushr 4 in 0..10)) "Effet Wolf non porté" else null
                else -> null
            }
        }
        private fun intAt(bytes: ByteArray, index: Int): Int = (bytes[index].toInt() and 255) or ((bytes[index + 1].toInt() and 255) shl 8) or ((bytes[index + 2].toInt() and 255) shl 16) or (bytes[index + 3].toInt() shl 24)
        private val basicKeyFlags = linkedMapOf(1 to NativeKey.ACCEPT, 2 to NativeKey.BACK, 4 to NativeKey.SHIFT,
            16 to NativeKey.DOWN, 32 to NativeKey.LEFT, 64 to NativeKey.RIGHT, 128 to NativeKey.UP)
        private fun direction(dx: Int, dy: Int): Int = when { dy > 0 -> if (dx < 0) 1 else if (dx > 0) 3 else 2; dy < 0 -> if (dx < 0) 7 else if (dx > 0) 9 else 8; dx < 0 -> 4; dx > 0 -> 6; else -> 2 }
        private fun delta(direction: Int): Pair<Int, Int> = when (direction) { 1 -> -1 to 1; 2 -> 0 to 1; 3 -> 1 to 1; 4 -> -1 to 0; 6 -> 1 to 0; 7 -> -1 to -1; 8 -> 0 to -1; 9 -> 1 to -1; else -> 0 to 0 }
    }
}
