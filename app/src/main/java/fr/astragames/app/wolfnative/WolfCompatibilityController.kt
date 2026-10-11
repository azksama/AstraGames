package fr.astragames.app.wolfnative

import android.content.Context
import android.net.Uri
import fr.astragames.app.data.local.GameEntity
import fr.astragames.wolf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference

internal data class WolfAuditState(val gameId: String = "", val running: Boolean = false,
    val progress: WolfAuditProgress = WolfAuditProgress("Prêt à analyser"), val report: WolfAuditRecord? = null,
    val error: String? = null, val cancelled: Boolean = false)

/** Owned by Astra's ViewModel: rotation/dismissing the dialog does not launch another scan. */
internal class WolfCompatibilityController(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val reports = WolfCompatibilityReports(context)
    private val mutable = MutableStateFlow(WolfAuditState())
    val state: StateFlow<WolfAuditState> = mutable
    private var job: Job? = null
    private val source = AtomicReference<AndroidWolfAssetSource?>()
    private var generation = 0

    fun open(gameId: String) {
        if (mutable.value.gameId == gameId) return
        cancel()
        val token = ++generation
        mutable.value = WolfAuditState(gameId)
        scope.launch {
            try {
                val previous = withContext(Dispatchers.IO) { reports.latest(gameId) }
                if (token == generation && !mutable.value.running) mutable.update { it.copy(report = previous) }
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (token == generation) mutable.update { it.copy(error = "Ancien rapport illisible : ${error.message}") }
            }
        }
    }
    fun start(game: GameEntity) {
        if (mutable.value.running) return
        val previous = job
        val token = ++generation
        val previousReport = mutable.value.takeIf { it.gameId == game.id }?.report
        mutable.value = WolfAuditState(game.id, running = true, progress = WolfAuditProgress("Accès au dossier d’origine"), report = previousReport)
        job = scope.launch {
            previous?.join()
            try {
                val record = withContext(Dispatchers.IO) {
                    val scanContext = currentCoroutineContext()
                    val assets = AndroidWolfAssetSource(app, Uri.parse(game.documentUri))
                    source.set(assets)
                    try {
                        val audit = WolfCompatibilityAuditor.inspect(assets, progress = { progress ->
                            scanContext.ensureActive()
                            if (token == generation) mutable.update { it.copy(progress = progress) }
                        }, checkpoint = { scanContext.ensureActive() })
                        scanContext.ensureActive()
                        WolfCompatibilityReports.record(game.id, game.title, assets, audit).also {
                            reports.save(it) { scanContext.ensureActive() }
                        }
                    } finally { source.compareAndSet(assets, null); assets.close() }
                }
                if (token == generation) mutable.update { it.copy(running = false, report = record) }
            } catch (error: Exception) {
                if (token == generation) mutable.update { it.copy(running = false,
                    cancelled = error is CancellationException,
                    error = if (error is CancellationException) null else "Analyse impossible : ${error.message ?: error.javaClass.simpleName}") }
            }
        }
    }
    fun cancel() {
        job?.cancel()
        source.getAndSet(null)?.let { assets -> scope.launch(Dispatchers.IO + NonCancellable) { assets.close() } }
        if (mutable.value.running) mutable.update { it.copy(running = false, cancelled = true) }
    }
    suspend fun share(record: WolfAuditRecord, json: Boolean) = withContext(Dispatchers.IO) { reports.shareIntent(record, json) }
    suspend fun export(record: WolfAuditRecord, uri: Uri, json: Boolean) = withContext(Dispatchers.IO) { reports.export(record, uri, json) }
}
