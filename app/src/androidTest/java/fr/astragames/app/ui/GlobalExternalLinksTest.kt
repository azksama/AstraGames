package fr.astragames.app.ui

import android.content.ActivityNotFoundException
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.astragames.app.ui.theme.AstraTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GlobalExternalLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test fun absentAndroidHandlerReportsFailureWithoutClosingTheUi() {
        var failures = 0
        var subsequentClicks = 0
        compose.setContent {
            val opener = SafeExternalUriHandler(LocalUriHandler.current) { failures++ }
            AstraTheme {
                Button(onClick = {
                    opener.openUri("astra-global-review-no-handler://fixture")
                    subsequentClicks++
                }) { androidx.compose.material3.Text("Open fixture") }
            }
        }
        compose.onNodeWithText("Open fixture").performClick().performClick()
        compose.runOnIdle {
            assertEquals(2, failures)
            assertEquals(2, subsequentClicks)
        }
    }

    @Test fun platformActivityAndPolicyFailuresAreRecoverable() {
        var failures = 0
        listOf(ActivityNotFoundException("fixture"), IllegalArgumentException("fixture"),
            SecurityException("fixture")).forEach { failure ->
            SafeExternalUriHandler(object : UriHandler {
                override fun openUri(uri: String) { throw failure }
            }) { failures++ }.openUri("https://example.invalid/fixture")
        }
        assertEquals(3, failures)
    }

    @Test fun supportedLinkIsPassedUnchangedExactlyOnce() {
        val opened = mutableListOf<String>()
        var failures = 0
        SafeExternalUriHandler(object : UriHandler {
            override fun openUri(uri: String) { opened += uri }
        }) { failures++ }.openUri("https://example.invalid/a?q=hello%20world")
        assertEquals(listOf("https://example.invalid/a?q=hello%20world"), opened)
        assertEquals(0, failures)
    }
}
