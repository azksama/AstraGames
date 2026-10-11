package fr.astragames.app.wolfnative

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import fr.astragames.app.BuildConfig
import fr.astragames.wolf.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class WolfAuditRecord(val gameId: String, val gameTitle: String, val createdAt: Long,
    val astraVersion: String, val metadataFingerprint: String, val sourceDescription: String,
    val audit: WolfCompatibilityAudit) {
    fun textReport() = "Astra $astraVersion\nDate : ${java.util.Date(createdAt)}\nDossier : $sourceDescription\n" +
        "Empreinte des métadonnées : $metadataFingerprint\n\n" + audit.copy(title = gameTitle).textReport()
}

/** Local, atomic reports. Never writes to the game, native saves or Winlator prefix. */
internal class WolfCompatibilityReports(private val context: Context) {
    private fun file(gameId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "wolf-compatibility/$key.json")
    }
    fun save(record: WolfAuditRecord, checkpoint: () -> Unit = {}) {
        val bytes = encode(record).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 32 * 1024 * 1024) { "Rapport trop volumineux" }
        checkpoint()
        val file = file(record.gameId); require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); checkpoint(); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    fun latest(gameId: String): WolfAuditRecord? {
        val atomic = AtomicFile(file(gameId))
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
        return atomic.openRead().use { stream ->
            require(atomic.baseFile.length() <= 32 * 1024 * 1024) { "Rapport trop volumineux" }
            decode(stream.readBytes().toString(Charsets.UTF_8)).also { require(it.gameId == gameId) }
        }
    }
    fun shareIntent(record: WolfAuditRecord, json: Boolean): Intent {
        val directory = File(context.cacheDir, "wolf-reports").apply { mkdirs() }
        val export = File(directory, "Astra-Wolf-Compatibilite-${record.createdAt}-${java.util.UUID.randomUUID()}.${if (json) "json" else "txt"}")
        export.writeText(if (json) encode(record) else record.textReport())
        // Retain immutable export snapshots while a chooser/recipient is reading them.
        directory.listFiles()?.filter { it.name.startsWith("Astra-Wolf-Compatibilite-") &&
            System.currentTimeMillis() - it.lastModified() > 7 * 24 * 60 * 60 * 1000L }?.forEach { it.delete() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", export)
        return Intent(Intent.ACTION_SEND).setType(if (json) "application/json" else "text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Compatibilité Wolf RPG", uri) }
    }
    fun export(record: WolfAuditRecord, uri: Uri, json: Boolean) {
        require(uri.scheme == "content") { "Destination Android invalide" }
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write((if (json) encode(record) else record.textReport()).toByteArray(Charsets.UTF_8))
        } ?: error("Impossible d’enregistrer le rapport")
    }
    companion object {
        fun record(gameId: String, title: String, source: AndroidWolfAssetSource, audit: WolfCompatibilityAudit) =
            WolfAuditRecord(gameId, title, System.currentTimeMillis(), BuildConfig.VERSION_NAME,
                source.metadataFingerprint, source.description, audit)

        internal fun encode(record: WolfAuditRecord): String {
            val a = record.audit
            val body = JSONObject().put("schema", 1).put("gameId", record.gameId).put("gameTitle", record.gameTitle)
                .put("createdAt", record.createdAt).put("astraVersion", record.astraVersion)
                .put("metadataFingerprint", record.metadataFingerprint).put("sourceDescription", record.sourceDescription)
                .put("scope", "Static native check of declared maps, all pages and common events; no gameplay or media decoding proof")
                .put("verdict", a.verdict.name).put("complete", a.complete)
            body.put("audit", JSONObject().put("title", a.title).put("declaredEngineVersion", a.declaredEngineVersion ?: JSONObject.NULL)
                .put("width", a.width).put("height", a.height).put("fps", a.fps).put("totalMaps", a.totalMaps)
                .put("visitedMaps", a.visitedMaps).put("readableMaps", a.readableMaps).put("commonEvents", a.commonEvents)
                .put("pages", a.pages).put("commands", a.commands).put("problemOccurrences", a.problemOccurrences)
                .put("detailsTruncated", a.detailsTruncated).put("inventoryTruncated", a.inventoryTruncated)
                .put("finished", a.finished).put("elapsedMs", a.elapsedMs)
                .put("features", JSONArray().apply { a.features.forEach { f -> put(JSONObject().put("key", f.key).put("label", f.label)
                    .put("occurrences", f.occurrences).put("blocked", f.blocked).put("runtimeChecks", f.runtimeChecks)) } })
                .put("findings", JSONArray().apply { a.findings.forEach { f -> val i = f.issue
                    put(JSONObject().put("file", i.file).put("eventId", i.eventId).put("opcode", i.opcode)
                        .put("offset", i.offset).put("reason", i.reason).put("operands", i.operands ?: JSONObject.NULL)
                        .put("mapId", f.mapId ?: JSONObject.NULL).put("pageIndex", f.pageIndex ?: JSONObject.NULL).put("occurrences", f.occurrences))
                } }))
            return body.toString(2) + "\n"
        }
        internal fun decode(text: String): WolfAuditRecord {
            val root = JSONObject(text); require(root.getInt("schema") == 1) { "Version de rapport non prise en charge" }
            val a = root.getJSONObject("audit")
            val features = a.getJSONArray("features"); require(features.length() <= WolfCompatibilityAuditor.MAX_FEATURES)
            val findings = a.getJSONArray("findings"); require(findings.length() <= WolfCompatibilityAuditor.MAX_DETAILS)
            fun JSONObject.optionalInt(key: String): Int? = if (isNull(key)) null else getInt(key)
            fun nonnegative(value: Long) = value.also { require(it >= 0) }
            val audit = WolfCompatibilityAudit(a.getString("title"), a.optionalInt("declaredEngineVersion"), a.getInt("width"),
                a.getInt("height"), a.getInt("fps"), a.getInt("totalMaps"), a.getInt("visitedMaps"), a.getInt("readableMaps"),
                a.getInt("commonEvents"), nonnegative(a.getLong("pages")), nonnegative(a.getLong("commands")),
                List(features.length()) { index -> features.getJSONObject(index).let { f ->
                    WolfAuditFeature(f.getString("key"), f.getString("label"), nonnegative(f.getLong("occurrences")),
                        nonnegative(f.getLong("blocked")), nonnegative(f.getLong("runtimeChecks"))) } },
                List(findings.length()) { index -> findings.getJSONObject(index).let { f ->
                    WolfAuditFinding(WolfCapabilityIssue(f.getString("file"), f.getInt("eventId"), f.getInt("opcode"),
                        f.getInt("offset"), f.getString("reason"), if (f.isNull("operands")) null else f.getString("operands")),
                        f.optionalInt("mapId"), f.optionalInt("pageIndex"), nonnegative(f.getLong("occurrences"))) } },
                nonnegative(a.getLong("problemOccurrences")), a.getBoolean("detailsTruncated"), a.getBoolean("inventoryTruncated"),
                a.getBoolean("finished"), nonnegative(a.getLong("elapsedMs")))
            require(audit.totalMaps >= 0 && audit.visitedMaps in 0..audit.totalMaps && audit.readableMaps in 0..audit.visitedMaps)
            require(audit.commonEvents >= 0 && audit.features.all { it.blocked <= it.occurrences && it.runtimeChecks <= it.occurrences })
            return WolfAuditRecord(root.getString("gameId"), root.getString("gameTitle"), root.getLong("createdAt"),
                root.getString("astraVersion"), root.getString("metadataFingerprint"), root.getString("sourceDescription"), audit)
        }
    }
}
