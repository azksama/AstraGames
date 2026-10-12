package fr.astragames.app.windows

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** No scan until explicitly opened; addresses and freezes never survive a game session. */
internal class WolfCheatSession(
    private val context: Context, directory: File, private val executable: String,
    private val runner: WolfProcess
) : AutoCloseable {
    private val directory = File(directory, "cheat-session-${UUID.randomUUID()}")
    private val mutex = Mutex()
    private val closed = AtomicBoolean()
    private val helperLock = Any()
    @Volatile private var helper: Process? = null
    private var sequence = 0
    data class Pin(val address: Long, val type: WolfCheatType, var name: String, var bits: Long, var frozen: Boolean = false)
    val pins = mutableListOf<Pin>()
    var type = WolfCheatType.INT32
    var result: WolfCheatResult? = null
    val engineData: EngineDataAdapter = WolfEngineDataAdapter(this)
    private var undoWrite: Triple<Long, WolfCheatType, Pair<Long, Long>>? = null

    suspend fun command(op: Int, mode: Int = 0, value: Long = 0, other: Long = 0, address: Long = 0,
                        offset: Int = 0, valueType: WolfCheatType = type): WolfCheatResult = exchange(
        { id -> WolfCheatProtocol.request(id, op, valueType, mode, address, value, other, offset) },
        { bytes, id -> WolfCheatProtocol.response(bytes, id).also { if (op in listOf(1, 2, 3, 8, 10)) result = it } }
    )

    suspend fun engineCommand(op: Int, token: Long = 0, category: EngineDataCategory? = null, bank: Int? = null,
                              query: String = "", variable: Int = 0, before: String = "", after: String = "", offset: Int = 0): WolfEngineDataProtocol.Response =
        exchange({ id -> WolfEngineDataProtocol.request(id, op, token, category, bank, query, variable, before, after, offset) }, WolfEngineDataProtocol::response)

    private suspend fun <T> exchange(makeRequest: (Int) -> ByteArray, parse: (ByteArray, Int) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed.get()) { "La session est fermée" }
            if (helper == null) {
                directory.mkdirs()
                val binary = File(directory, "astra-memory.exe")
                context.assets.open("windows/astra-memory.exe").use { input -> binary.outputStream().use { input.copyTo(it) } }
                File(directory, "target.txt").writeText("G:\\${executable.replace('/', '\\')}", Charsets.UTF_16LE)
                synchronized(helperLock) {
                    check(!closed.get()) { "La session est fermée" }
                    helper = runner.startAuxiliary(binary, emptyList(), directory)
                }
            }
            check(helper!!.isAlive) { "L’éditeur de mémoire s’est arrêté. Relancez le jeu pour ouvrir une nouvelle session." }
            val id = ++sequence
            File(directory, "cancel").delete()
            val temporary = File(directory, "request.tmp")
            temporary.writeBytes(makeRequest(id))
            Files.move(temporary.toPath(), File(directory, "request.bin").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            val response = File(directory, "response.bin")
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                while (true) {
                    check(!closed.get()) { "La session est fermée" }
                    if (response.isFile) {
                        val bytes = response.inputStream().use { input ->
                            // Stale response files are normal while a scan runs. Read their ID first,
                            // without allocating the maximum typed payload every 60 ms.
                            val prefix = ByteArray(8)
                            var length = 0
                            while (length < prefix.size) {
                                val read = input.read(prefix, length, prefix.size - length)
                                if (read < 0) break
                                length += read
                            }
                            if (length < 8 || ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).getInt(4) != id) return@use null
                            val output = ByteArrayOutputStream().apply { write(prefix) }
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                require(output.size() + read <= WolfEngineDataProtocol.MAX_RESPONSE_BYTES) { "Réponse de l’éditeur trop volumineuse." }
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        if (bytes != null) {
                            return@withLock parse(bytes, id)
                        }
                    }
                    check(helper!!.isAlive) { "Le processus du jeu ou de l’éditeur s’est arrêté." }
                    check(android.os.SystemClock.elapsedRealtime() - started < 180_000) { "Le moteur de recherche ne répond pas. Annulez puis relancez le jeu." }
                    delay(60)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            } catch (error: Throwable) { cancel(); throw error }
        }
    }
    suspend fun read(address: Long, type: WolfCheatType): Long = command(5, address = address, valueType = type).rows.single().bits
    suspend fun write(address: Long, type: WolfCheatType, before: Long, after: Long) {
        check(pins.none { it.address == address && it.frozen }) { "Libérez cette valeur avant de la modifier." }
        command(4, address = address, value = after, other = before, valueType = type)
        undoWrite = Triple(address, type, before to after)
        pins.filter { it.address == address && it.type == type }.forEach { it.bits = after }
    }
    suspend fun undo() {
        val (address, type, values) = checkNotNull(undoWrite) { "Aucune modification à annuler" }
        check(pins.none { it.address == address && it.frozen }) { "Libérez cette valeur avant d’annuler la modification." }
        command(4, address = address, value = values.first, other = values.second, valueType = type)
        pins.filter { it.address == address && it.type == type }.forEach { it.bits = values.first }
        undoWrite = null
    }
    suspend fun freeze(pin: Pin, enabled: Boolean) {
        val expected = if (enabled) read(pin.address, pin.type) else 0L
        command(if (enabled) 6 else 7, address = pin.address, value = pin.bits, other = expected, valueType = pin.type)
        pin.frozen = enabled
    }
    fun cancel() { if (directory.isDirectory) File(directory, "cancel").writeText("1") }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        synchronized(helperLock) { helper?.destroy(); helper?.destroyForcibly(); helper = null }
        // Only this random session's private IPC/cache is disposable. No game/saves path is used.
        directory.deleteRecursively(); pins.clear()
    }
}
