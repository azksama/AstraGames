package fr.astragames.app.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.MainActivity
import fr.astragames.app.settings.AppLanguage
import fr.astragames.app.updates.AppUpdateState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual MainActivity, its own ViewModel/window contract and real IME. No catalogue writes. */
@RunWith(AndroidJUnit4::class)
class GlobalSearchImeUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<AstraApplication>()
    private val previousLanguage = AppLocalizer.language
    private var scenario: ActivityScenario<MainActivity>? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var beforePreferences: Preferences? = null
    private var previousUpdates: AppUpdateState? = null
    private val onboardingKey = booleanPreferencesKey("onboarding_completed")
    private val languageKey = stringPreferencesKey("language")
    private val scanKey = booleanPreferencesKey("scan_on_launch")

    // Use the repository's existing singleton. A second DataStore for this file is unsafe.
    // Test-only access is needed because the production API intentionally only completes onboarding.
    @Suppress("UNCHECKED_CAST")
    private val settingsStore: DataStore<Preferences> by lazy {
        Class.forName("fr.astragames.app.settings.SettingsRepositoryKt")
            .getDeclaredMethod("getDataStore", Context::class.java)
            .apply { isAccessible = true }
            .invoke(null, app) as DataStore<Preferences>
    }

    @Before fun prepare() {
        runBlocking {
            val before = app.container.settings.settings.first()
            check(!before.lockPinEnabled && !before.lockBiometricEnabled) {
                "This test requires the unlocked SDK fixture installation; it never replaces a lock."
            }
            beforePreferences = settingsStore.data.first()
            settingsStore.edit {
                it[onboardingKey] = true
                it[languageKey] = AppLanguage.FRENCH.code
                it[scanKey] = false
            }
            previousUpdates = app.updates.state.value
            app.updates.configure(automatic = false,
                download = previousUpdates!!.autoDownload, wifiOnly = previousUpdates!!.wifiOnly)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java).also { activity ->
            activity.onActivity {
                previousOrientation = it.requestedOrientation
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        compose.waitUntil(10_000) { app.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
    }

    @After fun restore() {
        try {
            scenario?.let { activity ->
                try { activity.onActivity { it.requestedOrientation = previousOrientation } }
                finally { activity.close() }
            }
        } finally {
            runBlocking {
                beforePreferences?.let { before ->
                    settingsStore.edit {
                        it.restoreKey(before, onboardingKey)
                        it.restoreKey(before, languageKey)
                        it.restoreKey(before, scanKey)
                    }
                }
                previousUpdates?.let { before ->
                    app.updates.configure(before.automatic, before.autoDownload, before.wifiOnly)
                }
            }
            AppLocalizer.language = previousLanguage
        }
    }

    @Test fun landscapeKeyboardKeepsEnteredQueryVisibleAndEditable() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Jeux").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Jeux").performClick()
        compose.onNodeWithText("Rechercher un jeu").performClick()
        val field = compose.onNode(hasSetTextAction() and hasText("Rechercher"))
        field.performClick()
        val query = "Été gypq lisible"
        field.performTextInput(query)
        compose.waitUntil(10_000) { readWindow().imeBottom > 0 }
        compose.waitForIdle()
        val fontNoticeAcknowledged = awaitStableKeyboard()
        val window = readWindow()
        val node = field.fetchSemanticsNode()
        val bounds = node.boundsInWindow
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val directory = File(app.cacheDir, "global-review-ui").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "search-ime-query.txt").writeText(buildString {
            appendLine(app.resources.configuration)
            appendLine("$window fieldWindow=$bounds fieldRoot=${node.boundsInRoot}")
            appendLine("fontNoticeAcknowledged=$fontNoticeAcknowledged")
            layouts.forEachIndexed { index, layout ->
                appendLine("layout[$index] text=${layout.layoutInput.text.text} size=${layout.size} " +
                    "paragraphHeight=${layout.multiParagraph.height} lineCount=${layout.lineCount} " +
                    "firstBaseline=${layout.firstBaseline} lastBaseline=${layout.lastBaseline} " +
                    "overflowHeight=${layout.didOverflowHeight} overflowWidth=${layout.didOverflowWidth}")
                appendLine("fontSize=${layout.layoutInput.style.fontSize} lineHeight=${layout.layoutInput.style.lineHeight} " +
                    "constraints=${layout.layoutInput.constraints}")
                for (line in 0 until layout.lineCount) {
                    appendLine("line[$line] top=${layout.getLineTop(line)} baseline=${layout.getLineBaseline(line)} " +
                        "bottom=${layout.getLineBottom(line)} start=${layout.getLineStart(line)} end=${layout.getLineEnd(line)}")
                }
                layout.layoutInput.text.text.forEachIndexed { offset, character ->
                    appendLine("character[$offset]=$character box=${layout.getBoundingBox(offset)}")
                }
            }
            appendLine(roots.fetchSemanticsNodes().indices.joinToString("\n\n") { roots[it].printToString() })
        })
        File(directory, "search-ime-query.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals("MainActivity must use its manifest's adjustResize contract",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            window.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
        field.assertIsDisplayed().assertTextContains(query).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Retour").assertIsDisplayed().assertHasClickAction()
        assertTrue("The editable field must stay below the status bar", bounds.top >= window.statusTop - 1f)
        assertTrue("The editable field must stay above the actual IME", bounds.bottom <= window.height - window.imeBottom + 1f)
        assertTrue("The entered text must have a visible text layout", layouts.any { it.layoutInput.text.text == query && !it.didOverflowHeight })
        field.performImeAction()
        compose.waitUntil(10_000) { readWindow().imeBottom == 0 }
        field.assertTextContains(query).assertHeightIsAtLeast(56.dp)
        compose.onNodeWithContentDescription("Retour").assertIsDisplayed().performClick()
        compose.onNodeWithText("Rechercher un jeu").assertIsDisplayed()
    }

    private fun awaitStableKeyboard(): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previousFlags = automation.serviceInfo.flags
        var acknowledged = false
        var previousWindow: WindowState? = null
        var stableSince = SystemClock.elapsedRealtime()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            compose.waitUntil(10_000) {
                val notice = keyboardFontNotice(automation)
                if (notice != null) {
                    if (!acknowledged) {
                        var button = notice.findAccessibilityNodeInfosByText("OK").firstOrNull {
                            it.text?.toString() == "OK" && it.isVisibleToUser && it.isEnabled
                        }
                        while (button != null && !button.isClickable && button.packageName?.toString() == GBOARD_PACKAGE) {
                            button = button.parent
                        }
                        assertNotNull("The known Gboard font notice must expose its OK action", button)
                        assertEquals(GBOARD_PACKAGE, button!!.packageName?.toString())
                        assertTrue("Gboard must accept the font notice's OK action",
                            button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        acknowledged = true
                    }
                    previousWindow = null
                    stableSince = SystemClock.elapsedRealtime()
                    false
                } else {
                    val current = readWindow()
                    if (current != previousWindow || current.imeBottom == 0) {
                        previousWindow = current
                        stableSince = SystemClock.elapsedRealtime()
                    }
                    current.imeBottom > 0 && SystemClock.elapsedRealtime() - stableSince >= 500
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
        return acknowledged
    }

    private fun keyboardFontNotice(automation: UiAutomation): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return automation.windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .mapNotNull { it.root }
            .firstOrNull { root ->
                root.packageName?.toString() == GBOARD_PACKAGE &&
                    root.findAccessibilityNodeInfosByText("Keyboard font size updated").any {
                        it.isVisibleToUser && it.text?.toString()?.contains("Keyboard font size updated", ignoreCase = true) == true
                    }
            }
    }

    private fun readWindow(): WindowState {
        lateinit var result: WindowState
        requireNotNull(scenario).onActivity { activity ->
            val decor = activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor))
            val location = IntArray(2).also(decor::getLocationOnScreen)
            result = WindowState(decor.height,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                activity.window.attributes.softInputMode, location[1])
        }
        return result
    }

    private data class WindowState(val height: Int, val imeBottom: Int, val statusTop: Int,
        val softInputMode: Int, val decorY: Int)

    private fun <T> MutablePreferences.restoreKey(before: Preferences, key: Preferences.Key<T>) {
        val value = before[key]
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        private const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    }
}
