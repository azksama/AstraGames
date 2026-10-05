package fr.astragames.app.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseTest {
    @Test fun versionsAreComparedNumericallyAndDowngradesAreRefused() {
        assertTrue(newerVersion("1.11.0", "1.9.0"))
        assertFalse(newerVersion("1.9.9", "1.10.0"))
        assertFalse(newerVersion("1.10.0", "1.10.0"))
        assertFalse(newerVersion("1.11.0-beta", "1.10.0"))
        assertFalse(newerVersion("999999999999999999999999.0.0", "1.0.0"))
    }
    @Test fun onlyInstallablePublishedAssetsWithADigestAreAccepted() {
        val release = fixture()
        assertEquals(42L, GitHubRelease.parse(release, true).assetId)
        release.getJSONArray("assets").getJSONObject(0).put("name", "Astra-1.11.0-release-unsigned.apk")
        assertTrue(runCatching { GitHubRelease.parse(release, true) }.isFailure)
        assertTrue(runCatching { GitHubRelease.parse(fixture().put("draft", true), true) }.isFailure)
        assertTrue(runCatching { GitHubRelease.parse(fixture().put("prerelease", true), true) }.isFailure)
        val invalid = fixture().also { it.getJSONArray("assets").getJSONObject(0).put("digest", "") }
        assertTrue(runCatching { GitHubRelease.parse(invalid, true) }.isFailure)
    }
    @Test fun redirectsAreRestrictedToTheRepositoryAndGitHubAssetHosts() {
        assertTrue(allowedUpdateUrl("$UPDATE_API/releases/assets/42"))
        assertTrue(allowedUpdateUrl("https://release-assets.githubusercontent.com/path?signature=example"))
        listOf("http://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest", "https://api.github.com.evil.test/", "https://api.github.com/repos/other/repo/releases/latest", "https://token@api.github.com/repos/$UPDATE_REPOSITORY/releases/latest", "https://github.com/$UPDATE_REPOSITORY/issues", "file:///tmp/app.apk", "https://api.github.com:444/repos/$UPDATE_REPOSITORY/releases/latest").forEach {
            assertFalse(it, allowedUpdateUrl(it))
        }
    }
    private fun fixture() = JSONObject().put("tag_name", "v1.11.0").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", "Astra-1.11.0-debug.apk").put("id", 42).put("state", "uploaded")
            .put("size", 12345).put("digest", "sha256:" + "a".repeat(64))))
}
