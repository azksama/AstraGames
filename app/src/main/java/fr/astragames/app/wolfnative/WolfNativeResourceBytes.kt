package fr.astragames.app.wolfnative

import android.os.Looper
import fr.astragames.wolf.WolfAssetSource
import fr.astragames.wolf.WolfParser
import fr.astragames.wolf.WolfXResource
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Worker-only resource reader for images, masks, fonts, audio and movie payloads.
 *
 * Wolf's Pro manual specifies that foo.png.wolfx takes precedence over foo.png,
 * including when both exist. The explicit protected suffix also works. We only
 * admit the proven default empty key; unavailable keys/variables remain errors.
 * This is not a global parser/source wrapper for maps or event file commands.
 *
 * Only decoded protected payloads are retained, within a byte/count quota.
 * Callers treat the returned bytes as immutable. Concurrent requests for an alias
 * and its explicit path share one read/decode, without performing I/O in draw.
 */
internal class WolfNativeResourceBytes(
    private val source: WolfAssetSource,
    private val budgetBytes: Long = 16L * 1024 * 1024,
    private val maxEntries: Int = 64,
) : Closeable {
    private val lock = Any()
    private val cached = LinkedHashMap<String, ByteArray>(8, .75f, true)
    private val pending = mutableMapOf<String, CompletableFuture<ByteArray>>()
    private var closed = false
    private var bytesInCache = 0L

    init {
        require(budgetBytes in 0..64L * 1024 * 1024 && maxEntries in 1..256) { "Quota de ressources Wolf invalide" }
    }

    internal val residentBytes: Long get() = synchronized(lock) { bytesInCache }
    internal val residentEntries: Int get() = synchronized(lock) { cached.size }

    /** A root-game path is required, not a Data-relative picture name. */
    fun read(path: String, maxBytes: Int = MAX_FILE_BYTES): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper()) { "La lecture d’une ressource Wolf doit rester hors du thread UI" }
        require(maxBytes in 1..MAX_FILE_BYTES) { "Capacité de lecture Wolf invalide" }
        synchronized(lock) { check(!closed) { "lecture de ressource Wolf fermée" } }
        val requested = WolfParser.rootAssetPath(path)
        val explicit = requested.endsWith(PROTECTED_SUFFIX, ignoreCase = true)
        val key = if (!explicit && source.exists(requested + PROTECTED_SUFFIX)) requested + PROTECTED_SUFFIX else requested
        val future: CompletableFuture<ByteArray>
        val owner: Boolean
        synchronized(lock) {
            check(!closed) { "lecture de ressource Wolf fermée" }
            cached[key]?.let { return withinLimit(it, maxBytes) }
            val prior = pending[key]
            owner = prior == null
            if (prior == null) {
                check(pending.size < 64) { "plus de 64 lectures de ressources Wolf simultanées" }
                future = CompletableFuture<ByteArray>().also { pending[key] = it }
            } else future = prior
        }
        if (owner) {
            try {
                val input = source.read(key)
                require(input.size <= MAX_FILE_BYTES) { "ressource Wolf dépassant 128 Mio" }
                val encrypted = WolfXResource.isEncrypted(input)
                require(!key.endsWith(PROTECTED_SUFFIX, true) || encrypted) { "en-tête WOLFX absent ou incorrect" }
                val payload = if (encrypted) WolfXResource.decode(input) else input
                synchronized(lock) {
                    check(!closed) { "lecture de ressource Wolf fermée" }
                    if (encrypted && payload.size <= budgetBytes) {
                        cached.put(key, payload)?.let { bytesInCache -= it.size }
                        bytesInCache += payload.size
                        val iterator = cached.entries.iterator()
                        while ((bytesInCache > budgetBytes || cached.size > maxEntries) && iterator.hasNext()) {
                            bytesInCache -= iterator.next().value.size
                            iterator.remove()
                        }
                    }
                    // Publish before removing the in-flight entry: no duplicate
                    // protected read can race the cache handover.
                    future.complete(payload)
                }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                future.completeExceptionally(error)
            } finally {
                synchronized(lock) { if (pending[key] === future) pending.remove(key) }
            }
        }
        val payload = try { future.get() }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
        catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
        synchronized(lock) { check(!closed) { "lecture de ressource Wolf fermée" } }
        return withinLimit(payload, maxBytes)
    }

    private fun withinLimit(bytes: ByteArray, limit: Int): ByteArray {
        require(bytes.size <= limit) { "ressource Wolf dépassant ${limit / (1024 * 1024)} Mio après déchiffrement" }
        return bytes
    }

    override fun close() {
        val requests = synchronized(lock) {
            if (closed) return
            closed = true
            cached.clear(); bytesInCache = 0
            pending.values.toList().also { pending.clear() }
        }
        requests.forEach { it.completeExceptionally(CancellationException("lecture de ressource Wolf fermée")) }
    }

    companion object {
        private const val MAX_FILE_BYTES = 128 * 1024 * 1024
        private const val PROTECTED_SUFFIX = ".wolfx"
        fun payloadPath(path: String): String = if (path.endsWith(PROTECTED_SUFFIX, true)) path.dropLast(PROTECTED_SUFFIX.length) else path
    }
}
