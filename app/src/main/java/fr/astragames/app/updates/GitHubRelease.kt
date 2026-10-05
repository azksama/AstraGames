package fr.astragames.app.updates

import org.json.JSONObject
import java.net.URI

internal const val UPDATE_REPOSITORY = "azksama/AstraGames"
internal const val UPDATE_API = "https://api.github.com/repos/$UPDATE_REPOSITORY"
internal const val MAX_APK_BYTES = 256L * 1024 * 1024

internal data class GitHubRelease(val version: String, val assetId: Long, val assetName: String, val bytes: Long, val sha256: String, val notes: String) {
    val pageUrl get() = "https://github.com/$UPDATE_REPOSITORY/releases/tag/v$version"
    fun json() = JSONObject().put("version", version).put("assetId", assetId).put("assetName", assetName)
        .put("bytes", bytes).put("sha256", sha256).put("notes", notes)

    companion object {
        fun parse(value: JSONObject, debug: Boolean): GitHubRelease {
            require(!value.getBoolean("draft") && !value.getBoolean("prerelease")) { "La release GitHub doit être stable et publiée." }
            val version = value.getString("tag_name").removePrefix("v")
            require(versionParts(version) != null) { "Version GitHub non reconnue." }
            val assets = value.getJSONArray("assets")
            val acceptedNames = if (debug) listOf("Astra-$version-debug.apk", "Astra-$version.apk") else listOf("Astra-$version.apk", "Astra-$version-debug.apk")
            val asset = acceptedNames.firstNotNullOfOrNull { name ->
                (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.getString("name").equals(name, true) && it.optString("state") == "uploaded" }
            } ?: error("Aucun APK installable dans cette release. Les APK non signés sont exclus.")
            return validated(GitHubRelease(version, asset.getLong("id"), asset.getString("name"), asset.getLong("size"),
                asset.getString("digest").removePrefix("sha256:"), value.optString("body").take(12_000)))
        }
        fun fromCache(value: JSONObject) = validated(GitHubRelease(value.getString("version"), value.getLong("assetId"),
            value.getString("assetName"), value.getLong("bytes"), value.getString("sha256"), value.optString("notes").take(12_000)))
        private fun validated(release: GitHubRelease): GitHubRelease {
            require(versionParts(release.version) != null && release.assetId > 0 && release.bytes in 1..MAX_APK_BYTES && release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Métadonnées de mise à jour invalides." }
            return release.copy(sha256 = release.sha256.lowercase())
        }
    }
}

internal fun versionParts(version: String): List<Long>? = version.takeIf { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
    ?.split('.')?.map { it.toLongOrNull() ?: return null }

internal fun newerVersion(candidate: String, current: String): Boolean {
    val next = versionParts(candidate) ?: return false
    val installed = versionParts(current) ?: return false
    return next.zip(installed).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

internal fun allowedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && when (uri.host) {
        "api.github.com" -> uri.path.startsWith("/repos/$UPDATE_REPOSITORY/releases/")
        "github.com" -> uri.path.startsWith("/$UPDATE_REPOSITORY/releases/download/")
        "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
        else -> false
    }
}.getOrDefault(false)
