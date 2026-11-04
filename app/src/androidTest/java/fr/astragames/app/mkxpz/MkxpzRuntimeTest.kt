package fr.astragames.app.mkxpz

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.MainActivity
import fr.astragames.app.AstraApplication
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.saves.Marshal
import fr.astragames.app.data.saves.MarshalValue
import fr.astragames.app.data.saves.SaveFinder
import fr.astragames.app.launcher.CompatibilityDiagnostic
import fr.astragames.app.launcher.JoiPlayLauncher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real native execution; no assume/skip if the engine is missing or broken. */
class MkxpzRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun xpRendersInputsSavesResumesAndReloads() = exercise("xp", "RPG_MAKER_XP", "rxdata", 1)
    @Test fun vxRendersInputsSavesResumesAndReloads() = exercise("vx", "RPG_MAKER_VX", "rvdata", 2)
    @Test fun aceRendersInputsSavesResumesAndReloads() = exercise("ace", "RPG_MAKER_VX_ACE", "rvdata2", 3)
    @Test fun xpUppercaseIniRendersInputsSavesResumesAndReloads() =
        exercise("xp", "RPG_MAKER_XP", "rxdata", 1, iniName = "GAME.INI", proofName = "xp-uppercase-ini",
            existingSourceSave = true)
    @Test fun aceLowercaseExecutableRendersInputsSavesResumesAndReloads() =
        exercise("ace", "RPG_MAKER_VX_ACE", "rvdata2", 3, executable = "game.exe", proofName = "ace-lowercase-exe")

    private fun exercise(mode: String, engine: String, extension: String, rgss: Int,
                         executable: String = "Game.exe", iniName: String = "Game.ini",
                         proofName: String = mode, existingSourceSave: Boolean = false): Unit = runBlocking {
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        InstrumentationRegistry.getArguments().getString("expectedPageSize")?.let { expected ->
            assertEquals("The selected emulator must use the requested page size", expected.toLong(), pageSize)
        }
        assertNull("Native engine must be packaged", MkxpzRuntime.unavailableReason(context))
        val id = "mkxpz-$proofName-${System.currentTimeMillis()}"
        val source = File(context.filesDir, "mkxpz-fixtures/$id").apply { mkdirs() }
        copyAssets("mkxpz/$mode", source)
        val fixtureExecutable = File(source, "Game.exe")
        if (executable != "Game.exe" && fixtureExecutable.isFile) {
            check(fixtureExecutable.renameTo(File(source, executable)))
        }
        val sourceIni = File(source, iniName)
        if (iniName != "Game.ini") check(File(source, "Game.ini").renameTo(sourceIni))
        val sourceIniBytes = sourceIni.readBytes()
        val sourceSave = File(source, "Save01.$extension")
        val sourceSaveBytes = if (existingSourceSave) Marshal.dump(MarshalValue.IntValue(0)).also(sourceSave::writeBytes) else null
        val execName = executable.substringBeforeLast('.')
        val game = GameEntity(id = id, title = "Astra MKXP-Z $proofName validation", documentUri = Uri.fromFile(source).toString(),
            physicalPath = source.path, executableName = executable, engine = engine, launcher = MkxpzRuntime.LAUNCHER,
            sourceId = "mkxpz-fixtures", dateAdded = 1, lastModified = 1, fingerprint = id)
        // Keep the fixture visible in the actual catalogue for manual review after this test.
        (context.applicationContext as AstraApplication).container.dao.upsertGame(game)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(800)
        val launcher = JoiPlayLauncher()
        val diagnostic = CompatibilityDiagnostic(launcher).inspect(context, game, null)
        assertTrue(diagnostic.toString(), diagnostic.canLaunch)
        val folder = MkxpzStorage(context, id).gameDirectory
        val proof = File(folder, "astra-proof.json")
        fun state() = runCatching { JSONObject(proof.readText()) }.getOrNull()
        assertEquals(LaunchResult.Success, launcher.launch(context, game))
        val initialEvent = if (existingSourceSave) "loaded" else "ready"
        await("RGSS $rgss first frame") { state()?.optString("event") == initialEvent }
        assertEquals(0, state()!!.getInt("count"))
        val runtimeConfig = JSONObject(File(folder.parentFile, "bootstrap/mkxp.json").readText())
        assertEquals(execName, runtimeConfig.getString("execName"))
        assertArrayEquals(sourceIniBytes, File(folder, "$execName.ini").readBytes())
        assertEquals(rgss, state()!!.getInt("rgss"))
        assertEquals("Ruby must observe the actual process page size", pageSize, state()!!.getLong("nativePageSize"))
        assertEquals("passed", state()!!.getString("memoryProbe"))
        assertTrue("Compaction must actually move objects", state()!!.getInt("gcMoved") > 0)
        assertTrue(state()!!.getInt("frame") >= 3)
        capture("$proofName-ready")
        tap("▶")
        await("touch right") { state()?.optInt("count") == 1 }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        await("keyboard right") { state()?.optInt("count") == 2 }
        tap("A")
        await("Marshal save") { state()?.optString("event") == "saved" }
        assertTrue(File(folder, "Save01.$extension").length() > 0)
        assertEquals("bgm_requested", state()!!.getString("audio"))
        assertTrue("Private saves must be discoverable", SaveFinder(context).locations(game, Uri.fromFile(source))
            .any { it.uri == Uri.fromFile(folder).toString() })
        capture("$proofName-saved")
        // Native lifecycle pause/resume, preserving the same running VM and state.
        key(KeyEvent.KEYCODE_HOME)
        SystemClock.sleep(800)
        context.startActivity(MkxpzActivity.intent(context, File(folder.parentFile, "bootstrap")))
        await("resume controls") { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Quitter")?.isNotEmpty() == true }
        tap("▶")
        await("input after resume") { state()?.optInt("count") == 3 }
        // Do not save 3. On restart the persisted value must still be exactly 2.
        tap("B")
        await("Ruby exit") { state()?.optString("event") == "exit" }
        await("native activity gone") { automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Quitter")?.isEmpty() == true }
        SystemClock.sleep(600)
        assertEquals(LaunchResult.Success, launcher.launch(context, game))
        await("load saved Marshal") { state()?.optString("event") == "loaded" }
        assertEquals(2, state()!!.getInt("count"))
        assertEquals(pageSize, state()!!.getLong("nativePageSize"))
        assertEquals("passed", state()!!.getString("memoryProbe"))
        assertTrue(state()!!.getInt("gcMoved") > 0)
        capture("$proofName-reloaded")
        assertEquals(listOf(iniName), source.listFiles().orEmpty()
            .filter { it.name.equals("$execName.ini", ignoreCase = true) }.map { it.name })
        assertArrayEquals("Source INI must retain its authored bytes", sourceIniBytes, sourceIni.readBytes())
        assertEquals(MarshalValue.IntValue(2), Marshal.load(File(folder, "Save01.$extension").readBytes()))
        if (sourceSaveBytes != null) {
            assertArrayEquals("Source save must retain its authored bytes", sourceSaveBytes, sourceSave.readBytes())
            assertEquals(MarshalValue.IntValue(0), Marshal.load(sourceSave.readBytes()))
        } else {
            assertFalse("Source must remain untouched", sourceSave.exists())
        }
        val receipt = JSONObject(proof.readText()).put("gameId", id).put("abi", android.os.Build.SUPPORTED_ABIS[0])
            .put("pageSize", pageSize).put("androidApi", android.os.Build.VERSION.SDK_INT)
            .put("buildFingerprint", android.os.Build.FINGERPRINT)
            .put("sourceIni", iniName).put("executable", executable).put("runtimeExecName", execName)
            .put("runtimeIni", "$execName.ini").put("sourceIniPreserved", true)
            .put("existingSourceSave", existingSourceSave).put("sourceSavePreserved", true)
        File(context.getExternalFilesDir(null), "mkxpz-$proofName-result.json").writeText(receipt.toString(2))
        tap("B")
        await("final Ruby exit") { state()?.optString("event") == "exit" }
        SystemClock.sleep(1000)
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
        capture("failure-${description.replace(' ', '-')}")
        fail("Timeout: $description")
    }

    private fun tap(text: String) {
        var rect: Rect? = null
        await("button $text") {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString() == text }
            rect = node?.let { Rect().also(it::getBoundsInScreen) }
            rect != null
        }
        val bounds = rect!!
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(100)
        }
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), true)
        SystemClock.sleep(100)
        automation.injectInputEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0), true)
    }

    private fun capture(name: String) {
        SystemClock.sleep(3000) // Let the informational toast disappear from review screenshots.
        automation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "mkxpz-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
