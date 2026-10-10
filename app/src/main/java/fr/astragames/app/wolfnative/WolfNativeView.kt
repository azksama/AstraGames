package fr.astragames.app.wolfnative

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
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
import fr.astragames.wolf.NativePicture
import fr.astragames.wolf.WolfTileset
import java.io.Closeable
import java.util.LinkedHashMap
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
    )
    data class Callbacks(
        val key: (NativeKey, Boolean) -> Unit,
        val pointer: (Float, Float, Boolean) -> Unit,
        val choose: (Int) -> Unit = {},
        val firstFrame: () -> Unit = {},
        val error: (String) -> Unit,
        val renderMetrics: (Double, Double) -> Unit = { _, _ -> },
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
    private var requiredImages = emptySet<String>()
    private val main = Handler(Looper.getMainLooper())
    private val chunkWorkers = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(48)) { task ->
        Thread(task, "Wolf-native-map").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val chunks = LinkedHashMap<ChunkKey, Chunk>(64, .75f, true)
    private val pendingChunks = ConcurrentHashMap.newKeySet<ChunkKey>()
    private var chunkBytes = 0L
    private val textLayouts = LinkedHashMap<TextKey, StaticLayout>(64, .75f, true)
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

    init { setBackgroundColor(Color.BLACK); isFocusableInTouchMode = true; contentDescription = "Jeu Wolf RPG natif" }

    fun configure(value: Config) {
        config = value
        bitmapPaint.isFilterBitmap = value.smooth
        if (!value.pinchZoom) { zoom = 1f; panX = 0f; panY = 0f }
        textLayouts.clear()
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

    private fun prepareResources(scene: NativeFrame) {
        val visible = mutableSetOf<String>()
        val mandatory = mutableSetOf<String>()
        val optionalIcons = mutableSetOf<String>()
        fun image(path: String) {
            val logical = WolfNativeResources.logicalPath(path)
            visible += logical; mandatory += logical
        }
        fun textResources(text: String) {
            TEXT_CONTROL.findAll(text).forEach { match ->
                when (match.groupValues[1]) {
                    "i", "iS" -> match.groupValues[2].toIntOrNull()?.takeIf { it in 0..9999 }?.let {
                        val path = WolfNativeResources.logicalPath("BasicData/icon${it.toString().padStart(3, '0')}.png")
                        visible += path; optionalIcons += path
                    }
                    "img", "imgS" -> match.groupValues[2].takeUnless { it.startsWith('<') }?.let(::image)
                }
            }
        }
        scene.map?.let { map ->
            val size = map.tileSize.coerceAtLeast(1)
            // Cache aligned regions just outside the viewport too: scrolling must
            // not repeatedly decode the same edge tiles or rebuild half a chunk.
            val left = (floor(map.cameraX / size).toInt().coerceAtLeast(0) / CHUNK_SIZE) * CHUNK_SIZE
            val top = (floor(map.cameraY / size).toInt().coerceAtLeast(0) / CHUNK_SIZE) * CHUNK_SIZE
            val right = (ceil((map.cameraX + scene.logicalWidth) / (size * CHUNK_SIZE)).toInt() * CHUNK_SIZE).coerceAtMost(map.map.width)
            val bottom = (ceil((map.cameraY + scene.logicalHeight) / (size * CHUNK_SIZE)).toInt() * CHUNK_SIZE).coerceAtMost(map.map.height)
            for (layer in 0 until map.map.layerCount) for (y in top until bottom) for (x in left until right) {
                tilePath(map.tileset, map.map.tile(x, y, layer))?.let(::image)
            }
            map.characters.filter { character ->
                val margin = max(size * 4f, max(character.width, character.height))
                character.opacity > 0 && character.x + margin >= map.cameraX && character.x - margin <= map.cameraX + scene.logicalWidth &&
                    character.y + margin >= map.cameraY && character.y - margin <= map.cameraY + scene.logicalHeight
            }.forEach { character ->
                if (character.tileId >= 32) tilePath(map.tileset, character.tileId)?.let(::image)
                else if (character.path.isNotBlank()) image(character.path)
            }
        }
        scene.pictures.filter { it.opacity > 0 }.forEach { picture ->
            picture.path?.takeIf(String::isNotBlank)?.let(::image)
            picture.text?.let(::textResources)
        }
        scene.texts.forEach { textResources(it.text) }
        scene.dialog?.let { textResources(it.text); it.choices.forEach(::textResources) }
        resources.pin(visible)
        resources.prefetch(mandatory)
        (optionalIcons - mandatory).forEach { resources.request(it, optional = true) }
        requiredImages = visible
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
            scene.map?.let { map -> actualImage = drawMap(canvas, scene, map) || actualImage }
            scene.pictures.sortedWith(compareBy<NativePicture> { it.z }.thenBy { it.id }).forEach {
                actualImage = drawPicture(canvas, scene, it) || actualImage
            }
            scene.texts.forEach { text ->
                if (text.text.isNotEmpty()) {
                    drawText(canvas, text.text, text.x, text.y, text.size, text.color, scene.logicalWidth - text.x)
                    actualImage = true
                }
            }
            choiceRects = emptyList(); choiceIndexes = emptyList()
            scene.dialog?.let { drawDialog(canvas, scene, it); actualImage = true }
            if (scene.screenOpacity < 255 || scene.screenColor != 0) {
                uiPaint.color = if (scene.screenColor == 0) Color.BLACK else scene.screenColor
                uiPaint.alpha = 255 - scene.screenOpacity.coerceIn(0, 255)
                canvas.drawRect(0f, 0f, scene.logicalWidth.toFloat(), scene.logicalHeight.toFloat(), uiPaint)
                uiPaint.alpha = 255
            }
        } catch (error: Exception) { report(error.message ?: error.javaClass.simpleName) }
        finally { canvas.restoreToCount(saved) }
        if (actualImage && resources.isReady(requiredImages) && scene.screenOpacity > 0 && !firstImage && reportedError == null) {
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

    private fun drawMap(canvas: Canvas, scene: NativeFrame, map: NativeMapScene): Boolean {
        require(map.tileSize in 2..256 && map.tileSize % 2 == 0) { "Taille des tiles Wolf non prise en charge : ${map.tileSize}." }
        val size = map.tileSize
        val left = floor(map.cameraX / size).toInt().coerceAtLeast(0)
        val top = floor(map.cameraY / size).toInt().coerceAtLeast(0)
        val right = ceil((map.cameraX + scene.logicalWidth) / size).toInt().coerceAtMost(map.map.width)
        val bottom = ceil((map.cameraY + scene.logicalHeight) / size).toInt().coerceAtMost(map.map.height)
        var actual = false
        for (priority in 0..2) {
            actual = drawMapPriority(canvas, scene, map, priority, left, top, right, bottom) || actual
            if (priority < 2) map.characters.filter { it.onTop == (priority == 1) }.sortedBy { it.y }.forEach {
                actual = drawCharacter(canvas, map, it) || actual
            }
        }
        return actual
    }

    private data class ChunkKey(val map: Int, val tileset: Int, val tileSize: Int,
        val x: Int, val y: Int, val priority: Int, val content: Int, val textures: Long, val animation: Long)
    private data class Chunk(val bitmap: Bitmap, val actual: Boolean)

    private fun drawMapPriority(canvas: Canvas, scene: NativeFrame, map: NativeMapScene, priority: Int,
        left: Int, top: Int, right: Int, bottom: Int): Boolean {
        var actual = false
        for (cy in top / CHUNK_SIZE..(bottom - 1).coerceAtLeast(top) / CHUNK_SIZE) {
            for (cx in left / CHUNK_SIZE..(right - 1).coerceAtLeast(left) / CHUNK_SIZE) {
                val fromX = cx * CHUNK_SIZE; val fromY = cy * CHUNK_SIZE
                val countX = min(CHUNK_SIZE, map.map.width - fromX); val countY = min(CHUNK_SIZE, map.map.height - fromY)
                if (countX <= 0 || countY <= 0) continue
                val tiles = Array(map.map.layerCount) { layer ->
                    IntArray(countX * countY) { index -> map.map.tile(fromX + index % countX, fromY + index / countX, layer) }
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
                        if (tilePriority(map.tileset, tile) != priority) continue
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
                        if (tilePriority(map.tileset, tile) == key.priority) actual = drawTile(canvas, map.tileset, tile, map.tileSize,
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
        if (raw <= 0) return false
        val path = tilePath(tileset, raw) ?: return false
        val bitmap = resources.peek(path) ?: return false
        val source = if (localRects) Rect() else sourceRect
        val destination = if (localRects) RectF() else destinationRect
        if (raw >= 100_000) {
            require(bitmap.height == size * 5 && bitmap.width % size == 0) { "Autotile Wolf « $path » : taille ${bitmap.width} × ${bitmap.height}, attendue ${size}n × ${size * 5}." }
            val half = size / 2
            val animation = (tick / animationInterval(path) % (bitmap.width / size)).toInt()
            val digits = raw % 10_000
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
            require(raw >= 32) { "Numéro de tile Wolf non pris en charge : $raw." }
            require(bitmap.width == size * 8 && bitmap.height % size == 0) { "Tileset Wolf « $path » : dimensions ${bitmap.width} × ${bitmap.height} incompatibles avec les tiles de $size px." }
            val index = raw - 32
            val sx = index % 8 * size; val sy = index / 8 * size
            require(sy + size <= bitmap.height) { "Tile Wolf $raw hors de l’atlas « $path »." }
            source.set(sx, sy, sx + size, sy + size); destination.set(x, y, x + size, y + size)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }
        return true
    }

    private fun tilePath(tileset: WolfTileset, raw: Int): String? {
        if (raw <= 0) return null
        return if (raw >= 100_000) {
            val id = raw / 100_000
            require(id in 1..tileset.autoImages.size) { "Autotile Wolf $id absent du tileset ${tileset.id}." }
            tileset.autoImages[id - 1].takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Image de l’autotile Wolf $id manquante dans le tileset ${tileset.id}.")
        } else tileset.baseImage.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Image de base manquante dans le tileset Wolf ${tileset.id}.")
    }

    private fun tilePriority(tileset: WolfTileset, raw: Int): Int {
        if (raw <= 0) return 0
        val index = if (raw >= 100_000) raw / 100_000 else raw - 32 + tileset.autoImages.size + 1
        val flags = tileset.passage.getOrNull(index) ?: throw IllegalArgumentException("Attributs du tile Wolf $raw absents du tileset ${tileset.id}.")
        return if (flags and 0x10 != 0) 2 else if (flags and 0x100 != 0) 1 else 0
    }

    private fun drawCharacter(canvas: Canvas, map: NativeMapScene, character: NativeCharacter): Boolean {
        if (character.opacity <= 0) return false
        if (character.tileId >= 32) {
            bitmapPaint.alpha = character.opacity.coerceIn(0, 255)
            return drawTile(canvas, map.tileset, character.tileId, map.tileSize,
                character.x - map.cameraX - map.tileSize / 2f, character.y - map.cameraY - map.tileSize,
                frame?.tick ?: 0, bitmapPaint).also { bitmapPaint.alpha = 255 }
        }
        if (character.path.isBlank()) return false
        val bitmap = resources.peek(character.path) ?: return false
        val name = character.path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').uppercase()
        val full = name.endsWith('$')
        val patterns = map.animationPatterns.coerceIn(1, 5)
        val extra = if (name.endsWith("TX")) patterns else if (name.endsWith('T')) 1 else 0
        val directions = if (map.characterDirections == 8) 2 else 1
        val columns = (patterns + extra) * directions
        val cellWidth = if (full) bitmap.width else bitmap.width / columns
        val cellHeight = if (full) bitmap.height else bitmap.height / 4
        require(full || bitmap.width % columns == 0 && bitmap.height % 4 == 0 && cellWidth > 0) {
            "CharaChip Wolf « ${character.path} » : grille $columns × 4 incompatible avec ${bitmap.width} × ${bitmap.height}."
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
        sourceRect.set(sx, sy, sx + cellWidth, sy + cellHeight)
        val drawWidth = if (character.width > 0) character.width else cellWidth.toFloat()
        val drawHeight = if (character.height > 0) character.height else cellHeight.toFloat()
        val x = character.x - map.cameraX; val y = character.y - map.cameraY
        destinationRect.set(x - drawWidth / 2, y - drawHeight, x + drawWidth / 2, y)
        bitmapPaint.alpha = character.opacity.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint)
        bitmapPaint.alpha = 255
        return true
    }

    private fun drawPicture(canvas: Canvas, scene: NativeFrame, picture: NativePicture): Boolean {
        if (picture.opacity <= 0 || picture.scaleX == 0f || picture.scaleY == 0f) return false
        require(picture.blend in 0..2) { "Picture Wolf ${picture.id} : mélange ${picture.blend} non pris en charge par le rendu natif." }
        require(picture.anchor in 0..1) { "Ancrage de picture Wolf ${picture.id} non pris en charge : ${picture.anchor}." }
        val bitmap = picture.path?.takeIf(String::isNotBlank)?.let(resources::peek)
        if (picture.path?.isNotBlank() == true && bitmap == null) return false
        val text = picture.text
        val width = if (picture.width > 0) picture.width else picture.sourceRect?.width ?: bitmap?.width?.toFloat() ?: scene.logicalWidth.toFloat()
        val height = if (picture.height > 0) picture.height else picture.sourceRect?.height ?: bitmap?.height?.toFloat() ?: picture.fontSize * 1.4f
        val x = picture.x - if (!picture.screenRelative) scene.map?.cameraX ?: 0f else 0f
        val y = picture.y - if (!picture.screenRelative) scene.map?.cameraY ?: 0f else 0f
        val saved = canvas.save()
        val solid = bitmap == null && text.isNullOrEmpty() && picture.width > 0 && picture.height > 0
        try {
            canvas.translate(x, y); canvas.rotate(picture.rotation); canvas.scale(picture.scaleX, picture.scaleY)
            if (picture.anchor == 1) canvas.translate(-width / 2, -height / 2)
            if (bitmap != null) {
                val crop = picture.sourceRect
                if (crop == null) sourceRect.set(0, 0, bitmap.width, bitmap.height)
                else {
                    require(crop.x >= 0 && crop.y >= 0 && crop.width > 0 && crop.height > 0 &&
                        crop.x + crop.width <= bitmap.width && crop.y + crop.height <= bitmap.height) {
                        "Découpage hors image pour la picture Wolf ${picture.id} « ${picture.path} »."
                    }
                    sourceRect.set(crop.x.toInt(), crop.y.toInt(), (crop.x + crop.width).toInt(), (crop.y + crop.height).toInt())
                }
                destinationRect.set(0f, 0f, width, height)
                bitmapPaint.alpha = picture.opacity.coerceIn(0, 255)
                bitmapPaint.colorFilter = if (picture.color == -1) null else LightingColorFilter(picture.color, 0)
                bitmapPaint.xfermode = when (picture.blend) { 1 -> MULTIPLY; 2 -> ADD; else -> null }
                canvas.drawBitmap(bitmap, sourceRect, destinationRect, bitmapPaint)
            }
            if (solid) {
                bitmapPaint.color = picture.color
                bitmapPaint.alpha = picture.opacity.coerceIn(0, 255)
                bitmapPaint.xfermode = when (picture.blend) { 1 -> MULTIPLY; 2 -> ADD; else -> null }
                canvas.drawRect(0f, 0f, width, height, bitmapPaint)
            }
            if (!text.isNullOrEmpty()) {
                val layer = if (picture.opacity < 255) canvas.saveLayerAlpha(0f, 0f, width, max(height, scene.logicalHeight.toFloat()), picture.opacity) else -1
                drawText(canvas, text, 0f, 0f, picture.fontSize, picture.fontColor, width)
                if (layer >= 0) canvas.restoreToCount(layer)
            }
        } finally {
            bitmapPaint.color = Color.WHITE; bitmapPaint.alpha = 255; bitmapPaint.colorFilter = null; bitmapPaint.xfermode = null
            canvas.restoreToCount(saved)
        }
        return bitmap != null || !text.isNullOrEmpty() || solid
    }

    private fun drawDialog(canvas: Canvas, scene: NativeFrame, dialog: NativeDialog) {
        val margin = max(6f, scene.logicalWidth / 80f)
        val font = dialog.fontSize.coerceIn(4f, 512f)
        var top = scene.logicalHeight.toFloat()
        if (dialog.text.isNotEmpty()) {
            val text = textLayout(dialog.text, font, Color.WHITE, scene.logicalWidth - margin * 4)
            require(text.height + margin * 2 <= scene.logicalHeight - margin * 2) { "Le message Wolf dépasse la hauteur disponible ; pagination de texte non encore portée." }
            val textHeight = max(scene.logicalHeight * .30f, text.height + margin * 2)
            top = scene.logicalHeight - textHeight - margin
            uiPaint.color = Color.argb(235, 16, 20, 32)
            canvas.drawRoundRect(margin, top, scene.logicalWidth - margin, scene.logicalHeight - margin, 4f, 4f, uiPaint)
            uiPaint.style = Paint.Style.STROKE; uiPaint.strokeWidth = 1f; uiPaint.color = Color.argb(210, 200, 208, 228)
            canvas.drawRoundRect(margin, top, scene.logicalWidth - margin, scene.logicalHeight - margin, 4f, 4f, uiPaint)
            uiPaint.style = Paint.Style.FILL
            drawText(canvas, dialog.text, margin * 2, top + margin, font, Color.WHITE, scene.logicalWidth - margin * 4)
        }
        if (dialog.choices.isNotEmpty()) {
            val available = (top - margin * 2).coerceAtLeast(1f)
            var boxWidth = max(scene.logicalWidth * .55f, margin * 2 + 1f)
            fun heights() = dialog.choices.map { max(font * 1.6f, textLayout(it, font, Color.WHITE, boxWidth - margin * 2).height + font * .3f) }
            var rows = heights()
            // Prefer wider wrapped labels to overlapping fixed-height rows. A tall list follows
            // the selected row; hit testing retains the original choice indices.
            if (rows.sum() > available) { boxWidth = scene.logicalWidth - margin * 2; rows = heights() }
            require(rows.all { it <= available }) { "Un choix Wolf dépasse la hauteur disponible ; libellé non affichable dans cette fenêtre." }
            val selected = dialog.selectedChoice.coerceIn(dialog.choices.indices)
            var start = 0
            var selectedHeight = rows.take(selected + 1).sum()
            while (selectedHeight > available && start < selected) selectedHeight -= rows[start++]
            var end = start; var totalHeight = 0f
            while (end < rows.size && totalHeight + rows[end] <= available) totalHeight += rows[end++]
            val boxLeft = scene.logicalWidth - boxWidth - margin
            val boxTop = max(margin, top - totalHeight - margin)
            uiPaint.color = Color.argb(245, 20, 26, 42)
            canvas.drawRoundRect(boxLeft, boxTop, boxLeft + boxWidth, boxTop + totalHeight, 4f, 4f, uiPaint)
            var rowTop = boxTop
            choiceIndexes = (start until end).toList()
            choiceRects = choiceIndexes.map { index ->
                val bounds = RectF(boxLeft, rowTop, boxLeft + boxWidth, rowTop + rows[index]); rowTop = bounds.bottom
                if (index == selected) {
                    uiPaint.color = Color.argb(180, 58, 88, 144); canvas.drawRoundRect(bounds, 3f, 3f, uiPaint)
                }
                drawText(canvas, dialog.choices[index], boxLeft + margin, bounds.top + font * .15f, font, Color.WHITE, boxWidth - margin * 2)
                bounds
            }
        }
    }

    private data class TextKey(val text: String, val size: Float, val color: Int, val width: Int, val revision: Long)
    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int, maxWidth: Float) {
        if (text.isEmpty() || maxWidth <= 0 || !x.isFinite() || !y.isFinite()) return
        val layout = textLayout(text, size, color, maxWidth)
        val saved = canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restoreToCount(saved)
    }
    private fun textLayout(text: String, size: Float, color: Int, maxWidth: Float): StaticLayout {
        val width = maxWidth.toInt().coerceIn(1, 8192)
        val key = TextKey(text, size, color, width, resources.revision)
        val layout = textLayouts[key] ?: run {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.coerceIn(4f, 512f); this.color = color; typeface = config.typeface }
            val styled = styledText(text, paint.textSize, color)
            val result = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width)
                .setAlignment(if (text.startsWith("<C>")) Layout.Alignment.ALIGN_CENTER else if (text.startsWith("<R>")) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false).setLineSpacing(0f, 1.12f).build()
            textLayouts[key] = result
            while (textLayouts.size > 192) textLayouts.entries.iterator().run { next(); remove() }
            result
        }
        return layout
    }

    private fun styledText(input: String, defaultSize: Float, defaultColor: Int): SpannableStringBuilder {
        require(input.length <= 100_000) { "Texte Wolf dépassant 100 000 caractères." }
        val result = SpannableStringBuilder()
        var size = defaultSize
        var color = defaultColor
        var imageScale = 1f
        var index = 0
        var runStart = 0
        fun finishRun() {
            if (result.length > runStart) {
                if (size != defaultSize) result.setSpan(AbsoluteSizeSpan(size.toInt()), runStart, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (color != defaultColor) result.setSpan(ForegroundColorSpan(color), runStart, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            runStart = result.length
        }
        while (index < input.length) {
            if (input.startsWith("<L>", index) || input.startsWith("<C>", index) || input.startsWith("<R>", index)) { index += 3; continue }
            if (input[index] == '\\' && index + 1 < input.length) {
                if (input[index + 1] == '\\') { result.append('\\'); index += 2; continue }
                val match = TEXT_CONTROL.find(input, index)?.takeIf { it.range.first == index }
                if (match != null) {
                    val token = match.groupValues[1]; val argument = match.groupValues[2]
                    when (token) {
                        "f" -> { finishRun(); size = (argument.toFloatOrNull() ?: error("Taille de police Wolf invalide : $argument.")).coerceIn(4f, 512f) }
                        "f+" -> { finishRun(); size = (size + (argument.toFloatOrNull() ?: error("Variation de police Wolf invalide."))).coerceIn(4f, 512f) }
                        "c" -> { finishRun(); color = config.textColors[argument.toIntOrNull()] ?: error("Couleur de texte Wolf $argument absente du SystemDB type 12.") }
                        "isize" -> imageScale = (argument.toFloatOrNull() ?: 100f).coerceIn(1f, 1000f) / 100f
                        "i", "iS", "img", "imgS" -> {
                            val path = if (token == "i" || token == "iS") {
                                val id = argument.toIntOrNull() ?: error("Numéro d’icône Wolf invalide : $argument.")
                                require(id in 0..9999) { "Icône Wolf hors plage : $id." }
                                "BasicData/icon${id.toString().padStart(3, '0')}.png"
                            } else argument
                            require(!path.startsWith('<')) { "Découpage d’image incorporée Wolf non pris en charge : $path." }
                            val start = result.length; result.append('\uFFFC')
                            result.setSpan(ImageSpan(path, size * imageScale, token.endsWith('S')), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        "font" -> require(argument == "0") { "Sous-police Wolf $argument non chargée." }
                        "r" -> { // Ruby keeps the base word readable; a custom span supplies the annotation.
                            val comma = argument.indexOf(','); require(comma >= 0) { "Ruby Wolf invalide : $argument." }
                            val start = result.length; result.append(argument.substring(0, comma))
                            result.setSpan(RubySpan(argument.substring(0, comma), argument.substring(comma + 1), size, color), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        "sp", "space" -> Unit // timing belongs to the VM; line spacing defaults to the configured font
                        else -> error("Code de texte Wolf non pris en charge : \\$token[$argument].")
                    }
                    index = match.range.last + 1; continue
                }
                when (input[index + 1]) {
                    'E', 'N', '>', '<', '^', '!', '.' -> { index += 2; continue }
                    else -> if (input.startsWith("\\A+", index) || input.startsWith("\\A-", index)) { index += 3; continue }
                }
            }
            val cp = input.codePointAt(index)
            val next = index + Character.charCount(cp)
            result.append(input, index, next)
            index = next
        }
        finishRun()
        return result
    }

    private inner class ImageSpan(val path: String, val height: Float, val crisp: Boolean) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (resources.isOptionalMissing(path)) return 0
            val bitmap = resources.peek(path)
            val width = if (bitmap == null) height else height * bitmap.width / bitmap.height
            fm?.let { it.ascent = min(it.ascent, -height.toInt()); it.descent = max(it.descent, 0); it.top = it.ascent; it.bottom = it.descent }
            return width.toInt().coerceAtLeast(1)
        }
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val bitmap = resources.peek(path) ?: return
            val width = height * bitmap.width / bitmap.height
            val drawPaint = Paint().apply { isFilterBitmap = config.smooth && !crisp }
            canvas.drawBitmap(bitmap, null, RectF(x, y - height, x + width, y.toFloat()), drawPaint)
        }
    }

    private class RubySpan(val word: String, val ruby: String, val size: Float, val color: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val base = Paint(paint).apply { textSize = size }
            val small = Paint(paint).apply { textSize = size * .5f }
            fm?.let { it.ascent = min(it.ascent, -(size * 1.5f).toInt()); it.top = it.ascent }
            return max(base.measureText(word), small.measureText(ruby)).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val base = Paint(paint).apply { textSize = size; this.color = this@RubySpan.color }
            val small = Paint(base).apply { textSize = size * .5f }
            val width = max(base.measureText(word), small.measureText(ruby))
            canvas.drawText(word, x + (width - base.measureText(word)) / 2, y.toFloat(), base)
            canvas.drawText(ruby, x + (width - small.measureText(ruby)) / 2, y - size, small)
        }
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
        chunkWorkers.shutdownNow(); main.removeCallbacksAndMessages(null)
        synchronized(chunks) { chunks.clear(); chunkBytes = 0 }
        pendingChunks.clear(); textLayouts.clear(); frame = null
    }

    companion object {
        private const val CHUNK_SIZE = 8
        private const val CHUNK_BUDGET = 32L * 1024 * 1024
        private val MULTIPLY = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        private val ADD = PorterDuffXfermode(PorterDuff.Mode.ADD)
        private val TEXT_CONTROL = Regex("\\\\([A-Za-z]+\\+?|-)\\[([^]]*)]")
        private val ANIMATION_INTERVAL = Regex("_FRAME=(\\d+)(?=\\.[^.]+$)", RegexOption.IGNORE_CASE)
        private fun animationInterval(path: String): Int = ANIMATION_INTERVAL.find(path)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 100_000) ?: 20
    }
}
