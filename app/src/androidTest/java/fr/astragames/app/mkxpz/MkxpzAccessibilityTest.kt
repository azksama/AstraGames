package fr.astragames.app.mkxpz

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real ACTION_CLICK plus real touch input, using only a new synthetic XP game. */
class MkxpzAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun accessibleGameButtonsActAndPhysicalTouchDoesNotDoubleInput() = runBlocking {
        val id = "mkxpz-accessibility-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { check(mkdirs()) }
        copyAssets("mkxpz/xp", source)
        val storage = MkxpzStorage(context, id)
        val game = GameEntity(id = id, title = "MKXP-Z accessible controls fixture",
            documentUri = Uri.fromFile(source).toString(), physicalPath = source.absolutePath,
            executableName = "Game.exe", engine = "RPG_MAKER_XP", launcher = MkxpzRuntime.LAUNCHER,
            sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)
        val proof = File(storage.gameDirectory, "astra-proof.json")
        fun state() = runCatching { JSONObject(proof.readText()) }.getOrNull()
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val nativeProcessName = "${context.packageName}:mkxpz"
        var completed = false
        try {
            assertEquals(LaunchResult.Success, MkxpzRuntime.launch(context, game, null))
            await("native first frame") { state()?.optString("event") == "ready" }
            val nativePid = manager.runningAppProcesses?.firstOrNull { it.processName == nativeProcessName }?.pid
            assertNotNull("The new fixture must have an observable native process", nativePid)
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            assertEquals(pageSize, state()!!.getLong("nativePageSize"))
            InstrumentationRegistry.getArguments().getString("expectedPageSize")?.let {
                assertEquals(it.toLong(), pageSize)
            }

            accessibleClick("▶")
            await("ACTION_CLICK right reaches RGSS exactly once") { state()?.optInt("count") == 1 }
            SystemClock.sleep(300)
            assertEquals(1, state()!!.getInt("count"))

            touch("▶")
            await("physical right reaches RGSS exactly once") { state()?.optInt("count") == 2 }
            SystemClock.sleep(300)
            assertEquals("Touch UP must not generate a second accessibility click", 2, state()!!.getInt("count"))

            accessibleClick("A")
            await("ACTION_CLICK A saves") { state()?.optString("event") == "saved" }
            assertEquals(MarshalValue.IntValue(2), Marshal.load(File(storage.gameDirectory, "Save01.rxdata").readBytes()))
            capture("saved")
            accessibleClick("B")
            await("ACTION_CLICK B exits") { state()?.optString("event") == "exit" }
            // Ruby writes "exit" before SDL destroys its Activity/process. Observe
            // this fixture's actual PID disappearing instead of racing the next game.
            await("fixture native process exits") {
                manager.runningAppProcesses?.none { it.pid == nativePid && it.processName == nativeProcessName } == true
            }
            File(context.getExternalFilesDir(null), "mkxpz-accessibility-result.json").writeText(JSONObject()
                .put("gameId", id).put("nativePid", nativePid).put("nativeProcessExited", true)
                .put("accessibleRightCount", 1).put("physicalRightCount", 2)
                .put("accessibleSaveValue", 2).put("accessibleExit", true)
                .put("nativePageSize", pageSize).toString(2))
            completed = true
        } finally {
            // A failed ACTION_CLICK must not leave its game in front of later tests.
            // Only an original Intent naming this exact private bootstrap is owned.
            val expected = File(storage.directory, "bootstrap").canonicalFile
            manager.appTasks.filter { task -> runCatching {
                val launch = task.taskInfo.baseIntent
                launch.component == ComponentName(context, MkxpzActivity::class.java) &&
                    launch.getStringExtra("bootstrap")?.let { File(it).canonicalFile } == expected
            }.getOrDefault(false) }.forEach { it.finishAndRemoveTask() }
            if (completed) {
                check(source.deleteRecursively())
                check(storage.directory.deleteRecursively())
            }
        }
    }

    private fun button(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val root = automation.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != context.packageName) return null
        fun find(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 32 || !node.refresh()) return null
            if (node.isVisibleToUser && node.isEnabled && node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        return find(root, 0)
    }

    private fun accessibleClick(label: String) {
        automation.waitForIdle(750, 10_000)
        var target: AccessibilityNodeInfo? = null
        await("accessible button $label") { target = button(label); target != null }
        assertTrue("ACTION_CLICK accepted by $label", target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun touch(label: String) {
        automation.waitForIdle(750, 10_000)
        var previous: Rect? = null
        var stableSince = 0L
        var ready: Rect? = null
        await("stable native touch target $label") {
            val current = button(label)?.let { Rect().also(it::getBoundsInScreen) }
            val now = SystemClock.elapsedRealtime()
            if (current == null || current != previous) {
                previous = current
                stableSince = now
                false
            } else if (now - stableSince < 600) false
            else {
                val latest = button(label)?.let { Rect().also(it::getBoundsInScreen) }
                if (latest != current) { previous = latest; stableSince = now; false }
                else { ready = latest; true }
            }
        }
        val bounds = requireNotNull(ready)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(100)
        }
    }

    private fun await(description: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        capture("failure")
        fail("Timeout: $description")
    }

    private fun capture(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "mkxpz-accessibility-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun copyAssets(path: String, destination: File) {
        val assets = instrumentation.context.assets
        for (name in assets.list(path).orEmpty()) {
            val child = "$path/$name"
            val target = File(destination, name)
            if (assets.list(child).orEmpty().isNotEmpty()) { target.mkdirs(); copyAssets(child, target) }
            else assets.open(child).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}
