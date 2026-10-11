package fr.astragames.app.launcher

import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Versioned Ren'Py packages use the same run action, but are separate installed applications. */
internal object JoiPlayRuntimeTrust {
    private val renpyVersion = Regex("cyou\\.joiplay\\.runtime\\.renpy\\.v([78])d([0-9]{1,3})d([0-9]{1,3})")
    // Official Ren'Py 8.5 plugin from https://joiplay.net/assets/json/downloads.json, verified with apksigner.
    // APK SHA-256: e82444a80e127472547819b23bf41295790bc8c877cc13c773504ef52ff805f2.
    internal const val JOIPLAY_CERTIFICATE = "17ae227147e8ac4c3daab4a5b7baa63af16f9608b4ae5048cb018c7b4bbfd1f7"

    internal fun trusted(engine: String, packageName: String, signerDigests: Set<String>): Boolean =
        packageName in JoiPlayPayloadBuilder.trustedPackagesFor(engine) ||
            (engine == "RENPY" && renpyVersion.matches(packageName) && JOIPLAY_CERTIFICATE in signerDigests)

    fun accepts(manager: PackageManager, engine: String, packageName: String): Boolean {
        if (packageName in JoiPlayPayloadBuilder.trustedPackagesFor(engine)) return true
        if (engine != "RENPY" || !renpyVersion.matches(packageName)) return false
        return trusted(engine, packageName, signerDigests(manager, packageName))
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(manager: PackageManager, packageName: String): Set<String> = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (signing?.hasMultipleSigners() == true) signing.apkContentsSigners
            else signing?.signingCertificateHistory
        } else manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }.getOrDefault(emptySet())

    internal fun versionRank(packageName: String): Long = renpyVersion.matchEntire(packageName)?.groupValues?.let {
        it[1].toLong() * 1_000_000 + it[2].toLong() * 1_000 + it[3].toLong()
    } ?: 0
}
