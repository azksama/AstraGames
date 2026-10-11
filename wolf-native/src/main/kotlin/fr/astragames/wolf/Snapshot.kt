package fr.astragames.wolf

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Astra-owned format. Never written over the game's Windows save files. */
internal object WolfSnapshot {
    private const val MAGIC = 0x41574e53 // AWNS
    private const val VERSION = 22
    private const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_ENTRIES = 100_000
    data class State(
        val title: String, val ticks: Long, val mapId: Int,
        val x: Float, val y: Float, val direction: Int, val path: String, val randomState: Int,
        val numbers: Map<Int, Int>, val strings: Map<Int, String>,
        val self: Map<Triple<Int, Int, Int>, Int>, val common: Map<Pair<Int, Int>, Int>,
        val commonStrings: Map<Pair<Int, Int>, String>, val system: Map<Int, Int>, val systemStrings: Map<Int, String>,
        val mutableNumbers: Map<Triple<Int, Int, Int>, Int>, val mutableStrings: Map<Triple<Int, Int, Int>, String>,
        val hero: CharacterState? = null, val actors: List<CharacterState> = emptyList(),
        val erasedEvents: Set<Int> = emptySet(), val pictures: List<NativePicture> = emptyList(),
        val allowMovement: Boolean = false, val freezeWorld: Boolean = false,
        val bannedKeys: Set<NativeKey> = emptySet(),
        val companions: List<CharacterState> = emptyList(),
        val mutableRows: Map<Int, Int> = emptyMap(),
        val partyFollow: Boolean = true, val partySync: Boolean = false,
        val rememberedParty: List<String> = emptyList(),
        val partyTrail: List<Triple<Float, Float, Int>> = emptyList(),
        val audio: List<NativeEffect.Audio> = emptyList(),
        val screenOpacity: Int = 255, val screenColor: Int = 0,
        val transitionMode: Int = -1, val transitionDuration: Int = 10, val transitionWait: Boolean = false,
        val tweens: List<TweenState> = emptyList(),
        val mapZoom: Float = 1f, val mapZoomCenterX: Float = 0f, val mapZoomCenterY: Float = 0f,
        val mapZoomTween: MapZoomState? = null,
        val movementBannedKeys: Set<NativeKey> = bannedKeys,
        val mouseLeftBanned: Boolean = false,
        val timerFrameOrigin: Long = 0,
        val timerSecondOrigin: Long = 0,
        val screenEffects: WolfScreenEffects.State = WolfScreenEffects.State(),
        val pictureEffects: List<WolfPictureEffectState> = emptyList(),
        val mapShake: WolfMapShake.State = WolfMapShake.State(),
        val delayedPictures: List<DelayedPictureState> = emptyList(),
        val characterEffects: List<WolfCharacterEffectState> = emptyList(),
        val camera: WolfMapCamera.State? = null,
        val extendedPictureEffects: List<WolfExtendedPictureEffectState> = emptyList(),
        val visibilityMask: NativeVisibilityMask? = null,
        val maskedPictures: Set<Int> = emptySet(),
        val maskedCharacters: Set<Int> = emptySet(),
        val mapMasked: Boolean = false,
        val bgsVolumes: Map<Int, Int> = emptyMap(),
        val bgsPitches: Map<Int, Int> = emptyMap(),
        val mapChanges: WolfMapChanges.State? = null,
        val databaseChanges: WolfDatabaseChanges.State? = null,
        val mapTextures: WolfMapTextureLayers? = null,
    )
    data class CharacterState(val id: Int, val x: Float, val y: Float, val path: String,
        val direction: Int, val pattern: Int, val opacity: Int, val flags: Int,
        val speed: Int, val tileId: Int, val targetX: Float, val targetY: Float, val moveTicks: Int,
        val route: WolfRoute? = null, val routeIndex: Int = 0, val routeWait: Int = 0, val moving: Boolean = false,
        val shadow: Int = 0, val blend: Int = 0,
        val stepSize: Int = 0, val elevation: Int = 0, val jumpPeak: Int = 0, val jumpRemaining: Int = 0,
        val routeForced: Boolean = false, val savedRoute: WolfRoute? = null, val savedRouteIndex: Int = 0, val routeCommon: Int = -1,
        val navigation: NavigationState? = null,
        val animation: WolfCharacterAnimation.State? = null,
        val pixelMotion: WolfCharacterPixelMotion.State? = null,
        val motion: WolfCharacterMotion.State? = null)
    data class NavigationState(val goalX: Int, val goalY: Int, val mode: Int, val waypoints: List<Pair<Int, Int>>)
    data class TweenState(val from: NativePicture, val to: NativePicture, val length: Int, val elapsed: Int, val erase: Boolean)
    data class MapZoomState(val from: Float, val to: Float, val length: Int, val elapsed: Int,
        val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)
    data class DelayedPictureState(val id: Int, val action: Int, val duration: Int, val target: NativePicture?, val remaining: Int,
        val effectOperation: Int = -1, val p1: Int = 0, val p2: Int = 0, val p3: Int = 0)

    fun encode(state: State): ByteArray {
        val output = LimitedOutput()
        DataOutputStream(output).use { writer ->
            writer.writeInt(MAGIC); writer.writeInt(VERSION)
            writer.text(state.title); writer.writeLong(state.ticks); writer.writeInt(state.mapId)
            writer.writeFloat(state.x); writer.writeFloat(state.y); writer.writeInt(state.direction)
            writer.text(state.path); writer.writeInt(state.randomState)
            writer.numberMap(state.numbers); writer.stringMap(state.strings)
            writer.tripleNumberMap(state.self); writer.pairNumberMap(state.common)
            writer.pairStringMap(state.commonStrings); writer.numberMap(state.system); writer.stringMap(state.systemStrings)
            writer.tripleNumberMap(state.mutableNumbers); writer.tripleStringMap(state.mutableStrings)
            writer.writeBoolean(state.hero != null); state.hero?.let { writer.character(it) }
            writer.count(state.actors.size); state.actors.forEach { writer.character(it) }
            writer.count(state.erasedEvents.size); state.erasedEvents.sorted().forEach(writer::writeInt)
            writer.count(state.pictures.size); state.pictures.forEach { writer.picture(it) }
            writer.writeBoolean(state.allowMovement); writer.writeBoolean(state.freezeWorld)
            writer.count(state.bannedKeys.size); state.bannedKeys.sortedBy { it.ordinal }.forEach { writer.writeInt(it.ordinal) }
            writer.count(state.companions.size); state.companions.forEach { writer.character(it) }
            writer.numberMap(state.mutableRows); writer.writeBoolean(state.partyFollow); writer.writeBoolean(state.partySync)
            writer.count(state.rememberedParty.size); state.rememberedParty.forEach { writer.text(it) }
            writer.count(state.partyTrail.size); state.partyTrail.forEach { (x, y, direction) -> writer.writeFloat(x); writer.writeFloat(y); writer.writeInt(direction) }
            writer.count(state.audio.size); state.audio.forEach { audio ->
                writer.writeInt(audio.channel); writer.optionalText(audio.path); writer.writeInt(audio.volume)
                writer.writeInt(audio.pitch); writer.writeBoolean(audio.loop); writer.writeInt(audio.fadeTicks); writer.writeBoolean(audio.stop)
                writer.writeInt(audio.position); writer.writeInt(audio.loopStart); writer.writeInt(audio.voice)
                writer.writeInt(audio.pan); writer.writeInt(audio.delayTicks)
            }
            writer.writeInt(state.screenOpacity); writer.writeInt(state.screenColor)
            writer.writeInt(state.transitionMode); writer.writeInt(state.transitionDuration); writer.writeBoolean(state.transitionWait)
            writer.count(state.tweens.size); state.tweens.forEach { tween -> writer.picture(tween.from); writer.picture(tween.to); writer.writeInt(tween.length); writer.writeInt(tween.elapsed); writer.writeBoolean(tween.erase) }
            writer.writeFloat(state.mapZoom); writer.writeFloat(state.mapZoomCenterX); writer.writeFloat(state.mapZoomCenterY)
            writer.writeBoolean(state.mapZoomTween != null); state.mapZoomTween?.let { writer.writeFloat(it.from); writer.writeFloat(it.to); writer.writeInt(it.length); writer.writeInt(it.elapsed) }
            state.mapZoomTween?.let { writer.writeFloat(it.fromX); writer.writeFloat(it.fromY); writer.writeFloat(it.toX); writer.writeFloat(it.toY) }
            writer.count(state.movementBannedKeys.size); state.movementBannedKeys.sortedBy { it.ordinal }.forEach { writer.writeInt(it.ordinal) }
            writer.writeBoolean(state.mouseLeftBanned); writer.writeLong(state.timerFrameOrigin); writer.writeLong(state.timerSecondOrigin)
            with(state.screenEffects) {
                writer.tone(tone); writer.tone(from); writer.tone(target); writer.writeInt(toneTotal); writer.writeInt(toneRemaining)
                writer.tone(flash); writer.writeInt(flashTotal); writer.writeInt(flashRemaining)
            }
            writer.count(state.pictureEffects.size)
            state.pictureEffects.forEach { effect ->
                require(effect.words.size == WolfPictureEffects.WORD_COUNT)
                writer.writeInt(effect.id); effect.words.forEach(writer::writeInt)
            }
            listOf(state.mapShake.horizontal, state.mapShake.vertical).forEach { axis ->
                writer.writeInt(axis.amplitude); writer.writeInt(axis.speed); writer.writeInt(axis.remaining)
                writer.writeInt(axis.phase); writer.writeInt(axis.direction)
            }
            writer.count(state.delayedPictures.size)
            state.delayedPictures.forEach {
                writer.writeInt(it.id); writer.writeInt(it.action); writer.writeInt(it.duration); writer.writeInt(it.remaining)
                writer.writeBoolean(it.target != null); it.target?.let { target -> writer.picture(target) }
                writer.writeInt(it.effectOperation); writer.writeInt(it.p1); writer.writeInt(it.p2); writer.writeInt(it.p3)
            }
            writer.count(state.characterEffects.size)
            state.characterEffects.forEach {
                require(it.words.size == WolfCharacterEffects.WORD_COUNT)
                writer.writeInt(it.id); it.words.forEach(writer::writeInt)
            }
            writer.writeBoolean(state.camera != null)
            state.camera?.let {
                writer.writeInt(it.x); writer.writeInt(it.y); writer.writeBoolean(it.followsHero)
                listOf(it.horizontal, it.vertical).forEach { axis ->
                    writer.writeInt(axis.remaining); writer.writeInt(axis.step); writer.writeInt(axis.direction)
                }
            }
            writer.count(state.extendedPictureEffects.size)
            state.extendedPictureEffects.forEach {
                require(it.words.size == WolfExtendedPictureEffects.WORD_COUNT)
                writer.writeInt(it.id); it.words.forEach(writer::writeInt)
            }
            writer.writeBoolean(state.visibilityMask != null)
            state.visibilityMask?.let {
                require(WolfVisibilityMask.validDimensions(it.width, it.height) && it.pixels.size == it.width * it.height)
                writer.writeInt(it.width); writer.writeInt(it.height); writer.write(it.pixels)
            }
            writer.count(state.maskedPictures.size); state.maskedPictures.sorted().forEach(writer::writeInt)
            writer.count(state.maskedCharacters.size); state.maskedCharacters.sorted().forEach(writer::writeInt)
            writer.writeBoolean(state.mapMasked)
            writer.numberMap(state.bgsVolumes); writer.numberMap(state.bgsPitches)
            writer.writeBoolean(state.mapChanges != null)
            state.mapChanges?.let {
                require(it.tilesetId >= 0 && it.tiles.keys.all { index -> index >= 0 } && it.passage.keys.all { index -> index >= 0 })
                writer.writeInt(it.tilesetId); writer.numberMap(it.tiles); writer.numberMap(it.passage)
            }
            writer.writeBoolean(state.databaseChanges != null)
            state.databaseChanges?.let { db ->
                fun rowKey(key: WolfDatabaseChanges.RowKey) { writer.writeInt(key.kind.ordinal); writer.writeInt(key.type) }
                fun cell(key: WolfDatabaseChanges.Cell) { rowKey(WolfDatabaseChanges.RowKey(key.kind, key.type)); writer.writeInt(key.row); writer.writeInt(key.field) }
                writer.count(db.rows.size)
                db.rows.entries.sortedWith(compareBy({ it.key.kind.ordinal }, { it.key.type })).forEach { rowKey(it.key); writer.writeInt(it.value) }
                val cells = compareBy<Map.Entry<WolfDatabaseChanges.Cell, *>>({ it.key.kind.ordinal }, { it.key.type }, { it.key.row }, { it.key.field })
                writer.count(db.numbers.size); db.numbers.entries.sortedWith(cells).forEach { cell(it.key); writer.writeInt(it.value) }
                writer.count(db.strings.size); db.strings.entries.sortedWith(cells).forEach { cell(it.key); writer.text(it.value) }
                writer.count(db.names.size)
                db.names.entries.sortedWith(compareBy({ it.key.first.kind.ordinal }, { it.key.first.type }, { it.key.second })).forEach {
                    rowKey(it.key.first); writer.writeInt(it.key.second); writer.text(it.value)
                }
            }
            writer.writeBoolean(state.mapTextures != null)
            state.mapTextures?.let { layers ->
                writer.mapTexture(layers.backdrop); writer.mapTexture(layers.fog)
            }
        }
        val body = output.toByteArray()
        require(body.size <= MAX_BYTES - 8) { "Sauvegarde native trop grande" }
        val checked = ByteArrayOutputStream()
        DataOutputStream(checked).use { writer -> writer.writeInt(body.size); writer.write(body); writer.writeInt(CRC32().apply { update(body) }.value.toInt()) }
        return checked.toByteArray()
    }

    fun decode(bytes: ByteArray, expectedTitle: String): State {
        require(bytes.size in 12..MAX_BYTES) { "Taille de sauvegarde native invalide" }
        val outer = DataInputStream(ByteArrayInputStream(bytes))
        val size = outer.readInt()
        require(size == bytes.size - 8 && size >= 8) { "Sauvegarde native tronquée" }
        val body = ByteArray(size).also(outer::readFully)
        require(outer.readInt() == CRC32().apply { update(body) }.value.toInt()) { "Somme de contrôle de sauvegarde incorrecte" }
        val input = DataInputStream(ByteArrayInputStream(body))
        require(input.readInt() == MAGIC) { "Format de sauvegarde native non pris en charge" }
        val version = input.readInt(); require(version in 1..VERSION) { "Version de sauvegarde native non prise en charge" }
        val title = input.text()
        require(title == expectedTitle) { "Sauvegarde d'un autre jeu" }
        val ticks = input.readLong(); val map = input.readInt()
        val x = input.readFloat(); val y = input.readFloat(); val direction = input.readInt()
        val path = input.text(); val random = input.readInt()
        require(ticks >= 0 && map >= 0 && x.isFinite() && y.isFinite() && direction in 1..9) { "État de sauvegarde invalide" }
        var state = State(title, ticks, map, x, y, direction, path, random,
            input.numberMap(), input.stringMap(), input.tripleNumberMap(), input.pairNumberMap(), input.pairStringMap(),
            input.numberMap(), input.stringMap(), input.tripleNumberMap(), input.tripleStringMap())
        if (version >= 2) {
            val hero = if (input.readBoolean()) input.character(version) else null
            val actors = buildList { repeat(input.count(48)) { add(input.character(version)) } }
            require(actors.map { it.id }.distinct().size == actors.size) { "Événements de sauvegarde dupliqués" }
            val erased = buildSet { repeat(input.count(4)) { require(add(input.readInt())) { "Événement effacé dupliqué" } } }
            val pictures = buildList { repeat(input.count(68)) { add(input.picture(version)) } }
            require(pictures.map { it.id }.distinct().size == pictures.size) { "Images de sauvegarde dupliquées" }
            val movement = input.readBoolean(); val frozen = input.readBoolean()
            val banned = buildSet { repeat(input.count(4)) { val key = NativeKey.entries.getOrNull(input.readInt()) ?: error("Touche de sauvegarde invalide"); require(add(key)); } }
            state = state.copy(hero = hero, actors = actors, erasedEvents = erased, pictures = pictures, allowMovement = movement, freezeWorld = frozen, bannedKeys = banned)
        }
        if (version >= 4) {
            val companions = buildList { repeat(input.count(48)) { add(input.character(version)) } }
            require(companions.size <= 5 && companions.map { it.id }.distinct().size == companions.size && companions.all { it.id in -7..-3 }) { "Partie de sauvegarde invalide" }
            val mutableRows = input.numberMap(); require(mutableRows.all { (type, count) -> type in 0..99 && count in 0..10_000 }) { "Taille DB sauvegardée invalide" }
            val follow = input.readBoolean(); val sync = input.readBoolean()
            val remembered = buildList { repeat(input.count(4)) { add(input.text()) } }; require(remembered.size <= 6)
            val trail = buildList { repeat(input.count(12)) { val point = Triple(input.readFloat(), input.readFloat(), input.readInt()); require(point.first.isFinite() && point.second.isFinite() && point.third in 1..9); add(point) } }; require(trail.size <= 4096)
            val audio = buildList { repeat(input.count(22)) {
                val channel = input.readInt(); val path = input.optionalText(); val volume = input.readInt(); val pitch = input.readInt(); val loop = input.readBoolean(); val fade = input.readInt(); val stop = input.readBoolean()
                require(channel in 0..255 && volume in 0..400 && pitch in 1..400 && fade in 0..360_000)
                val position = if (version >= 15) input.readInt() else 0
                val loopStart = if (version >= 15) input.readInt() else 0
                val voice = if (version >= 15) input.readInt() else 0
                val pan = if (version >= 15) input.readInt() else 0
                val delay = if (version >= 15) input.readInt() else 0
                require(position >= 0 && loopStart >= -127 && voice in -1..999_999 && pan in -255..255 && delay in 0..360_000)
                add(NativeEffect.Audio(channel, path, volume, pitch, loop, fade, stop, position, loopStart, voice, pan, delay))
            } }; require(audio.map { it.channel to it.voice }.distinct().size == audio.size)
            val opacity = input.readInt(); val color = input.readInt(); require(opacity in 0..255)
            val mode = input.readInt(); val duration = input.readInt(); val wait = input.readBoolean(); require(duration in 0..65535)
            val tweens = buildList { repeat(input.count(24)) { val from = input.picture(version); val to = input.picture(version); val length = input.readInt(); val elapsed = input.readInt(); val erase = input.readBoolean(); require(from.id == to.id && length > 0 && elapsed in 0..length); add(TweenState(from, to, length, elapsed, erase)) } }
            state = state.copy(companions = companions, mutableRows = mutableRows, partyFollow = follow, partySync = sync, rememberedParty = remembered, partyTrail = trail, audio = audio,
                screenOpacity = opacity, screenColor = color, transitionMode = mode, transitionDuration = duration, transitionWait = wait, tweens = tweens)
        }
        if (version >= 5) {
            val zoom = input.readFloat(); val cx = input.readFloat(); val cy = input.readFloat()
            require(zoom.isFinite() && zoom in .01f..100f && cx.isFinite() && cy.isFinite()) { "Zoom sauvegardé invalide" }
            var tween = if (input.readBoolean()) MapZoomState(input.readFloat(), input.readFloat(), input.readInt(), input.readInt(), cx, cy, cx, cy).also {
                require(it.from.isFinite() && it.to.isFinite() && it.from in .01f..100f && it.to in .01f..100f && it.length in 1..360_000 && it.elapsed in 0..it.length) { "Animation de zoom sauvegardée invalide" }
            } else null
            if (version >= 6 && tween != null) tween = tween.copy(fromX = input.readFloat(), fromY = input.readFloat(), toX = input.readFloat(), toY = input.readFloat()).also {
                require(listOf(it.fromX, it.fromY, it.toX, it.toY).all(Float::isFinite)) { "Centre de zoom sauvegardé invalide" }
            }
            state = state.copy(mapZoom = zoom, mapZoomCenterX = cx, mapZoomCenterY = cy, mapZoomTween = tween)
        }
        if (version >= 8) {
            val movement = buildSet { repeat(input.count(4)) { val key = NativeKey.entries.getOrNull(input.readInt()) ?: error("Touche de déplacement sauvegardée invalide"); require(add(key)) } }
            val mouse = input.readBoolean(); val frameOrigin = input.readLong(); val secondOrigin = input.readLong()
            require(frameOrigin in 0..ticks && secondOrigin in 0..ticks) { "Origine du compteur sauvegardé invalide" }
            state = state.copy(movementBannedKeys = movement, mouseLeftBanned = mouse, timerFrameOrigin = frameOrigin, timerSecondOrigin = secondOrigin)
        }
        if (version >= 9) {
            val screen = WolfScreenEffects.State(input.tone(), input.tone(), input.tone(), input.readInt(), input.readInt(),
                input.tone(), input.readInt(), input.readInt())
            WolfScreenEffects().restore(screen)
            state = state.copy(screenEffects = screen)
        }
        if (version >= 10) {
            val effects = buildList { repeat(input.count(4 + 4 * WolfPictureEffects.WORD_COUNT)) {
                add(WolfPictureEffectState(input.readInt(), List(WolfPictureEffects.WORD_COUNT) { input.readInt() }))
            } }
            val pictureIds = state.pictures.mapTo(hashSetOf()) { it.id }
            require(effects.all { it.id in pictureIds }) { "Effet d'image sauvegardée sans image" }
            WolfPictureEffects().restore(effects)
            state = state.copy(pictureEffects = effects)
        }
        if (version >= 11) {
            fun axis() = WolfMapShake.Axis(input.readInt(), input.readInt(), input.readInt(), input.readInt(), input.readInt())
            val shake = WolfMapShake.State(axis(), axis())
            WolfMapShake().restore(shake)
            val delayed = buildList { repeat(input.count(17)) {
                val id = input.readInt(); val action = input.readInt(); val duration = input.readInt(); val remaining = input.readInt()
                val target = if (input.readBoolean()) input.picture(version) else null
                val operation = if (version >= 18) input.readInt() else -1
                val p1 = if (version >= 18) input.readInt() else 0
                val p2 = if (version >= 18) input.readInt() else 0
                val p3 = if (version >= 18) input.readInt() else 0
                require((action in 0..2 || action == 4) && duration in 0..360_000 && remaining in (if (action == 4) 0 else 1)..360_000 && (target == null || target.id == id)) { "Image différée sauvegardée invalide" }
                require(if (action == 4) target == null && (operation in 0..14 || operation in 17..28) else operation == -1 && (action == 2 || target != null))
                add(DelayedPictureState(id, action, duration, target, remaining, operation, p1, p2, p3))
            } }
            require(delayed.size <= 10_000)
            state = state.copy(mapShake = shake, delayedPictures = delayed)
        }
        if (version >= 12) {
            val effects = buildList { repeat(input.count(4 + 4 * WolfCharacterEffects.WORD_COUNT)) {
                add(WolfCharacterEffectState(input.readInt(), List(WolfCharacterEffects.WORD_COUNT) { input.readInt() }))
            } }
            val ids = (state.actors + state.companions).mapTo(hashSetOf()) { it.id }.apply { add(-2) }
            require(effects.all { it.id in ids }) { "Effet de personnage sauvegardé absent" }
            WolfCharacterEffects().restore(effects)
            state = state.copy(characterEffects = effects)
        }
        if (version >= 13 && input.readBoolean()) {
            val x = input.readInt(); val y = input.readInt(); val follows = input.readBoolean()
            fun axis() = WolfMapCamera.Axis(input.readInt(), input.readInt(), input.readInt())
            val camera = WolfMapCamera.State(x, y, follows, axis(), axis())
            WolfMapCamera().restore(camera)
            state = state.copy(camera = camera)
        }
        if (version >= 14) {
            val extended = buildList { repeat(input.count(4 + 4 * WolfExtendedPictureEffects.WORD_COUNT)) {
                add(WolfExtendedPictureEffectState(input.readInt(), List(WolfExtendedPictureEffects.WORD_COUNT) { input.readInt() }))
            } }
            val ids = state.pictures.mapTo(hashSetOf()) { it.id }
            require(extended.all { it.id in ids }) { "Effet étendu sauvegardé sans image" }
            WolfExtendedPictureEffects().restore(extended)
            state = state.copy(extendedPictureEffects = extended)
            val mask = if (input.readBoolean()) {
                val width = input.readInt(); val height = input.readInt()
                require(WolfVisibilityMask.validDimensions(width, height) && width.toLong() * height <= input.available()) { "Masque de sauvegarde tronqué" }
                NativeVisibilityMask(width, height, ByteArray(width * height).also(input::readFully))
            } else null
            fun masks() = buildSet { repeat(input.count(4)) { require(add(input.readInt())) { "Masque sauvegardé dupliqué" } } }
            val pictures = masks(); val characters = masks()
            require(pictures.all { it in ids }) { "Masque sauvegardé sans image" }
            val actorIds = (state.actors + state.companions).mapTo(hashSetOf()) { it.id }.apply { add(-2) }
            require(characters.all { it in actorIds }) { "Masque sauvegardé sans personnage" }
            state = state.copy(visibilityMask = mask, maskedPictures = pictures, maskedCharacters = characters, mapMasked = input.readBoolean())
        }
        if (version >= 15) {
            val volumes = input.numberMap(); val pitches = input.numberMap()
            require(volumes.all { (voice, value) -> voice in 0..999_999 && value in 0..100 } &&
                pitches.all { (voice, value) -> voice in 0..999_999 && value in 0..400 }) { "Paramètres BGS sauvegardés invalides" }
            state = state.copy(bgsVolumes = volumes, bgsPitches = pitches)
        }
        if (version >= 17 && input.readBoolean()) {
            val tileset = input.readInt(); val tiles = input.numberMap(); val passage = input.numberMap()
            require(tileset >= 0 && tiles.keys.all { it >= 0 } && passage.keys.all { it >= 0 }) { "Modifications de carte sauvegardées invalides" }
            state = state.copy(mapChanges = WolfMapChanges.State(tileset, tiles, passage))
        }
        if (version >= 18 && input.readBoolean()) {
            fun rowKey(): WolfDatabaseChanges.RowKey {
                val kind = WolfDatabaseKind.entries.getOrNull(input.readInt()) ?: error("Type de DB sauvegardée invalide")
                val type = input.readInt(); require(type in 0..99) { "Type DB sauvegardé hors limite" }
                return WolfDatabaseChanges.RowKey(kind, type)
            }
            fun cell(): WolfDatabaseChanges.Cell {
                val key = rowKey(); val row = input.readInt(); val field = input.readInt()
                require(row in 0..9999 && field in 0..999) { "Cellule DB sauvegardée hors limite" }
                return WolfDatabaseChanges.Cell(key.kind, key.type, row, field)
            }
            val rows = linkedMapOf<WolfDatabaseChanges.RowKey, Int>()
            repeat(input.count(12)) { val key = rowKey(); val count = input.readInt(); require(count in 0..10_000 && rows.put(key, count) == null) }
            val numbers = linkedMapOf<WolfDatabaseChanges.Cell, Int>()
            repeat(input.count(20)) { val key = cell(); require(numbers.put(key, input.readInt()) == null) }
            val strings = linkedMapOf<WolfDatabaseChanges.Cell, String>()
            repeat(input.count(20)) { val key = cell(); require(strings.put(key, input.text()) == null) }
            val names = linkedMapOf<Pair<WolfDatabaseChanges.RowKey, Int>, String>()
            repeat(input.count(16)) { val key = rowKey() to input.readInt(); require(key.second in 0..9999 && names.put(key, input.text()) == null) }
            require(rows.size <= 300 && numbers.size.toLong() + strings.size + names.size <= MAX_ENTRIES) { "Overlay DB sauvegardé trop grand" }
            state = state.copy(databaseChanges = WolfDatabaseChanges.State(rows, numbers, strings, names))
        }
        if (version >= 22 && input.readBoolean()) {
            state = state.copy(mapTextures = WolfMapTextureLayers(input.mapTexture(), input.mapTexture()))
        }
        require(input.available() == 0) { "Données de sauvegarde supplémentaires" }
        return state
    }

    private fun DataOutputStream.tone(value: NativeTone) { writeInt(value.red); writeInt(value.green); writeInt(value.blue) }
    private fun DataInputStream.tone() = NativeTone(readInt(), readInt(), readInt())
    private fun validateMapTexture(value: WolfMapTextureState) {
        WolfMapTextures.validate(value.scroll, value.behaviorVersion)
        require(value.path == null || (value.path.isNotEmpty() && '\u0000' !in value.path)) {
            "Chemin de texture de carte sauvegardé invalide"
        }
        require(!value.scroll.active || value.path != null) { "Texture de carte active sans ressource" }
    }
    private fun DataOutputStream.mapTexture(value: WolfMapTextureState) {
        validateMapTexture(value)
        writeInt(value.behaviorVersion)
        writeInt(value.id); writeBoolean(value.path != null); value.path?.let { text(it, 8192) }
        val scroll = value.scroll
        writeBoolean(scroll.active); writeInt(scroll.width); writeInt(scroll.height)
        writeInt(scroll.accumulatedX); writeInt(scroll.accumulatedY)
        writeInt(scroll.phaseX); writeInt(scroll.phaseY); writeInt(scroll.parallaxDivisor)
        writeBoolean(value.stringReloadPending)
    }
    private fun DataInputStream.mapTexture(): WolfMapTextureState {
        val behaviorVersion = readInt()
        val id = readInt(); val path = if (readBoolean()) text(8192) else null
        val scroll = WolfMapTextures.State(readBoolean(), readInt(), readInt(), readInt(), readInt(),
            readInt(), readInt(), readInt())
        return WolfMapTextureState(id, path, scroll, readBoolean(), behaviorVersion).also(::validateMapTexture)
    }
    private fun DataOutputStream.text(value: String, maximum: Int = 1_000_000) {
        require(value.length <= maximum) { "Chaîne de sauvegarde trop grande" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximum) { "Chaîne de sauvegarde trop grande" }
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(maximum: Int = 1_000_000): String {
        val size = readInt()
        require(size in 0..maximum && size <= available()) { "Chaîne de sauvegarde tronquée" }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(ByteArray(size).also(::readFully))).toString()
    }
    private fun DataOutputStream.count(size: Int) { require(size in 0..MAX_ENTRIES); writeInt(size) }
    private fun DataInputStream.count(minimumBytes: Int): Int = readInt().also { require(it in 0..MAX_ENTRIES && it.toLong() * minimumBytes <= available()) { "Compte de sauvegarde invalide" } }
    private fun DataOutputStream.numberMap(map: Map<Int, Int>) { count(map.size); map.toSortedMap().forEach { (k, v) -> writeInt(k); writeInt(v) } }
    private fun DataInputStream.numberMap(): Map<Int, Int> = buildMap { repeat(count(8)) { val k = readInt(); require(k !in this); put(k, readInt()) } }
    private fun DataOutputStream.stringMap(map: Map<Int, String>) { count(map.size); map.toSortedMap().forEach { (k, v) -> writeInt(k); text(v) } }
    private fun DataInputStream.stringMap(): Map<Int, String> = buildMap { repeat(count(8)) { val k = readInt(); require(k !in this); put(k, text()) } }
    private fun DataOutputStream.pairNumberMap(map: Map<Pair<Int, Int>, Int>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); writeInt(v) } }
    private fun DataInputStream.pairNumberMap(): Map<Pair<Int, Int>, Int> = buildMap { repeat(count(12)) { val k = readInt() to readInt(); require(k !in this); put(k, readInt()) } }
    private fun DataOutputStream.pairStringMap(map: Map<Pair<Int, Int>, String>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); text(v) } }
    private fun DataInputStream.pairStringMap(): Map<Pair<Int, Int>, String> = buildMap { repeat(count(12)) { val k = readInt() to readInt(); require(k !in this); put(k, text()) } }
    private fun DataOutputStream.tripleNumberMap(map: Map<Triple<Int, Int, Int>, Int>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); writeInt(k.third); writeInt(v) } }
    private fun DataInputStream.tripleNumberMap(): Map<Triple<Int, Int, Int>, Int> = buildMap { repeat(count(16)) { val k = Triple(readInt(), readInt(), readInt()); require(k !in this); put(k, readInt()) } }
    private fun DataOutputStream.tripleStringMap(map: Map<Triple<Int, Int, Int>, String>) { count(map.size); map.forEach { (k, v) -> writeInt(k.first); writeInt(k.second); writeInt(k.third); text(v) } }
    private fun DataInputStream.tripleStringMap(): Map<Triple<Int, Int, Int>, String> = buildMap { repeat(count(16)) { val k = Triple(readInt(), readInt(), readInt()); require(k !in this); put(k, text()) } }
    private fun DataOutputStream.character(c: CharacterState) {
        writeInt(c.id); writeFloat(c.x); writeFloat(c.y); text(c.path); writeInt(c.direction); writeInt(c.pattern)
        writeInt(c.opacity); writeInt(c.flags); writeInt(c.speed); writeInt(c.tileId)
        writeFloat(c.targetX); writeFloat(c.targetY); writeInt(c.moveTicks)
        route(c.route)
        writeInt(c.routeIndex); writeInt(c.routeWait); writeBoolean(c.moving); writeInt(c.shadow); writeInt(c.blend)
        writeInt(c.stepSize); writeInt(c.elevation); writeInt(c.jumpPeak); writeInt(c.jumpRemaining)
        writeBoolean(c.routeForced); route(c.savedRoute); writeInt(c.savedRouteIndex); writeInt(c.routeCommon)
        writeBoolean(c.navigation != null); c.navigation?.let { path ->
            writeInt(path.goalX); writeInt(path.goalY); writeInt(path.mode); count(path.waypoints.size)
            path.waypoints.forEach { (x, y) -> writeInt(x); writeInt(y) }
        }
        writeBoolean(c.animation != null)
        c.animation?.let {
            WolfCharacterAnimation.validate(it)
            writeInt(it.frequency); writeInt(it.idlePattern); writeInt(it.basePattern); writeInt(it.phase)
            writeDouble(it.accumulator); writeInt(it.idleTicks); writeInt(it.movementMemory); writeBoolean(it.animateOnStop)
        }
        writeBoolean(c.pixelMotion != null)
        c.pixelMotion?.let {
            writeInt(it.halfX); writeInt(it.halfY); writeInt(it.offsetX); writeInt(it.offsetY)
            writeInt(it.requestedX); writeInt(it.requestedY); writeInt(it.actualX); writeInt(it.actualY)
            writeBoolean(it.moved); writeBoolean(it.crossed)
        }
        writeBoolean(c.motion != null)
        c.motion?.let {
            WolfCharacterMotion.validate(it)
            writeInt(it.frequency); writeInt(it.autonomousMode); writeBoolean(it.alternateSpeed)
        }
    }
    private fun DataInputStream.character(version: Int): CharacterState {
        var c = CharacterState(readInt(), readFloat(), readFloat(), text(), readInt(), readInt(), readInt(), readInt(), readInt(), readInt(), readFloat(), readFloat(), readInt())
        if (version >= 4) {
            val route = route()
            val index = readInt(); val wait = readInt(); val moving = readBoolean(); require(index in 0..(route?.commands?.size ?: 0) && wait in 0..360_000)
            val shadow = readInt(); val blend = readInt(); require(shadow in -1..9999 && blend in 0..3)
            c = c.copy(route = route, routeIndex = index, routeWait = wait, moving = moving, shadow = shadow, blend = blend)
        }
        if (version >= 7) {
            val step = readInt(); val elevation = readInt(); val peak = readInt(); val remaining = readInt()
            require(step in 0..2 && elevation in -1_000_000..1_000_000 && peak in 0..360_000 && remaining in 0..peak * 2) { "Saut ou demi-pas sauvegardé invalide" }
            c = c.copy(stepSize = step, elevation = elevation, jumpPeak = peak, jumpRemaining = remaining)
            val forced = readBoolean(); val saved = route(); val savedIndex = readInt(); val common = readInt()
            require(savedIndex in 0..(saved?.commands?.size ?: 0) && common in -1..99_999) { "Contexte de route sauvegardé invalide" }
            c = c.copy(routeForced = forced, savedRoute = saved, savedRouteIndex = savedIndex, routeCommon = common)
        }
        if (version >= 8 && readBoolean()) {
            val x = readInt(); val y = readInt(); val mode = readInt(); val points = buildList { repeat(count(8)) { add(readInt() to readInt()) } }
            require(x >= 0 && y >= 0 && mode in 0..2 && points.size <= 65536) { "Trajet sauvegardé invalide" }
            c = c.copy(navigation = NavigationState(x, y, mode, points))
        }
        if (version >= 14 && readBoolean()) {
            c = c.copy(animation = WolfCharacterAnimation.validate(WolfCharacterAnimation.State(
                readInt(), readInt(), readInt(), readInt(), readDouble(), readInt(), readInt(), readBoolean())))
        }
        if (version >= 15 && readBoolean()) {
            c = c.copy(pixelMotion = WolfCharacterPixelMotion.State(readInt(), readInt(), readInt(), readInt(),
                readInt(), readInt(), readInt(), readInt(), readBoolean(), readBoolean()))
        }
        if (version >= 20 && readBoolean()) {
            c = c.copy(motion = WolfCharacterMotion.validate(WolfCharacterMotion.State(readInt(), readInt(), readBoolean())))
        }
        require(c.x.isFinite() && c.y.isFinite() && c.targetX.isFinite() && c.targetY.isFinite() && c.direction in 1..9 && c.direction != 5 && c.pattern in 0..16 && c.opacity in 0..255 && c.speed in 0..6 && c.moveTicks in 0..720_000) { "État de personnage invalide" }
        return c
    }
    private fun DataOutputStream.route(route: WolfRoute?) {
        writeBoolean(route != null); route?.let { writeInt(it.flags); count(it.header.size); write(it.header); count(it.commands.size); it.commands.forEach { c -> writeInt(c.opcode); count(c.args.size); c.args.forEach(::writeInt) } }
    }
    private fun DataInputStream.route(): WolfRoute? = if (readBoolean()) {
        val flags = readInt(); val header = ByteArray(count(1)).also(::readFully); require(header.size in setOf(0, 5))
        val commands = buildList { repeat(count(8)) { val opcode = readInt(); val args = IntArray(count(4)) { readInt() }; require(args.size <= 32); add(WolfRouteCommand(opcode, args)) } }
        WolfRoute(flags, commands, header)
    } else null
    private fun DataOutputStream.optionalText(s: String?) { writeBoolean(s != null); s?.let { text(it) } }
    private fun DataInputStream.optionalText(): String? = if (readBoolean()) text() else null
    private fun DataOutputStream.picture(p: NativePicture) {
        writeInt(p.id); optionalText(p.path); optionalText(p.text)
        writeFloat(p.x); writeFloat(p.y); writeFloat(p.width); writeFloat(p.height)
        writeFloat(p.scaleX); writeFloat(p.scaleY); writeInt(p.opacity); writeFloat(p.rotation)
        writeInt(p.blend); writeInt(p.z); writeInt(p.color); writeInt(p.anchor); writeFloat(p.fontSize); writeInt(p.fontColor); writeBoolean(p.screenRelative)
        writeBoolean(p.sourceRect != null); p.sourceRect?.let { writeFloat(it.x); writeFloat(it.y); writeFloat(it.width); writeFloat(it.height) }
        writeInt(p.kind); writeInt(p.divisionsX); writeInt(p.divisionsY); writeInt(p.pattern)
        writeFloat(p.colorR); writeFloat(p.colorG); writeFloat(p.colorB)
        writeFloat(p.rubySize); writeInt(p.shadowOffset); writeInt(p.visibleCharacters)
        tone(p.tone)
        require(p.imageFilters.size <= 256) { "Trop de conversions d'image sauvegardées" }
        count(p.imageFilters.size)
        p.imageFilters.forEach { filter ->
            writeInt(filter.operation); writeInt(filter.first); writeInt(filter.second)
            writeInt(filter.third); writeInt(filter.fourth)
        }
        writeBoolean(p.video != null)
        p.video?.let { video ->
            WolfVideo.validate(video)
            require(p.path != null && p.text == null && p.kind in 0..1)
            writeLong(video.token); writeLong(video.seekMs); writeLong(video.seekRevision)
            writeInt(video.speedPercent); writeInt(video.volumePercent); writeBoolean(video.loop)
            writeLong(video.positionMs); writeLong(video.durationMs); writeBoolean(video.playing)
            writeInt(video.width); writeInt(video.height)
        }
        fontMap(p.fontOverrides)
        writeBoolean(p.textTiming != null)
        p.textTiming?.let { clock ->
            require(p.kind == 2 && p.text != null && p.path == null) { "Horloge de texte sans picture texte" }
            val program = clock.program(p.text)
            require(WolfTextPictureTiming.validate(program, clock.state)) { "Horloge de texte sauvegardée invalide" }
            writeInt(clock.encoding.ordinal); writeInt(clock.compatibilityVersion); writeBoolean(clock.convertSpecial)
            val state = clock.state
            writeDouble(state.speed); writeDouble(state.progress); writeDouble(state.previous)
            writeInt(state.correction); writeInt(state.waitTicks); writeInt(state.pauseCheckpoint); writeBoolean(state.autoClose)
            count(state.speedAnchors.size)
            state.speedAnchors.toSortedMap().forEach { (cursor, value) -> writeInt(cursor); writeDouble(value) }
        }
    }
    private fun DataInputStream.picture(version: Int): NativePicture {
        val id = readInt(); val path = optionalText(); val text = optionalText()
        val x = readFloat(); val y = readFloat(); val width = readFloat(); val height = readFloat()
        val sx = readFloat(); val sy = readFloat(); val opacity = readInt(); val rotation = readFloat()
        val blend = readInt(); val z = readInt(); val color = readInt(); val anchor = readInt(); val fontSize = readFloat(); val fontColor = readInt(); val relative = readBoolean()
        val rect = if (readBoolean()) NativeRect(readFloat(), readFloat(), readFloat(), readFloat()) else null
        val kind = if (version >= 3) readInt() else if (text != null) 2 else 0
        val divX = if (version >= 3) readInt() else 1; val divY = if (version >= 3) readInt() else 1
        val pattern = if (version >= 3) readInt() else 1
        val red = if (version >= 3) readFloat() else 1f; val green = if (version >= 3) readFloat() else 1f; val blue = if (version >= 3) readFloat() else 1f
        val ruby = if (version >= 4) readFloat() else fontSize / 2; val shadow = if (version >= 4) readInt() else 0; val visible = if (version >= 4) readInt() else Int.MAX_VALUE
        val tone = if (version >= 10) tone() else NativeTone()
        val filters = if (version >= 15) buildList {
            val size = count(20); require(size <= 256) { "Trop de conversions d'image sauvegardées" }
            repeat(size) {
                val filter = NativeImageFilter(readInt(), readInt(), readInt(), readInt(), readInt())
                require(filter.operation in setOf(21, 22, 23, 24, 26)) { "Conversion d'image sauvegardée inconnue" }
                add(filter)
            }
        } else emptyList()
        val video = if (version >= 16 && readBoolean()) NativeVideoState(readLong(), readLong(), readLong(),
            readInt(), readInt(), readBoolean(), readLong(), readLong(), readBoolean(), readInt(), readInt()).also {
            WolfVideo.validate(it)
            require(path != null && text == null && kind in 0..1) { "Vidéo de sauvegarde sans média" }
        } else null
        val fonts = if (version >= 19) fontMap() else emptyMap()
        val clock = if (version >= 21 && readBoolean()) {
            require(kind == 2 && text != null && path == null) { "Horloge sauvegardée sans picture texte" }
            val encoding = requireNotNull(WolfEncoding.entries.getOrNull(readInt())) { "Encodage de texte sauvegardé inconnu" }
            val compatibility = readInt(); val special = readBoolean()
            val speed = readDouble(); val progress = readDouble(); val previous = readDouble()
            val correction = readInt(); val wait = readInt(); val checkpoint = readInt(); val auto = readBoolean()
            val anchors = linkedMapOf<Int, Double>()
            repeat(count(12)) { val cursor = readInt(); require(anchors.put(cursor, readDouble()) == null) { "Point de vitesse sauvegardé dupliqué" } }
            NativeTextPictureTiming(encoding, compatibility, special,
                WolfTextPictureTiming.State(speed, progress, previous, correction, wait, checkpoint, auto, anchors)).also {
                require(WolfTextPictureTiming.validate(it.program(text), it.state)) { "Horloge sauvegardée invalide" }
            }
        } else null
        require(listOf(x, y, width, height, sx, sy, rotation, fontSize, red, green, blue).all { it.isFinite() } && width in 0f..16384f && height in 0f..16384f && opacity in 0..255 && blend in 0..3 && anchor in 0..6 && fontSize in 0f..1024f && kind in 0..4 && divX in 1..256 && divY in 1..256 && pattern in 1..65_536 && red in 0f..10f && green in 0f..10f && blue in 0f..10f) { "Image de sauvegarde invalide" }
        require(rect == null || listOf(rect.x, rect.y, rect.width, rect.height).all { it.isFinite() } && rect.width in 0f..16384f && rect.height in 0f..16384f) { "Zone d'image de sauvegarde invalide" }
        require(ruby.isFinite() && ruby in 0f..1024f && shadow in -1024..1024 && visible >= 0) { "Style d'image sauvegardée invalide" }
        require(listOf(tone.red, tone.green, tone.blue).all { it in -32768..32767 }) { "Tonalité d'image sauvegardée invalide" }
        return NativePicture(id, path, text, x, y, width, height, sx, sy, opacity, rotation, blend, z, rect, color, anchor, fontSize, fontColor, relative, kind, divX, divY, pattern, red, green, blue, ruby, shadow, visible, tone, imageFilters = filters, video = video, fontOverrides = fonts, textTiming = clock)
    }
    private val fontIndices = setOf(0, 1, 2, 3, 5)
    private fun DataOutputStream.fontMap(fonts: Map<Int, String>) {
        require(fonts.size <= fontIndices.size && fonts.keys.all { it in fontIndices }) { "Police sauvegardée inconnue" }
        count(fonts.size)
        fonts.toSortedMap().forEach { (index, name) ->
            require(name.isNotEmpty() && '\u0000' !in name) { "Nom de police sauvegardé invalide" }
            writeInt(index); text(name, 4096)
        }
    }
    private fun DataInputStream.fontMap(): Map<Int, String> = buildMap {
        val size = count(8); require(size <= fontIndices.size) { "Trop de polices sauvegardées" }
        repeat(size) {
            val index = readInt()
            require(index in fontIndices && index !in this) { "Police sauvegardée inconnue ou dupliquée" }
            val name = text(4096)
            require(name.isNotEmpty() && '\u0000' !in name) { "Nom de police sauvegardé invalide" }
            put(index, name)
        }
    }
    /** Abort while writing, before a save with many large strings can grow without bound. */
    private class LimitedOutput : ByteArrayOutputStream() {
        override fun write(b: Int) { require(count < MAX_BYTES - 8) { "Sauvegarde native trop grande" }; super.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { require(len >= 0 && count.toLong() + len <= MAX_BYTES - 8) { "Sauvegarde native trop grande" }; super.write(b, off, len) }
    }
}
