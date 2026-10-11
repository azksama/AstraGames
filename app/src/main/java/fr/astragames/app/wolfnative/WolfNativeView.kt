package fr.astragames.app.wolfnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import fr.astragames.wolf.NativeCharacter
import fr.astragames.wolf.NativeDialog
import fr.astragames.wolf.NativeFrame
import fr.astragames.wolf.NativeKey
import fr.astragames.wolf.NativeMapScene
import fr.astragames.wolf.NativeMapTexture
import fr.astragames.wolf.NativePicture
import fr.astragames.wolf.NativeRect
import fr.astragames.wolf.NativeTone
import fr.astragames.wolf.NativeVideoState
import fr.astragames.wolf.NativeVisibilityMask
import fr.astragames.wolf.WolfVisibilityMask
import fr.astragames.wolf.WolfTileset
import fr.astragames.wolf.WolfMapTextures
import java.io.Closeable
import java.util.LinkedHashMap
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Hardware Canvas renderer: this view never starts a Windows/X11 process. */
internal class WolfNativeView(
    context: Context,
    private val resources: WolfNativeResources,
    private val callbacks: Callbacks,
) : View(context), Closeable {
    enum class DisplayMode { FIT, FILL, STRETCH }
    data class Config(
        val mode: DisplayMode = DisplayMode.FIT,
        val smooth: Boolean = true,
        val pinchZoom: Boolean = false,
        val directionalTouch: Boolean = false,
        val showFps: Boolean = true,
        val backKey: NativeKey = NativeKey.BACK,
        val textColors: Map<Int, Int> = emptyMap(),
        val typeface: Typeface = Typeface.DEFAULT,
        val fontPaths: Map<Int, String> = emptyMap(),
        val availableFontIds: Set<Int> = setOf(0),
        val pictureBehaviorVersion: Int = 2,
    )
    data class Callbacks(
        val key: (NativeKey, Boolean) -> Unit,
        val pointer: (Float, Float, Boolean) -> Unit,
        val choose: (Int) -> Unit = {},
        val firstFrame: () -> Unit = {},
        val error: (String) -> Unit,
        val renderMetrics: (Double, Double) -> Unit = { _, _ -> },
        val notice: (String) -> Unit = {},
        val videoState: (Int, NativeVideoState) -> Unit = { _, _ -> },
    )

    @Volatile private var frame: NativeFrame? = null
    private var config = Config()
    private val bitmapPaint = Paint()
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var firstImage = false
    @Volatile private var closed = false
    private var reportedError: String? = null
    private val shadowNotices = mutableSetOf<String>()
    private var requiredImages = emptySet<String>()
    private var requiredVariants = emptySet<WolfNativeResources.ImageVariant>()
    private var requiredVideos = emptySet<WolfNativeVideo.Key>()
    private var requiredMasks = emptySet<String>()
    private var requiredFonts = emptySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val chunkWorkers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(48)) { task ->
        Thread(task, "Wolf-native-map").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val chunks = LinkedHashMap<ChunkKey, Chunk>(64, .75f, true)
    private val pendingChunks = ConcurrentHashMap.newKeySet<ChunkKey>()
    private var chunkBytes = 0L
    private val textLayouts = LinkedHashMap<TextKey, WolfTextLayout>(64, .75f, true)
    private var textLayoutBytes = 0L
    private var choiceRects = emptyList<RectF>()
    private var choiceIndexes = emptyList<Int>()
    private var pointerId = -1
    private var pointerDown = false
    private var pointerOriginX = 0f
    private var pointerOriginY = 0f
    private var pointerLastX = 0f
    private var pointerLastY = 0f
    private var startedAt = 0L
    private var twoFingerCandidate = false
    private var multi = false
    private var fingerIds = intArrayOf()
    private var fingerOrigins = floatArrayOf()
    private var directions = emptySet<NativeKey>()
    private val physicalKeys = mutableMapOf<Int, NativeKey>()
    private val pulseKeys = mutableSetOf<NativeKey>()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var metricTime = 0L
    private var metricFrames = 0
    private var metricDrawNanos = 0L
    private var measuredFps = 0.0
    private var measuredDrawMs = 0.0
    private var softwareScene: SoftwareScene? = null
    private val softwareLayers = mutableMapOf<Int, SoftwareScene>()
    private class LogicalViewport(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
    }
    private var logicalViewport: LogicalViewport? = null
    private val viewportPaint = Paint()
    internal var viewportAllocations = 0
        private set
    private val transitionMasks = mutableMapOf<String, TransitionMaskTexture>()
    private val transitionPaint = Paint()
    private val transitionMaskPaint = Paint().apply {
        color = Color.WHITE
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var renderingTick = 0L
    private var renderingFontWeight = 5
    private var renderingFontOverrides: Map<Int, String> = emptyMap()
    private var mapTone = NativeTone()
    private var mapToneFilter: android.graphics.ColorFilter? = null
    private class VisibilityKey(val width: Int, val height: Int, val pixels: ByteArray) {
        override fun hashCode() = 31 * (31 * width + height) + System.identityHashCode(pixels)
        override fun equals(other: Any?) = other is VisibilityKey && width == other.width && height == other.height && pixels === other.pixels
    }
    private class VisibilityTexture(val allVisible: Boolean, val noneVisible: Boolean, val shader: BitmapShader?, val path: Path?)
    private val visibilityTextures = LinkedHashMap<VisibilityKey, VisibilityTexture>()
    private val pendingVisibility = ConcurrentHashMap.newKeySet<VisibilityKey>()
    private var requiredVisibility = emptySet<VisibilityKey>()
    private var renderingVisibility: NativeVisibilityMask? = null
    private var renderingVisibilityTexture: VisibilityTexture? = null
    private var visibilityEnabled = false
    private var renderingWidth = 1
    private var renderingHeight = 1
    private val sceneScreenMatrix = Matrix()
    private data class FontKey(val family: Typeface, val weight: Int)
    private val weightedFonts = LinkedHashMap<FontKey, Typeface>(32, .75f, true)
    private val fontNotices = mutableSetOf<Int>()
    private val pointerHold = Runnable {
        if (!closed && pointerId >= 0 && !multi && !config.directionalTouch && !pointerDown) {
            pointerDown = true; callbacks.pointer(pointerLastX, pointerLastY, true)
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = config.pinchZoom && firstImage
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!config.pinchZoom || !firstImage || !detector.scaleFactor.isFinite()) return false
            twoFingerCandidate = false
            val previous = zoom
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            // Preserve the logical point under the fingers while changing scale.
            val ratio = zoom / previous
            panX = (panX + width / 2f - detector.focusX) * ratio - width / 2f + detector.focusX
            panY = (panY + height / 2f - detector.focusY) * ratio - height / 2f + detector.focusY
            clampPan(); invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true; contentDescription = "Jeu Wolf RPG natif"
        resources.videos.onState = { id, state -> if (!closed) callbacks.videoState(id, state) }
    }

    fun setVideoAudioMaster(percent: Int) = resources.videos.setAudioMaster(percent)
    fun suspendVideo(paused: Boolean) = resources.videos.suspendPlayback(paused)

    fun configure(value: Config) {
        config = value
        bitmapPaint.isFilterBitmap = value.smooth
        if (!value.pinchZoom) { zoom = 1f; panX = 0f; panY = 0f }
        textLayouts.clear()
        textLayoutBytes = 0
        weightedFonts.clear()
        frame?.let(::prepareResources)
        invalidate()
    }

    /** The activity coalesces snapshots from the VM thread before calling this. */
    fun submitFrame(value: NativeFrame) {
        if (closed) return
        check(Looper.myLooper() == Looper.getMainLooper()) { "La scène Wolf doit être soumise sur le thread Android principal." }
        try {
            require(value.logicalWidth in 1..8192 && value.logicalHeight in 1..8192) { "Résolution logique Wolf invalide." }
            if (frame?.map?.map !== value.map?.map) synchronized(chunks) { chunks.clear(); chunkBytes = 0 }
            frame = value
            prepareResources(value)
        } catch (error: Exception) { report(error.message ?: error.javaClass.simpleName) }
        invalidate()
    }

    /** Intrinsic logical dimensions, using the same Android fonts and text layout as drawing. */
    fun measurePicture(picture: NativePicture, fontWeight: Int, fontOverrides: Map<Int, String> = picture.fontOverrides): Pair<Int, Int>? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "La mesure Wolf doit être demandée sur le thread Android principal." }
        check(!closed) { "Le rendu Wolf est fermé." }
        require(fontWeight in 1..10) { "Épaisseur de police Wolf invalide : $fontWeight." }
        picture.video?.let { video ->
            val path = requireNotNull(picture.path) { "Chemin vidéo Wolf ${picture.id} absent." }
            val key = resources.videos.request(picture.id, path, video)
            val state = resources.videos.state(key) ?: return null
            return state.width to state.height
        }
        val text = picture.text
        if (text != null) {
            val convertSpecial = picture.textTiming?.convertSpecial != false
            val dependencies = textResources(text, fontOverrides, convertSpecial)
            resources.prefetch(dependencies.mandatory)
            (dependencies.optional - dependencies.mandatory).forEach { resources.request(it, optional = true) }
            dependencies.fonts.forEach(resources::requestFont)
            if (!resources.isReady(dependencies.mandatory + dependencies.optional) || !resources.areFontsReady(dependencies.fonts)) return null
            val previousWeight = renderingFontWeight
            try {
                renderingFontWeight = fontWeight
                val layout = withFontOverrides(fontOverrides) { textLayout(text, picture.fontSize, picture.fontColor,
                    if (picture.width > 0) picture.width else 8192f, picture.rubySize, picture.shadowOffset,
                    intrinsic = picture.width <= 0, convertSpecial = convertSpecial) }
                return ceil((0 until layout.lineCount).maxOfOrNull(layout::getLineWidth) ?: 0f).toInt() to layout.height
            } finally { renderingFontWeight = previousWeight }
        }
        if (picture.kind in 3..4 || picture.path.isNullOrBlank()) {
            require(picture.width.isFinite() && picture.height.isFinite()) { "Dimensions de picture Wolf ${picture.id} non finies." }
            return ceil(abs(picture.width)).toInt() to ceil(abs(picture.height)).toInt()
        }
        val reference = imageReference(picture.path!!)
        val bitmap = resources.bitmap(reference.path) ?: return null
        val rectangle = Rect()
        pictureSource(bitmap, reference, picture, renderingTick, rectangle, geometryOnly = true)
        return rectangle.width() to rectangle.height()
    }

    private data class TextResources(val mandatory: Set<String>, val optional: Set<String>, val fonts: Set<String>)

    private fun textResources(text: String, overrides: Map<Int, String>, convertSpecial: Boolean = true): TextResources {
        val mandatory = mutableSetOf<String>()
        val optional = mutableSetOf<String>()
        val fonts = mutableSetOf<String>()
        config.fontPaths[0]?.let(fonts::add)
        overrides[0]?.let(WolfNativeResources::fontOverrideReference)?.let(fonts::add)
        if (!convertSpecial) return TextResources(mandatory, optional, fonts)
        TEXT_CONTROL.findAll(text).filter { match ->
            // The second slash in \\img[...] is literal text, not an asset.
            var at = match.range.first - 1
            var slashes = 0
            while (at >= 0 && text[at] == '\\') { slashes++; at-- }
            slashes % 2 == 0
        }.forEach { match ->
            when (match.groupValues[1]) {
                "i", "iS" -> match.groupValues[2].toIntOrNull()?.takeIf { it in 0..9999 }?.let {
                    optional += WolfNativeResources.logicalPath("BasicData/icon${it.toString().padStart(3, '0')}.png")
                }
                "img", "imgS" -> mandatory += imageReference(match.groupValues[2]).path
                "font" -> match.groupValues[2].toIntOrNull()?.let { id ->
                    val override = overrides[id]?.takeIf { id in 0..3 }?.let(WolfNativeResources::fontOverrideReference)
                    override?.let(fonts::add)
                    config.fontPaths[id]?.let(fonts::add)
                        ?: require(id in config.availableFontIds || override != null) { "Sous-police Wolf $id absente des ressources configurées du jeu." }
                }
            }
        }
        return TextResources(mandatory, optional, fonts)
    }

    private fun prepareResources(scene: NativeFrame) {
        val visible = mutableSetOf<String>()
        val imagePaths = mutableSetOf<String>()
        val maskPaths = mutableSetOf<String>()
        val mandatory = mutableSetOf<String>()
        val optionalIcons = mutableSetOf<String>()
        val optionalShadows = mutableSetOf<String>()
        val fontPaths = mutableSetOf<String>()
        val visibilityKeys = mutableSetOf<VisibilityKey>()
        val variants = mutableSetOf<WolfNativeResources.ImageVariant>()
        val videos = mutableSetOf<WolfNativeVideo.Key>()
        config.fontPaths[0]?.let(fontPaths::add)
        fun image(path: String) {
            val logical = WolfNativeResources.logicalPath(path)
            visible += logical; imagePaths += logical; mandatory += logical
        }
        fun textResources(text: String, overrides: Map<Int, String>, convertSpecial: Boolean = true) {
            val dependencies = this@WolfNativeView.textResources(text, overrides, convertSpecial)
            dependencies.mandatory.forEach(::image)
            visible += dependencies.optional; imagePaths += dependencies.optional; optionalIcons += dependencies.optional
            fontPaths += dependencies.fonts
        }
        fun collect(current: NativeFrame, depth: Int) {
        require(depth <= 4) { "Transitions Wolf imbriquées dépassant le budget de composition." }
        current.visibilityMask?.let { mask ->
            require(WolfVisibilityMask.validDimensions(mask.width, mask.height) && mask.pixels.size == mask.width * mask.height) {
                "Toile de visibilité Wolf invalide."
            }
            val key = VisibilityKey(mask.width, mask.height, mask.pixels)
            visibilityKeys += key; prepareVisibility(key)
        }
        current.transition?.let { transition ->
            require(transition.previous.logicalWidth == current.logicalWidth && transition.previous.logicalHeight == current.logicalHeight) {
                "Une transition Wolf ne peut pas changer la résolution logique du jeu."
            }
            transition.maskPath?.takeIf(String::isNotBlank)?.let {
                val path = WolfNativeResources.logicalPath(it)
                visible += path; maskPaths += path
            }
            collect(transition.previous, depth + 1)
        }
        current.map?.let { map ->
            map.backdrop?.let { backdrop ->
                val path = WolfNativeResources.logicalPath(backdrop.path)
                visible += path
                variants += WolfNativeResources.ImageVariant(path, emptyList(), ignoreAlpha = true)
            }
            map.fog?.let { fog ->
                if (isWolf2FogMultiply(fog)) {
                    val path = WolfNativeResources.logicalPath(fog.path)
                    visible += path
                    variants += WolfNativeResources.ImageVariant(path, emptyList(), ignoreAlpha = true)
                } else image(fog.path)
            }
            val size = map.tileSize.coerceAtLeast(1)
            val region = visibleTiles(current, map)
            // Cache aligned regions just outside the viewport too: scrolling must
            // not repeatedly decode the same edge tiles or rebuild half a chunk.
            val left = Math.floorDiv(region.left, CHUNK_SIZE) * CHUNK_SIZE
            val top = Math.floorDiv(region.top, CHUNK_SIZE) * CHUNK_SIZE
            val right = ((Math.floorDiv(region.right - 1, CHUNK_SIZE) + 1) * CHUNK_SIZE).let { if (map.map.loopX) it else it.coerceAtMost(map.map.width) }
            val bottom = ((Math.floorDiv(region.bottom - 1, CHUNK_SIZE) + 1) * CHUNK_SIZE).let { if (map.map.loopY) it else it.coerceAtMost(map.map.height) }
            for (layer in 0 until map.map.layerCount) for (y in top until bottom) for (x in left until right) {
                tilePath(map.tileset, sourceTile(map, x, y, layer))?.let(::image)
            }
            projectedCharacters(current, map).filter { character ->
                val margin = max(size * 4f, max(character.width, character.height))
                character.opacity > 0 && character.x + margin >= region.left * size && character.x - margin <= region.right * size &&
                    character.y + margin >= region.top * size && character.y - margin <= region.bottom * size
            }.forEach { character ->
                if (character.tileId >= 0) tilePath(map.tileset, character.tileId)?.let(::image)
                else if (character.path.isNotBlank()) {
                    image(character.path)
                    character.shadowPath?.takeIf(String::isNotBlank)?.let { path ->
                        val logical = WolfNativeResources.logicalPath(path)
                        visible += logical; imagePaths += logical; optionalShadows += logical
                    }
                }
            }
        }
        // Opacity zero pictures deliberately preload their resources in Wolf.
        current.pictures.forEach { picture ->
            picture.path?.takeIf { it.isNotBlank() && (picture.kind !in 3..4 || !it.startsWith('<') || it.startsWith("<CUT/", true)) }?.let {
                val path = imageReference(it).path
                val video = picture.video
                if (video != null) {
                    require(picture.imageFilters.isEmpty()) { "Conversion de pixels d’une vidéo Wolf non prise en charge." }
                    videos += resources.videos.request(picture.id, path, video)
                } else {
                    image(path)
                    if (picture.imageFilters.isNotEmpty()) variants += WolfNativeResources.ImageVariant(path, picture.imageFilters)
                }
            }
            picture.text?.let { textResources(it, picture.fontOverrides, picture.textTiming?.convertSpecial != false) }
        }
        current.texts.forEach { textResources(it.text, current.fontOverrides) }
        current.dialog?.let {
            textResources(it.text, it.fontOverrides, it.convertSpecial)
            it.choices.forEach { text -> textResources(text, it.fontOverrides, it.convertSpecial) }
            it.skinPath?.takeIf(String::isNotBlank)?.let(::image)
            if (it.drawChoiceWindow) it.choiceSkinPath?.takeIf(String::isNotBlank)?.let(::image)
            it.choiceCursorPath?.takeIf(String::isNotBlank)?.let(::image)
            it.pausePath?.takeIf(String::isNotBlank)?.let(::image)
        }
        }
        collect(scene, 0)
        resources.videos.retain(videos)
        requiredVideos = videos
        resources.pin(visible, variants)
        resources.prefetch(mandatory)
        variants.forEach { resources.requestVariant(it.path, it.filters, it.ignoreAlpha) }
        requiredVariants = variants
        (optionalIcons - mandatory).forEach { resources.request(it, optional = true) }
        (optionalShadows - mandatory).forEach { resources.request(it, optional = true, tolerateFailure = true) }
        requiredImages = imagePaths
        maskPaths.forEach(resources::requestMask)
        requiredMasks = maskPaths
        transitionMasks.keys.retainAll(maskPaths)
        fontPaths.forEach(resources::requestFont)
        requiredFonts = fontPaths
        requiredVisibility = visibilityKeys
        synchronized(visibilityTextures) { visibilityTextures.keys.retainAll(visibilityKeys) }
    }

    /** Compile the binary alpha clip once per immutable canvas, off the UI thread. */
    private fun prepareVisibility(key: VisibilityKey) {
        synchronized(visibilityTextures) { if (key in visibilityTextures) return }
        if (!pendingVisibility.add(key)) return
        try {
            chunkWorkers.execute {
                try {
                    if (closed) return@execute
                    val all = key.pixels.all { it.toInt() == 0 }
                    val none = !all && key.pixels.none { it.toInt() == 0 }
                    var shader: BitmapShader? = null
                    var path: Path? = null
                    if (!all && !none) {
                        if (Build.VERSION.SDK_INT >= 35) {
                            val bitmap = Bitmap.createBitmap(key.width, key.height, Bitmap.Config.ALPHA_8)
                            val bytes = ByteArray(bitmap.rowBytes * key.height)
                            for (y in 0 until key.height) for (x in 0 until key.width) {
                                if (key.pixels[y * key.width + x].toInt() == 0) bytes[y * bitmap.rowBytes + x] = -1
                            }
                            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
                            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                                setFilterMode(BitmapShader.FILTER_MODE_NEAREST)
                            }
                        } else {
                            path = Path()
                            for (y in 0 until key.height) {
                                var x = 0
                                while (x < key.width) {
                                    while (x < key.width && key.pixels[y * key.width + x].toInt() != 0) x++
                                    val start = x
                                    while (x < key.width && key.pixels[y * key.width + x].toInt() == 0) x++
                                    if (x > start) path.addRect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f, Path.Direction.CW)
                                }
                            }
                        }
                    }
                    val texture = VisibilityTexture(all, none, shader, path)
                    synchronized(visibilityTextures) {
                        if (!closed) {
                            visibilityTextures[key] = texture
                            while (visibilityTextures.size > 5) visibilityTextures.keys.iterator().run { next(); remove() }
                        }
                    }
                    main.post { if (!closed) invalidate() }
                } catch (error: Exception) { main.post { if (!closed) report(error.message ?: error.javaClass.simpleName) } }
                catch (_: OutOfMemoryError) { main.post { if (!closed) report("Mémoire insuffisante pour composer le masque de visibilité Wolf.") } }
                finally { pendingVisibility.remove(key) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { pendingVisibility.remove(key) }
    }

    internal fun areVisibilityMasksReady() = synchronized(visibilityTextures) { requiredVisibility.all { it in visibilityTextures } }
    internal fun areImageVariantsReady() = resources.areVariantsReady(requiredVariants)
    internal fun areImageResourcesReady() = resources.isReady(requiredImages)
    internal fun areVideosReady() = resources.videos.ready(requiredVideos)

    /** Keep the physical screen mask independent from map/picture transforms.
     * Character masks cover the shadow as well as the sprite. */
    private inline fun withVisibility(canvas: Canvas, enabled: Boolean, draw: () -> Boolean): Boolean {
        val previous = visibilityEnabled
        visibilityEnabled = enabled
        val mask = renderingVisibility
        val texture = renderingVisibilityTexture
        if (!enabled || mask == null || texture == null || texture.allVisible) {
            return try { draw() } finally { visibilityEnabled = previous }
        }
        if (texture.noneVisible) { visibilityEnabled = previous; return false }
        val saved = canvas.save()
        val matrix = Matrix(); canvas.getMatrix(matrix)
        try {
            canvas.setMatrix(sceneScreenMatrix)
            canvas.clipRect(0f, 0f, renderingWidth.toFloat(), renderingHeight.toFloat())
            val scale = Matrix().apply { setScale(renderingWidth.toFloat() / mask.width, renderingHeight.toFloat() / mask.height) }
            if (Build.VERSION.SDK_INT >= 35) {
                texture.shader!!.setLocalMatrix(scale)
                canvas.clipShader(texture.shader)
            } else {
                val clip = Path(); texture.path!!.transform(scale, clip); canvas.clipPath(clip)
            }
            canvas.setMatrix(matrix)
            return draw()
        } finally { canvas.restoreToCount(saved); visibilityEnabled = previous }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = frame ?: return
        if (closed || width <= 0 || height <= 0) return
        val start = System.nanoTime()
        val viewport = viewport(scene)
        val saved = canvas.save()
        var actualImage = false
        try {
            canvas.translate(viewport.left, viewport.top)
            canvas.scale(viewport.scaleX, viewport.scaleY)
            canvas.clipRect(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat())
            if (scene.windowSmooth != null) {
                // Sys140 filters the finished game window, including text and
                // shapes. Texture filters inside the logical scene stay local.
                var logical = logicalViewport
                if (logical == null || logical.bitmap.width != scene.logicalWidth || logical.bitmap.height != scene.logicalHeight) {
                    require(scene.logicalWidth.toLong() * scene.logicalHeight <= 4_194_304) {
                        "La composition de la fenêtre Wolf dépasse 16 Mio de pixels ARGB."
                    }
                    logical = LogicalViewport(scene.logicalWidth, scene.logicalHeight)
                    logicalViewport = logical; viewportAllocations++
                }
                logical.canvas.setMatrix(null)
                logical.canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                actualImage = drawComposition(logical.canvas, scene, 0)
                viewportPaint.isFilterBitmap = scene.windowSmooth == true
                canvas.drawBitmap(logical.bitmap, 0f, 0f, viewportPaint)
            } else actualImage = drawComposition(canvas, scene, 0)
        } catch (error: Exception) { report(error.message ?: error.javaClass.simpleName) }
        catch (_: OutOfMemoryError) { report("Mémoire insuffisante pour composer la scène Wolf ${scene.logicalWidth} × ${scene.logicalHeight}.") }
        finally { canvas.restoreToCount(saved) }
        if (actualImage && resources.isReady(requiredImages) && resources.areVariantsReady(requiredVariants) && areVideosReady() && resources.areMasksReady(requiredMasks) && resources.areFontsReady(requiredFonts) && areVisibilityMasksReady() && scene.screenOpacity > 0 && !firstImage && reportedError == null) {
            firstImage = true
            main.post { if (!closed) callbacks.firstFrame() }
        }
        recordMetrics(start)
        if (config.showFps && firstImage) {
            uiPaint.color = Color.WHITE; uiPaint.textSize = 12f * context.resources.displayMetrics.scaledDensity
            uiPaint.setShadowLayer(2f, 0f, 1f, Color.BLACK)
            canvas.drawText("${measuredFps.toInt()} FPS · ${"%.1f".format(measuredDrawMs)} ms", 12f, uiPaint.textSize + 10f, uiPaint)
            uiPaint.clearShadowLayer()
        }
    }

    private fun drawComposition(canvas: Canvas, scene: NativeFrame, depth: Int): Boolean {
        val transition = scene.transition ?: return drawSceneLayer(canvas, scene, depth)
        require(depth <= 4 && transition.progress.isFinite()) { "Transition Wolf invalide ou trop profondément imbriquée." }
        val border = transition.border?.also { require(it in 0..255) { "Bordure de transition Wolf invalide : $it." } }
            ?: ceil(transition.progress.coerceIn(0f, 1f) * 255f).toInt()
        val current = drawSceneLayer(canvas, scene, depth)
        if (border == 255) return current
        val path = transition.maskPath?.takeIf(String::isNotBlank)?.let(WolfNativeResources::logicalPath)
        val mask = path?.let { resources.peekMask(it) ?: return false }
        val texture = if (mask != null) transitionMaskTexture(path!!, mask) else null
        // A frozen scene can contain an old choice menu. Its geometry must not
        // replace the hit targets belonging to the current VM frame.
        val currentChoices = choiceRects; val currentIndexes = choiceIndexes
        val currentTick = renderingTick; val currentWeight = renderingFontWeight
        val currentFonts = renderingFontOverrides
        val layer = if (texture == null) canvas.saveLayerAlpha(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat(), 255 - border)
            else canvas.saveLayer(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat(), transitionPaint)
        val previous: Boolean
        try {
            previous = drawComposition(canvas, transition.previous, depth + 1)
            if (texture != null) {
                // W2 D3D9/11 uses an integer offset, then the shader saturates
                // (red + offset) * 4. The shader runs on the cached mask, so
                // neither file reads nor a per-pixel CPU loop run each frame.
                val position = 1280 - border * 1280 / 255
                val offset = if (position < 1024) -(255 - position * 255 / 1024) else 64 * (position - 1024) / 256
                transitionMaskPaint.colorFilter = texture.filter(offset)
                transitionMaskPaint.isFilterBitmap = config.smooth
                canvas.drawBitmap(texture.bitmap, null, RectF(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat()), transitionMaskPaint)
            }
        } finally {
            canvas.restoreToCount(layer)
            choiceRects = currentChoices; choiceIndexes = currentIndexes
            renderingTick = currentTick; renderingFontWeight = currentWeight
            renderingFontOverrides = currentFonts
        }
        return previous && border < 255 || current && border > 0
    }

    private fun transitionMaskTexture(path: String, mask: WolfNativeResources.TransitionMask): TransitionMaskTexture {
        transitionMasks[path]?.takeIf { it.source === mask }?.let { return it }
        return TransitionMaskTexture(mask).also { transitionMasks[path] = it }
    }

    private class TransitionMaskTexture(val source: WolfNativeResources.TransitionMask) {
        val bitmap get() = source.bitmap
        private var offset = Int.MIN_VALUE
        private var colorFilter: ColorMatrixColorFilter? = null
        fun filter(value: Int): ColorMatrixColorFilter {
            if (offset != value) {
                offset = value
                colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, 0f,
                    0f, 0f, 0f, 4f, value * 4f,
                )))
            }
            return colorFilter!!
        }
    }

    private fun drawSceneLayer(canvas: Canvas, scene: NativeFrame, depth: Int): Boolean {
        val fog = scene.map?.fog
        val fogActive = fog != null && (fog.opacity > 0 || isWolf2FogMultiply(fog))
        if (scene.pictures.none { it.blend == 2 && it.opacity > 0 || it.blend == 3 && Build.VERSION.SDK_INT < 29 } &&
            scene.map?.characters.orEmpty().none { it.blend == 2 && it.opacity > 0 || it.blend == 3 && Build.VERSION.SDK_INT < 29 } &&
            (!fogActive || fog!!.blend != 2 && (fog.blend < 3 || Build.VERSION.SDK_INT >= 29))) return drawScene(canvas, scene)
        var software = softwareLayers[depth]
        if (software == null || software.bitmap.width != scene.logicalWidth || software.bitmap.height != scene.logicalHeight) {
            require(scene.logicalWidth.toLong() * scene.logicalHeight <= 4_194_304) { "Les mélanges de couleurs Wolf dépassent le budget de composition native." }
            software = SoftwareScene(scene.logicalWidth, scene.logicalHeight); softwareLayers[depth] = software
        }
        val previous = softwareScene
        softwareScene = software
        val actual = try { drawScene(software.canvas, scene) } finally { softwareScene = previous }
        bitmapPaint.alpha = 255; bitmapPaint.colorFilter = null; bitmapPaint.xfermode = null
        canvas.drawBitmap(software.bitmap, 0f, 0f, bitmapPaint)
        return actual
    }

    private fun drawScene(canvas: Canvas, scene: NativeFrame): Boolean {
        val visibility = scene.visibilityMask
        val texture = visibility?.let { synchronized(visibilityTextures) { visibilityTextures[VisibilityKey(it.width, it.height, it.pixels)] } }
        if (visibility != null && texture == null) return false
        renderingVisibility = visibility; renderingVisibilityTexture = texture; visibilityEnabled = false
        renderingWidth = scene.logicalWidth; renderingHeight = scene.logicalHeight
        canvas.getMatrix(sceneScreenMatrix)
        renderingTick = scene.tick
        require(scene.fontWeight in 1..10) { "Épaisseur de police Wolf hors plage : ${scene.fontWeight}." }
        renderingFontWeight = scene.fontWeight
        renderingFontOverrides = scene.fontOverrides
        canvas.drawColor(Color.BLACK)
        var actual = drawTonedMap(canvas, scene)
        scene.map?.let { map -> map.fog?.let { actual = drawMapTexture(canvas, map, it, opaque = false) || actual } }
        actual = drawPictures(canvas, scene, 0, 99_999) || actual
        scene.texts.forEach { text ->
            if (text.text.isNotEmpty()) {
                drawText(canvas, text.text, text.x, text.y, text.size, text.color, scene.logicalWidth - text.x); actual = true
            }
        }
        choiceRects = emptyList(); choiceIndexes = emptyList()
        scene.dialog?.let { actual = drawDialog(canvas, scene, it) || actual }
        actual = drawPictures(canvas, scene, 100_000, Int.MAX_VALUE) || actual
        if (scene.screenOpacity < 255 || scene.screenColor != 0) {
            uiPaint.color = if (scene.screenColor == 0) Color.BLACK else scene.screenColor
            uiPaint.alpha = 255 - scene.screenOpacity.coerceIn(0, 255)
            canvas.drawRect(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat(), uiPaint); uiPaint.alpha = 255
        }
        // W2 004cbef0 draws the global flash with NORMAL alpha after all
        // pictures/dialogs, before the captured-screen transition is composed.
        scene.flash?.takeIf { it.opacity > 0 }?.let { flash ->
            uiPaint.color = Color.rgb(flash.red.coerceIn(0, 255), flash.green.coerceIn(0, 255), flash.blue.coerceIn(0, 255))
            uiPaint.alpha = flash.opacity.coerceIn(0, 255)
            canvas.drawRect(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat(), uiPaint)
            uiPaint.alpha = 255
        }
        return actual
    }

    private fun drawPictures(canvas: Canvas, scene: NativeFrame, minimum: Int, maximum: Int): Boolean {
        var actual = false
        // IDs choose Wolf's fixed compositing groups; Z orders only within a
        // group. Changing Z must not move a map picture above the dialog.
        scene.pictures.filter { it.id in minimum..maximum }
            .sortedWith(compareBy<NativePicture> { pictureGroup(it.id) }.thenBy { it.z }.thenBy { it.id })
            .forEach { actual = drawPicture(canvas, scene, it) || actual }
        return actual
    }

    private fun pictureGroup(id: Int): Int = if (config.pictureBehaviorVersion < 2) when {
        id < -100_000 -> -2
        id < 0 -> -1
        id < 100_000 -> 0
        else -> 1
    } else when {
        id <= -400_000 -> -5
        id <= -300_000 -> -4
        id <= -200_000 -> -3
        id <= -100_000 -> -2
        id < 0 -> -1
        id < 100_000 -> 0
        else -> 1
    }

    private fun drawMap(canvas: Canvas, scene: NativeFrame, map: NativeMapScene): Boolean {
        require(map.tileSize in 2..256 && map.tileSize % 2 == 0) { "Taille des tiles Wolf non prise en charge : ${map.tileSize}." }
        validateMapZoom(map)
        val saved = canvas.save()
        canvas.scale(map.zoom, map.zoom, map.zoomCenterX, map.zoomCenterY)
        try {
        val size = map.tileSize
        val region = visibleTiles(scene, map)
        val left = region.left; val top = region.top; val right = region.right; val bottom = region.bottom
        var actual = if (config.pictureBehaviorVersion >= 2) {
            var drawn = drawMapPictures(canvas, scene, map, Int.MIN_VALUE, -400_000)
            map.backdrop?.let { backdrop ->
                val savedTexture = canvas.save()
                canvas.scale(1f / map.zoom, 1f / map.zoom, map.zoomCenterX, map.zoomCenterY)
                try { drawn = drawMapTexture(canvas, map, backdrop, opaque = true) || drawn }
                finally { canvas.restoreToCount(savedTexture) }
            }
            drawMapPictures(canvas, scene, map, -399_999, -300_000) || drawn
        } else {
            require(map.backdrop == null && map.fog == null) { "Fond/brouillard de carte du profil Wolf antérieur à 2 non qualifié." }
            drawMapPictures(canvas, scene, map, Int.MIN_VALUE, -100_001)
        }
        actual = drawMapPriority(canvas, scene, map, 0, left, top, right, bottom) || actual
        actual = drawMapPictures(canvas, scene, map, if (config.pictureBehaviorVersion >= 2) -299_999 else -100_000,
            if (config.pictureBehaviorVersion >= 2) -200_000 else -1) || actual
        // Wolf ▲ tiles hide a character on their row or behind them, but a
        // character in front must overlap the tile. A through character stays
        // behind all ▲ tiles; above-character pages stay in front of them.
        val projected = projectedCharacters(scene, map)
        val characters = projected.filterNot { it.onTop }.sortedWith(compareBy<NativeCharacter> { it.y }.thenBy { it.id })
        characters.filter { it.through }.forEach { actual = drawCharacter(canvas, map, it, scene.tick) || actual }
        val normal = characters.filterNot { it.through }
        var next = 0
        for (y in top until bottom) {
            val depth = (y + 1f) * size
            while (next < normal.size && normal[next].y <= depth) {
                actual = drawCharacter(canvas, map, normal[next++], scene.tick) || actual
            }
            actual = drawTriangleRow(canvas, map, left, right, y, scene.tick) || actual
        }
        while (next < normal.size) actual = drawCharacter(canvas, map, normal[next++], scene.tick) || actual
        if (config.pictureBehaviorVersion >= 2) actual = drawMapPictures(canvas, scene, map, -199_999, -100_000) || actual
        projected.filter { it.onTop }.sortedWith(compareBy<NativeCharacter> { it.y }.thenBy { it.id }).forEach {
            actual = drawCharacter(canvas, map, it, scene.tick) || actual
        }
        actual = drawMapPriority(canvas, scene, map, 2, left, top, right, bottom) || actual
        return actual
        } finally { canvas.restoreToCount(saved) }
    }

    /** Picture tier affects depth/color only. Map zoom never supplies picture scale. */
    private fun drawMapPictures(canvas: Canvas, scene: NativeFrame, map: NativeMapScene, minimum: Int, maximum: Int): Boolean {
        val saved = canvas.save()
        canvas.scale(1f / map.zoom, 1f / map.zoom, map.zoomCenterX, map.zoomCenterY)
        return try { drawPictures(canvas, scene, minimum, maximum) } finally { canvas.restoreToCount(saved) }
    }

    private fun isWolf2FogMultiply(texture: NativeMapTexture) = config.pictureBehaviorVersion == 2 && texture.blend >= 3

    /** W2 4d1e20/4d3480 and W3 9ce7f0/9cfe90 draw four clipped parts of the bitmap's own extent.
     * The VM supplies the scroll/parallax phase. This pass is outside map masks,
     * and the fog runs after the map tone has been completely restored. */
    private fun drawMapTexture(canvas: Canvas, map: NativeMapScene, texture: NativeMapTexture, opaque: Boolean): Boolean {
        val nativeMultiply = !opaque && isWolf2FogMultiply(texture)
        if (!opaque && !nativeMultiply && texture.opacity <= 0 || texture.path.isBlank()) return false
        // DxLib MUL4 multiplies straight RGB and ignores the alpha parameter.
        // MULA11 instead interpolates using texture alpha and the supplied opacity.
        val bitmap = if (opaque || nativeMultiply) resources.peekOpaque(texture.path) else resources.peek(texture.path)
        if (bitmap == null) return false
        val zoom = round(map.zoom * 100f).toInt()
        val geometry = WolfMapTextures.layout(bitmap.width, bitmap.height, texture.phaseX, texture.phaseY,
            zoom = zoom, originX = map.zoomCenterX.toInt(), originY = map.zoomCenterY.toInt(),
            behaviorVersion = config.pictureBehaviorVersion)
        val blend = if (opaque || texture.blend <= 0) 0 else texture.blend.coerceAtMost(3)
        val priorFilter = bitmapPaint.colorFilter
        val priorVisibility = visibilityEnabled
        visibilityEnabled = false
        try {
            bitmapPaint.colorFilter = if (opaque) mapToneFilter else null
            bitmapPaint.alpha = if (opaque || nativeMultiply) 255 else texture.opacity.coerceIn(0, 255)
            setGraphicBlend(blend)
            val target = if (softwareGraphicBlend(blend)) {
                val software = softwareScene ?: error("Composition du brouillard Wolf non préparée.")
                software.mask.eraseColor(Color.TRANSPARENT)
                canvas.getMatrix(software.matrix); software.maskCanvas.setMatrix(software.matrix)
                software.maskCanvas
            } else canvas
            var drawn = false
            for (rectangle in geometry.rectangles) if (rectangle.drawable) {
                sourceRect.set(rectangle.sourceLeft, rectangle.sourceTop, rectangle.sourceRight, rectangle.sourceBottom)
                destinationRect.set(rectangle.destinationLeft.toFloat(), rectangle.destinationTop.toFloat(),
                    rectangle.destinationRight.toFloat(), rectangle.destinationBottom.toFloat())
                if (zoom == 100) {
                    // Unit DrawRectGraph clips the destination and draws the whole
                    // bitmap translated by dest-src. W2 can supply negative signed
                    // phases: an out-of-range source must not be clamped and stretched.
                    val saved = target.save()
                    try {
                        target.clipRect(destinationRect)
                        target.drawBitmap(bitmap, destinationRect.left - sourceRect.left,
                            destinationRect.top - sourceRect.top, bitmapPaint)
                    } finally { target.restoreToCount(saved) }
                } else {
                    // DerivationGraph rejects an invalid starting point, but
                    // clamps an oversized extent before DrawExtendGraph uses
                    // the original destination. W2 signed phases reach both cases.
                    if (sourceRect.left < 0 || sourceRect.top < 0 || sourceRect.left >= bitmap.width || sourceRect.top >= bitmap.height) continue
                    sourceRect.right = sourceRect.right.coerceAtMost(bitmap.width)
                    sourceRect.bottom = sourceRect.bottom.coerceAtMost(bitmap.height)
                    target.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint)
                }
                drawn = true
            }
            if (drawn && softwareGraphicBlend(blend)) applySoftwareGraphicBlend(blend)
            return drawn
        } finally {
            bitmapPaint.alpha = 255; bitmapPaint.colorFilter = priorFilter; setGraphicBlend(0)
            visibilityEnabled = priorVisibility
        }
    }

    private fun validateMapZoom(map: NativeMapScene) {
        require(map.zoom.isFinite() && map.zoom > 0 && map.zoom <= 100 && map.zoomCenterX.isFinite() && map.zoomCenterY.isFinite()) {
            "Zoom de carte Wolf invalide : ${map.zoom} autour de ${map.zoomCenterX},${map.zoomCenterY}."
        }
    }

    /** Resource culling and depth traversal use the inverse of the actual map transform. */
    private fun visibleTiles(scene: NativeFrame, map: NativeMapScene): Rect {
        validateMapZoom(map)
        val size = map.tileSize.coerceAtLeast(1)
        fun x(screen: Float) = map.cameraX + map.zoomCenterX + (screen - map.zoomCenterX) / map.zoom
        fun y(screen: Float) = map.cameraY + map.zoomCenterY + (screen - map.zoomCenterY) / map.zoom
        fun bound(value: Int, loop: Boolean, limit: Int) = if (loop) value else value.coerceIn(0, limit)
        val region = Rect(bound(floor(x(0f) / size).toInt(), map.map.loopX, map.map.width),
            bound(floor(y(0f) / size).toInt(), map.map.loopY, map.map.height),
            bound(ceil(x(scene.logicalWidth.toFloat()) / size).toInt(), map.map.loopX, map.map.width),
            bound(ceil(y(scene.logicalHeight.toFloat()) / size).toInt(), map.map.loopY, map.map.height))
        require(region.width().toLong() * region.height() <= 1_048_576L) { "Le zoom Wolf dépasse le budget de tiles visibles." }
        return region
    }

    /** Native tile lookup wraps independently on each configured loop axis. */
    private fun sourceTile(map: NativeMapScene, x: Int, y: Int, layer: Int) = map.map.tile(
        if (map.map.loopX) Math.floorMod(x, map.map.width) else x,
        if (map.map.loopY) Math.floorMod(y, map.map.height) else y, layer)

    /** Put the character's equivalent world position nearest the viewport center.
     * Its ground/shadow, depth sorting and elevated sprite use this same copy. */
    private fun projectedCharacters(scene: NativeFrame, map: NativeMapScene): List<NativeCharacter> {
        if (!map.map.loopX && !map.map.loopY) return map.characters
        val centerX = map.cameraX + map.zoomCenterX + (scene.logicalWidth / 2f - map.zoomCenterX) / map.zoom
        val centerY = map.cameraY + map.zoomCenterY + (scene.logicalHeight / 2f - map.zoomCenterY) / map.zoom
        fun closest(value: Float, center: Float, period: Int) = value + floor((center - value) / period + .5f) * period
        return map.characters.map { it.copy(x = if (map.map.loopX) closest(it.x, centerX, map.map.width * map.tileSize) else it.x,
            y = if (map.map.loopY) closest(it.y, centerY, map.map.height * map.tileSize) else it.y) }
    }

    private fun drawTriangleRow(canvas: Canvas, map: NativeMapScene, left: Int, right: Int, y: Int, tick: Long): Boolean =
        withVisibility(canvas, map.maskEnabled) { drawTriangleRowSource(canvas, map, left, right, y, tick) }

    private fun drawTriangleRowSource(canvas: Canvas, map: NativeMapScene, left: Int, right: Int, y: Int, tick: Long): Boolean {
        var actual = false
        for (x in left until right) {
            var inherited = 0
            for (layer in map.map.layerCount - 1 downTo 0) {
                val tile = sourceTile(map, x, y, layer)
                inherited = max(inherited, tilePriority(map.tileset, tile))
                if (tile >= 0 && inherited == 1) actual = drawTile(canvas, map.tileset, tile, map.tileSize,
                    x * map.tileSize - map.cameraX, y * map.tileSize - map.cameraY, tick, bitmapPaint) || actual
            }
        }
        return actual
    }

    /** Negative tone changes map sources; positive tone adds after the map. */
    private fun drawTonedMap(canvas: Canvas, scene: NativeFrame): Boolean {
        val previousTone = mapTone
        val previousFilter = mapToneFilter
        val previousPaintFilter = bitmapPaint.colorFilter
        mapTone = scene.tone
        mapToneFilter = colorScaleFilter(toneScale(mapTone.red), toneScale(mapTone.green), toneScale(mapTone.blue))
        bitmapPaint.colorFilter = mapToneFilter
        val actual = try {
            var drawn = scene.map?.let { drawMap(canvas, scene, it) }
                ?: drawPictures(canvas, scene, Int.MIN_VALUE, if (config.pictureBehaviorVersion >= 2) -100_000 else -1)
            if (config.pictureBehaviorVersion >= 2) drawn = drawPictures(canvas, scene, -99_999, -1) || drawn
            drawn
        } finally {
            mapTone = previousTone; mapToneFilter = previousFilter; bitmapPaint.colorFilter = previousPaintFilter
        }
        if (scene.tone.red > 0 || scene.tone.green > 0 || scene.tone.blue > 0) {
            uiPaint.color = Color.rgb(scene.tone.red.coerceIn(0, 255), scene.tone.green.coerceIn(0, 255), scene.tone.blue.coerceIn(0, 255))
            uiPaint.alpha = 255; uiPaint.xfermode = ADD
            try { canvas.drawRect(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat(), uiPaint) }
            finally { uiPaint.xfermode = null }
        }
        return actual
    }

    private fun toneScale(channel: Int) = (255 + min(channel, 0)).coerceIn(0, 255) / 255f

    /** The file stores layers top to bottom; higher layers inherit ▲/★ below. */
    private fun inheritedTilePriority(tileset: WolfTileset, tiles: Array<IntArray>, layer: Int, index: Int): Int {
        if (tiles[layer][index] < 0) return 0
        var priority = 0
        for (below in layer until tiles.size) priority = max(priority, tilePriority(tileset, tiles[below][index]))
        return priority
    }

    private data class ChunkKey(val map: Int, val tileset: Int, val tileSize: Int,
        val x: Int, val y: Int, val priority: Int, val content: Int, val textures: Long, val animation: Long)
    private data class Chunk(val bitmap: Bitmap, val actual: Boolean)

    private fun drawMapPriority(canvas: Canvas, scene: NativeFrame, map: NativeMapScene, priority: Int,
        left: Int, top: Int, right: Int, bottom: Int): Boolean =
        withVisibility(canvas, map.maskEnabled) { drawMapPrioritySource(canvas, scene, map, priority, left, top, right, bottom) }

    private fun drawMapPrioritySource(canvas: Canvas, scene: NativeFrame, map: NativeMapScene, priority: Int,
        left: Int, top: Int, right: Int, bottom: Int): Boolean {
        if (left >= right || top >= bottom) return false
        var actual = false
        for (cy in Math.floorDiv(top, CHUNK_SIZE)..Math.floorDiv(bottom - 1, CHUNK_SIZE)) {
            for (cx in Math.floorDiv(left, CHUNK_SIZE)..Math.floorDiv(right - 1, CHUNK_SIZE)) {
                val fromX = cx * CHUNK_SIZE; val fromY = cy * CHUNK_SIZE
                val countX = if (map.map.loopX) CHUNK_SIZE else min(CHUNK_SIZE, map.map.width - fromX)
                val countY = if (map.map.loopY) CHUNK_SIZE else min(CHUNK_SIZE, map.map.height - fromY)
                if (countX <= 0 || countY <= 0) continue
                val tiles = Array(map.map.layerCount) { layer ->
                    IntArray(countX * countY) { index -> sourceTile(map, fromX + index % countX, fromY + index / countX, layer) }
                }
                var hash = 1
                var animated = false
                for (layer in tiles) for (tile in layer) {
                    hash = 31 * hash + tile
                    tilePath(map.tileset, tile)?.let { path ->
                        if (tile >= 100_000 && (resources.peek(path)?.width ?: 0) > map.tileSize) animated = true
                    }
                }
                val key = ChunkKey(System.identityHashCode(map.map), System.identityHashCode(map.tileset), map.tileSize,
                    cx, cy, priority, hash, resources.revision, animationSignature(map, scene.tick, animated))
                val chunk = synchronized(chunks) { chunks[key] }
                if (chunk != null) {
                    canvas.drawBitmap(chunk.bitmap, fromX * map.tileSize - map.cameraX, fromY * map.tileSize - map.cameraY, bitmapPaint)
                    actual = chunk.actual || actual
                } else {
                    val paths = tiles.flatMap { it.asIterable() }.mapNotNull { tilePath(map.tileset, it) }.distinct()
                    if (resources.isReady(paths)) buildChunk(key, map, tiles, countX, countY, scene.tick)
                    // Correct first frame while the off-thread flattened region is prepared.
                    for (layer in tiles.indices.reversed()) for (index in tiles[layer].indices) {
                        val tile = tiles[layer][index]
                        if (inheritedTilePriority(map.tileset, tiles, layer, index) != priority) continue
                        val x = fromX + index % countX; val y = fromY + index / countX
                        if (x !in left until right || y !in top until bottom) continue
                        actual = drawTile(canvas, map.tileset, tile, map.tileSize,
                            x * map.tileSize - map.cameraX, y * map.tileSize - map.cameraY, scene.tick, bitmapPaint) || actual
                    }
                }
            }
        }
        return actual
    }

    private fun animationSignature(map: NativeMapScene, tick: Long, animated: Boolean): Long {
        if (!animated) return 0
        var hash = 1L
        map.tileset.autoImages.forEach { path ->
            val bitmap = if (path.isBlank()) null else resources.peek(path)
            if (bitmap != null && bitmap.width > map.tileSize) {
                val frames = bitmap.width / map.tileSize
                hash = 31 * hash + tick / animationInterval(path) % frames
            }
        }
        return hash
    }

    private fun buildChunk(key: ChunkKey, map: NativeMapScene, tiles: Array<IntArray>, countX: Int, countY: Int, tick: Long) {
        if (!pendingChunks.add(key)) return
        try {
            chunkWorkers.execute {
                var bitmap: Bitmap? = null
                try {
                    if (closed) return@execute
                    bitmap = Bitmap.createBitmap(countX * map.tileSize, countY * map.tileSize, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap!!)
                    val paint = Paint().apply { isFilterBitmap = false }
                    var actual = false
                    for (layer in tiles.indices.reversed()) for (index in tiles[layer].indices) {
                        val tile = tiles[layer][index]
                        if (inheritedTilePriority(map.tileset, tiles, layer, index) == key.priority) actual = drawTile(canvas, map.tileset, tile, map.tileSize,
                            (index % countX * map.tileSize).toFloat(), (index / countX * map.tileSize).toFloat(), tick, paint, localRects = true) || actual
                    }
                    synchronized(chunks) {
                        if (!closed && frame?.map?.map === map.map) {
                            chunks.put(key, Chunk(bitmap!!, actual))?.let { chunkBytes -= it.bitmap.allocationByteCount }
                            chunkBytes += bitmap!!.allocationByteCount
                            bitmap = null
                            val iterator = chunks.entries.iterator()
                            while (chunkBytes > CHUNK_BUDGET && iterator.hasNext()) { chunkBytes -= iterator.next().value.bitmap.allocationByteCount; iterator.remove() }
                        }
                    }
                    main.post { if (!closed) invalidate() }
                } catch (error: Exception) { main.post { if (!closed) report(error.message ?: error.javaClass.simpleName) } }
                catch (_: OutOfMemoryError) { main.post { if (!closed) report("Mémoire insuffisante pour le cache de carte Wolf.") } }
                finally { bitmap?.recycle(); pendingChunks.remove(key) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { pendingChunks.remove(key) }
    }

    private fun drawTile(canvas: Canvas, tileset: WolfTileset, raw: Int, size: Int, x: Float, y: Float,
        tick: Long, paint: Paint, localRects: Boolean = false): Boolean {
        if (raw < 0) return false
        val path = tilePath(tileset, raw) ?: return false
        val bitmap = resources.peek(path) ?: return false
        val source = if (localRects) Rect() else sourceRect
        val destination = if (localRects) RectF() else destinationRect
        if (raw >= 100_000) {
            require(bitmap.height == size * 5 && bitmap.width % size == 0) { "Autotile Wolf « $path » : taille ${bitmap.width} × ${bitmap.height}, attendue ${size}n × ${size * 5}." }
            val half = size / 2
            val animation = (tick / animationInterval(path) % (bitmap.width / size)).toInt()
            val digits = raw % 100_000
            val modes = intArrayOf(digits / 1000, digits / 100 % 10, digits / 10 % 10, digits % 10)
            for (quarter in 0..3) {
                val mode = modes[quarter]
                require(mode in 0..4) { "Autotile Wolf $raw : quadrant $quarter inconnu ($mode)." }
                val qx = quarter % 2 * half; val qy = quarter / 2 * half
                source.set(animation * size + qx, mode * size + qy, animation * size + qx + half, mode * size + qy + half)
                destination.set(x + qx, y + qy, x + qx + half, y + qy + half)
                canvas.drawBitmap(bitmap, source, destination, paint)
            }
        } else {
            require(bitmap.width == size * 8 && bitmap.height % size == 0) { "Tileset Wolf « $path » : dimensions ${bitmap.width} × ${bitmap.height} incompatibles avec les tiles de $size px." }
            val index = raw
            val sx = index % 8 * size; val sy = index / 8 * size
            require(sy + size <= bitmap.height) { "Tile Wolf $raw hors de l’atlas « $path »." }
            source.set(sx, sy, sx + size, sy + size); destination.set(x, y, x + size, y + size)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }
        return true
    }

    private fun tilePath(tileset: WolfTileset, raw: Int): String? {
        if (raw < 0) return null
        return if (raw >= 100_000) {
            // The .mps matrix uses slot 1 for the transparent tile (all four
            // quadrant states), slots 2..16 for W2's 15 actual autotiles, and
            // zero-based base-atlas indices. The 0..31/32.. chip numbers shown
            // in the editor are a separate logical numbering system.
            val id = raw / 100_000 - 1
            if (id == 0) return null
            require(id in 1..tileset.autoImages.size) { "Autotile Wolf $id absent du tileset ${tileset.id}." }
            // W2 FUN_004d01f0 checks the configured slot before drawing: an
            // empty declared autotile is transparent, even when a map uses it.
            tileset.autoImages[id - 1].takeIf(String::isNotBlank)
        } else tileset.baseImage.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Image de base manquante dans le tileset Wolf ${tileset.id}.")
    }

    private fun tilePriority(tileset: WolfTileset, raw: Int): Int {
        if (raw < 0) return 0
        val index = tileset.passageIndex(raw)
        val flags = tileset.passage.getOrNull(index) ?: throw IllegalArgumentException("Attributs du tile Wolf $raw absents du tileset ${tileset.id}.")
        return if (flags and 0x10 != 0) 2 else if (flags and 0x100 != 0) 1 else 0
    }

    private fun drawCharacter(canvas: Canvas, map: NativeMapScene, character: NativeCharacter, tick: Long): Boolean =
        withVisibility(canvas, character.maskEnabled) { drawCharacterSource(canvas, map, character, tick) }

    private fun drawCharacterSource(canvas: Canvas, map: NativeMapScene, character: NativeCharacter, tick: Long): Boolean {
        // W2 004c6e80 skips all blends when the character opacity is zero.
        if (character.opacity <= 0) return false
        require(character.blend in 0..3) { "Personnage Wolf ${character.id} : mélange ${character.blend} inconnu." }
        require(character.heightOffset.isFinite()) { "Hauteur du personnage Wolf ${character.id} non finie." }
        val previousFilter = bitmapPaint.colorFilter
        val inBush = character.bush && character.heightOffset <= 0f
        bitmapPaint.alpha = if (character.blend == 3 && inBush) 255 else character.opacity.coerceIn(0, 255)
        setGraphicBlend(character.blend)
        fun graphicDestination(): Canvas = if (softwareGraphicBlend(character.blend)) {
            val software = softwareScene ?: error("Composition des couleurs de personnage Wolf non préparée.")
            software.mask.eraseColor(Color.TRANSPARENT)
            canvas.getMatrix(software.matrix); software.maskCanvas.setMatrix(software.matrix)
            software.maskCanvas
        } else canvas
        try {
        val x = character.x - map.cameraX; val y = character.y - map.cameraY
        val bitmap = if (character.tileId >= 0) null else {
            if (character.path.isBlank()) return false
            resources.peek(character.path) ?: return false
        }
        if (bitmap != null) character.shadowPath?.takeIf(String::isNotBlank)?.let { path ->
            if (resources.isOptionalMissing(path) && shadowNotices.add(WolfNativeResources.logicalPath(path))) {
                callbacks.notice("Ombre Wolf « $path » indisponible : ${resources.optionalMissingReason(path)} ; passe d’ombre ignorée, personnage conservé.")
            }
            resources.peek(path)?.let { shadow ->
                characterSource(shadow, path, map, character, sourceRect)
                val width = sourceRect.width().toFloat(); val height = sourceRect.height().toFloat()
                destinationRect.set(x - width / 2, y - height, x + width / 2, y)
                val shadowPaint = Paint(bitmapPaint).apply {
                    // Initial MULA/ALPHA/ADD/SUB state also draws the shadow,
                    // before character RGB effects and before the bush split.
                    alpha = character.opacity.coerceIn(0, 255) / if (inBush) 3 else 1
                }
                graphicDestination().drawBitmap(shadow, sourceRect, destinationRect, shadowPaint)
                if (softwareGraphicBlend(character.blend)) applySoftwareGraphicBlend(character.blend)
            }
        }
        if (bitmap != null) characterSource(bitmap, character.path, map, character, sourceRect)
        val sourceHeight = if (bitmap == null) map.tileSize else sourceRect.height()
        val drawWidth = if (bitmap == null) map.tileSize.toFloat() else if (character.width > 0) character.width else sourceRect.width().toFloat()
        val drawHeight = if (bitmap == null) map.tileSize.toFloat() else if (character.height > 0) character.height else sourceRect.height().toFloat()
        val left = x - drawWidth / 2; val top = y - character.heightOffset - drawHeight
        val right = x + drawWidth / 2; val bottom = y - character.heightOffset
        fun drawSource(target: Canvas): Boolean = drawBushGraphic(target, map, character, sourceHeight,
            left, top, right, bottom) {
            if (bitmap == null) drawTile(target, map.tileset, character.tileId, map.tileSize, x - map.tileSize / 2f,
                y - character.heightOffset - map.tileSize, tick, bitmapPaint)
            else { destinationRect.set(left, top, right, bottom); target.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint); true }
        }
        bitmapPaint.colorFilter = sourceToneColor(character.tone, additive = false)
        val drawn = drawSource(graphicDestination())
        if (drawn && softwareGraphicBlend(character.blend)) applySoftwareGraphicBlend(character.blend)
        if (drawn && (character.tone.red > 0 || character.tone.green > 0 || character.tone.blue > 0)) {
            bitmapPaint.colorFilter = sourceToneColor(character.tone, additive = true)
            // The full sprite's ADD effect is opaque in W2. Bush branches
            // instead restore character opacity on top and opacity/3 below.
            bitmapPaint.alpha = if (inBush) character.opacity.coerceIn(0, 255) else 255
            setGraphicBlend(1)
            drawSource(canvas)
        }
        return drawn
        } finally { bitmapPaint.alpha = 255; bitmapPaint.colorFilter = previousFilter; setGraphicBlend(0) }
    }

    /** W2 004c6e80: last half-tile at opacity/3, remainder at full character opacity. */
    private inline fun drawBushGraphic(canvas: Canvas, map: NativeMapScene, character: NativeCharacter,
        sourceHeight: Int, left: Float, top: Float, right: Float, bottom: Float, draw: () -> Boolean): Boolean {
        if (!character.bush || character.heightOffset > 0f) return draw()
        val upperAlpha = bitmapPaint.alpha
        val split = bottom - min(map.tileSize / 2, sourceHeight) * (bottom - top) / sourceHeight
        var actual = false
        val lowerSaved = canvas.save()
        try {
            canvas.clipRect(left, split, right, bottom)
            // MULA for the lower rectangle also observes opacity; the full
            // multiply rectangle keeps the existing MUL opacity semantics.
            bitmapPaint.alpha = character.opacity.coerceIn(0, 255) / 3
            actual = draw()
        } finally { bitmapPaint.alpha = upperAlpha; canvas.restoreToCount(lowerSaved) }
        if (split > top) {
            val upperSaved = canvas.save()
            try {
                canvas.clipRect(left, top, right, split)
                actual = draw() || actual
            } finally { canvas.restoreToCount(upperSaved) }
        }
        return actual
    }

    private fun characterSource(bitmap: Bitmap, path: String, map: NativeMapScene, character: NativeCharacter, result: Rect) {
        val name = path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').uppercase()
        val full = name.endsWith('$')
        val patterns = map.animationPatterns.coerceIn(1, 5)
        val extra = if (name.endsWith("TX")) patterns else if (name.endsWith('T')) 1 else 0
        val directions = if (map.characterDirections == 8) 2 else 1
        val columns = (patterns + extra) * directions
        val cellWidth = if (full) bitmap.width else bitmap.width / columns
        val cellHeight = if (full) bitmap.height else bitmap.height / 4
        require(full || bitmap.width % columns == 0 && bitmap.height % 4 == 0 && cellWidth > 0) {
            "CharaChip Wolf « $path » : grille $columns × 4 incompatible avec ${bitmap.width} × ${bitmap.height}."
        }
        val direction = character.direction
        val row = when (direction) { 2, 1 -> 0; 4, 3 -> 1; 6, 7 -> 2; 8, 9 -> 3; else -> error("Direction Wolf inconnue : $direction.") }
        val diagonal = if (directions == 2 && direction in setOf(1, 3, 7, 9)) patterns + extra else 0
        val normalPattern = character.pattern.coerceIn(0, patterns - 1)
        val pattern = when {
            name.endsWith("TX") -> normalPattern + if (character.moving) patterns else 0
            name.endsWith('T') -> if (character.moving) normalPattern + 1 else 0
            else -> normalPattern
        }
        val sx = if (full) 0 else (diagonal + pattern) * cellWidth
        val sy = if (full) 0 else row * cellHeight
        result.set(sx, sy, sx + cellWidth, sy + cellHeight)
    }

    private fun drawPicture(canvas: Canvas, scene: NativeFrame, picture: NativePicture): Boolean =
        withVisibility(canvas, picture.maskEnabled) { drawPictureSource(canvas, scene, picture) }

    private fun drawPictureSource(canvas: Canvas, scene: NativeFrame, picture: NativePicture): Boolean {
        if (picture.opacity <= 0 && picture.blend != 3 || picture.scaleX == 0f || picture.scaleY == 0f) return false
        require(picture.blend in 0..3) { "Picture Wolf ${picture.id} : mélange ${picture.blend} inconnu." }
        require(picture.anchor in 0..6) { "Ancrage de picture Wolf ${picture.id} inconnu : ${picture.anchor}." }
        val window = picture.kind in 3..4
        val picturePath = picture.path
        val shape = window && picturePath?.startsWith('<') == true && !picturePath.startsWith("<CUT/", true)
        val reference = picturePath?.takeIf { it.isNotBlank() && !shape }?.let(::imageReference)
        val bitmap = reference?.let { image -> picture.video?.let { resources.videos.peek(resources.videos.key(picture.id, image.path, it)) }
            ?: if (picture.video == null) resources.peek(image.path, picture.imageFilters) else null }
        if (reference != null && bitmap == null) return false
        val text = picture.text
        // W2 4920a0 omits nonpositive patterns. W3 98fd20 first compares the
        // unsigned pattern to vector.size (430150): a negative or too-large
        // value selects the last handle, while zero still omits the bitmap.
        if (bitmap != null && !window && text.isNullOrEmpty() && reference!!.animationTicks <= 0 &&
            selectedPattern(reference, picture).let { it == 0 || config.pictureBehaviorVersion < 3 && it < 0 }) return false
        if (bitmap != null) pictureSource(bitmap, reference!!, picture, scene.tick, sourceRect, geometryOnly = window)
        val measuredText = text?.takeIf(String::isNotEmpty)?.let { pictureTextLayout(picture) }
        val textWidth = measuredText?.let { layout -> (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f } ?: 0f
        val width = if (window || picture.width > 0) picture.width else measuredText?.let { textWidth } ?: sourceRect.takeIf { bitmap != null }?.width()?.toFloat() ?: scene.logicalWidth.toFloat()
        val height = if (window || picture.height > 0) picture.height else measuredText?.height?.toFloat() ?: sourceRect.takeIf { bitmap != null }?.height()?.toFloat() ?: picture.fontSize * 1.4f
        val x = picture.x - if (!picture.screenRelative) scene.map?.cameraX ?: 0f else 0f
        val y = picture.y - if (!picture.screenRelative) scene.map?.cameraY ?: 0f else 0f
        val tone = pictureTone(picture)
        val saved = canvas.save()
        val previousFilter = bitmapPaint.colorFilter
        val solid = bitmap == null && text.isNullOrEmpty() && picture.width > 0 && picture.height > 0
        try {
            canvas.translate(x, y)
            if (!window) canvas.rotate(picture.rotation)
            canvas.scale(picture.scaleX, picture.scaleY)
            val offset = anchorOffset(picture.anchor, width, height)
            canvas.translate(offset.first, offset.second)
            val destination = if (softwareGraphicBlend(picture.blend)) {
                val software = softwareScene ?: error("Composition des couleurs Wolf non préparée.")
                software.mask.eraseColor(Color.TRANSPARENT)
                canvas.getMatrix(software.matrix); software.maskCanvas.setMatrix(software.matrix)
                software.maskCanvas
            } else canvas
            bitmapPaint.alpha = if (picture.blend == 3) 255 else picture.opacity.coerceIn(0, 255)
            bitmapPaint.colorFilter = pictureColor(picture, tone, additive = false)
            setGraphicBlend(picture.blend)
            fun drawSource(target: Canvas) {
                if (bitmap != null) {
                    if (window) drawWindow(target, bitmap, RectF(0f, 0f, width, height), bitmapPaint)
                    else { destinationRect.set(0f, 0f, width, height); target.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint) }
                }
                if (shape) drawShape(target, picture.path!!, width, height, bitmapPaint)
                else if (solid && !window) {
                    bitmapPaint.color = Color.WHITE
                    target.drawRect(0f, 0f, width, height, bitmapPaint)
                }
                if (!text.isNullOrEmpty()) {
                    val layer = target.saveLayer(RectF(-picture.fontSize, -picture.fontSize, width + picture.fontSize, height + picture.fontSize), bitmapPaint)
                    try {
                        measuredText!!.draw(target, picture.visibleCharacters)
                    } finally { target.restoreToCount(layer) }
                }
            }
            drawSource(destination)
            if (softwareGraphicBlend(picture.blend)) applySoftwareGraphicBlend(picture.blend)
            // W2 004920a0 draws the texture again with ADD and positive RGB
            // brightness. It does not add a constant color through a white
            // alpha mask, nor apply a >100% multiplier to the first blend.
            if ((tone.red > 0 || tone.green > 0 || tone.blue > 0) && picture.opacity > 0) {
                bitmapPaint.alpha = picture.opacity.coerceIn(0, 255)
                bitmapPaint.colorFilter = pictureColor(picture, tone, additive = true)
                setGraphicBlend(1)
                drawSource(canvas)
            }
        } finally {
            bitmapPaint.color = Color.WHITE; bitmapPaint.alpha = 255; bitmapPaint.colorFilter = previousFilter; setGraphicBlend(0)
            canvas.restoreToCount(saved)
        }
        return bitmap != null || !text.isNullOrEmpty() && picture.visibleCharacters > 0 || shape || solid && !window
    }

    private fun softwareGraphicBlend(blend: Int) = blend == 2 || blend == 3 && Build.VERSION.SDK_INT < 29

    private fun setGraphicBlend(blend: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            bitmapPaint.blendMode = when (blend) { 1 -> BlendMode.PLUS; 3 -> BlendMode.MULTIPLY; else -> BlendMode.SRC_OVER }
        } else bitmapPaint.xfermode = if (blend == 1) ADD else null
    }

    private fun applySoftwareGraphicBlend(blend: Int) {
        val visibility = renderingVisibility.takeIf { visibilityEnabled }
        if (blend == 2) softwareScene!!.subtract(visibility) else softwareScene!!.multiply(visibility)
    }

    /** Exact legacy RGB blends; PorterDuff MULTIPLY would also multiply alpha. */
    private class SoftwareScene(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        val matrix = Matrix()
        private val destination = IntArray(width * height)
        private val source = IntArray(width * height)
        private fun visible(index: Int, visibility: NativeVisibilityMask?): Boolean {
            if (visibility == null) return true
            val x = index % bitmap.width; val y = index / bitmap.width
            val mx = ((2L * x + 1) * visibility.width / (2L * bitmap.width)).toInt().coerceAtMost(visibility.width - 1)
            val my = ((2L * y + 1) * visibility.height / (2L * bitmap.height)).toInt().coerceAtMost(visibility.height - 1)
            return visibility.pixels[my * visibility.width + mx].toInt() == 0
        }
        fun subtract(visibility: NativeVisibilityMask?) {
            val width = bitmap.width; val height = bitmap.height
            bitmap.getPixels(destination, 0, width, 0, 0, width, height)
            mask.getPixels(source, 0, width, 0, 0, width, height)
            for (i in destination.indices) {
                val alpha = source[i] ushr 24
                if (alpha == 0 || !visible(i, visibility)) continue
                val d = destination[i]; val s = source[i]
                val r = max(0, (d ushr 16 and 255) - ((s ushr 16 and 255) * alpha + 127) / 255)
                val g = max(0, (d ushr 8 and 255) - ((s ushr 8 and 255) * alpha + 127) / 255)
                val b = max(0, (d and 255) - ((s and 255) * alpha + 127) / 255)
                destination[i] = (d and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(destination, 0, width, 0, 0, width, height)
        }

        fun multiply(visibility: NativeVisibilityMask?) {
            val width = bitmap.width; val height = bitmap.height
            bitmap.getPixels(destination, 0, width, 0, 0, width, height)
            mask.getPixels(source, 0, width, 0, 0, width, height)
            for (i in destination.indices) {
                val alpha = source[i] ushr 24
                if (alpha == 0 || !visible(i, visibility)) continue
                val d = destination[i]; val s = source[i]
                fun channel(shift: Int): Int {
                    val value = d ushr shift and 255
                    val product = (value * (s ushr shift and 255) + 127) / 255
                    return (product * alpha + value * (255 - alpha) + 127) / 255
                }
                destination[i] = (d and 0xff000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            }
            bitmap.setPixels(destination, 0, width, 0, 0, width, height)
        }
    }

    /** Command150's percentage brightness plus the signed command290 offsets. */
    private fun pictureTone(picture: NativePicture): NativeTone {
        fun offset(value: Float, effect: Int): Int {
            require(value.isFinite()) { "Composantes de couleur Wolf invalides." }
            val channel = (value.toDouble() - 1.0) * 255.0
            // Float snapshots must not turn the exact 140% -> +102 wire value
            // into +101 solely because 1.4f is stored just below 1.4.
            val integer = round(channel)
            val base = if (abs(channel - integer) < .001) integer.toInt() else channel.toInt()
            return (base.toLong() + effect).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        }
        return NativeTone(offset(picture.colorR, picture.tone.red), offset(picture.colorG, picture.tone.green),
            offset(picture.colorB, picture.tone.blue))
    }

    private fun pictureColor(picture: NativePicture, tone: NativeTone, additive: Boolean): android.graphics.ColorFilter? {
        return sourceToneColor(tone, additive, picture.color)
    }

    private fun sourceToneColor(tone: NativeTone, additive: Boolean, color: Int = -1): android.graphics.ColorFilter? {
        fun scale(channel: Int) = if (additive) channel.coerceIn(0, 255) / 255f else toneScale(channel)
        val r = scale(tone.red) * toneScale(mapTone.red) * if (color == -1) 1f else Color.red(color) / 255f
        val g = scale(tone.green) * toneScale(mapTone.green) * if (color == -1) 1f else Color.green(color) / 255f
        val b = scale(tone.blue) * toneScale(mapTone.blue) * if (color == -1) 1f else Color.blue(color) / 255f
        return colorScaleFilter(r, g, b)
    }

    private fun colorScaleFilter(r: Float, g: Float, b: Float): android.graphics.ColorFilter? =
        if (r == 1f && g == 1f && b == 1f) null else ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))

    private fun anchorOffset(anchor: Int, width: Float, height: Float): Pair<Float, Float> = when (anchor) {
        0 -> 0f to 0f
        1 -> -width / 2 to -height / 2
        2 -> 0f to -height
        3 -> -width to 0f
        4 -> -width to -height
        5 -> -width / 2 to 0f
        6 -> -width / 2 to -height
        else -> error("Ancrage Wolf inconnu : $anchor.")
    }

    /** Wolf EASY WINDOW images contain nine cells, with fixed-size corner/edge caps. */
    private fun drawWindow(canvas: Canvas, bitmap: Bitmap, target: RectF, paint: Paint, selected: Rect? = null) {
        val source = selected ?: Rect(0, 0, bitmap.width, bitmap.height)
        val borderX = source.width() / 3; val borderY = source.height() / 3
        require(borderX > 0 && borderY > 0 && target.width() >= borderX * 2 && target.height() >= borderY * 2) {
            "Fenêtre Wolf ${bitmap.width}×${bitmap.height} trop petite pour ${target.width()}×${target.height()}."
        }
        val sx = intArrayOf(source.left, source.left + borderX, source.right - borderX, source.right)
        val sy = intArrayOf(source.top, source.top + borderY, source.bottom - borderY, source.bottom)
        val dx = floatArrayOf(target.left, target.left + borderX, target.right - borderX, target.right)
        val dy = floatArrayOf(target.top, target.top + borderY, target.bottom - borderY, target.bottom)
        val src = Rect(); val dst = RectF()
        for (row in 0..2) for (col in 0..2) {
            if (dx[col] >= dx[col + 1] || dy[row] >= dy[row + 1]) continue
            src.set(sx[col], sy[row], sx[col + 1], sy[row + 1]); dst.set(dx[col], dy[row], dx[col + 1], dy[row + 1])
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
    }

    private fun drawShape(canvas: Canvas, descriptor: String, width: Float, height: Float, paint: Paint) {
        val end = descriptor.indexOf('>'); require(end >= 0) { "Forme Wolf invalide : $descriptor." }
        val type = descriptor.substring(1, end).uppercase()
        val outline = descriptor.substring(end + 1).equals("FRAME", true)
        require(type.startsWith("LINE") || width > 0 && height > 0) { "Dimensions de forme Wolf invalides : $width × $height." }
        paint.color = Color.WHITE; paint.style = if (outline) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = 1f
        try {
            when {
                type == "SQUARE" -> canvas.drawRect(0f, 0f, width, height, paint)
                type == "CIRCLE" -> canvas.drawOval(0f, 0f, width, height, paint)
                type.startsWith("LINE") -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = if (type == "LINE") 1f else type.removePrefix("LINE-").toFloatOrNull()?.takeIf { it > 0 } ?: error("Épaisseur de ligne Wolf invalide : $type.")
                    canvas.drawLine(0f, 0f, width, height, paint)
                }
                type.startsWith("TRI-") -> {
                    val path = Path()
                    when (type) {
                        "TRI-UP" -> { path.moveTo(width / 2, 0f); path.lineTo(width, height); path.lineTo(0f, height) }
                        "TRI-DOWN" -> { path.moveTo(0f, 0f); path.lineTo(width, 0f); path.lineTo(width / 2, height) }
                        "TRI-LEFT" -> { path.moveTo(0f, height / 2); path.lineTo(width, 0f); path.lineTo(width, height) }
                        "TRI-RIGHT" -> { path.moveTo(0f, 0f); path.lineTo(width, height / 2); path.lineTo(0f, height) }
                        else -> error("Triangle Wolf inconnu : $type.")
                    }
                    path.close(); canvas.drawPath(path, paint)
                }
                type.startsWith("GRADX-") || type.startsWith("GRADY-") -> {
                    val colors = type.split('-'); require(colors.size == 3) { "Dégradé Wolf invalide : $type." }
                    fun color(value: String): Int {
                        require(value.length == 3 && value.all(Char::isDigit)) { "Couleur de dégradé Wolf invalide : $value." }
                        return Color.rgb(value[0].digitToInt() * 255 / 9, value[1].digitToInt() * 255 / 9, value[2].digitToInt() * 255 / 9)
                    }
                    val horizontal = colors[0] == "GRADX"
                    paint.shader = LinearGradient(0f, 0f, if (horizontal) width else 0f, if (horizontal) 0f else height,
                        color(colors[1]), color(colors[2]), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, width, height, paint)
                }
                else -> error("Forme de fenêtre Wolf non portée : $descriptor.")
            }
        } finally { paint.style = Paint.Style.FILL; paint.shader = null; paint.strokeWidth = 1f }
    }

    private data class ImageReference(val path: String, val columns: Int = 1, val rows: Int = 1,
        val pattern: Int = 1, val crop: NativeRect? = null, val animationTicks: Int = 0)

    private fun imageReference(value: String): ImageReference {
        if (!value.startsWith('<')) return ImageReference(value)
        val end = value.indexOf('>'); require(end in 1 until value.lastIndex) { "Référence d’image Wolf invalide : $value." }
        val descriptor = value.substring(1, end)
        val path = value.substring(end + 1)
        val crop = Regex("CUT/X(\\d+)-(\\d+)/Y(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).matchEntire(descriptor)
        if (crop != null) {
            val (left, right, top, bottom) = crop.destructured
            val rect = NativeRect(left.toFloat(), top.toFloat(), right.toFloat() - left.toFloat(), bottom.toFloat() - top.toFloat())
            require(rect.width > 0 && rect.height > 0) { "Découpage Wolf vide : $value." }
            return ImageReference(path, crop = rect)
        }
        val divided = Regex("(DIV|ANIME)/X(\\d+)/Y(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).matchEntire(descriptor)
            ?: error("Préfixe d’image Wolf non porté : $value.")
        val columns = divided.groupValues[2].toInt(); val rows = divided.groupValues[3].toInt(); val last = divided.groupValues[4].toInt()
        require(columns in 1..1024 && rows in 1..1024 && last > 0) { "Grille d’image Wolf invalide : $value." }
        return if (divided.groupValues[1].equals("ANIME", true)) ImageReference(path, columns, rows, animationTicks = last)
        else ImageReference(path, columns, rows, last)
    }

    private fun pictureSource(bitmap: Bitmap, reference: ImageReference, picture: NativePicture, tick: Long, result: Rect,
        geometryOnly: Boolean = false) {
        val columns = if (reference.columns != 1) reference.columns else picture.divisionsX
        val rows = if (reference.rows != 1) reference.rows else picture.divisionsY
        require(columns > 0 && rows > 0 && bitmap.width % columns == 0 && bitmap.height % rows == 0) {
            "Grille de picture Wolf ${picture.id} incompatible avec ${bitmap.width}×${bitmap.height} ($columns × $rows)."
        }
        val cellWidth = bitmap.width / columns; val cellHeight = bitmap.height / rows
        // Intrinsic size and crop dimensions do not depend on a drawable selection,
        // including the native hidden pattern0. Windows also bypass pattern for a skin.
        val count = columns * rows
        val pattern = if (geometryOnly) 0
            else if (reference.animationTicks > 0) (tick / reference.animationTicks % count).toInt()
            else selectedPattern(reference, picture).let { selected ->
                val oneBased = if (config.pictureBehaviorVersion >= 3 && selected.toUInt() >= count.toUInt()) count else selected
                oneBased - 1
            }
        require(pattern in 0 until columns * rows) { "Motif Wolf ${pattern + 1} hors grille ($columns × $rows)." }
        val left = pattern % columns * cellWidth; val top = pattern / columns * cellHeight
        val crop = picture.sourceRect ?: reference.crop
        if (crop == null) result.set(left, top, left + cellWidth, top + cellHeight)
        else {
            require(crop.x >= 0 && crop.y >= 0 && crop.width > 0 && crop.height > 0 &&
                crop.x + crop.width <= cellWidth && crop.y + crop.height <= cellHeight) {
                "Découpage hors motif pour la picture Wolf ${picture.id} « ${reference.path} »."
            }
            result.set(left + crop.x.toInt(), top + crop.y.toInt(), left + (crop.x + crop.width).toInt(), top + (crop.y + crop.height).toInt())
        }
    }

    private fun drawDialog(canvas: Canvas, scene: NativeFrame, dialog: NativeDialog): Boolean = withFontOverrides(dialog.fontOverrides) {
        drawDialogContent(canvas, scene, dialog)
    }
    private fun drawDialogContent(canvas: Canvas, scene: NativeFrame, dialog: NativeDialog): Boolean {
        val margin = max(6f, scene.logicalWidth / 80f)
        val font = dialog.fontSize.coerceIn(4f, 512f)
        val textColor = config.textColors[0] ?: Color.WHITE
        var top = scene.logicalHeight.toFloat()
        var actual = false
        if (dialog.text.isNotEmpty()) {
            val requested = dialog.bounds
            val textWidth = requested?.width?.takeIf { it > 0 } ?: scene.logicalWidth - margin * 4
            val text = textLayout(dialog.text, font, textColor, textWidth, dialog.rubySize, dialog.shadowOffset,
                convertSpecial = dialog.convertSpecial)
            val textHeight = requested?.height?.takeIf { it > 0 } ?: max(scene.logicalHeight * .30f, text.height.toFloat())
            val textX = requested?.x ?: margin * 2
            val textY = requested?.y ?: scene.logicalHeight - textHeight - margin * 2
            require(text.height <= textHeight + 1 && textY + textHeight <= scene.logicalHeight) {
                "Le message Wolf dépasse la zone de texte ($textWidth × $textHeight) ; pagination requise."
            }
            top = textY - margin
            if (dialog.drawWindow) {
                val bounds = RectF(textX - margin, textY - margin, textX + textWidth + margin, textY + textHeight + margin)
                val skin = dialog.skinPath?.let(resources::peek)
                if (skin != null) { drawWindow(canvas, skin, bounds, bitmapPaint); actual = true }
                else if (dialog.skinPath == null) {
                    uiPaint.color = Color.argb(235, 16, 20, 32); canvas.drawRoundRect(bounds, 4f, 4f, uiPaint); actual = true
                }
            }
            drawText(canvas, dialog.text, textX, textY, font, textColor, textWidth, dialog.visibleCharacters,
                dialog.rubySize, dialog.shadowOffset, dialog.convertSpecial)
            actual = actual || dialog.visibleCharacters > 0
            if (dialog.waiting) dialog.pausePath?.let { path ->
                resources.peek(path)?.let { bitmap ->
                    val frame = verticalFrame(bitmap, dialog.pauseFrames, dialog.pauseFrame, path)
                    val x = textX + (dialog.pauseX ?: textWidth / 2f) - frame.width() / 2f
                    val y = textY + (dialog.pauseY ?: textHeight)
                    bitmapPaint.alpha = 255; bitmapPaint.colorFilter = null; bitmapPaint.xfermode = null
                    canvas.drawBitmap(bitmap, frame, RectF(x, y, x + frame.width(), y + frame.height()), bitmapPaint)
                    actual = true
                }
            }
        }
        if (dialog.choices.isNotEmpty()) {
            val padX = dialog.marginX.coerceAtLeast(0f); val padY = dialog.marginY.coerceAtLeast(0f)
            val available = (dialog.choiceY?.let { scene.logicalHeight - it - margin } ?: (top - margin * 2)).coerceAtLeast(1f)
            val maximumWidth = scene.logicalWidth - margin * 2
            val naturalWidth = dialog.choices.maxOf { text ->
                val layout = textLayout(text, font, textColor, maximumWidth, dialog.rubySize, dialog.shadowOffset,
                    convertSpecial = dialog.convertSpecial)
                (0 until layout.lineCount).maxOfOrNull(layout::getLineWidth) ?: 0f
            }
            var boxWidth = (naturalWidth + padX * 2).coerceIn(padX * 2 + 1f, max(padX * 2 + 1f, maximumWidth))
            fun heights() = dialog.choices.map { max(font * 1.25f, textLayout(it, font, textColor, boxWidth - padX * 2,
                dialog.rubySize, dialog.shadowOffset, convertSpecial = dialog.convertSpecial).height + padY * 2) }
            var rows = heights()
            // Prefer wider wrapped labels to overlapping fixed-height rows. A tall list follows
            // the selected row; hit testing retains the original choice indices.
            if (rows.sum() > available) { boxWidth = maximumWidth; rows = heights() }
            require(rows.all { it <= available }) { "Un choix Wolf dépasse la hauteur disponible ; libellé non affichable dans cette fenêtre." }
            val selected = dialog.selectedChoice.coerceIn(dialog.choices.indices)
            var start = 0
            var selectedHeight = rows.take(selected + 1).sum()
            while (selectedHeight > available && start < selected) selectedHeight -= rows[start++]
            var end = start; var totalHeight = 0f
            while (end < rows.size && totalHeight + rows[end] <= available) totalHeight += rows[end++]
            val boxLeft = dialog.choiceX?.let { it - boxWidth / 2 } ?: scene.logicalWidth - boxWidth - margin
            val boxTop = dialog.choiceY ?: max(margin, top - totalHeight - margin)
            val skin = dialog.choiceSkinPath?.let(resources::peek)
            if (dialog.drawChoiceWindow && skin != null) drawWindow(canvas, skin, RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + totalHeight), bitmapPaint)
            else if (dialog.drawChoiceWindow && dialog.choiceSkinPath == null) {
                uiPaint.color = Color.argb(245, 20, 26, 42)
                canvas.drawRoundRect(boxLeft, boxTop, boxLeft + boxWidth, boxTop + totalHeight, 4f, 4f, uiPaint)
            }
            var rowTop = boxTop
            choiceIndexes = (start until end).toList()
            choiceRects = choiceIndexes.map { index ->
                val bounds = RectF(boxLeft, rowTop, boxLeft + boxWidth, rowTop + rows[index]); rowTop = bounds.bottom
                if (index == selected) {
                    val cursor = dialog.choiceCursorPath?.let(resources::peek)
                    if (cursor != null) {
                        require(dialog.cursorBlend in 0..1) { "Mode du curseur de choix Wolf inconnu : ${dialog.cursorBlend}." }
                        bitmapPaint.xfermode = if (dialog.cursorBlend == 1) ADD else null
                        val selectedFrame = verticalFrame(cursor, dialog.choiceCursorFrames, dialog.choiceCursorFrame, dialog.choiceCursorPath!!)
                        try { drawWindow(canvas, cursor, bounds, bitmapPaint, selectedFrame) } finally { bitmapPaint.xfermode = null }
                    } else if (dialog.choiceCursorPath == null) {
                        uiPaint.color = Color.argb(180, 58, 88, 144); canvas.drawRoundRect(bounds, 3f, 3f, uiPaint)
                    }
                }
                drawText(canvas, dialog.choices[index], boxLeft + padX, bounds.top + padY, font, textColor, boxWidth - padX * 2,
                    rubySize = dialog.rubySize, shadowOffset = dialog.shadowOffset, convertSpecial = dialog.convertSpecial)
                bounds
            }
            actual = true
        }
        return actual
    }

    private fun verticalFrame(bitmap: Bitmap, frames: Int, frame: Int, path: String): Rect {
        require(frames in 1..1024 && bitmap.height % frames == 0 && frame in 0 until frames) {
            "Atlas vertical Wolf « $path » : frame $frame/$frames incompatible avec ${bitmap.width} × ${bitmap.height}."
        }
        val height = bitmap.height / frames
        return Rect(0, frame * height, bitmap.width, (frame + 1) * height)
    }

    private data class TextKey(val text: String, val size: Float, val color: Int, val width: Int, val revision: Long,
        val rubySize: Float, val shadowOffset: Int, val fontWeight: Int, val intrinsic: Boolean, val fonts: Map<Int, String>,
        val convertSpecial: Boolean)
    private inline fun <T> withFontOverrides(fonts: Map<Int, String>, block: () -> T): T {
        val previous = renderingFontOverrides
        return try { renderingFontOverrides = fonts; block() } finally { renderingFontOverrides = previous }
    }
    private fun pictureTextLayout(picture: NativePicture) = withFontOverrides(picture.fontOverrides) {
        textLayout(picture.text.orEmpty(), picture.fontSize, picture.fontColor,
            if (picture.width > 0) picture.width else 8192f, picture.rubySize, picture.shadowOffset,
            intrinsic = picture.width <= 0, convertSpecial = picture.textTiming?.convertSpecial != false)
    }
    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float, visibleCharacters: Int = Int.MAX_VALUE,
        rubySize: Float = size / 2, shadowOffset: Int = 0, convertSpecial: Boolean = true) {
        if (text.isEmpty() || maxWidth <= 0 || !x.isFinite() || !y.isFinite()) return
        val layout = textLayout(text, size, color, maxWidth, rubySize, shadowOffset, convertSpecial = convertSpecial)
        val saved = canvas.save(); canvas.translate(x, y); layout.draw(canvas, visibleCharacters); canvas.restoreToCount(saved)
    }
    private fun textLayout(text: String, size: Float, color: Int, maxWidth: Float, rubySize: Float = size / 2, shadowOffset: Int = 0,
        intrinsic: Boolean = false, convertSpecial: Boolean = true): WolfTextLayout {
        val width = maxWidth.toInt().coerceIn(1, 8192)
        val key = TextKey(text, size, color, width, resources.revision, rubySize, shadowOffset, renderingFontWeight,
            intrinsic, renderingFontOverrides, convertSpecial)
        val layout = textLayouts[key] ?: run {
            val result = buildTextLayout(text, size, color, width, rubySize, shadowOffset, intrinsic, convertSpecial)
            textLayouts[key] = result
            textLayoutBytes += result.estimatedBytes
            while ((textLayouts.size > 128 || textLayoutBytes > TEXT_BUDGET) && textLayouts.size > 1) {
                textLayouts.entries.iterator().run { textLayoutBytes -= next().value.estimatedBytes; remove() }
            }
            result
        }
        return layout
    }

    private data class TextStyle(val size: Float, val color: Int, val font: Int = 0, val outline: Boolean = false,
        val edgeWidth: Float = 1f, val edgeColor: Int = Color.BLACK, val shadow: Int? = null, val condense: Float = 0f,
        val antialias: Boolean = true, val rubySize: Float = size / 2, val rubyColor: Int = Color.WHITE, val shadowOffset: Float = 0f)

    private class TextCell(val text: String?, val style: TextStyle, val paint: TextPaint,
        var x: Float, var y: Float, val width: Float, val advance: Float, val ascent: Float, val descent: Float,
        val revealAt: Int, val characters: Int = 1, val image: ImageReference? = null, val crisp: Boolean = false,
        val imageHeight: Float = 0f, val ruby: String? = null)

    private inner class WolfTextLayout(val cells: List<TextCell>, private val lineWidths: List<Float>, val height: Int) {
        val lineCount get() = lineWidths.size
        val estimatedBytes = cells.size * 100L + cells.sumOf { (it.text?.length ?: 0) * 2L }
        fun getLineWidth(index: Int) = lineWidths[index]

        fun draw(canvas: Canvas, visible: Int) {
            var index = 0
            while (index < cells.size) {
                val cell = cells[index]
                if (cell.revealAt >= visible) break
                if (cell.image != null) {
                    val bitmap = resources.peek(cell.image.path)
                    if (bitmap != null) {
                        val rect = inlineSource(bitmap, cell.image, renderingTick)
                        val paint = Paint().apply { isFilterBitmap = config.smooth && !cell.crisp }
                        canvas.drawBitmap(bitmap, rect, RectF(cell.x, cell.y - cell.imageHeight, cell.x + cell.width, cell.y), paint)
                    }
                    index++; continue
                }
                if (cell.ruby != null) {
                    val count = (visible - cell.revealAt).coerceIn(0, cell.characters)
                    val word = cell.text.orEmpty()
                    val part = word.substring(0, word.offsetByCodePoints(0, count))
                    val small = TextPaint(cell.paint).apply { textSize = cell.style.rubySize; color = cell.style.rubyColor }
                    drawGlyph(canvas, part, cell.x + (cell.width - cell.paint.measureText(word)) / 2, cell.y, cell.paint, cell.style)
                    if (count == cell.characters) drawGlyph(canvas, cell.ruby, cell.x + (cell.width - small.measureText(cell.ruby)) / 2,
                        cell.y - cell.style.size, small, cell.style.copy(color = cell.style.rubyColor))
                    index++; continue
                }
                // Draw contiguous runs together, preserving Android's glyph shaping and kerning.
                val content = StringBuilder(cell.text.orEmpty())
                var next = index + 1
                var right = cell.x + cell.advance
                while (next < cells.size) {
                    val other = cells[next]
                    if (other.revealAt >= visible || other.style != cell.style || other.image != null || other.ruby != null ||
                        other.y != cell.y || abs(other.x - right) > .001f) break
                    content.append(other.text)
                    right += other.advance; next++
                }
                drawGlyph(canvas, content.toString(), cell.x, cell.y, cell.paint, cell.style)
                index = next
            }
        }
    }

    private fun drawGlyph(canvas: Canvas, text: String, x: Float, y: Float, paint: TextPaint, style: TextStyle) {
        paint.style = Paint.Style.FILL
        if (style.shadowOffset > 0) style.shadow?.let { paint.color = it; canvas.drawText(text, x + style.shadowOffset, y + style.shadowOffset, paint) }
        if (style.outline) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = style.edgeWidth * 2f; paint.strokeJoin = Paint.Join.ROUND
            paint.color = style.edgeColor; canvas.drawText(text, x, y, paint)
        }
        paint.style = Paint.Style.FILL; paint.color = style.color
        canvas.drawText(text, x, y, paint)
    }

    private fun weightedTypeface(base: Typeface): Typeface {
        val weight = renderingFontWeight * 100
        val key = FontKey(base, weight)
        return weightedFonts[key] ?: run {
            // DxLib passes its thickness * 100 to the platform font weight.
            // Android also selects the nearest weight present in this family.
            val font = if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, base.isItalic)
            else {
                if (fontNotices.add(weight)) callbacks.notice(
                    "Police Wolf : poids $weight rendu avec le style de famille le plus proche sur Android API ${Build.VERSION.SDK_INT} ; la sélection de poids précis nécessite API28.")
                val style = when {
                    weight >= 700 && base.isItalic -> Typeface.BOLD_ITALIC
                    weight >= 700 -> Typeface.BOLD
                    base.isItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                Typeface.create(base, style)
            }
            weightedFonts[key] = font
            while (weightedFonts.size > 256) weightedFonts.entries.iterator().run { next(); remove() }
            font
        }
    }

    private fun selectedPattern(reference: ImageReference, picture: NativePicture) =
        if (reference.columns != 1 || reference.rows != 1) reference.pattern else picture.pattern

    private fun buildTextLayout(input: String, defaultSize: Float, defaultColor: Int, maxWidth: Int, rubySize: Float, shadowOffset: Int,
        intrinsic: Boolean, convertSpecial: Boolean): WolfTextLayout {
        require(input.length <= 100_000) { "Texte Wolf dépassant 100 000 caractères." }
        val cells = ArrayList<TextCell>()
        val lineWidths = ArrayList<Float>()
        val lineRanges = ArrayList<Pair<IntRange, Int>>()
        val initialSize = defaultSize.coerceIn(4f, 512f)
        var style = TextStyle(initialSize, if (defaultColor == -1) config.textColors[0] ?: Color.WHITE else defaultColor,
            rubySize = rubySize.coerceIn(2f, 512f), rubyColor = config.textColors[13] ?: Color.WHITE,
            shadow = if (shadowOffset > 0) Color.BLACK else null, shadowOffset = shadowOffset.coerceIn(0, 512).toFloat())
        fun textPaint(): TextPaint = TextPaint().apply {
            textSize = style.size; color = style.color; isAntiAlias = style.antialias
            val replacement = renderingFontOverrides[style.font]?.takeIf { style.font in 0..3 }?.let(resources::namedTypeface)
            typeface = weightedTypeface(replacement ?: config.fontPaths[style.font]?.let(resources::typeface) ?: config.typeface)
            letterSpacing = -style.condense / style.size
        }
        var paint = textPaint()
        var x = 0f; var y = 0f; var nextLineX = 0f
        var lineStart = 0; var maximumSize = 0f; var lineGap = 0f; var explicitMaximum: Float? = null
        var lineAscent = 0f; var lineDescent = 0f
        var alignment = 0; var lineAlignment = 0; var reveal = 0; var imageScale = 1f
        var bottom = 0f
        fun finishLine(newline: Boolean) {
            // A trailing \ax reserves horizontal space even without a glyph.
            // Official HP/SP pictures reserve 50px this way, then place a
            // right-aligned second line back at \ay[0]. Align only once the
            // intrinsic width of every line is known.
            val width = max(x, cells.subList(lineStart, cells.size).maxOfOrNull { it.x + it.width } ?: 0f).coerceAtLeast(0f)
            val empty = cells.size == lineStart
            val ascent = max(if (empty) -paint.fontMetrics.ascent else lineAscent,
                explicitMaximum ?: if (empty) style.size else maximumSize)
            val descent = if (empty) paint.fontMetrics.descent else lineDescent
            for (i in lineStart until cells.size) {
                cells[i].y += ascent
                bottom = max(bottom, cells[i].y + cells[i].descent)
            }
            lineRanges += (lineStart until cells.size) to lineAlignment
            lineWidths += width
            bottom = max(bottom, y + ascent + descent)
            if (newline) {
                y += ascent + descent + lineGap
                x = nextLineX; lineStart = cells.size; lineAlignment = alignment
                maximumSize = 0f; explicitMaximum = null
                lineAscent = 0f; lineDescent = 0f
            }
        }
        fun add(text: String?, image: ImageReference? = null, crisp: Boolean = false, ruby: String? = null) {
            val bitmap = image?.let { resources.peek(it.path) }
            val rectangle = if (bitmap == null || image == null) null else inlineSource(bitmap, image, renderingTick)
            val imageHeight = style.size * imageScale
            val naturalWidth = when {
                image != null -> if (resources.isOptionalMissing(image.path)) 0f else imageHeight * (rectangle?.width() ?: 1) / (rectangle?.height() ?: 1)
                ruby != null -> max(paint.measureText(text.orEmpty()), TextPaint(paint).apply { textSize = style.rubySize }.measureText(ruby))
                else -> paint.measureText(text.orEmpty())
            }
            val count = text?.codePointCount(0, text.length) ?: 1
            val advance = if (image != null || ruby != null) naturalWidth else naturalWidth - style.condense
            if (cells.size > lineStart && x + naturalWidth > maxWidth && x >= 0) finishLine(true)
            val ascent = when { image != null -> imageHeight; ruby != null -> -paint.fontMetrics.ascent + style.rubySize; else -> -paint.fontMetrics.ascent }
            val descent = if (image != null) 0f else paint.fontMetrics.descent
            cells += TextCell(text, style, paint, x, y, naturalWidth, advance, ascent, descent, reveal, count, image, crisp, imageHeight, ruby)
            x += advance; reveal += count
            lineAscent = max(lineAscent, ascent); lineDescent = max(lineDescent, descent); maximumSize = max(maximumSize, style.size)
        }
        fun number(argument: String, token: String) = argument.toFloatOrNull()?.takeIf(Float::isFinite)
            ?: error("Valeur de texte Wolf invalide : \\$token[$argument].")
        fun color(argument: String) = config.textColors[argument.toIntOrNull()]
            ?: error("Couleur de texte Wolf $argument absente du SystemDB type 12.")
        var index = 0
        while (index < input.length) {
            if (input[index] == '\r') { index++; continue }
            if (input[index] == '\n') { finishLine(true); index++; continue }
            if (convertSpecial && (input.startsWith("<L>", index) || input.startsWith("<C>", index) || input.startsWith("<R>", index))) {
                alignment = when (input[index + 1]) { 'C' -> 1; 'R' -> 2; else -> 0 }; lineAlignment = alignment
                index += 3; continue
            }
            if (convertSpecial && input[index] == '\\' && index + 1 < input.length) {
                if (input[index + 1] == '\\') { add("\\"); index += 2; continue }
                val match = TEXT_CONTROL.find(input, index)?.takeIf { it.range.first == index }
                if (match != null) {
                    val token = match.groupValues[1]; val arg = match.groupValues[2]
                    when (token) {
                        "f" -> style = style.copy(size = number(arg, token).coerceIn(4f, 512f))
                        "f+" -> style = style.copy(size = (style.size + number(arg, token)).coerceIn(4f, 512f))
                        "fR" -> style = style.copy(rubySize = number(arg, token).coerceIn(2f, 512f))
                        "m" -> explicitMaximum = number(arg, token).coerceIn(4f, 512f)
                        "c" -> style = style.copy(color = color(arg))
                        "wE" -> style = style.copy(outline = true, edgeWidth = number(arg, token).coerceIn(1f, 5f))
                        "cE" -> style = style.copy(edgeColor = color(arg))
                        "cS" -> style = style.copy(shadow = color(arg))
                        "cR" -> style = style.copy(rubyColor = color(arg))
                        "-" -> style = style.copy(condense = number(arg, token).coerceIn(-8192f, 8192f))
                        "font" -> {
                            val id = arg.toIntOrNull() ?: error("Numéro de sous-police Wolf invalide : $arg.")
                            require(id in config.availableFontIds || id in config.fontPaths ||
                                id in 0..3 && renderingFontOverrides[id]?.let(WolfNativeResources::fontOverrideReference) != null) { "Sous-police Wolf $id non chargée." }; style = style.copy(font = id)
                        }
                        "mx" -> x += number(arg, token)
                        "my" -> y += number(arg, token)
                        "bx" -> nextLineX = number(arg, token)
                        "ax" -> x = number(arg, token)
                        "ay" -> y = number(arg, token)
                        "space" -> lineGap = number(arg, token).coerceIn(-512f, 8192f)
                        "isize" -> imageScale = (number(arg, token) / 100f).coerceIn(.01f, 10f)
                        "i", "iS", "img", "imgS" -> {
                            val path = if (token == "i" || token == "iS") {
                                val id = arg.toIntOrNull() ?: error("Icône Wolf invalide : $arg."); require(id in 0..9999) { "Icône Wolf hors plage : $id." }
                                "BasicData/icon${id.toString().padStart(3, '0')}.png"
                            } else arg
                            add(null, imageReference(path), token.endsWith('S'))
                        }
                        "r" -> {
                            val comma = arg.indexOf(','); require(comma >= 0) { "Ruby Wolf invalide : $arg." }
                            add(arg.substring(0, comma), ruby = arg.substring(comma + 1))
                        }
                        "sp", "se" -> Unit // VM owns delays and exactly-once audio at the reveal cursor.
                        else -> error("Code de texte Wolf non porté : \\$token[$arg].")
                    }
                    paint = textPaint(); index = match.range.last + 1; continue
                }
                val consumed = when {
                    input.startsWith("\\E", index) -> { style = style.copy(outline = true); 2 }
                    input.startsWith("\\N", index) -> { style = style.copy(outline = false); 2 }
                    input.startsWith("\\A+", index) -> { style = style.copy(antialias = true); 3 }
                    input.startsWith("\\A-", index) -> { style = style.copy(antialias = false); 3 }
                    input[index + 1] in charArrayOf('>', '<', '^', '!', '.') -> 2
                    else -> 0
                }
                if (consumed > 0) { paint = textPaint(); index += consumed; continue }
            }
            val next = index + Character.charCount(input.codePointAt(index))
            add(input.substring(index, next)); index = next
        }
        finishLine(false)
        val alignmentWidth = if (intrinsic) lineWidths.maxOrNull() ?: 0f else maxWidth.toFloat()
        lineRanges.forEachIndexed { line, (range, align) ->
            val shift = when (align) { 1 -> (alignmentWidth - lineWidths[line]) / 2; 2 -> alignmentWidth - lineWidths[line]; else -> 0f }
            range.forEach { cells[it].x += shift }
        }
        require(bottom.isFinite() && bottom <= 200_000) { "Hauteur de texte Wolf invalide." }
        return WolfTextLayout(cells, lineWidths, ceil(bottom).toInt().coerceAtLeast(1))
    }

    private fun inlineSource(bitmap: Bitmap, reference: ImageReference, tick: Long): Rect {
        val rectangle = Rect()
        pictureSource(bitmap, reference, NativePicture(0), tick, rectangle)
        return rectangle
    }

    private data class Viewport(val left: Float, val top: Float, val scaleX: Float, val scaleY: Float,
        val logicalWidth: Int, val logicalHeight: Int) {
        fun point(x: Float, y: Float): Pair<Float, Float>? {
            if (!x.isFinite() || !y.isFinite() || scaleX <= 0 || scaleY <= 0) return null
            val lx = (x - left) / scaleX; val ly = (y - top) / scaleY
            return if (lx >= 0 && ly >= 0 && lx < logicalWidth && ly < logicalHeight) lx to ly else null
        }
    }
    private fun viewport(scene: NativeFrame): Viewport {
        val sx = width.toFloat() / scene.logicalWidth; val sy = height.toFloat() / scene.logicalHeight
        val base = if (config.mode == DisplayMode.FILL) max(sx, sy) else min(sx, sy)
        val scaleX = (if (config.mode == DisplayMode.STRETCH) sx else base) * zoom
        val scaleY = (if (config.mode == DisplayMode.STRETCH) sy else base) * zoom
        return Viewport((width - scene.logicalWidth * scaleX) / 2f + panX,
            (height - scene.logicalHeight * scaleY) / 2f + panY, scaleX, scaleY, scene.logicalWidth, scene.logicalHeight)
    }
    private fun clampPan() {
        val scene = frame ?: return
        val viewport = viewport(scene)
        val allowanceX = max(0f, (scene.logicalWidth * viewport.scaleX - width) / 2f)
        val allowanceY = max(0f, (scene.logicalHeight * viewport.scaleY - height) / 2f)
        panX = panX.coerceIn(-allowanceX, allowanceX); panY = panY.coerceIn(-allowanceY, allowanceY)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { releaseInput(); clampPan() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scene = frame ?: return true
        if (closed || !firstImage) return true
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { releaseInput(); return true }
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTouch(); requestFocus()
                val point = viewport(scene).point(event.x, event.y) ?: return true
                pointerId = event.getPointerId(0); startedAt = event.eventTime
                pointerOriginX = event.x; pointerOriginY = event.y
                pointerLastX = point.first; pointerLastY = point.second
                if (config.directionalTouch) setDirections(point, scene)
                else main.postDelayed(pointerHold, 150)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val active = pointerId >= 0
                releasePointer(); multi = true
                if (event.pointerCount == 2 && active) {
                    fingerIds = intArrayOf(event.getPointerId(0), event.getPointerId(1))
                    fingerOrigins = floatArrayOf(event.getX(0), event.getY(0), event.getX(1), event.getY(1))
                    twoFingerCandidate = (0..1).all { viewport(scene).point(event.getX(it), event.getY(it)) != null }
                } else twoFingerCandidate = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (multi) {
                    checkFingers(event)
                    if (config.pinchZoom && !twoFingerCandidate && !scaleDetector.isInProgress && fingerIds.size == 2) {
                        val first = event.findPointerIndex(fingerIds[0]); val second = event.findPointerIndex(fingerIds[1])
                        if (first >= 0 && second >= 0) {
                            val x = (event.getX(first) + event.getX(second)) / 2
                            val y = (event.getY(first) + event.getY(second)) / 2
                            panX += x - (fingerOrigins[0] + fingerOrigins[2]) / 2
                            panY += y - (fingerOrigins[1] + fingerOrigins[3]) / 2
                            fingerOrigins = floatArrayOf(event.getX(first), event.getY(first), event.getX(second), event.getY(second))
                            clampPan(); invalidate()
                        }
                    }
                } else {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) viewport(scene).point(event.getX(index), event.getY(index))?.let { point ->
                        pointerLastX = point.first; pointerLastY = point.second
                        if (config.directionalTouch) setDirections(point, scene)
                        else if (pointerDown) callbacks.pointer(point.first, point.second, true)
                        else if (hypot(event.getX(index) - pointerOriginX, event.getY(index) - pointerOriginY) > slop) {
                            main.removeCallbacks(pointerHold); pointerDown = true
                            callbacks.pointer(point.first, point.second, true)
                        }
                    } ?: releasePointer()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> checkFingers(event)
            MotionEvent.ACTION_UP -> {
                if (multi) {
                    checkFingers(event)
                    val back = twoFingerCandidate && event.eventTime - startedAt in 0..350
                    cancelTouch()
                    if (back) {
                        val key = config.backKey
                        val held = key in physicalKeys.values || key in directions || key in pulseKeys
                        pulseKeys += key
                        if (!held) callbacks.key(key, true)
                        main.postDelayed({
                            if (pulseKeys.remove(key) && key !in physicalKeys.values && key !in directions)
                                callbacks.key(key, false)
                        }, 70)
                    }
                } else {
                    val point = viewport(scene).point(event.x, event.y)
                    val tap = hypot(event.x - pointerOriginX, event.y - pointerOriginY) <= slop
                    val choice = if (point != null && tap) choiceRects.indexOfFirst { it.contains(point.first, point.second) } else -1
                    if (point != null && tap && choice < 0 && !config.directionalTouch && !pointerDown) {
                        pointerLastX = point.first; pointerLastY = point.second
                        pointerDown = true; callbacks.pointer(point.first, point.second, true)
                    }
                    releasePointer()
                    if (point != null && tap) {
                        if (choice >= 0) callbacks.choose(choiceIndexes[choice])
                        performClick()
                    }
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun setDirections(point: Pair<Float, Float>, scene: NativeFrame) {
        val dx = (point.first - scene.logicalWidth / 2f) / (scene.logicalWidth / 2f)
        val dy = (point.second - scene.logicalHeight / 2f) / (scene.logicalHeight / 2f)
        val next = if (max(abs(dx), abs(dy)) < .12f) emptySet() else setOf(
            if (abs(dx) > abs(dy)) { if (dx < 0) NativeKey.LEFT else NativeKey.RIGHT }
            else if (dy < 0) NativeKey.UP else NativeKey.DOWN)
        (directions - next).filterNot { it in physicalKeys.values || it in pulseKeys }.forEach { callbacks.key(it, false) }
        (next - directions).filterNot { it in physicalKeys.values || it in pulseKeys }.forEach { callbacks.key(it, true) }
        directions = next
    }
    private fun checkFingers(event: MotionEvent) {
        fingerIds.indices.forEach { i ->
            val index = event.findPointerIndex(fingerIds[i])
            if (index >= 0 && hypot(event.getX(index) - fingerOrigins[i * 2], event.getY(index) - fingerOrigins[i * 2 + 1]) > slop)
                twoFingerCandidate = false
        }
    }
    private fun releasePointer() {
        main.removeCallbacks(pointerHold)
        if (pointerDown) callbacks.pointer(pointerLastX, pointerLastY, false)
        pointerDown = false; pointerId = -1
        directions.filterNot { it in physicalKeys.values || it in pulseKeys }.forEach { callbacks.key(it, false) }; directions = emptySet()
    }
    private fun cancelTouch() { releasePointer(); multi = false; twoFingerCandidate = false; fingerIds = intArrayOf() }
    fun releaseInput() {
        cancelTouch()
        physicalKeys.values.toSet().forEach { callbacks.key(it, false) }; physicalKeys.clear()
        pulseKeys.forEach { callbacks.key(it, false) }; pulseKeys.clear()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0) nativeKey(keyCode)?.let {
            val alreadyHeld = it in physicalKeys.values || it in directions || it in pulseKeys
            physicalKeys[keyCode] = it
            if (!alreadyHeld) callbacks.key(it, true)
            return true
        }
        return nativeKey(keyCode) != null || super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        nativeKey(keyCode)?.let {
            physicalKeys.remove(keyCode)
            if (it !in physicalKeys.values && it !in directions && it !in pulseKeys) callbacks.key(it, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
    private fun nativeKey(code: Int): NativeKey? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> NativeKey.UP; KeyEvent.KEYCODE_DPAD_DOWN -> NativeKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NativeKey.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> NativeKey.RIGHT
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> NativeKey.ACCEPT
        KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> NativeKey.BACK
        KeyEvent.KEYCODE_SPACE -> NativeKey.SPACE; KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> NativeKey.SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> NativeKey.CONTROL
        KeyEvent.KEYCODE_Z -> NativeKey.Z; KeyEvent.KEYCODE_X -> NativeKey.X; KeyEvent.KEYCODE_C -> NativeKey.C
        KeyEvent.KEYCODE_A -> NativeKey.A; KeyEvent.KEYCODE_S -> NativeKey.S; KeyEvent.KEYCODE_D -> NativeKey.D; KeyEvent.KEYCODE_W -> NativeKey.W
        KeyEvent.KEYCODE_F1 -> NativeKey.F1; KeyEvent.KEYCODE_F5 -> NativeKey.F5; KeyEvent.KEYCODE_F12 -> NativeKey.F12
        else -> null
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (!hasWindowFocus) releaseInput() }
    override fun onDetachedFromWindow() { releaseInput(); super.onDetachedFromWindow() }

    private fun recordMetrics(start: Long) {
        val now = SystemClock.uptimeMillis()
        if (metricTime == 0L) metricTime = now
        metricFrames++; metricDrawNanos += System.nanoTime() - start
        val duration = now - metricTime
        if (duration >= 500) {
            measuredFps = metricFrames * 1000.0 / duration
            measuredDrawMs = metricDrawNanos / metricFrames / 1_000_000.0
            callbacks.renderMetrics(measuredFps, measuredDrawMs)
            metricTime = now; metricFrames = 0; metricDrawNanos = 0
        }
    }
    private fun report(reason: String) {
        if (reportedError == reason || closed) return
        reportedError = reason
        callbacks.error("Rendu Wolf natif : $reason")
    }
    override fun close() {
        if (closed) return
        releaseInput(); closed = true
        resources.videos.onState = { _, _ -> }
        resources.videos.retain(emptySet())
        chunkWorkers.shutdownNow(); main.removeCallbacksAndMessages(null)
        synchronized(chunks) { chunks.clear(); chunkBytes = 0 }
        pendingChunks.clear(); textLayouts.clear(); textLayoutBytes = 0; weightedFonts.clear(); fontNotices.clear()
        shadowNotices.clear()
        synchronized(visibilityTextures) { visibilityTextures.clear() }; pendingVisibility.clear(); requiredVisibility = emptySet()
        softwareScene = null; softwareLayers.clear(); logicalViewport = null; transitionMasks.clear(); frame = null
    }

    companion object {
        private const val CHUNK_SIZE = 8
        private const val CHUNK_BUDGET = 32L * 1024 * 1024
        private const val TEXT_BUDGET = 16L * 1024 * 1024
        private val ADD = PorterDuffXfermode(PorterDuff.Mode.ADD)
        private val TEXT_CONTROL = Regex("\\\\([A-Za-z]+\\+?|-)\\[([^]]*)]")
        private val ANIMATION_INTERVAL = Regex("_FRAME=(\\d+)(?=\\.[^.]+$)", RegexOption.IGNORE_CASE)
        private fun animationInterval(path: String): Int = ANIMATION_INTERVAL.find(path)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100_000) ?: 20
    }
}
