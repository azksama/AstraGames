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
        assertTrue(newerVersion("1.11.0-beta", "1.10.0"))
        assertFalse(newerVersion("999999999999999999999999.0.0", "1.0.0"))
    }
    @Test fun previewsFollowSemanticVersionOrderAndStableSupersedesThem() {
        val versions = listOf("1.11.0", "1.12.0-alpha", "1.12.0-alpha.1", "1.12.0-beta.2", "1.12.0-beta.10", "1.12.0-rc.1", "1.12.0", "1.12.1-beta.1")
        versions.zipWithNext().forEach { (old, next) ->
            assertTrue("$next > $old", newerVersion(next, old))
            assertFalse(newerVersion(old, next))
        }
        assertFalse(newerVersion("1.12.0+build.2", "1.12.0+build.1"))
        assertTrue(newerVersion("1.12.0-beta.999999999999999999999", "1.12.0-beta.10"))
        listOf("01.12.0", "1.12.0-beta.01", "1.12.0-", "1.12.0-beta..1", "1.12.0/evil").forEach { assertNull(it, releaseVersion(it)) }
    }
    @Test fun betaChannelChoosesNewestInstallableVersionRatherThanResponseOrder() {
        val list = JSONArray().put(fixture("1.12.0-beta.2", true)).put(fixture("1.11.0"))
            .put(fixture("1.12.0-beta.10", true)).put(fixture("9.0.0", true).put("draft", true))
            .put(fixture("8.0.0", true).put("assets", JSONArray()))
        assertEquals("1.11.0", GitHubRelease.newest(list, false, false).version)
        assertEquals("1.12.0-beta.10", GitHubRelease.newest(list, false, true).version)
        list.put(fixture("1.12.0"))
        assertEquals("1.12.0", GitHubRelease.newest(list, false, true).version)
    }
    @Test fun betaFlagAndVersionSuffixBothRequireExplicitOptInAndSurviveCache() {
        val beta = GitHubRelease.parse(fixture("1.12.0-beta.1", true), false, true)
        assertEquals(beta, GitHubRelease.fromCache(beta.json()))
        assertFalse(beta.allowedInChannel(false))
        assertTrue(beta.allowedInChannel(true))
        assertTrue(runCatching { GitHubRelease.parse(fixture("1.12.0-beta.1", false), false) }.isFailure)
        val flagged = GitHubRelease.parse(fixture("1.12.0", true), false, true)
        assertFalse(GitHubRelease.fromCache(flagged.json()).allowedInChannel(false))
        val legacy = beta.json().apply { remove("prerelease") }
        assertFalse(GitHubRelease.fromCache(legacy).allowedInChannel(false))
        assertTrue(runCatching { GitHubRelease.parse(fixture("1.12.0", true).put("draft", true), false, true) }.isFailure)
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
        assertTrue(allowedUpdateUrl("$UPDATE_API/releases?per_page=100"))
        assertFalse(allowedUpdateUrl("$UPDATE_API/releases-evil"))
        assertTrue(allowedUpdateUrl("https://release-assets.githubusercontent.com/path?signature=example"))
        listOf("http://api.github.com/repos/$UPDATE_REPOSITORY/releases/latest", "https://api.github.com.evil.test/", "https://api.github.com/repos/other/repo/releases/latest", "https://token@api.github.com/repos/$UPDATE_REPOSITORY/releases/latest", "https://github.com/$UPDATE_REPOSITORY/issues", "file:///tmp/app.apk", "https://api.github.com:444/repos/$UPDATE_REPOSITORY/releases/latest").forEach {
            assertFalse(it, allowedUpdateUrl(it))
        }
    }
    private fun fixture(version: String = "1.11.0", preview: Boolean = false) = JSONObject().put("tag_name", "v$version").put("draft", false).put("prerelease", preview)
        .put("assets", JSONArray().put(JSONObject().put("name", "Astra-$version-debug.apk").put("id", 42).put("state", "uploaded")
            .put("size", 12345).put("digest", "sha256:" + "a".repeat(64))))
}
