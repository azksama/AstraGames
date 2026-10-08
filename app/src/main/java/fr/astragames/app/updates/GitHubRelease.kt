package fr.astragames.app.updates

import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

internal const val UPDATE_REPOSITORY = "azksama/AstraGames"
internal const val UPDATE_API = "https://api.github.com/repos/$UPDATE_REPOSITORY"
internal const val MAX_APK_BYTES = 256L * 1024 * 1024

internal data class GitHubRelease(val version: String, val assetId: Long, val assetName: String, val bytes: Long, val sha256: String, val notes: String, val prerelease: Boolean = false) {
    fun allowedInChannel(includePrereleases: Boolean) = includePrereleases || (!prerelease && releaseVersion(version)?.preview.isNullOrEmpty())
    val pageUrl get() = "https://github.com/$UPDATE_REPOSITORY/releases/tag/v$version"
    fun json() = JSONObject().put("version", version).put("assetId", assetId).put("assetName", assetName)
        .put("bytes", bytes).put("sha256", sha256).put("notes", notes).put("prerelease", prerelease)

    companion object {
        fun parse(value: JSONObject, debug: Boolean, includePrereleases: Boolean = false): GitHubRelease {
            require(!value.getBoolean("draft")) { "La release GitHub doit être publiée." }
            val version = value.getString("tag_name").removePrefix("v")
            val parsed = requireNotNull(releaseVersion(version)) { "Version GitHub non reconnue." }
            val prerelease = value.getBoolean("prerelease") || parsed.preview.isNotEmpty()
            require(includePrereleases || !prerelease) { "La release GitHub doit être stable." }
            val assets = value.getJSONArray("assets")
            val acceptedNames = if (debug) listOf("Astra-$version-debug.apk", "Astra-$version.apk") else listOf("Astra-$version.apk", "Astra-$version-debug.apk")
            val asset = acceptedNames.firstNotNullOfOrNull { name ->
                (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.getString("name").equals(name, true) && it.optString("state") == "uploaded" }
            } ?: error("Aucun APK installable dans cette release. Les APK non signés sont exclus.")
            return validated(GitHubRelease(version, asset.getLong("id"), asset.getString("name"), asset.getLong("size"),
                asset.getString("digest").removePrefix("sha256:"), value.optString("body").take(12_000), prerelease))
        }
        fun newest(values: JSONArray, debug: Boolean, includePrereleases: Boolean): GitHubRelease =
            (0 until values.length()).mapNotNull { index ->
                runCatching { parse(values.getJSONObject(index), debug, includePrereleases) }.getOrNull()
            }.maxWithOrNull { a, b -> releaseVersion(a.version)!!.compareTo(releaseVersion(b.version)!!) }
                ?: error("Aucune mise à jour installable disponible pour ce canal.")

        fun fromCache(value: JSONObject) = validated(GitHubRelease(value.getString("version"), value.getLong("assetId"),
            value.getString("assetName"), value.getLong("bytes"), value.getString("sha256"), value.optString("notes").take(12_000), value.optBoolean("prerelease", false)))
        private fun validated(release: GitHubRelease): GitHubRelease {
            require(releaseVersion(release.version) != null && release.assetId > 0 && release.bytes in 1..MAX_APK_BYTES && release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Métadonnées de mise à jour invalides." }
            return release.copy(sha256 = release.sha256.lowercase())
        }
    }
}

internal data class ReleaseVersion(val core: List<Long>, val preview: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        core.zip(other.core).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (preview.isEmpty() || other.preview.isEmpty()) return when {
            preview.isEmpty() && other.preview.isEmpty() -> 0
            preview.isEmpty() -> 1
            else -> -1
        }
        preview.zip(other.preview).forEach { (a, b) ->
            if (a != b) {
                val aNumber = a.all(Char::isDigit)
                val bNumber = b.all(Char::isDigit)
                return when {
                    aNumber && bNumber -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                    aNumber -> -1
                    bNumber -> 1
                    else -> a.compareTo(b)
                }
            }
        }
        return preview.size.compareTo(other.preview.size)
    }
}

private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
internal fun releaseVersion(version: String): ReleaseVersion? {
    if (version.length > 200) return null
    val match = versionPattern.matchEntire(version) ?: return null
    val core = (1..3).map { match.groupValues[it].toLongOrNull() ?: return null }
    val preview = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
    if (preview.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
    return ReleaseVersion(core, preview)
}

internal fun newerVersion(candidate: String, current: String): Boolean {
    val next = releaseVersion(candidate) ?: return false
    val installed = releaseVersion(current) ?: return false
    return next > installed
}

internal fun allowedUpdateUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) && when (uri.host) {
        "api.github.com" -> uri.path == "/repos/$UPDATE_REPOSITORY/releases" || uri.path.startsWith("/repos/$UPDATE_REPOSITORY/releases/")
        "github.com" -> uri.path.startsWith("/$UPDATE_REPOSITORY/releases/download/")
        "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
        else -> false
    }
}.getOrDefault(false)
