package fr.astragames.app.wolfnative

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.wolf.NativeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfNativeTextInputInstrumentedTest {
    @Test fun keyboardCompletionUsesUnicodeCharacterLimitAndResumesTheRealEvent() = input(false)
    @Test fun cancellingPreservesTheOriginalStringAndReportsMinusOneToTheEvent() = input(true)

    private fun input(cancel: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.cacheDir, "native-input-${UUID.randomUUID()}").apply { mkdirs() }
        val id = root.name
        NativeGameFixture.writeTextInput(root)
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE))
        val field = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        fun activity() = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<WolfNativeActivity>().firstOrNull()
        fun frame(): NativeFrame? {
            var result: NativeFrame? = null
            instrumentation.runOnMainSync { activity()?.let { a -> descendants(a.window.decorView)
                .filterIsInstance<WolfNativeView>().firstOrNull()?.let { result = field.get(it) as NativeFrame? } } }
            return result
        }
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (condition()) return
                assertNull("Native input failed at $label", frame()?.diagnostic)
                SystemClock.sleep(40)
            }
            fail("Native input did not reach $label; tick=${frame()?.tick}; dialog=${frame()?.dialog}")
        }
        fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                node ?: return null
                if (predicate(node)) return node
                for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
                return null
            }
            return find(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun editable() = node { it.isEditable }
        try {
            context.startActivity(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", "Text input test")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("native keyboard") { editable() != null }
            // The game keeps updating during text entry. Global Espresso idle waits
            // cannot settle here; use the SDK's actual accessibility text/click actions.
            val entered = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "😺abcd") }
            assertTrue(requireNotNull(editable()).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, entered))
            await("Unicode limit") { editable()?.text?.toString() == "😺abc" }
            val label = if (cancel) "Annuler" else "Valider"
            val button = requireNotNull(node { it.isClickable && it.text?.toString()?.equals(label, true) == true })
            assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("event continuation") { frame()?.dialog != null }
            assertEquals(if (cancel) "prior|-1" else "😺abc|0", frame()?.dialog?.text)
        } finally {
            instrumentation.runOnMainSync { activity()?.finish() }
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
