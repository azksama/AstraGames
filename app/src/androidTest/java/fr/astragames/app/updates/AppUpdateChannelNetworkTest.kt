package fr.astragames.app.updates

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AppUpdateChannelNetworkTest {
    @Test fun publicGitHubFeedIncludesTheExpectedBeta() = runBlocking(Dispatchers.IO) {
        val expected = InstrumentationRegistry.getArguments().getString("expectedPublicBeta")
        assumeTrue(expected != null)
        val client = GitHubUpdateClient()
        val stable = client.latest(debug = false)
        val beta = client.latest(debug = false, includePrereleases = true)
        assertFalse(stable.prerelease)
        assertEquals(expected, beta.version)
        assertTrue(beta.prerelease)
        assertTrue(newerVersion(beta.version, stable.version))
        assertTrue(beta.sha256.matches(Regex("[a-f0-9]{64}")))
    }
}
