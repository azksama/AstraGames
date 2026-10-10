package fr.astragames.app.wolfnative

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import fr.astragames.wolf.NativeFrame
import fr.astragames.app.core.model.GameEngine
import fr.astragames.app.core.model.LaunchResult
import fr.astragames.app.data.local.GameEntity
import fr.astragames.app.data.local.LaunchProfileEntity
import fr.astragames.app.launcher.JoiPlayLauncher
import fr.astragames.app.windows.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WolfNativeLaunchTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun selectedEngineRoutesToTheRealHostAndExternalProfileIsPreserved() {
        val id = "native-route-${UUID.randomUUID()}"
        val captured = object : ContextWrapper(context) {
            lateinit var launched: Intent
            override fun startActivity(intent: Intent) { launched = intent }
        }
        val game = GameEntity(id, "Wolf launch test", documentUri = "file:///fixture", physicalPath = "/fixture", executableName = "GamePro.exe",
            engine = GameEngine.WOLF_RPG.name, launcher = "JOIPLAY", sourceId = "fixture", dateAdded = 0, lastModified = 0, fingerprint = "fixture")
        val options = WolfGameOptions(context, id)
        try {
            for (mode in WolfRuntimeMode.entries) {
                options.save(WolfOptions(runtime = mode))
                assertEquals(LaunchResult.Success, runBlocking { JoiPlayLauncher().launch(captured, game) })
                val expected = if (mode == WolfRuntimeMode.WINLATOR) WolfRuntimeActivity::class.java.name else WolfNativeActivity::class.java.name
                assertEquals(expected, captured.launched.component!!.className)
                assertEquals("GamePro.exe", captured.launched.getStringExtra("executable")); assertEquals(id, captured.launched.getStringExtra("id"))
                assertTrue(captured.launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            }
            val external = LaunchProfileEntity(id, launcherType = "EXTERNAL", customAction = "test.wolf.RUN", packageName = "test.wolf", arguments = "-test")
            assertEquals(LaunchResult.Success, runBlocking { JoiPlayLauncher().launch(captured, game, external) })
            assertEquals("test.wolf.RUN", captured.launched.action); assertEquals("test.wolf", captured.launched.`package`)
            assertEquals("-test", captured.launched.getStringExtra("arguments"))
        } finally { clearOptions(id) }
    }

    @Test fun nativePreparationFailureKeepsControlsHiddenAndProvidesReportAndFallback() {
        val id = "native-error-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id).apply { mkdirs() }
        File(root, "original.sav").writeText("unchanged")
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE))
        try {
            ActivityScenario.launch<WolfNativeActivity>(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", id)).use { scenario ->
                await { var visible = false; scenario.onActivity { activity -> visible = descendants(activity.window.decorView).any { it is Button && it.text == "Lancer avec Winlator" } }; visible }
                scenario.onActivity { activity ->
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<WolfTouchControls>().all { it.visibility == View.GONE })
                    assertTrue(descendants(activity.window.decorView).filterIsInstance<Button>().any { it.text == "Diagnostic" })
                }
                val diagnostics = WolfDiagnostics(context)
                val report = diagnostics.reports().first { diagnostics.report(it).contains(id) }
                assertTrue(diagnostics.report(report).contains("wolf-native-kotlin-1"))
                assertTrue(diagnostics.report(report).contains("Game.dat"))
                capture(scenario, "native-error.png")
            }
            assertEquals("unchanged", File(root, "original.sav").readText())
        } finally { clearOptions(id); check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun authoredBinaryGameDisplaysItsFirstFrameWithoutWineOrGameTransfer() {
        val id = "native-boot-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id).apply { mkdirs() }
        NativeGameFixture.write(root)
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE, showFps = false))
        val before = File(root, "Save/original.sav").readBytes()
        try {
            ActivityScenario.launch<WolfNativeActivity>(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", id)).use { scenario ->
                await {
                    var ready = false
                    scenario.onActivity { activity -> ready = descendants(activity.window.decorView).filterIsInstance<WolfTouchControls>().any { it.visibility == View.VISIBLE } }
                    ready
                }
                val diagnostics = WolfDiagnostics(context)
                val report = diagnostics.reports().first { diagnostics.report(it).contains(id) }
                assertTrue(diagnostics.report(report).contains("Première image native affichée"))
                assertTrue(diagnostics.report(report).contains("aucun transfert général"))
                assertFalse(File(context.filesDir, "wolf-games/${WolfNativeSaveStore.digest(id.toByteArray())}/game").exists())
                capture(scenario, "native-title.png")
                if (InstrumentationRegistry.getArguments().getString("nativeCapture") == "true") {
                    File(context.getExternalFilesDir(null), "native-boot-report.txt").writeText(diagnostics.report(report))
                }
                if (InstrumentationRegistry.getArguments().getString("nativeReleaseFixture") == "true") {
                    val destination = File(context.filesDir, "wolf-probe/native-authored-beta14")
                    check(!destination.exists() || File(destination, ".astra-test-fixture").readText() == "AUTHORED_BETA14")
                    root.copyRecursively(destination, overwrite = true)
                    File(destination, ".astra-test-fixture").writeText("AUTHORED_BETA14")
                }
            }
            assertArrayEquals(before, File(root, "Save/original.sav").readBytes())
        } finally { clearOptions(id); check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively() }
    }

    @Test fun hostControlsAndMenuReallySaveLoadAndOpenGameSettings() {
        val id = "native-host-journey-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id).apply { mkdirs() }
        NativeGameFixture.write(root)
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE, showFps = false))
        val savesDirectory = WolfNativeSaveStore.directoryFor(context, id)
        val frameField = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        try {
            ActivityScenario.launch<WolfNativeActivity>(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", id)).use { scenario ->
                fun frame(): NativeFrame? {
                    var result: NativeFrame? = null
                    scenario.onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<WolfNativeView>().firstOrNull()?.let { result = frameField.get(it) as NativeFrame? } }
                    return result // Immutable frame published by the actual owner thread.
                }
                fun press(label: String) = scenario.onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<Button>().first { it.text == label }.performClick() }
                fun menu() { press("Menu"); onView(withText("Réglages du jeu")).perform(click()) }
                await { frame()?.dialog?.choices?.firstOrNull() == "Nouvelle partie" }
                SystemClock.sleep(200); press("Valider")
                await { frame()?.dialog?.text == "Bienvenue !" }
                SystemClock.sleep(200); press("Valider")
                await { frame()?.dialog == null }
                val heroX = frame()!!.map!!.characters.single { it.id == -2 }.x
                capture(scenario, "native-game.png")
                menu(); onView(withText("Sauvegarder · emplacement 0")).perform(click())
                await { File(savesDirectory, "slot-0.astrawolf").isFile }
                onView(withText("Fermer")).perform(click())
                press("Valider"); SystemClock.sleep(200)
                scenario.onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<Button>().first { it.contentDescription == "Droite" }.performClick() }
                await { frame()!!.map!!.characters.single { it.id == -2 }.x > heroX }
                menu(); onView(withText("Charger une sauvegarde native")).perform(click())
                onView(withText("Emplacement 0")).perform(click())
                await { kotlin.math.abs(frame()!!.map!!.characters.single { it.id == -2 }.x - heroX) < .001f }
                menu(); onView(withText("Réglages du jeu")).perform(click())
                capture(scenario, "native-options.png")
                onView(withText("Annuler")).perform(click())
                assertEquals("Original Windows save must be preserved", File(root, "Save/original.sav").readText())
            }
        } finally {
            clearOptions(id); check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
            check(savesDirectory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
            savesDirectory.parentFile!!.deleteRecursively()
        }
    }

    @Test fun automaticIncompatibilityDispatchesWinlatorAndFinishesNativeSession() {
        val id = "native-fallback-${UUID.randomUUID()}"
        val root = File(context.cacheDir, id).apply { mkdirs() }
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.AUTO))
        // Observe the real activity dispatch but block the downloader in this routing test.
        val monitor = android.app.Instrumentation.ActivityMonitor(WolfRuntimeActivity::class.java.name,
            android.app.Instrumentation.ActivityResult(0, null), true)
        instrumentation.addMonitor(monitor)
        try {
            ActivityScenario.launch<WolfNativeActivity>(WolfNativeActivity.intent(context, id, Uri.fromFile(root).toString(), "Game.exe", id)).use { scenario ->
                await { monitor.hits == 1 }
                await { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
                val diagnostics = WolfDiagnostics(context)
                val report = diagnostics.reports().first { diagnostics.report(it).contains(id) }
                await { diagnostics.report(report).contains("Secours Winlator") }
                assertFalse(File(context.filesDir, "wolf-native/${WolfNativeSaveStore.digest(id.toByteArray())}/saves").exists())
            }
        } finally {
            instrumentation.removeMonitor(monitor); clearOptions(id)
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }

    private fun clearOptions(id: String) { context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit().remove(WolfNativeSaveStore.digest(id.toByteArray())).commit() }
    private fun capture(scenario: ActivityScenario<WolfNativeActivity>, name: String) {
        if (InstrumentationRegistry.getArguments().getString("nativeCapture") != "true") return
        scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
        SystemClock.sleep(350)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(100) }
        fail("Native launch did not reach the expected state in 15 seconds")
    }
}
