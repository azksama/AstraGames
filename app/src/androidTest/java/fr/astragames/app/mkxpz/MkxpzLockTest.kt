package fr.astragames.app.mkxpz

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.AstraApplication
import fr.astragames.app.MainActivity
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.launcher.JoiPlayLauncher
import fr.astragames.app.settings.AppLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Run only on the dedicated SDK fixture AVD; refuses to replace an existing user's lock. */
class MkxpzLockTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun backgroundReturnRequiresAuthenticationAndPreservesRunningGame() = runBlocking {
        val app = context.applicationContext as AstraApplication
        val settings = app.container.settings
        val before = settings.settings.first()
        check(!before.lockPinEnabled && !before.lockBiometricEnabled) {
            "Use an unlocked SDK fixture AVD; this test never replaces an existing lock."
        }
        @Suppress("DEPRECATION")
        assertFalse(context.packageManager.getActivityInfo(ComponentName(context, MkxpzUnlockActivity::class.java), 0).exported)
        val id = "mkxpz-lock-${UUID.randomUUID()}"
        val source = File(context.cacheDir, id).apply { check(mkdirs()) }
        copyAssets("mkxpz/xp", source)
        val game = GameEntity(id = id, title = "MKXP-Z lock fixture", documentUri = Uri.fromFile(source).toString(),
            physicalPath = source.absolutePath, executableName = "Game.exe", engine = "RPG_MAKER_XP",
            launcher = MkxpzRuntime.LAUNCHER, sourceId = id, dateAdded = 1, lastModified = 1, fingerprint = id)
        val storage = MkxpzStorage(context, id)
        val proof = File(storage.gameDirectory, "astra-proof.json")
        fun state() = runCatching { JSONObject(proof.readText()) }.getOrNull()
        val previousAccessibilityFlags = automation.serviceInfo.flags
        var completed = false
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            settings.setLanguage(AppLanguage.FRENCH)
            settings.setLockOnBackground(true)
            settings.setPinLock("4826")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(600)
            assertEquals(LaunchResult.Success, JoiPlayLauncher().launch(context, game))
            await("native first frame") { state()?.optString("event") == "ready" }
            tap("▶")
            await("native input before lock") { state()?.optInt("count") == 1 }
            tap("A")
            await("private save before lock") { state()?.optString("event") == "saved" }
            val save = File(storage.gameDirectory, "Save01.rxdata")
            val savedBytes = save.readBytes()

            key(KeyEvent.KEYCODE_HOME)
            await("Android launcher visible") {
                automation.rootInActiveWindow?.packageName?.toString()?.contains("launcher", ignoreCase = true) == true
            }
            // Let Android complete its Home transition and deliver onStop; returning
            // during the transition only pauses/resumes the same foreground activity.
            SystemClock.sleep(2000)
            val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val task = manager.appTasks.firstOrNull { it.taskInfo.topActivity?.className == MkxpzActivity::class.java.name }
            assertNotNull("Native task must survive backgrounding", task)
            task!!.moveToFront()
            await("native lock gate") { hasText("Session MKXP-Z verrouillée") }
            assertFalse("Game controls must be hidden from accessibility", hasText("▶"))
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            SystemClock.sleep(300)
            assertEquals(1, state()!!.getInt("count"))
            capture("locked")

            key(KeyEvent.KEYCODE_BACK)
            await("exit confirmation while locked") { hasText("Quitter le jeu ?") }
            tap("Continuer")
            await("cancel exit keeps lock") { hasText("Session MKXP-Z verrouillée") }
            assertEquals(1, state()!!.getInt("count"))

            tap("Déverrouiller")
            await("authentication screen") { hasText("Astra est verrouillé") }
            capture("auth-open")
            hideKeyboardIfShown()
            assertTrue("Dismissing the IME must keep authentication open", hasText("Astra est verrouillé"))
            capture("auth-before-cancel")
            key(KeyEvent.KEYCODE_BACK)
            await("cancel keeps native lock") { hasText("Session MKXP-Z verrouillée") }
            assertFalse(hasText("▶"))
            capture("cancelled")
            tap("Déverrouiller")
            enterPin("1111")
            tap("Déverrouiller")
            await("wrong PIN rejected") { hasText("Code incorrect") }
            assertFalse(hasText("▶"))
            assertEquals(1, state()!!.getInt("count"))
            capture("wrong-pin")

            enterPin("4826")
            tap("Déverrouiller")
            await("native game unlocked") { hasText("▶") && !hasText("Session MKXP-Z verrouillée") }
            SystemClock.sleep(400)
            assertEquals("Inputs while locked must not be queued into RGSS", 1, state()!!.getInt("count"))
            assertArrayEquals("Unlock preserves the private save", savedBytes, save.readBytes())
            tap("▶")
            await("same native session accepts input") { state()?.optInt("count") == 2 }
            capture("resumed")
            File(context.getExternalFilesDir(null), "mkxpz-lock-result.json").writeText(JSONObject()
                .put("cancelStayedLocked", true).put("wrongPinStayedLocked", true)
                .put("inputWhileLockedIgnored", true).put("sameSessionResumed", true)
                .put("savePreserved", true).put("finalCount", state()!!.getInt("count")).toString(2))
            tap("B")
            await("native exit") { state()?.optString("event") == "exit" }
            SystemClock.sleep(700)
            completed = true
        } finally {
            val cleanupFailure = runCatching { finishFixtureTask(storage) }.exceptionOrNull()
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousAccessibilityFlags }
            // Restore only the settings changed here; existing credentials were refused above.
            settings.setPinLock(null)
            settings.setLockOnBackground(before.lockOnBackground)
            settings.setLanguage(before.language)
            // Keep a failed native fixture for diagnosis, never remove a running game's files.
            if (state()?.optString("event") == "exit") {
                check(source.deleteRecursively())
                check(storage.directory.deleteRecursively())
            }
            if (cleanupFailure != null) {
                // Preserve the original test failure and its screenshots if cleanup
                // also fails. A successful scenario must not silently leave its task.
                if (completed) throw cleanupFailure
                android.util.Log.e("MkxpzLockTest", "Could not close owned fixture task", cleanupFailure)
            }
        }
    }

    private fun finishFixtureTask(storage: MkxpzStorage) {
        val manager = context.getSystemService(android.app.ActivityManager::class.java)
        val expectedBootstrap = File(storage.directory, "bootstrap").canonicalFile
        fun owned(task: android.app.ActivityManager.AppTask): Boolean = runCatching {
            val launch = task.taskInfo.baseIntent
            launch.component == ComponentName(context, MkxpzActivity::class.java) &&
                launch.getStringExtra("bootstrap")?.let { File(it).canonicalFile } == expectedBootstrap
        }.getOrDefault(false)
        val tasks = manager.appTasks.filter(::owned)
        if (tasks.isEmpty()) return
        // Never finish an unrelated task or force-stop the application. The unique
        // fixture bootstrap must match the task's original native launch Intent.
        val nativePids = manager.runningAppProcesses.orEmpty()
            .filter { it.processName == "${context.packageName}:mkxpz" }.map { it.pid }.toSet()
        tasks.forEach { it.finishAndRemoveTask() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        fun stillRunning() = manager.appTasks.any(::owned) ||
            manager.runningAppProcesses.orEmpty().any { it.pid in nativePids && it.processName == "${context.packageName}:mkxpz" }
        while (stillRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(!stillRunning()) { "The owned MKXP-Z fixture task did not finish." }
    }

    private fun freshWindows(): List<AccessibilityWindowInfo> {
        // Compose can invalidate descendants without replacing the root. A fresh
        // root alone can therefore still return cached text from an older frame.
        if (android.os.Build.VERSION.SDK_INT >= 34) automation.clearCache()
        return automation.windows
    }

    // The IME can be the active accessibility window while the app remains resumed.
    // Select the uppermost app window (dialog/activity), not the keyboard or a covered screen.
    private fun appRoot(): AccessibilityNodeInfo? = freshWindows().sortedByDescending { it.layer }
        .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == context.packageName }
        ?: automation.rootInActiveWindow?.takeIf { it.packageName?.toString() == context.packageName }

    private fun findNode(root: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var visited = 0
        fun visit(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 48 || ++visited > 2000) return null
            if (android.os.Build.VERSION.SDK_INT < 34 && !node.refresh()) return null
            if (predicate(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)?.let { return it }
            return null
        }
        return visit(root, 0)
    }

    private fun hasText(text: String) = findNode(appRoot()) {
        it.isVisibleToUser && it.text?.toString()?.equals(text, ignoreCase = true) == true
    } != null

    private fun hideKeyboardIfShown() {
        fun visible() = freshWindows().any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true
        }
        // The field can appear before its IME window. Observe the settled window
        // state before deciding whether Back means "hide keyboard" or "cancel".
        waitForUiIdle()
        var previous: Boolean? = null
        var stableSince = 0L
        await("keyboard visibility settled") {
            val current = visible()
            val now = SystemClock.elapsedRealtime()
            if (current != previous) {
                previous = current
                stableSince = now
                false
            } else now - stableSince >= 600
        }
        if (previous == true) {
            key(KeyEvent.KEYCODE_BACK)
            await("keyboard hidden") { !visible() }
            waitForUiIdle()
        }
        assertFalse("Keyboard must be hidden before the next app action", visible())
    }

    private fun enterPin(pin: String) {
        var field: AccessibilityNodeInfo? = null
        await("PIN field") { field = findNode(appRoot()) { it.isEditable }; field != null }
        assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin)
        }))
        hideKeyboardIfShown()
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

    private fun await(description: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        capture("failure")
        dumpWindows()
        fail("Timeout: $description")
    }

    private fun tap(text: String) {
        var bounds: Rect? = null
        var target: AccessibilityNodeInfo? = null
        await("button $text") {
            val node = findNode(appRoot()) {
                // The platform SDL theme uppercases native Button labels; Compose
                // authentication buttons preserve case and expose virtual descendants.
                it.isVisibleToUser && it.text?.toString()?.equals(text, ignoreCase = true) == true
            }
            target = node
            bounds = node?.let { Rect().also(it::getBoundsInScreen) }
            bounds != null
        }
        if (text !in setOf("◀", "▲", "▼", "▶", "A", "B")) {
            // Android/Compose controls expose real click actions. Use those while
            // dialogs animate, rather than tapping a rectangle that is still moving.
            // Native game buttons require down/up events and are exercised below.
            var clickable = target
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertNotNull("Visible click action for $text", clickable)
            assertTrue("Click accepted for $text", clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return
        }
        val rect = stableNativeButtonBounds(text)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                rect.centerX().toFloat(), rect.centerY().toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(100)
        }
    }

    private data class NativeButtonGeometry(val windowId: Int, val root: Rect, val button: Rect, val rotation: Int)

    private fun waitForUiIdle() {
        try {
            automation.waitForIdle(750, 10_000)
        } catch (error: java.util.concurrent.TimeoutException) {
            capture("failure")
            dumpWindows()
            throw error
        }
    }

    private fun stableNativeButtonBounds(text: String): Rect {
        // The native frame can be ready before Android finishes rotating its window.
        // Observe the geometry until it settles; never retry an injected game input.
        waitForUiIdle()
        @Suppress("DEPRECATION")
        fun geometry(): NativeButtonGeometry? {
            val root = appRoot()?.takeIf { it.refresh() } ?: return null
            val node = findNode(root) {
                it.isVisibleToUser && it.isEnabled && it.text?.toString()?.equals(text, ignoreCase = true) == true
            }?.takeIf { it.refresh() } ?: return null
            val rootBounds = Rect().also(root::getBoundsInScreen)
            val buttonBounds = Rect().also(node::getBoundsInScreen)
            if (rootBounds.isEmpty || buttonBounds.isEmpty || !rootBounds.contains(buttonBounds)) return null
            val display = context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
            return NativeButtonGeometry(root.windowId, rootBounds, buttonBounds, display.rotation)
        }
        var previous: NativeButtonGeometry? = null
        var stableSince = 0L
        var ready: Rect? = null
        await("stable native button $text") {
            val current = geometry()
            val now = SystemClock.elapsedRealtime()
            if (current == null || current != previous) {
                previous = current
                stableSince = now
                false
            } else if (now - stableSince < 600) {
                false
            } else {
                // Reacquire the node immediately before DOWN, rather than reusing
                // the accessibility rectangle found during the first-frame wait.
                val latest = geometry()
                if (latest != current) {
                    previous = latest
                    stableSince = now
                    false
                } else {
                    ready = Rect(requireNotNull(latest).button)
                    true
                }
            }
        }
        return requireNotNull(ready)
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true)
        SystemClock.sleep(100)
        automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true)
    }

    private fun capture(name: String) {
        automation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "mkxpz-lock-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    private fun dumpWindows() {
        // Capture the app's virtual Compose descendants and the IME separately so
        // a failed selection can be diagnosed without rerunning the native session.
        runCatching {
            File(context.getExternalFilesDir(null), "mkxpz-lock-failure-windows.xml").outputStream().use { output ->
                val xml = android.util.Xml.newSerializer().apply { setOutput(output, "UTF-8") }
                xml.startDocument("UTF-8", true)
                xml.startTag(null, "windows")
                var visited = 0
                fun writeNode(node: AccessibilityNodeInfo?, depth: Int) {
                    if (node == null || depth > 48 || ++visited > 2000) return
                    xml.startTag(null, "node")
                    xml.attribute(null, "class", node.className?.toString().orEmpty())
                    xml.attribute(null, "package", node.packageName?.toString().orEmpty())
                    xml.attribute(null, "text", if (node.isPassword) "[masked]" else node.text?.toString().orEmpty())
                    xml.attribute(null, "description", node.contentDescription?.toString().orEmpty())
                    xml.attribute(null, "clickable", node.isClickable.toString())
                    xml.attribute(null, "enabled", node.isEnabled.toString())
                    xml.attribute(null, "visible", node.isVisibleToUser.toString())
                    xml.attribute(null, "editable", node.isEditable.toString())
                    xml.attribute(null, "bounds", Rect().also(node::getBoundsInScreen).toShortString())
                    for (index in 0 until node.childCount) writeNode(node.getChild(index), depth + 1)
                    xml.endTag(null, "node")
                }
                for (window in freshWindows()) {
                    xml.startTag(null, "window")
                    xml.attribute(null, "type", window.type.toString())
                    xml.attribute(null, "layer", window.layer.toString())
                    xml.attribute(null, "active", window.isActive.toString())
                    xml.attribute(null, "focused", window.isFocused.toString())
                    writeNode(window.root, 0)
                    xml.endTag(null, "window")
                }
                xml.endTag(null, "windows")
                xml.endDocument()
            }
        }
    }
}
