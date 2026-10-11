package fr.astragames.app.wolfnative

import fr.astragames.wolf.NativeEffect
import fr.astragames.wolf.WolfDownloadRequest
import fr.astragames.wolf.WolfDownloadProgress
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Command260 owns one asynchronous reception, with standard Android certificate validation. */
internal class WolfNativeDownloads(private val files: WolfNativeFiles) : Closeable {
    private val closed = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { job ->
        Thread(job, "wolf-native-download").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private class Reception(val id: Long) {
        @Volatile var cancelled = false
        @Volatile var connection: HttpURLConnection? = null
        @Volatile var future: java.util.concurrent.Future<*>? = null
    }
    private var reception: Reception? = null

    fun execute(effect: NativeEffect.Download, progress: (WolfDownloadProgress) -> Unit = {}, completed: (Result<String>) -> Unit) {
        val job = synchronized(this) {
            if (closed.get()) return
            if (reception != null) {
                completed(Result.failure(IOException("Un téléchargement Wolf est déjà en cours")))
                return
            }
            Reception(effect.requestId).also { reception = it }
        }
        try { job.future = worker.submit {
            val result = runCatching {
                val bytes = receive(effect.request, job, progress)
                // Decode before committing the file, so malformed text leaves both destinations intact.
                val value = if (effect.request.variable != null) effect.request.decodeResponse(bytes) else ""
                synchronized(this) {
                    checkActive(job)
                    effect.request.filePath()?.let { files.write(it, bytes) }
                }
                value
            }
            synchronized(this) { if (reception === job) reception = null }
            if (!closed.get() && !job.cancelled) completed(result)
        } } catch (error: java.util.concurrent.RejectedExecutionException) {
            synchronized(this) { if (reception === job) reception = null }
            if (!closed.get() && !job.cancelled) completed(Result.failure(error))
        }
    }

    /** Cancelling one game request leaves this worker usable for the next title or world. */
    fun cancel(requestId: Long): Boolean {
        val job = synchronized(this) {
            reception?.takeIf { it.id == requestId }?.also { it.cancelled = true; reception = null }
        } ?: return false
        job.future?.cancel(true)
        worker.purge()
        disconnectAsync(job)
        return true
    }

    private fun disconnectAsync(job: Reception) {
        // Android's disconnect can wait for a platform read. Never block the VM or UI thread.
        job.connection?.let { connection -> Thread({ connection.disconnect() }, "wolf-native-download-cancel").start() }
    }

    private fun checkActive(job: Reception) {
        check(!closed.get() && !job.cancelled && !Thread.currentThread().isInterrupted) { "Téléchargement Wolf annulé" }
    }

    private fun receive(request: WolfDownloadRequest, job: Reception, progress: (WolfDownloadProgress) -> Unit): ByteArray {
        var url = URL(transportUrl(request.urlBytes))
        var method = request.method
        var post = request.postBytes
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        val started = System.nanoTime()
        var lastProgress = 0L
        for (redirect in 0..5) {
            checkActive(job)
            require(url.protocol == "http" || url.protocol == "https") { "Protocole de redirection Wolf non pris en charge" }
            val connection = url.openConnection() as HttpURLConnection
            job.connection = connection
            try {
                connection.connectTimeout = 15_000; connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false; connection.useCaches = false
                connection.requestMethod = method
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("User-Agent", "Astra-Wolf-Native/1")
                post?.let { body ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.setRequestProperty("Content-Type", request.contentType ?: "application/x-www-form-urlencoded")
                    connection.outputStream.use { it.write(body) }
                }
                checkActive(job)
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5) { "Trop de redirections du téléchargement Wolf" }
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirection Wolf sans destination")
                    require(location.length <= WolfDownloadRequest.MAX_PATH) { "Redirection Wolf trop longue" }
                    val next = URI(url.toString()).resolve(location)
                    require(next.rawUserInfo == null && next.fragment == null && next.host != null) { "Redirection Wolf invalide" }
                    require(url.protocol != "https" || next.scheme.equals("https", true)) { "Redirection HTTPS vers HTTP refusée" }
                    url = next.toURL()
                    if (status == 303 || method == "POST" && status in setOf(301, 302)) { method = "GET"; post = null }
                    continue
                }
                if (status != 200) throw IOException("Téléchargement Wolf : HTTP $status")
                val length = connection.contentLengthLong
                require(length <= WolfDownloadRequest.MAX_TEXT_BYTES) { "Téléchargement Wolf supérieur à 16 Mio" }
                val output = ByteArrayOutputStream(if (length in 1..1_048_576) length.toInt() else 8192)
                fun report(final: Boolean = false) {
                    val now = System.nanoTime()
                    if (!final && now - lastProgress < 200_000_000L) return
                    lastProgress = now
                    val elapsed = (now - started).coerceAtLeast(1)
                    val rate = (output.size().toLong() * 1_000_000_000L / elapsed).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val total = length.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    progress(WolfDownloadProgress(output.size(), total, rate, (elapsed / 1_000_000_000L).toInt(),
                        if (rate == 0 || total <= output.size()) 0 else (total - output.size()) / rate))
                }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        checkActive(job)
                        require(System.nanoTime() <= deadline) { "Téléchargement Wolf sans réponse complète après 45 secondes" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() <= WolfDownloadRequest.MAX_TEXT_BYTES - count) { "Téléchargement Wolf supérieur à 16 Mio" }
                        output.write(buffer, 0, count)
                        report()
                    }
                }
                if (length >= 0 && output.size().toLong() != length) throw IOException("Téléchargement Wolf incomplet")
                report(final = true)
                return output.toByteArray()
            } finally { connection.disconnect(); job.connection = null }
        }
        throw IOException("Téléchargement Wolf sans réponse")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val job = synchronized(this) { reception.also { it?.cancelled = true; reception = null } }
            worker.shutdownNow()
            job?.let(::disconnectAsync)
        }
    }

    companion object {
        /** Escape raw non-ASCII bytes, retaining CP932 queries instead of silently converting to UTF8. */
        internal fun transportUrl(encoded: ByteArray): String = buildString(encoded.size) {
            val hex = "0123456789ABCDEF"
            for (byte in encoded) {
                val value = byte.toInt() and 255
                require(value >= 32 && value != 127) { "Caractère de contrôle dans l’URL Wolf" }
                val plain = value in 48..57 || value in 65..90 || value in 97..122 ||
                    value.toChar() in ":/?@!$&'()*+,;=%._~-[]"
                if (plain) append(value.toChar())
                else { append('%'); append(hex[value ushr 4]); append(hex[value and 15]) }
            }
        }
    }
}
