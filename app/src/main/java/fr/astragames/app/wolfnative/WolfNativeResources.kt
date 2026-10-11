package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Xml
import fr.astragames.wolf.WolfAssetSource
import fr.astragames.wolf.NativeImageFilter
import fr.astragames.wolf.NativeVisibilityMask
import fr.astragames.wolf.WolfImageFilters
import fr.astragames.wolf.WolfVisibilityMask
import fr.astragames.wolf.WolfParser
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.LinkedHashMap
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-session image cache. Reads and bitmap decoding never run from onDraw.
 * The source owns confinement and SAF permissions; paths remain game-relative.
 */
internal class WolfNativeResources(
    private val source: WolfAssetSource,
    private val onChanged: () -> Unit,
    private val onError: (String) -> Unit,
    private val budgetBytes: Long = 96L * 1024 * 1024,
    fontCacheDirectory: File? = null,
) : Closeable {
    private val resourceBytes = WolfNativeResourceBytes(source)
    val videos = WolfNativeVideo(source, onChanged, onError, resourceBytes)
    private val handler = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64)) { task ->
        Thread(task, "Wolf-native-image").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val mandatory = ConcurrentHashMap.newKeySet<String>()
    private val missingOptional = ConcurrentHashMap.newKeySet<String>()
    private val missingOptionalReasons = ConcurrentHashMap<String, String>()
    private val images = LinkedHashMap<String, Bitmap>(32, .75f, true)
    internal data class ImageVariant(val path: String, val filters: List<NativeImageFilter>, val ignoreAlpha: Boolean = false)
    private val converted = LinkedHashMap<ImageVariant, Bitmap>(16, .75f, true)
    private val waitingVariants = ConcurrentHashMap.newKeySet<ImageVariant>()
    private val pendingVariants = ConcurrentHashMap.newKeySet<ImageVariant>()
    private val failedVariants = ConcurrentHashMap.newKeySet<ImageVariant>()
    internal class TransitionMask(val bitmap: Bitmap)
    private val masks = LinkedHashMap<String, TransitionMask>(4, .75f, true)
    private val pendingMasks = ConcurrentHashMap.newKeySet<String>()
    private val failedMasks = ConcurrentHashMap.newKeySet<String>()
    private val fonts = ConcurrentHashMap<String, Typeface>()
    private val pendingFonts = ConcurrentHashMap.newKeySet<String>()
    private val failedFonts = ConcurrentHashMap.newKeySet<String>()
    private data class NamedFont(val typeface: Typeface?, val reason: String? = null)
    private val namedFonts = ConcurrentHashMap<String, NamedFont>()
    private val pendingNamedFonts = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private val fontDiscoveryLock = Any()
    @Volatile private var gameFontCandidates: List<String>? = null
    @Volatile private var systemFontFamilies: Set<String>? = null
    private val fontDirectory = fontCacheDirectory?.let { File(it, "session-${UUID.randomUUID()}") }
    private var residentBytes = 0L
    private var pinned = emptySet<String>()
    private var pinnedVariants = emptySet<ImageVariant>()
    @Volatile var revision: Long = 0
        private set

    /** This only looks in RAM; a cache miss schedules background work. */
    fun bitmap(path: String): Bitmap? {
        val key = logicalPath(path)
        synchronized(images) { images[key]?.let { return it } }
        request(key)
        return null
    }

    fun peek(path: String): Bitmap? = synchronized(images) { images[logicalPath(path)] }

    /** Filter history belongs to a picture; equal histories may share immutable pixels. */
    fun peek(path: String, filters: List<NativeImageFilter>): Bitmap? = if (filters.isEmpty()) peek(path)
        else synchronized(images) { converted[variant(path, filters)] }

    fun peekOpaque(path: String): Bitmap? = synchronized(images) { converted[variant(path, emptyList(), ignoreAlpha = true)] }

    private fun variant(path: String, filters: List<NativeImageFilter>, ignoreAlpha: Boolean = false) =
        ImageVariant(logicalPath(path), filters.toList(), ignoreAlpha)

    fun requestVariant(path: String, filters: List<NativeImageFilter>, ignoreAlpha: Boolean = false) {
        if (filters.isEmpty() && !ignoreAlpha) { request(path); return }
        if (closed.get()) return
        val key = variant(path, filters, ignoreAlpha)
        if (key in failedVariants || key in pendingVariants) return
        val original = synchronized(images) {
            if (key in converted) return
            if (ignoreAlpha) null else images[key.path]
        }
        if (original == null && !ignoreAlpha) {
            waitingVariants += key
            request(key.path)
            // An image worker can finish between the RAM lookup and enqueue.
            if (peek(key.path) != null) { waitingVariants.remove(key); requestVariant(key.path, key.filters) }
            return
        }
        waitingVariants.remove(key)
        if (!pendingVariants.add(key)) return
        try {
            workers.execute {
                var result: Bitmap? = null
                try {
                    if (key.ignoreAlpha) {
                        require(key.filters.isEmpty()) { "conversion combinée d’un fond opaque Wolf non portée" }
                        result = decodeOpaque(key.path)
                    } else {
                        val bitmap = requireNotNull(original)
                        require(bitmap.width.toLong() * bitmap.height <= 16_777_216L) { "image trop grande pour une conversion Wolf" }
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val transformed = WolfImageFilters.convert(bitmap.width, bitmap.height, pixels, key.filters)
                        result = Bitmap.createBitmap(transformed, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    }
                    synchronized(images) {
                        if (!closed.get()) {
                            val visibleBytes = visibleBytes(excludingVariant = key) + if (key in pinnedVariants) result!!.allocationByteCount else 0
                            require(visibleBytes <= budgetBytes) { "textures visibles dépassant le budget de ${budgetBytes / (1024 * 1024)} Mio" }
                            val prior = converted.put(key, result!!)
                            if (prior != null) residentBytes -= prior.allocationByteCount
                            residentBytes += result!!.allocationByteCount
                            result = null // cache / display list ownership
                            revision++; trim()
                        }
                    }
                    handler.post { if (!closed.get()) onChanged() }
                } catch (error: Exception) {
                    failedVariants += key
                    handler.post { if (!closed.get()) onError("Conversion de l’image Wolf « ${key.path} » : ${error.message ?: error.javaClass.simpleName}") }
                } catch (_: OutOfMemoryError) {
                    failedVariants += key
                    handler.post { if (!closed.get()) onError("Mémoire insuffisante pour convertir l’image Wolf « ${key.path} ».") }
                } finally { result?.recycle(); pendingVariants.remove(key) }
            }
        } catch (_: RejectedExecutionException) { pendingVariants.remove(key) }
    }

    fun areVariantsReady(variants: Collection<ImageVariant>) = synchronized(images) {
        variants.all { value -> variant(value.path, value.filters, value.ignoreAlpha).let { it !in failedVariants && it in converted } }
    }

    /** DrawGraph trans=false ignores even zero file alpha. Decode straight RGB
     * before premultiplication can erase those bytes; the shared image stays intact. */
    private fun decodeOpaque(path: String): Bitmap {
        val bytes = resourceBytes.read(WolfParser.rootAssetPath(path), MAX_FILE_BYTES)
        require(bytes.size <= MAX_FILE_BYTES) { "fond dépassant 64 Mio" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val count = bounds.outWidth.toLong() * bounds.outHeight
        require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384 && count in 1..MAX_PIXELS) {
            "format de fond inconnu ou dimensions trop grandes (${bounds.outWidth} × ${bounds.outHeight})"
        }
        require(count * 4 <= budgetBytes) { "fond dépassant le budget de textures de la session" }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inPremultiplied = false
        }) ?: error("décodage du fond opaque impossible")
        var result: Bitmap? = null
        try {
            val opaque = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            result = opaque
            val row = IntArray(decoded.width)
            for (y in 0 until decoded.height) {
                decoded.getPixels(row, 0, decoded.width, 0, y, decoded.width, 1)
                for (x in row.indices) row[x] = row[x] or 0xff000000.toInt()
                opaque.setPixels(row, 0, decoded.width, 0, y, decoded.width, 1)
            }
            return opaque.also { result = null }
        } finally { decoded.recycle(); result?.recycle() }
    }

    fun peekMask(path: String): TransitionMask? = synchronized(images) { masks[logicalPath(path)] }

    /** DxLib LoadBlendGraph takes the original red channel as mask alpha. */
    fun requestMask(path: String) {
        if (path.isBlank() || closed.get()) return
        val key = logicalPath(path)
        if (key in failedMasks || !pendingMasks.add(key)) return
        synchronized(images) { if (masks.containsKey(key)) { pendingMasks.remove(key); return } }
        try {
            workers.execute {
                var decoded: Bitmap? = null
                var alphaMask: Bitmap? = null
                try {
                    val bytes = resourceBytes.read(WolfParser.rootAssetPath(key), MAX_FILE_BYTES)
                    require(bytes.size <= MAX_FILE_BYTES) { "masque dépassant 64 Mio" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val count = bounds.outWidth.toLong() * bounds.outHeight
                    require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384 && count in 1..MAX_PIXELS) {
                        "format de masque inconnu ou dimensions trop grandes (${bounds.outWidth} × ${bounds.outHeight})"
                    }
                    decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                        // A zero PNG alpha must not erase the RGB used by
                        // LoadBlendGraph. This bitmap is read, never drawn.
                        inPremultiplied = false
                    }) ?: error("décodage du masque impossible")
                    require(decoded!!.allocationByteCount <= budgetBytes) { "masque dépassant le budget de textures de la session" }
                    val width = decoded!!.width; val height = decoded!!.height
                    alphaMask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val row = IntArray(width)
                    for (y in 0 until height) {
                        decoded!!.getPixels(row, 0, width, 0, y, width, 1)
                        for (x in 0 until width) row[x] = ((row[x] ushr 16 and 255) shl 24) or 0x00ffffff
                        alphaMask!!.setPixels(row, 0, width, 0, y, width, 1)
                    }
                    if (!closed.get()) synchronized(images) {
                        if (!closed.get()) {
                            val visibleBytes = visibleBytes(excludingMask = key) + if (key in pinned) alphaMask!!.allocationByteCount else 0
                            require(visibleBytes <= budgetBytes) { "textures visibles dépassant le budget de ${budgetBytes / (1024 * 1024)} Mio" }
                            val previous = masks.put(key, TransitionMask(alphaMask!!))
                            if (previous != null) residentBytes -= previous.bitmap.allocationByteCount
                            residentBytes += alphaMask!!.allocationByteCount
                            alphaMask = null // cache / hardware display list ownership
                            revision++; trim()
                        }
                    }
                    handler.post { if (!closed.get()) onChanged() }
                } catch (error: Exception) {
                    failedMasks += key
                    handler.post { if (!closed.get()) onError("Masque Wolf « $key » : ${error.message ?: error.javaClass.simpleName}") }
                } catch (_: OutOfMemoryError) {
                    failedMasks += key
                    handler.post { if (!closed.get()) onError("Mémoire insuffisante pour le masque Wolf « $key ».") }
                } finally { decoded?.recycle(); alphaMask?.recycle(); pendingMasks.remove(key) }
            }
        } catch (_: RejectedExecutionException) { pendingMasks.remove(key) }
    }

    fun areMasksReady(paths: Collection<String>) = synchronized(images) {
        paths.all { logicalPath(it).let { key -> key !in failedMasks && masks.containsKey(key) } }
    }

    /** W3 LoadMask copies BLUE from decoded BGR/BGRA pixels, ignoring PNG alpha.
     * The VM owns the byte canvas; imported images need no persistent texture. */
    fun loadVisibilityImage(path: String, callback: (Result<NativeVisibilityMask>) -> Unit) {
        if (closed.get()) return
        val key = try { logicalPath(path) } catch (error: Exception) { callback(Result.failure(error)); return }
        fun complete(result: Result<NativeVisibilityMask>) {
            handler.post { if (!closed.get()) callback(result) }
        }
        try {
            workers.execute {
                var decoded: Bitmap? = null
                try {
                    val bytes = resourceBytes.read(WolfParser.rootAssetPath(key), MAX_FILE_BYTES)
                    require(bytes.size <= MAX_FILE_BYTES) { "masque dépassant 64 Mio" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(WolfVisibilityMask.validImageDimensions(bounds.outWidth, bounds.outHeight)) {
                        "dimensions de masque de visibilité invalides (${bounds.outWidth} × ${bounds.outHeight})"
                    }
                    decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false; inPremultiplied = false
                    }) ?: error("décodage du masque de visibilité impossible")
                    val bitmap = decoded!!
                    val pixels = ByteArray(bitmap.width * bitmap.height)
                    val row = IntArray(bitmap.width)
                    for (y in 0 until bitmap.height) {
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                        for (x in row.indices) pixels[y * bitmap.width + x] = (row[x] and 255).toByte()
                    }
                    complete(Result.success(NativeVisibilityMask(bitmap.width, bitmap.height, pixels)))
                } catch (error: Exception) { complete(Result.failure(error)) }
                catch (_: OutOfMemoryError) { complete(Result.failure(IllegalStateException("Mémoire insuffisante pour le masque de visibilité Wolf."))) }
                finally { decoded?.recycle() }
            }
        } catch (error: RejectedExecutionException) { complete(Result.failure(error)) }
    }

    /** TrueType/OpenType loading, including Android's font parser, stays off the frame thread. */
    fun typeface(path: String): Typeface? = if (path.startsWith(NAMED_FONT_PREFIX))
        namedFonts[path.removePrefix(NAMED_FONT_PREFIX)]?.typeface else fonts[fontPath(path)]

    /** Runtime font names are distinct from game-relative asset paths. No IO here. */
    fun namedTypeface(name: String): Typeface? = namedFonts[fontName(name)]?.typeface
    fun fontUnavailableReason(name: String): String? = namedFonts[fontName(name)]?.reason

    /** Callback always runs on main; discovery, WOLFX and Android parsing run on a worker. */
    fun resolveFontOverride(name: String, callback: (Boolean) -> Unit) = requestNamedFont(name, callback)

    private fun requestNamedFont(name: String, callback: ((Boolean) -> Unit)? = null) {
        if (closed.get()) return
        val key = fontName(name)
        if (key.isEmpty()) { if (callback != null) handler.post { if (!closed.get()) callback(true) }; return }
        namedFonts[key]?.let { cached ->
            if (callback != null) handler.post { if (!closed.get()) callback(cached.typeface != null) }
            return
        }
        synchronized(pendingNamedFonts) {
            pendingNamedFonts[key]?.let { if (callback != null) it += callback; return }
            pendingNamedFonts[key] = mutableListOf<(Boolean) -> Unit>().apply { if (callback != null) add(callback) }
        }
        fun complete(result: NamedFont) {
            if (closed.get()) return
            namedFonts[key] = result
            synchronized(images) { revision++ }
            val callbacks = synchronized(pendingNamedFonts) { pendingNamedFonts.remove(key).orEmpty() }
            handler.post {
                if (!closed.get()) { onChanged(); callbacks.forEach { it(result.typeface != null) } }
            }
        }
        try {
            workers.execute {
                try {
                    require(namedFonts.size < 256) { "plus de 256 familles de polices demandées dans cette session" }
                    val loaded = findNamedTypeface(name.removePrefix("<<?>>").trim())
                    complete(NamedFont(loaded, if (loaded == null) "famille de police absente des fichiers du jeu et des familles Android" else null))
                } catch (error: Exception) { complete(NamedFont(null, error.message ?: error.javaClass.simpleName)) }
                catch (_: OutOfMemoryError) { complete(NamedFont(null, "mémoire insuffisante pour charger cette police")) }
                finally { if (closed.get()) fontDirectory?.delete() }
            }
        } catch (error: RejectedExecutionException) { complete(NamedFont(null, "chargement de police indisponible : ${error.message}")) }
    }

    private fun findNamedTypeface(name: String): Typeface? {
        val key = fontName(name)
        require(name.length <= 512 && '\u0000' !in name && '/' !in name && '\\' !in name && ':' !in name) { "nom de famille de police invalide" }
        var invalid: String? = null
        val direct = FONT_DIRECTORIES.flatMap { directory -> FONT_EXTENSIONS.map { extension ->
            (if (directory.isEmpty()) "" else "$directory/") + name + extension
        } }
        fun fromGame(path: String): Typeface? {
            if (closed.get()) return null
            return try {
                val bytes = fontBytes(resourceBytes.read(path, 32 * 1024 * 1024))
                val face = WolfNativeFontInfo.faces(bytes).firstOrNull { value -> value.names.any { fontName(it) == key } } ?: return null
                buildTypeface(bytes, "named:$key:$path", face.index)
            } catch (error: Exception) { invalid = "« $path » : ${error.message ?: error.javaClass.simpleName}"; null }
        }
        for (path in direct) if (source.exists(path)) fromGame(path)?.let { return it }
        // Only fixed font locations are listed. Never recursively enumerate a game's map/audio files.
        val candidates = gameFontCandidates ?: synchronized(fontDiscoveryLock) {
            gameFontCandidates ?: FONT_DIRECTORIES.flatMap { directory ->
                runCatching { source.list(directory) }.getOrDefault(emptyList()).filter { file ->
                    FONT_EXTENSIONS.any { file.endsWith(it, true) } && '/' !in file && '\\' !in file
                }.map { file -> fontPath((if (directory.isEmpty()) "" else "$directory/") + file) }
            }.distinct().also {
                require(it.size <= 256) { "plus de 256 fichiers de police dans les dossiers de polices du jeu" }
                gameFontCandidates = it
            }
        }
        var scannedBytes = 0L
        for (path in candidates) {
            if (path in direct || closed.get()) continue
            try {
                val bytes = fontBytes(resourceBytes.read(path, 32 * 1024 * 1024)); scannedBytes += bytes.size
                require(scannedBytes <= 128L * 1024 * 1024) { "recherche de famille de police dépassant 128 Mio" }
                val face = WolfNativeFontInfo.faces(bytes).firstOrNull { value -> value.names.any { fontName(it) == key } } ?: continue
                return buildTypeface(bytes, "named:$key:$path", face.index)
            } catch (error: Exception) {
                if (scannedBytes > 128L * 1024 * 1024) throw error
                invalid = "« $path » : ${error.message ?: error.javaClass.simpleName}"
            }
        }
        val families = systemFontFamilies ?: synchronized(fontDiscoveryLock) {
            systemFontFamilies ?: readSystemFontFamilies().also { systemFontFamilies = it }
        }
        // Typeface.create silently falls back for unknown names: check the actual Android catalog first.
        if (key in families) return Typeface.create(key, Typeface.NORMAL)
        if (invalid != null) throw IllegalArgumentException(invalid)
        return null
    }

    private fun readSystemFontFamilies(): Set<String> {
        val found = mutableSetOf("sans-serif", "serif", "monospace", "sans", "default")
        val file = File("/system/etc/fonts.xml")
        if (file.isFile) file.inputStream().use { stream ->
            val parser = Xml.newPullParser(); parser.setInput(stream, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) if (parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("family", "alias"))
                parser.getAttributeValue(null, "name")?.let { found += fontName(it) }
        }
        return found
    }

    private fun fontBytes(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..32 * 1024 * 1024) { "fichier de police vide ou dépassant 32 Mio" }
        return bytes
    }

    private fun buildTypeface(bytes: ByteArray, key: String, index: Int = 0): Typeface {
        val directory = fontDirectory ?: error("cache des polices du jeu non configuré")
        require(directory.isDirectory || directory.mkdirs()) { "création du cache de polices impossible" }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$digest.font")
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "chemin de cache de police non valide" }
        check(!closed.get()) { "rendu fermé pendant le chargement de police" }
        return try {
            FileOutputStream(file).use { it.write(bytes) }
            Typeface.Builder(file).setTtcIndex(index).build()
        } finally { file.delete() }
    }

    fun requestFont(path: String) {
        if (path.isBlank() || closed.get()) return
        if (path.startsWith(NAMED_FONT_PREFIX)) { requestNamedFont(path.removePrefix(NAMED_FONT_PREFIX)); return }
        val key = fontPath(path)
        if (fonts.containsKey(key) || key in failedFonts || !pendingFonts.add(key)) return
        try {
            workers.execute {
                try {
                    val bytes = fontBytes(resourceBytes.read(WolfParser.rootAssetPath(key), 32 * 1024 * 1024))
                    if (closed.get()) return@execute
                    val loaded = buildTypeface(bytes, key)
                    if (!closed.get()) {
                        fonts[key] = loaded
                        synchronized(images) { revision++ }
                        handler.post { if (!closed.get()) onChanged() }
                    }
                } catch (error: Exception) {
                    failedFonts += key
                    handler.post { if (!closed.get()) onError("Police Wolf « $key » : ${error.message ?: error.javaClass.simpleName}") }
                } catch (_: OutOfMemoryError) {
                    failedFonts += key
                    handler.post { if (!closed.get()) onError("Mémoire insuffisante pour la police Wolf « $key ».") }
                } finally {
                    pendingFonts.remove(key)
                    if (closed.get()) fontDirectory?.delete()
                }
            }
        } catch (_: RejectedExecutionException) { pendingFonts.remove(key) }
    }

    fun areFontsReady(paths: Collection<String>) = paths.all { if (it.startsWith(NAMED_FONT_PREFIX))
        namedFonts.containsKey(it.removePrefix(NAMED_FONT_PREFIX)) else fonts.containsKey(fontPath(it)) }

    /** Keep currently visible textures resident while evicting old maps/pictures. */
    fun pin(paths: Collection<String>, variants: Collection<ImageVariant> = emptyList()) {
        synchronized(images) {
            pinned = paths.filter(String::isNotBlank).map(::logicalPath).toSet()
            pinnedVariants = variants.map { variant(it.path, it.filters, it.ignoreAlpha) }.toSet()
            trim()
        }
    }

    fun request(path: String, optional: Boolean = false, tolerateFailure: Boolean = false) {
        if (path.isBlank() || closed.get()) return
        val key = logicalPath(path)
        if (!optional) mandatory += key
        if (key in missingOptional) {
            if (!optional && failed.add(key)) handler.post {
                if (!closed.get()) onError("Ressource Wolf « $key » : ${missingOptionalReasons[key] ?: "image introuvable"}.")
            }
            return
        }
        if (failed.contains(key) || !pending.add(key)) return
        synchronized(images) { if (images.containsKey(key)) { pending.remove(key); return } }
        try {
            workers.execute {
                var decoded: Bitmap? = null
                try {
                    val rootPath = WolfParser.rootAssetPath(key)
                    if (optional && key !in mandatory && !source.exists(rootPath) &&
                        (rootPath.endsWith(".wolfx", true) || !source.exists("$rootPath.wolfx"))) {
                        missingOptionalReasons[key] = "image introuvable"
                        missingOptional += key
                        require(key !in mandatory) { "image introuvable" }
                        synchronized(images) { revision++ }
                        handler.post { if (!closed.get()) onChanged() }
                        return@execute
                    }
                    val bytes = resourceBytes.read(rootPath, MAX_FILE_BYTES)
                    require(bytes.size <= MAX_FILE_BYTES) { "image dépassant 64 Mio" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
                    require(bounds.outWidth in 1..16_384 && bounds.outHeight in 1..16_384 && pixels in 1..MAX_PIXELS) {
                        "format d’image inconnu ou dimensions trop grandes (${bounds.outWidth} × ${bounds.outHeight})"
                    }
                    decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    }) ?: error("décodage de l’image impossible")
                    require(decoded!!.allocationByteCount <= budgetBytes) { "image dépassant le budget de textures de la session" }
                    if (!closed.get()) {
                        synchronized(images) {
                            if (!closed.get()) {
                                val visibleBytes = visibleBytes(excludingImage = key) + if (key in pinned) decoded!!.allocationByteCount else 0
                                require(visibleBytes <= budgetBytes) { "textures visibles dépassant le budget de ${budgetBytes / (1024 * 1024)} Mio" }
                                val previous = images.put(key, decoded!!)
                                if (previous != null) residentBytes -= previous.allocationByteCount
                                residentBytes += decoded!!.allocationByteCount
                                revision++
                                decoded = null // ownership is now with the cache / display list
                                trim()
                            }
                        }
                        handler.post { if (!closed.get()) onChanged() }
                        waitingVariants.filter { it.path == key }.forEach { requestVariant(it.path, it.filters) }
                    }
                } catch (error: Exception) {
                    // The native character loader keeps an invalid shadow handle
                    // and omits only that draw pass. Required sprites, pictures
                    // and later promotions of the same path must still fail.
                    if (optional && tolerateFailure && key !in mandatory) {
                        missingOptionalReasons[key] = error.message ?: error.javaClass.simpleName
                        missingOptional += key
                        if (key !in mandatory) {
                            synchronized(images) { revision++ }
                            handler.post { if (!closed.get()) onChanged() }
                            return@execute
                        }
                    }
                    failed.add(key)
                    val message = "Ressource Wolf « $key » : ${error.message ?: error.javaClass.simpleName}"
                    handler.post { if (!closed.get()) onError(message) }
                } catch (_: OutOfMemoryError) {
                    failed.add(key)
                    handler.post { if (!closed.get()) onError("Mémoire insuffisante pour l’image Wolf « $key ».") }
                } finally {
                    // An unreferenced worker bitmap is safe to recycle. Cached bitmaps may
                    // still be in a hardware display list and are left to Android's GC.
                    decoded?.recycle()
                    pending.remove(key)
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
        }
    }

    fun prefetch(paths: Collection<String>) { paths.filter(String::isNotBlank).forEach { request(it) } }

    fun isReady(paths: Collection<String>): Boolean = synchronized(images) {
        paths.filter(String::isNotBlank).all {
            val key = logicalPath(it)
            key !in failed && (images.containsKey(key) || key in missingOptional && key !in mandatory)
        }
    }

    fun isOptionalMissing(path: String): Boolean = logicalPath(path) in missingOptional
    fun optionalMissingReason(path: String): String? = missingOptionalReasons[logicalPath(path)]

    fun failure(path: String): Boolean = failed.contains(logicalPath(path))

    private fun visibleBytes(excludingImage: String? = null, excludingMask: String? = null, excludingVariant: ImageVariant? = null) = images.entries.filter { it.key in pinned && it.key != excludingImage }
        .sumOf { it.value.allocationByteCount.toLong() } + masks.entries.filter { it.key in pinned && it.key != excludingMask }
        .sumOf { it.value.bitmap.allocationByteCount.toLong() } + converted.entries.filter { it.key in pinnedVariants && it.key != excludingVariant }
        .sumOf { it.value.allocationByteCount.toLong() }

    private fun trim() {
        if (residentBytes <= budgetBytes) return
        val variantIterator = converted.entries.iterator()
        while (residentBytes > budgetBytes && variantIterator.hasNext()) {
            val entry = variantIterator.next()
            if (entry.key in pinnedVariants) continue
            residentBytes -= entry.value.allocationByteCount
            variantIterator.remove()
        }
        val iterator = images.entries.iterator()
        while (residentBytes > budgetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in pinned) continue
            residentBytes -= entry.value.allocationByteCount
            iterator.remove()
        }
        val maskIterator = masks.entries.iterator()
        while (residentBytes > budgetBytes && maskIterator.hasNext()) {
            val entry = maskIterator.next()
            if (entry.key in pinned) continue
            residentBytes -= entry.value.bitmap.allocationByteCount
            maskIterator.remove()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        videos.close()
        resourceBytes.close()
        workers.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        synchronized(images) { images.clear(); masks.clear(); converted.clear(); pinned = emptySet(); pinnedVariants = emptySet(); residentBytes = 0L; revision++ }
        pending.clear()
        waitingVariants.clear(); pendingVariants.clear(); failedVariants.clear()
        failed.clear()
        mandatory.clear()
        missingOptional.clear()
        missingOptionalReasons.clear()
        pendingMasks.clear(); failedMasks.clear()
        fonts.clear()
        namedFonts.clear()
        synchronized(pendingNamedFonts) { pendingNamedFonts.clear() }
        if (pendingFonts.isEmpty()) fontDirectory?.delete()
    }

    companion object {
        private const val MAX_FILE_BYTES = 64 * 1024 * 1024
        private const val MAX_PIXELS = 32L * 1024 * 1024
        private const val NAMED_FONT_PREFIX = "@font:"
        private val FONT_DIRECTORIES = listOf("", "Data", "Font", "Fonts", "Data/Font", "Data/Fonts", "Data/BasicData")
        private val FONT_EXTENSIONS = listOf(".ttf.wolfx", ".otf.wolfx", ".ttc.wolfx", ".ttf", ".otf", ".ttc")
        private fun fontName(name: String) = name.removePrefix("<<?>>").trim().lowercase(Locale.ROOT)
        fun fontOverrideReference(name: String): String? = name.removePrefix("<<?>>").trim().takeIf {
            it.isNotEmpty() && it != "<<NotFound>>"
        }?.let { NAMED_FONT_PREFIX + fontName(it) }
        private fun fontPath(path: String): String {
            val normalized = path.replace('\\', '/').removePrefix("./")
            require(!normalized.startsWith('/') && ':' !in normalized && normalized.split('/').none { it == ".." }) {
                "Chemin de police Wolf non relatif : $path"
            }
            return normalized
        }
        /**
         * Raw Wolf names are Data-relative. VM assets outside Data carry an
         * explicit marker: keep it in the cache key so normalization is
         * idempotent across prepare, pin, request and draw.
         */
        fun logicalPath(path: String): String {
            val normalized = if (path.startsWith(WolfParser.DATA_ROOT_PREFIX)) WolfParser.rootAssetPath(path)
                else WolfParser.dataPath(path)
            return if (normalized.startsWith("Data/", true) || normalized.isEmpty()) normalized
                else WolfParser.DATA_ROOT_PREFIX + normalized
        }
    }
}
