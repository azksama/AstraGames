package fr.astragames.wolf

/** W3 9cd510/9ceca0 and W2 4d2530/4d3d90, plus their deferred filename loaders. */
internal class WolfMapTextureController(
    private val source: WolfAssetSource,
    private val compatibilityVersion: Int,
    private val system: MutableMap<Int, Int>,
    private val strings: MutableMap<Int, String>,
    private val databases: Map<WolfDatabaseKind, WolfDatabase>,
    private val dbNumber: (Int, Int, Int) -> Int,
    private val dbString: (Int, Int, Int) -> String,
    private val notice: (String) -> Unit,
) {
    private val behaviorVersion = if (compatibilityVersion >= 300) 3 else 2
    var state = cold(); private set
    private val dimensions = linkedMapOf<String, Pair<Int, Int>>()

    fun number(id: Int): Int {
        requireProfile(); require(id in 61..68)
        return system[id] ?: when (id) { 61, 66 -> -1; 65 -> 255; else -> 0 }
    }

    fun string(id: Int): String { requireProfile(); require(id in 30..31); return strings[id].orEmpty() }

    fun writeNumber(id: Int, value: Int, parameters: (WolfMapTextures.Kind) -> WolfMapTextures.Parameters) {
        requireProfile(); require(id in 61..68)
        if (id == 61 || id == 66) {
            val kind = if (id == 61) WolfMapTextures.Kind.FOG else WolfMapTextures.Kind.BACKGROUND
            set(kind, reloadId(get(kind), value, kind, parameters(kind)))
        }
    }

    /** The native setter stores the string immediately and flags the next draw's reload. */
    fun writeString(id: Int) {
        requireProfile(); require(id in 30..31)
        val kind = if (id == 30) WolfMapTextures.Kind.BACKGROUND else WolfMapTextures.Kind.FOG
        set(kind, get(kind).copy(stringReloadPending = true))
    }

    fun advance(parameters: (WolfMapTextures.Kind) -> WolfMapTextures.Parameters) {
        WolfMapTextures.Kind.entries.forEach { kind ->
            val old = get(kind)
            val id = number(idField(kind))
            if (old.id != id) set(kind, reloadId(old, id, kind, parameters(kind)))
            else set(kind, old.copy(scroll = WolfMapTextures.advance(old.scroll, kind, parameters(kind), behaviorVersion)))
        }
    }

    /** Draw consumes filename flags, but never creates an additional clock tick. */
    fun frame(kind: WolfMapTextures.Kind, parameters: WolfMapTextures.Parameters, zoom: Int,
        originX: Int, originY: Int, opacity: Int = 255): NativeMapTexture? {
        var current = get(kind)
        val id = number(idField(kind))
        if (current.id != id) current = reloadId(current, id, kind, parameters)
        if (current.stringReloadPending) current = reloadFilename(current, kind)
        if (current.scroll.active) {
            val geometry = WolfMapTextures.layout(current.scroll, zoom = zoom, originX = originX, originY = originY,
                behaviorVersion = behaviorVersion)
            current = current.copy(scroll = current.scroll.copy(phaseX = geometry.phaseX, phaseY = geometry.phaseY))
        }
        set(kind, current)
        if (number(idField(kind)) == -1 || !current.scroll.active) return null
        return NativeMapTexture(current.path ?: error("Texture de carte active sans fichier"),
            current.scroll.phaseX, current.scroll.phaseY,
            if (kind == WolfMapTextures.Kind.FOG) number(64) else 0, opacity.coerceIn(0, 255))
    }

    fun validate(value: WolfMapTextureLayers?, numbers: Map<Int, Int>, reloadIds: Set<Int> = emptySet()) {
        if (value == null) return
        requireProfile()
        listOf(WolfMapTextures.Kind.BACKGROUND to value.backdrop, WolfMapTextures.Kind.FOG to value.fog).forEach { (kind, layer) ->
            require(layer.behaviorVersion == behaviorVersion) { "Profil de texture sauvegardé incompatible" }
            WolfMapTextures.validate(layer.scroll, layer.behaviorVersion)
            require(idField(kind) in reloadIds || layer.id == (numbers[idField(kind)] ?: -1)) { "Identité de texture sauvegardée incohérente" }
            if (layer.scroll.active) {
                require(layer.id != -1 && layer.path != null) { "Texture de carte active sans identité" }
                val path = WolfParser.imageAssetPath(layer.path)
                require(path == layer.path && resourcePath(path) != null) { "Texture de sauvegarde absente ou chemin invalide" }
                val size = imageDimensions(path, fresh = true)
                require(size.first == layer.scroll.width && size.second == layer.scroll.height) { "Dimensions de texture sauvegardées incompatibles" }
            }
        }
    }

    fun restore(value: WolfMapTextureLayers?) { state = value ?: cold() }
    fun reset() { state = cold() }

    private fun cold() = WolfMapTextureLayers(WolfMapTextureState(behaviorVersion = behaviorVersion),
        WolfMapTextureState(behaviorVersion = behaviorVersion))

    private fun reloadId(old: WolfMapTextureState, id: Int, kind: WolfMapTextures.Kind,
        parameters: WolfMapTextures.Parameters): WolfMapTextureState {
        require(id >= -2) { "ID de texture de carte non établi : $id" }
        if (id == -1) return disabled(id, old)
        var divisor = old.scroll.parallaxDivisor
        val name = if (id == -2) strings[stringField(kind)].orEmpty() else {
            val type = databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(13)
                ?: error("Table système13 de texture de carte absente")
            val value = dbString(13, id, 0)
            val speedField = if (kind == WolfMapTextures.Kind.FOG) 62 else 67
            system[speedField] = dbNumber(13, id, 1)
            system[speedField + 1] = dbNumber(13, id, 2)
            if (type.fields.size >= 4) divisor = dbNumber(13, id, 3)
            value
        }
        if (name.isEmpty()) return disabled(id, old, divisor)
        val path = WolfParser.imageAssetPath(name)
        if (resourcePath(path) == null) {
            if (kind == WolfMapTextures.Kind.FOG)
                error("Ressource de brouillard DB absente ; le handle natif de ce cas n'est pas établi : $path")
            notice("Fond de carte absent : $path")
            return disabled(id, old, divisor)
        }
        val size = imageDimensions(path)
        val actualParameters = parameters.copy(speedX = number(if (kind == WolfMapTextures.Kind.FOG) 62 else 67),
            speedY = number(if (kind == WolfMapTextures.Kind.FOG) 63 else 68))
        return WolfMapTextureState(id, path,
            WolfMapTextures.reload(size.first, size.second, kind, actualParameters, divisor, behaviorVersion = behaviorVersion),
            old.stringReloadPending, behaviorVersion)
    }

    private fun reloadFilename(old: WolfMapTextureState, kind: WolfMapTextures.Kind): WolfMapTextureState {
        val field = idField(kind)
        val description = strings[stringField(kind)].orEmpty().let {
            if (kind == WolfMapTextures.Kind.BACKGROUND && '\n' in it) it.replace("\r", "") else it
        }
        val parts = description.split('\n', limit = 4)
        val name = parts[0]
        val speedField = if (kind == WolfMapTextures.Kind.FOG) 62 else 67
        parts.getOrNull(1)?.let { system[speedField] = decimal(it) }
        parts.getOrNull(2)?.let { system[speedField + 1] = decimal(it) }
        val divisor = parts.getOrNull(3)?.let { decimal(it).coerceIn(-999, 999) } ?: old.scroll.parallaxDivisor
        if (name.isEmpty()) { system[field] = -1; return disabled(-1, old, divisor).copy(stringReloadPending = false) }
        val path = WolfParser.imageAssetPath(name)
        if (resourcePath(path) == null) {
            notice("${if (kind == WolfMapTextures.Kind.FOG) "Brouillard" else "Fond"} de carte absent : $path")
            return disabled(number(field), old, divisor).copy(stringReloadPending = false)
        }
        val size = imageDimensions(path)
        system[field] = -2
        return WolfMapTextureState(-2, path, WolfMapTextures.reset(size.first, size.second, divisor),
            behaviorVersion = behaviorVersion)
    }

    private fun imageDimensions(path: String, fresh: Boolean = false): Pair<Int, Int> {
        if (!fresh) dimensions[path]?.let { return it }
        val bytes = source.read(resourcePath(path) ?: throw java.io.FileNotFoundException(path))
        val decoded = if (WolfXResource.isEncrypted(bytes)) WolfXResource.decode(bytes) else bytes
        val result = WolfImageHeader.dimensions(decoded, path)
            ?: error("Format de texture de carte non établi : $path")
        if (dimensions.size >= 256 && path !in dimensions) dimensions.remove(dimensions.keys.first())
        dimensions[path] = result
        return result
    }
    /** Native resources prioritize a matching encrypted alias even when the clear file exists. */
    private fun resourcePath(path: String): String? {
        val root = WolfParser.rootAssetPath(path)
        if (!root.endsWith(".wolfx", ignoreCase = true) && source.exists("$root.wolfx")) return "$root.wolfx"
        return root.takeIf(source::exists)
    }
    private fun disabled(id: Int, old: WolfMapTextureState, divisor: Int = old.scroll.parallaxDivisor) =
        WolfMapTextureState(id, scroll = WolfMapTextures.reset(2, 2, divisor, active = false),
            stringReloadPending = old.stringReloadPending, behaviorVersion = behaviorVersion)
    private fun get(kind: WolfMapTextures.Kind) = if (kind == WolfMapTextures.Kind.FOG) state.fog else state.backdrop
    private fun set(kind: WolfMapTextures.Kind, value: WolfMapTextureState) {
        state = if (kind == WolfMapTextures.Kind.FOG) state.copy(fog = value) else state.copy(backdrop = value)
    }
    private fun idField(kind: WolfMapTextures.Kind) = if (kind == WolfMapTextures.Kind.FOG) 61 else 66
    private fun stringField(kind: WolfMapTextures.Kind) = if (kind == WolfMapTextures.Kind.FOG) 31 else 30
    /** W2's _atol wraps Int32; W3's signed strtol saturates. Both consume a decimal prefix. */
    private fun decimal(text: String): Int {
        var index = 0
        while (index < text.length && text[index] in " \t\r\n\u000b\u000c") index++
        val negative = text.getOrNull(index) == '-'
        if (text.getOrNull(index) == '-' || text.getOrNull(index) == '+') index++
        if (behaviorVersion == 2) {
            var result = 0
            while (index < text.length && text[index] in '0'..'9') { result = result * 10 + (text[index++] - '0') }
            return if (negative) -result else result
        }
        val limit = if (negative) 2_147_483_648L else 2_147_483_647L
        var magnitude = 0L
        var overflow = false
        while (index < text.length && text[index] in '0'..'9') {
            val digit = text[index++] - '0'
            if (magnitude > (limit - digit) / 10) overflow = true
            else if (!overflow) magnitude = magnitude * 10 + digit
        }
        if (overflow) return if (negative) Int.MIN_VALUE else Int.MAX_VALUE
        return (if (negative) -magnitude else magnitude).toInt()
    }
    private fun requireProfile() { require(behaviorVersion == 2 || behaviorVersion == 3) }
}
