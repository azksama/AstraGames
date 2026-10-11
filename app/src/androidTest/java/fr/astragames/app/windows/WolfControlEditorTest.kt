package fr.astragames.app.windows

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.xserver.XKeycode
import fr.astragames.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class WolfControlEditorTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun userAddsSearchesEditsSavesAndLoadsControlsAcrossGames() {
        val id = "editor-${System.nanoTime()}"
        val name = "Preset $id"
        val first = WolfGameOptions(context, id)
        first.save(WolfOptions(opacity = 31, size = 120))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    install(activity, WolfTouchControls(activity, { _, _ -> }, {}, first).apply { setEditing(true) })
                }
                click("Ajouter un bouton de contrôle")
                text("Nom du bouton", "Potion")
                click("Touche : Entrée")
                text("Rechercher une touche", "F8")
                capture("wolf-key-picker")
                click("F8")
                click("Ajouter")
                await { first.customControls().size == 1 }
                val control = first.customControls().single()
                assertEquals("Potion", control.label); assertEquals(XKeycode.KEY_F8, control.code)
                first.position(control.id, false, .2f, .7f); first.position(control.id, true, .8f, .3f)
                click("Presets de contrôles")
                capture("wolf-presets-empty")
                click("Enregistrer la disposition")
                text("Nom du preset", name)
                click("Enregistrer")
                await { name in WolfControlPresets(context).names() }
                click("Potion")
                click("Supprimer")
                await { first.customControls().isEmpty() }
                val second = WolfGameOptions(context, "$id-other")
                second.save(WolfOptions(runtime = WolfRuntimeMode.WINLATOR, resolution = "1920x1080"))
                scenario.onActivity { activity ->
                    install(activity, WolfTouchControls(activity, { _, _ -> }, {}, second).apply { setEditing(true) })
                }
                click("Presets de contrôles"); click(name); click("Charger ce preset")
                await { second.customControls().size == 1 }
                assertEquals(31, second.read().opacity); assertEquals(120, second.read().size)
                assertEquals(WolfRuntimeMode.WINLATOR, second.read().runtime); assertEquals("1920x1080", second.read().resolution)
                assertEquals(.2f to .7f, second.position(control.id, false)); assertEquals(.8f to .3f, second.position(control.id, true))
                capture("wolf-controls-editor")
                click("Terminer")
            }
        } finally { WolfControlPresets(context).delete(name) }
    }

    @Test fun deletingCustomButtonsAndLoadingPresetReleaseSharedHeldKeys() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario -> scenario.onActivity { activity ->
            val store = WolfGameOptions(activity, "held-custom-${System.nanoTime()}")
            val first = store.addControl("One F8", XKeycode.KEY_F8)
            val second = store.addControl("Two F8", XKeycode.KEY_F8)
            val emissions = mutableListOf<Pair<XKeycode, Boolean>>()
            val controls = WolfTouchControls(activity, { code, down -> emissions += code to down }, {}, store)
            activity.setContentView(controls)
            fun touch(label: String, action: Int) {
                val button = descendants(controls).filterIsInstance<Button>().single { it.contentDescription == label }
                MotionEvent.obtain(0, 0, action, 10f, 10f, 0).let { button.dispatchTouchEvent(it); it.recycle() }
            }
            touch("One F8", MotionEvent.ACTION_DOWN); touch("Two F8", MotionEvent.ACTION_DOWN)
            assertEquals(listOf(XKeycode.KEY_F8 to true), emissions)
            touch("One F8", MotionEvent.ACTION_UP)
            assertEquals(1, emissions.size)
            store.removeControl(second.id); controls.reload()
            assertEquals(listOf(XKeycode.KEY_F8 to true, XKeycode.KEY_F8 to false), emissions)
            assertTrue(descendants(controls).none { it.contentDescription == "Two F8" })
            val preset = store.controlLayout()
            touch("One F8", MotionEvent.ACTION_DOWN)
            store.loadControlLayout(preset); controls.reload()
            assertEquals(XKeycode.KEY_F8 to false, emissions.last())
            store.removeControl(first.id)
        } }
    }

    @Test fun loadingShowsCurrentPhaseAndActionsRemainAccessibleWithLargeText() {
        var cancelled = false; var diagnosed = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val loading = WolfLoadingView(activity, { cancelled = true }, { diagnosed = true })
                install(activity, loading)
                loading.update(6, "Préparation des premières ressources…")
                assertNull(loading.stalled()); assertEquals("Préparation des premières ressources…", loading.status.text)
                assertTrue(descendants(loading).filterIsInstance<android.widget.TextView>().any { it.text == "ÉTAPE 6 SUR 8" })
            }
            capture("wolf-loading")
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<android.widget.ScrollView>().single().fullScroll(View.FOCUS_DOWN)
            }
            capture("wolf-loading-actions")
            scenario.onActivity { activity ->
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<Button>().single { it.text == "Voir le diagnostic" }.performClick()
                views.filterIsInstance<Button>().single { it.text == "Annuler le lancement" }.performClick()
            }
            assertTrue(cancelled); assertTrue(diagnosed)
        }
    }

    private fun click(label: String) { await { node(label, clickable = true)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } }
    private fun text(label: String, value: String) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        await { node(label)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true }
    }
    private fun node(label: String, clickable: Boolean = false): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val matches = node.text?.toString()?.equals(label, true) == true || node.contentDescription?.toString()?.equals(label, true) == true
            if (matches && (!clickable || node.className != "android.widget.EditText" && node.isClickable)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(80) }
        assertTrue("Expected editor interaction did not complete", condition())
    }
    private fun capture(prefix: String) {
        if (InstrumentationRegistry.getArguments().getString("wolfUiCapture") != "true") return
        // Settle the native edit hint before capturing a dialog or its actions.
        SystemClock.sleep(if (prefix.startsWith("wolf-loading")) 300 else 5000)
        val label = InstrumentationRegistry.getArguments().getString("captureLabel") ?: "phone"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), "$prefix-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun install(activity: MainActivity, view: View) {
        val root = android.widget.FrameLayout(activity).apply { setBackgroundColor(WolfViewStyle.background) }
        root.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        activity.setContentView(root)
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
