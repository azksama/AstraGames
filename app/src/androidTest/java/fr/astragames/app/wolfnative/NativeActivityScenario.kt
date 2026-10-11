package fr.astragames.app.wolfnative

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** Observe the actual Activity lifecycle without requiring a continuously drawing game to idle. */
internal class NativeActivityScenario private constructor(private val activity: WolfNativeActivity) : Closeable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val closed = AtomicBoolean()

    val state: Lifecycle.State get() {
        var result = Lifecycle.State.INITIALIZED
        instrumentation.runOnMainSync { result = activity.lifecycle.currentState }
        return result
    }

    fun onActivity(action: (WolfNativeActivity) -> Unit): NativeActivityScenario {
        check(!closed.get()) { "Native test Activity is closed" }
        instrumentation.runOnMainSync { action(activity) }
        return this
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        instrumentation.runOnMainSync { if (!activity.isDestroyed) activity.finish() }
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        check(state == Lifecycle.State.DESTROYED) { "Native test Activity did not finish within five seconds" }
    }

    companion object {
        fun launch(intent: Intent): NativeActivityScenario {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(WolfNativeActivity::class.java.name, null, false)
            val activity = try {
                instrumentation.targetContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForMonitorWithTimeout(monitor, 10_000) as? WolfNativeActivity
                    ?: error("Native test Activity did not launch within ten seconds")
            } finally { instrumentation.removeMonitor(monitor) }
            val scenario = NativeActivityScenario(activity)
            try {
                val deadline = SystemClock.elapsedRealtime() + 10_000
                while (scenario.state !in setOf(Lifecycle.State.RESUMED, Lifecycle.State.DESTROYED) &&
                    SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
                check(scenario.state in setOf(Lifecycle.State.RESUMED, Lifecycle.State.DESTROYED)) {
                    "Native test Activity did not reach its lifecycle state within ten seconds"
                }
                return scenario
            } catch (failure: Throwable) { scenario.close(); throw failure }
        }
    }
}

internal inline fun <T> NativeActivityScenario.useNativeGame(block: (NativeActivityScenario) -> T): T = use(block)

/** Click the real dialog node with a bounded SDK action, without an Espresso UI-idle precondition. */
internal fun clickNativeDialog(text: String, timeoutMs: Long = 5_000) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    var observed = emptyList<String>()
    while (SystemClock.elapsedRealtime() < deadline) {
        val root = automation.rootInActiveWindow
        val nodes = root?.findAccessibilityNodeInfosByText(text).orEmpty()
        observed = nodes.mapNotNull { it.text?.toString() }
        for (node in nodes) {
            // Framework dialog buttons expose their transformed ALL CAPS label to accessibility.
            if (!node.text?.toString().equals(text, ignoreCase = true)) continue
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(80)
                    return
                }
                target = target.parent
            }
        }
        SystemClock.sleep(40)
    }
    error("Native dialog '$text' was not clickable within $timeoutMs ms; accessibility matches=$observed")
}
