package fr.astragames.app.wolfnative

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import fr.astragames.wolf.WolfAssetSource
import java.io.Closeable
import java.util.LinkedHashMap
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
) : Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64)) { task ->
        Thread(task, "Wolf-native-image").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val mandatory = ConcurrentHashMap.newKeySet<String>()
    private val missingOptional = ConcurrentHashMap.newKeySet<String>()
    private val images = LinkedHashMap<String, Bitmap>(32, .75f, true)
    private var residentBytes = 0L
    private var pinned = emptySet<String>()
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

    /** Keep currently visible textures resident while evicting old maps/pictures. */
    fun pin(paths: Collection<String>) {
        synchronized(images) { pinned = paths.filter(String::isNotBlank).map(::logicalPath).toSet(); trim() }
    }

    fun request(path: String, optional: Boolean = false) {
        if (path.isBlank() || closed.get()) return
        val key = logicalPath(path)
        if (!optional) mandatory += key
        if (key in missingOptional) {
            if (!optional && failed.add(key)) handler.post { if (!closed.get()) onError("Ressource Wolf « $key » introuvable.") }
            return
        }
        if (failed.contains(key) || !pending.add(key)) return
        synchronized(images) { if (images.containsKey(key)) { pending.remove(key); return } }
        try {
            workers.execute {
                var decoded: Bitmap? = null
                try {
                    if (optional && key !in mandatory && !source.exists(key)) {
                        missingOptional += key
                        require(key !in mandatory) { "image introuvable" }
                        synchronized(images) { revision++ }
                        handler.post { if (!closed.get()) onChanged() }
                        return@execute
                    }
                    val bytes = source.read(key)
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
                                val visibleBytes = images.entries.filter { it.key in pinned && it.key != key }
                                    .sumOf { it.value.allocationByteCount.toLong() } + if (key in pinned) decoded!!.allocationByteCount else 0
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
                    }
                } catch (error: Exception) {
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

    fun failure(path: String): Boolean = failed.contains(logicalPath(path))

    private fun trim() {
        if (residentBytes <= budgetBytes) return
        val iterator = images.entries.iterator()
        while (residentBytes > budgetBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in pinned) continue
            residentBytes -= entry.value.allocationByteCount
            iterator.remove()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        workers.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        synchronized(images) { images.clear(); pinned = emptySet(); residentBytes = 0L; revision++ }
        pending.clear()
        failed.clear()
        mandatory.clear()
        missingOptional.clear()
    }

    companion object {
        private const val MAX_FILE_BYTES = 64 * 1024 * 1024
        private const val MAX_PIXELS = 32L * 1024 * 1024
        /** Wolf stores image/sound names relative to Data. Never accept absolute paths. */
        fun logicalPath(path: String): String {
            val normalized = path.replace('\\', '/').removePrefix("./")
            require(!normalized.startsWith('/') && ':' !in normalized && normalized.split('/').none { it == ".." }) {
                "Chemin de ressource Wolf non relatif : $path"
            }
            return if (normalized.startsWith("Data/", true)) normalized else "Data/$normalized"
        }
    }
}
