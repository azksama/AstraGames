package fr.astragames.app.windows

import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.winlator.xserver.XKeycode
import fr.astragames.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class WolfControlPresetUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun editorAssignsAComboAndToggleAndExportsThenImportsThroughRealActions() {
        instrumentation.uiAutomation
        val name = "Portable UI ${System.nanoTime()}"
        val store = WolfGameOptions(instrumentation.targetContext, "portable-ui-${System.nanoTime()}")
        var exported: String? = null; var filename: String? = null; var imported = false
        lateinit var controls: WolfTouchControls
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    controls = WolfTouchControls(activity, { _, _ -> }, {}, store,
                        exportPreset = { file, json -> filename = file; exported = json },
                        importPreset = { controls.importPreset(requireNotNull(exported)); imported = true })
                    activity.setContentView(controls); controls.setEditing(true)
                }
                click("Valider"); click("Configurer une combinaison")
                text("Rechercher une touche", "Ctrl gauche"); click("Ctrl gauche")
                click("Valider la combinaison"); click("Action : Maintenir"); click("Bascule : activer / désactiver"); click("Enregistrer")
                await { store.action("accept", XKeycode.KEY_ENTER).mode == WolfControlActionMode.TOGGLE }
                assertEquals(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_ENTER), store.action("accept", XKeycode.KEY_ENTER).keys)
                click("Presets de contrôles"); click("Exporter la disposition")
                text("Nom du preset", name); click("Exporter")
                await { exported != null }; assertTrue(requireNotNull(filename).endsWith(".json"))
                val document = WolfControlPresetCodec.decode(requireNotNull(exported)); assertEquals(name, document.name)
                click("Presets de contrôles"); click("Importer un preset")
                await { imported }; assertTrue(name in WolfControlPresets(instrumentation.targetContext).names())
                click("Terminer")
            }
        } finally { WolfControlPresets(instrumentation.targetContext).delete(name) }
    }
    private fun click(label: String) { await { node(label, true)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } }
    private fun text(label: String, value: String) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        await { node(label)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) == true }
    }
    private fun node(label: String, clickable: Boolean = false): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                if (!clickable) return node
                var parent: AccessibilityNodeInfo? = node
                while (parent != null) { if (parent.isClickable && parent.className != "android.widget.EditText") return parent; parent = parent.parent }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { result -> return result } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 7000
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; SystemClock.sleep(80) }
        assertTrue("Control editor action did not complete", condition())
    }
}
