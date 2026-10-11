package fr.astragames.app.wolfnative

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.platform.app.InstrumentationRegistry
import fr.astragames.app.windows.WolfDiagnostics
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.app.windows.WolfTouchControls
import fr.astragames.wolf.NativeFrame
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real, unmodified official data. Provisioned via SDK ADB; never distributed with the app. */
class WolfOfficialNativeJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val frameField = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }

    @Test fun official22961TitleNewGameMovementAndPrivateSave() = journey("22961")
    @Test fun official3729TitleNewGameMovementAndPrivateSave() = journey("3729")

    private fun journey(version: String) {
        val supplied = InstrumentationRegistry.getArguments().getString("nativeOfficialRoot")
        assumeTrue("Official sample files must be provisioned explicitly", !supplied.isNullOrBlank())
        val root = File(requireNotNull(supplied), version)
        assertTrue("Missing official sample $version", File(root, "Data/BasicData/Game.dat").isFile)
        val id = "official-native-$version-${UUID.randomUUID()}"
        val saveDirectory = WolfNativeSaveStore.directoryFor(context, id)
        val originalSaves = File(root, "Save").walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(root).invariantSeparatorsPath to WolfNativeSaveStore.digest(it.readBytes())
        }
        val checkpoints = JSONArray()
        val evidence = JSONObject().put("sample", version).put("engine", "NATIVE")
            .put("checkpoints", checkpoints).put("started", System.currentTimeMillis())
        val output = File(context.getExternalFilesDir(null), "native-official-$version.json")
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE, showFps = false))
        val begin = SystemClock.elapsedRealtime()
        try {
            NativeActivityScenario.launch(WolfNativeActivity.intent(context, id,
                Uri.fromFile(root).toString(), "Game.exe", "Official Wolf $version")).useNativeGame { scenario ->
                // Poll the activity without waiting for an idle UI queue: a 60 FPS renderer keeps
                // posting work while the game is running. Waiting for UI idleness is not loading time.
                fun onActivity(action: (WolfNativeActivity) -> Unit) = instrumentation.runOnMainSync {
                    action(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<WolfNativeActivity>().single())
                }
                fun frame(): NativeFrame? {
                    var value: NativeFrame? = null
                    onActivity { activity -> descendants(activity.window.decorView)
                        .filterIsInstance<WolfNativeView>().firstOrNull()?.let { value = frameField.get(it) as NativeFrame? } }
                    return value
                }
                fun report(): String = WolfDiagnostics(context).let { diagnostics ->
                    diagnostics.reports().firstOrNull { diagnostics.report(it).contains("Official Wolf $version") }
                        ?.let(diagnostics::report).orEmpty()
                }
                fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + timeout
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (condition()) return
                        var failed = false
                        onActivity { activity -> failed = descendants(activity.window.decorView)
                            .filterIsInstance<Button>().any { it.text == "Lancer avec Winlator" && it.isShown } }
                        assertFalse("Native engine failed at $label:\n${report()}", failed)
                        SystemClock.sleep(80)
                    }
                    val current = frame()
                    fail("Native $version did not reach $label:\n${report()}\n" +
                        "tick=${current?.tick},map=${current?.map?.map?.name}," +
                        "hero=${current?.map?.characters?.firstOrNull { it.id == -2 }}," +
                        "dialog=${current?.dialog},pictures=${current?.pictures?.map { it.id to (it.path ?: it.text?.take(100)) }}")
                }
                fun press(label: String) = onActivity { activity -> descendants(activity.window.decorView)
                    .filterIsInstance<Button>().first { it.text == label }.performClick() }
                fun menu() { press("Menu"); clickNativeDialog("Réglages du jeu") }
                fun checkpoint(label: String) {
                    val current = requireNotNull(frame())
                    checkpoints.put(JSONObject().put("label", label).put("elapsedMs", SystemClock.elapsedRealtime() - begin)
                        .put("tick", current.tick).put("map", current.map?.map?.name)
                        .put("pictures", JSONArray(current.pictures.map { it.path ?: it.text.orEmpty() }))
                        .put("dialog", current.dialog?.text))
                    onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                    SystemClock.sleep(100)
                    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                        File(context.getExternalFilesDir(null), "native-official-$version-$label.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    output.writeText(evidence.toString(2))
                }
                await("title image") { frame()?.pictures?.any { it.path?.contains("Title", true) == true } == true }
                await("visible controls") {
                    var visible = false
                    onActivity { activity -> visible = descendants(activity.window.decorView)
                        .filterIsInstance<WolfTouchControls>().any { it.visibility == View.VISIBLE } }
                    visible
                }
                // Wait for the real menu, rather than injecting an input during its transition.
                SystemClock.sleep(1500)
                checkpoint("title")
                val titleMap = requireNotNull(frame()).map?.map?.name
                press("Valider")
                await("new game map") { frame()?.map?.map?.name?.let { it != titleMap } == true }
                checkpoint("new-game")
                await("tutorial dialogue", 15_000) { frame()?.dialog != null }
                SystemClock.sleep(600)
                checkpoint("dialogue")
                val tutorialDeadline = SystemClock.elapsedRealtime() + 60_000
                var lastDialogAt = SystemClock.elapsedRealtime()
                var nextConfirmAt = lastDialogAt
                while (SystemClock.elapsedRealtime() < tutorialDeadline) {
                    val now = SystemClock.elapsedRealtime()
                    if (frame()?.dialog != null) {
                        lastDialogAt = now
                        if (now >= nextConfirmAt) { press("Valider"); nextConfirmAt = now + 600 }
                    } else if (now - lastDialogAt >= 1500) break
                    await("tutorial progress", 1000) { frame() != null }
                    SystemClock.sleep(80)
                }
                assertNull("Tutorial did not finish", frame()?.dialog)
                checkpoint("tutorial")
                val beforeMovement = frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y }
                onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<Button>()
                    .first { it.contentDescription == "Droite" }.performClick() }
                await("movement") { frame()?.map?.characters?.firstOrNull { it.id == -2 }
                    ?.let { it.x != beforeMovement.first || it.y != beforeMovement.second } == true }
                checkpoint("movement")
                await("movement settled") { frame()?.map?.characters?.firstOrNull { it.id == -2 }?.moving == false }
                menu(); clickNativeDialog("Sauvegarder · emplacement 0")
                await("native save") { File(saveDirectory, "slot-0.astrawolf").isFile }
                val saved = File(saveDirectory, "slot-0.astrawolf").readBytes()
                assertTrue(saved.size > 100)
                clickNativeDialog("Fermer")
                checkpoint("saved")
                val savedPosition = frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y }
                onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<Button>()
                    .first { it.contentDescription == "Droite" }.performClick() }
                await("movement after save") { frame()?.map?.characters?.firstOrNull { it.id == -2 }
                    ?.let { it.x != savedPosition.first || it.y != savedPosition.second } == true }
                menu(); clickNativeDialog("Charger une sauvegarde native")
                clickNativeDialog("Emplacement 0")
                await("restored position") { frame()?.map?.characters?.firstOrNull { it.id == -2 }
                    ?.let { kotlin.math.abs(it.x - savedPosition.first) < .001f && kotlin.math.abs(it.y - savedPosition.second) < .001f } == true }
                checkpoint("loaded")
                val runtimeReport = report()
                assertTrue(runtimeReport.contains("Première image native affichée"))
                assertTrue(runtimeReport.contains("aucun transfert général"))
                assertFalse(runtimeReport.contains("Secours Winlator"))
                assertFalse(File(context.filesDir, "wolf-games/${WolfNativeSaveStore.digest(id.toByteArray())}/game").exists())
                evidence.put("diagnostic", runtimeReport).put("result", "PASS")
                originalSaves.forEach { (path, checksum) -> assertEquals("Original Windows save changed", checksum,
                    WolfNativeSaveStore.digest(File(root, path).readBytes())) }
            }
        } catch (failure: Throwable) {
            evidence.put("result", "FAIL").put("error", failure.toString()); throw failure
        } finally {
            output.writeText(evidence.toString(2))
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            if (saveDirectory.exists()) {
                check(saveDirectory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
                saveDirectory.parentFile!!.deleteRecursively()
            }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
