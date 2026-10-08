package fr.astragames.app.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.edit
import fr.astragames.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

internal data class AppUpdateState(
    val release: GitHubRelease? = null, val busy: Boolean = false, val progress: Int? = null,
    val ready: Boolean = false, val error: String? = null, val message: String? = null,
    val automatic: Boolean = true, val autoDownload: Boolean = true, val wifiOnly: Boolean = true,
    val includePrereleases: Boolean = false
)

internal class AppUpdateManager(
    private val context: Context,
    private val latestRelease: suspend (Boolean, Boolean) -> GitHubRelease = { debug, previews -> GitHubUpdateClient().latest(debug, previews) }
) {
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val directory = File(context.cacheDir, "app-updates")
    private val apk get() = File(directory, "update.apk")
    private val mutex = Mutex()
    private val saved = runCatching { GitHubRelease.fromCache(JSONObject(preferences.getString("release", "")!!)) }.getOrNull()
        ?.takeIf { newerVersion(it.version, BuildConfig.VERSION_NAME) && it.allowedInChannel(preferences.getBoolean("includePrereleases", false)) }
    private val mutableState = MutableStateFlow(AppUpdateState(
        release = saved, ready = saved != null && preferences.getString("ready", null) == saved.sha256 && apk.isFile,
        automatic = preferences.getBoolean("automatic", true), autoDownload = preferences.getBoolean("download", true),
        wifiOnly = preferences.getBoolean("wifi", true), includePrereleases = preferences.getBoolean("includePrereleases", false)
    ))
    val state = mutableState.asStateFlow()

    suspend fun configure(automatic: Boolean, download: Boolean, wifiOnly: Boolean) = withContext(Dispatchers.IO) {
        check(preferences.edit().putBoolean("automatic", automatic).putBoolean("download", download).putBoolean("wifi", wifiOnly).commit())
        mutableState.update { it.copy(automatic = automatic, autoDownload = download, wifiOnly = wifiOnly) }
        AppUpdateWorker.schedule(context, automatic, wifiOnly)
    }

    private fun client() = GitHubUpdateClient()

    suspend fun setIncludePrereleases(enabled: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        if (mutableState.value.includePrereleases == enabled) return@withLock
        check(preferences.edit().putBoolean("includePrereleases", enabled).remove("release").remove("ready").remove("notified").commit())
        mutableState.update { it.copy(includePrereleases = enabled, release = null, ready = false, message = null, error = null) }
        apk.delete()
        context.getSystemService(android.app.NotificationManager::class.java).cancel(811)
    } }

    suspend fun check(downloadAutomatically: Boolean = false) = operation {
        val release = latestRelease(BuildConfig.DEBUG, mutableState.value.includePrereleases)
        preferences.edit { putLong("lastCheck", System.currentTimeMillis()) }
        if (!newerVersion(release.version, BuildConfig.VERSION_NAME)) {
            preferences.edit { remove("release"); remove("ready") }
            mutableState.update { it.copy(release = null, ready = false, message = "Astra est à jour.") }
        } else {
            val ready = mutableState.value.ready && mutableState.value.release?.sha256 == release.sha256 && apk.isFile
            if (!ready) preferences.edit { remove("ready") }
            preferences.edit { putString("release", release.json().toString()) }
            mutableState.update { it.copy(release = release, ready = ready) }
            if (downloadAutomatically && mutableState.value.autoDownload && !ready) downloadLocked(release)
        }
    }

    suspend fun download() = operation { downloadLocked(requireNotNull(mutableState.value.release)) }

    private suspend fun downloadLocked(release: GitHubRelease) {
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        check(!mutableState.value.wifiOnly || !network.isActiveNetworkMetered) { "Téléchargement en attente d’un réseau Wi-Fi." }
        check(directory.isDirectory || directory.mkdirs())
        val part = File(directory, "update.part")
        preferences.edit { remove("ready") }
        mutableState.update { it.copy(ready = false, progress = 0) }
        try {
            client().download(release, part) { percent -> mutableState.update { it.copy(progress = percent) } }
            verifyPackage(part, release)
            Files.move(part.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            check(preferences.edit().putString("ready", release.sha256).commit())
            mutableState.update { it.copy(ready = true, message = "Mise à jour prête à installer.") }
        } finally { part.delete() }
    }

    /** Revalidate bytes and Android signing identity immediately before granting the installer access. */
    suspend fun installerIntent(): Intent = withContext(Dispatchers.IO) { mutex.withLock {
        check(!mutableState.value.busy && mutableState.value.ready) { "Téléchargez la mise à jour avant de l’installer." }
        val release = requireNotNull(mutableState.value.release)
        try {
            require(apk.length() == release.bytes && fileDigest(apk) == release.sha256) { "Le fichier téléchargé a changé. Téléchargez-le à nouveau." }
            verifyPackage(apk, release)
        } catch (error: Exception) {
            preferences.edit { remove("ready") }
            mutableState.update { it.copy(ready = false) }
            throw error
        }
        Intent(Intent.ACTION_VIEW).setDataAndType(
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk), "application/vnd.android.package-archive"
        ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } }

    @Suppress("DEPRECATION")
    internal fun verifyPackage(file: File, release: GitHubRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("APK Android invalide.")
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        fun code(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: android.content.pm.PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toList() }.toSet()
        require(candidate.packageName == context.packageName && candidate.versionName == release.version && code(candidate) > code(installed)) { "Cet APK ne correspond pas à une nouvelle version d’Astra." }
        val currentSigners = signers(installed)
        require(currentSigners.isNotEmpty() && signers(candidate) == currentSigners) { "Signature APK incompatible avec cette installation d’Astra." }
    }

    private suspend fun operation(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) return@withContext
        mutableState.update { it.copy(busy = true, progress = null, error = null, message = null) }
        try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { mutableState.update { it.copy(error = if (error is java.io.IOException) "Connexion à GitHub impossible. Réessayez." else error.message ?: "Mise à jour impossible.") } }
        finally { mutableState.update { it.copy(busy = false, progress = null) }; mutex.unlock() }
    }

    internal fun notificationNeeded(): Boolean {
        val release = state.value.release ?: return false
        return state.value.ready && preferences.getString("notified", null) != release.version
    }
    internal fun markNotified() { preferences.edit { putString("notified", state.value.release?.version) } }
}

internal fun fileDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val buffer = ByteArray(128 * 1024); while (true) {
        val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
    } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
