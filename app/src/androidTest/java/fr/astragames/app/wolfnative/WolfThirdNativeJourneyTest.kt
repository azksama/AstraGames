package fr.astragames.app.wolfnative

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import fr.astragames.app.windows.WolfDiagnostics
import fr.astragames.app.windows.WolfGameOptions
import fr.astragames.app.windows.WolfOptions
import fr.astragames.app.windows.WolfRuntimeMode
import fr.astragames.app.windows.WolfTouchControls
import fr.astragames.wolf.NativeFrame
import fr.astragames.wolf.WolfRuntime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** The externally provisioned freeware is not included in the APK, tests or release sources. */
class WolfThirdNativeJourneyTest {
    @Test fun crookedManOriginalArchiveLanguageTitleAndOpeningRunNatively() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val supplied = InstrumentationRegistry.getArguments().getString("nativeThirdRoot")
        assumeTrue("The third corpus must be provisioned explicitly through SDK ADB", !supplied.isNullOrBlank())
        val original = File(requireNotNull(supplied), "crookedman")
        val archive = File(original, "Data.wolf")
        val originalDigest = WolfNativeSaveStore.digest(archive.readBytes())
        assertEquals("Unmodified corpus archive required", "c081098247a3efc2cc8b40aff33ef776a853570814b5836fb7eee99ea11aca53", originalDigest)
        val id = "third-native-${UUID.randomUUID()}"
        WolfGameOptions(context, id).save(WolfOptions(runtime = WolfRuntimeMode.NATIVE, showFps = false))
        val saveDirectory = WolfNativeSaveStore.directoryFor(context, id)
        val checkpoints = JSONArray()
        val evidence = JSONObject().put("engine", "NATIVE").put("corpus", "The Crooked Man v4")
            .put("archiveSha256", originalDigest).put("checkpoints", checkpoints)
        val output = File(context.getExternalFilesDir(null), "native-third-crookedman.json")
        val started = SystemClock.elapsedRealtime()
        val field = WolfNativeView::class.java.getDeclaredField("frame").apply { isAccessible = true }
        try {
                fun onActivity(action: (WolfNativeActivity) -> Unit) = instrumentation.runOnMainSync {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<WolfNativeActivity>().firstOrNull()?.let(action)
                }
                fun frame(): NativeFrame? {
                    var result: NativeFrame? = null
                    onActivity { activity -> descendants(activity.window.decorView).filterIsInstance<WolfNativeView>()
                        .firstOrNull()?.let { result = field.get(it) as NativeFrame? } }
                    return result
                }
                fun report(): String = WolfDiagnostics(context).let { store -> store.reports()
                    .firstOrNull { store.report(it).contains("Third native corpus") }?.let(store::report).orEmpty() }
                fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
                    val deadline = SystemClock.elapsedRealtime() + timeout
                    while (SystemClock.elapsedRealtime() < deadline) {
                        if (condition()) return
                        var failed = false
                        onActivity { activity -> failed = descendants(activity.window.decorView).filterIsInstance<Button>()
                            .any { it.isShown && it.text == "Lancer avec Winlator" } }
                        assertFalse("Third native corpus failed at $label:\n${report()}", failed)
                        SystemClock.sleep(80)
                    }
                    val current = frame()
                    fail("Third corpus did not reach $label: map=${current?.map?.map?.name},tick=${current?.tick}," +
                        "dialog=${current?.dialog},pictures=${current?.pictures?.map { it.id to (it.path ?: it.text) }}\n${report()}")
                }
                fun press(label: String) = onActivity { activity -> descendants(activity.window.decorView)
                    .filterIsInstance<Button>().first { it.text == label }.performClick() }
                fun right() = onActivity { activity -> descendants(activity.window.decorView)
                    .filterIsInstance<Button>().first { it.contentDescription == "Droite" }.performClick() }
                fun down() = onActivity { activity -> descendants(activity.window.decorView)
                    .filterIsInstance<Button>().first { it.contentDescription == "Bas" }.performClick() }
                fun waitTicks(count: Int) {
                    val tick = requireNotNull(frame()).tick
                    await("$count logical ticks") { (frame()?.tick ?: 0) >= tick + count }
                }
                fun holdDirection(label: String, pressed: Boolean) = onActivity { activity ->
                    val button = descendants(activity.window.decorView).filterIsInstance<Button>()
                        .first { it.contentDescription == label }
                    val now = SystemClock.uptimeMillis()
                    val event = MotionEvent.obtain(now, now,
                        if (pressed) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP,
                        button.width / 2f, button.height / 2f, 0)
                    try { button.dispatchTouchEvent(event) } finally { event.recycle() }
                }
                fun tap(label: String) {
                    holdDirection(label, true); holdDirection(label, false)
                }
                fun launch() = context.startActivity(WolfNativeActivity.intent(context, id,
                    Uri.fromFile(original).toString(), "GamePro.exe", "Third native corpus")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                fun closeActivity() {
                    onActivity { it.finish() }
                    val deadline = SystemClock.elapsedRealtime() + 5000
                    var closed = false
                    while (!closed && SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.runOnMainSync {
                            closed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .none { it is WolfNativeActivity }
                        }
                        if (!closed) SystemClock.sleep(40)
                    }
                    assertTrue("The old native Activity must close before restart", closed)
                }
                fun textContains(value: String) = frame()?.let { scene ->
                    scene.dialog?.text?.contains(value) == true || scene.pictures.any { it.text?.contains(value) == true }
                } == true
                fun checkpoint(label: String) {
                    val current = requireNotNull(frame())
                    checkpoints.put(JSONObject().put("label", label).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("map", current.map?.map?.name).put("tick", current.tick)
                        .put("hero", current.map?.characters?.firstOrNull { it.id == -2 }?.let {
                            JSONObject().put("x", it.x).put("y", it.y).put("moving", it.moving)
                        })
                        .put("pictures", JSONArray(current.pictures.map { it.path ?: it.text.orEmpty() })))
                    onActivity { activity -> activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
                    SystemClock.sleep(100)
                    instrumentation.uiAutomation.takeScreenshot()?.let { image ->
                        File(context.getExternalFilesDir(null), "native-third-$label.png").outputStream()
                            .use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                    }
                    output.writeText(evidence.toString(2))
                }
                launch()
                await("language selection") { textContains("English") }
                await("controls") {
                    var shown = false
                    onActivity { activity -> shown = descendants(activity.window.decorView)
                        .filterIsInstance<WolfTouchControls>().any { it.isShown } }
                    shown
                }
                SystemClock.sleep(700)
                checkpoint("languages")
                // Common312 uses a column-major grid: English is below Japanese.
                down(); SystemClock.sleep(200); press("Valider")
                await("English confirmation") { textContains("Do you want to play in English?") }
                checkpoint("english-confirmation")
                press("Valider")
                await("language saved") { textContains("The language has been set to English.") }
                checkpoint("english-saved")
                press("Valider")
                await("title") { frame()?.pictures?.any { it.path?.endsWith("Picture/Title.jpg") == true } == true }
                await("title menu fade complete") { frame()?.pictures?.filter {
                    it.path?.endsWith("Picture/TitleMenu_EN.png") == true
                }?.let { it.size == 3 && it.all { picture -> picture.opacity == 255 } } == true }
                waitTicks(30)
                checkpoint("title")
                assertEquals("Aozora Mincho Regular", frame()!!.fontOverrides[0])
                assertEquals(1250, WolfNativeLaunchPreferences(context, id).nextWindowScalePermille)
                // The title starts on Load; move to New through the game's own controls.
                right(); waitTicks(30)
                press("Valider")
                await("content warning") { frame()?.pictures?.any {
                    it.text?.contains("This is a horror game.") == true && it.opacity == 255
                } == true }
                checkpoint("content-warning")
                press("Valider")
                var lastIntroAck = SystemClock.elapsedRealtime()
                await("opening map", timeout = 130_000) {
                    val scene = frame()
                    if (scene?.map?.map?.name?.endsWith("Map001.mps") == true) true
                    else {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastIntroAck >= 2_000 && scene?.pictures?.any { it.text != null && it.opacity == 255 } == true) {
                            press("Valider"); lastIntroAck = now
                        }
                        false
                    }
                }
                checkpoint("opening")
                assertTrue("Scene must include the original map and characters", frame()?.map?.characters?.isNotEmpty() == true)
                var introConfirmAt = SystemClock.elapsedRealtime()
                var introMessages = 0
                var lastIntroText: String? = null
                val introTexts = JSONArray()
                evidence.put("openingMessages", introTexts)
                await("opening event finished", timeout = 130_000) {
                    val scene = frame()
                    val text = scene?.dialog?.text ?: scene?.pictures?.firstOrNull { it.text != null && it.opacity == 255 }?.text
                    if (text != null && text != lastIntroText) { introMessages++; lastIntroText = text; introTexts.put(text) }
                    val now = SystemClock.elapsedRealtime()
                    if (text != null && now - introConfirmAt >= 700) {
                        press("Valider"); introConfirmAt = now
                    }
                    introMessages >= 4 && scene != null && !scene.eventRunning && scene.dialog == null && scene.pictures.none { it.text != null }
                }
                assertTrue("The original opening messages must be exercised", introMessages >= 4)
                checkpoint("intro-finished")
                val before = frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y }
                holdDirection("Bas", true); try { waitTicks(30) } finally { holdDirection("Bas", false) }
                await("movement settled") { frame()?.map?.characters?.firstOrNull { it.id == -2 }?.moving == false }
                val savedPosition = frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y }
                assertTrue("Movement must come from real controls", before != savedPosition)
                checkpoint("movement")
                press("Menu"); clickNativeDialog("Réglages du jeu")
                clickNativeDialog("Sauvegarder · emplacement 0")
                await("native save") { File(saveDirectory, "slot-0.astrawolf").isFile }
                AndroidWolfAssetSource(context, Uri.fromFile(original)).use { source ->
                    val reader = WolfRuntime.load(source)
                    val stored = WolfNativeSaveStore(context, id, source.metadataFingerprint).readData("slot:0")
                    val snapshot = requireNotNull(stored.snapshot)
                    assertEquals(2, reader.savedNumber(snapshot, 2_000_270))
                    assertEquals(1, reader.savedNumber(snapshot, 2_000_271))
                }
                clickNativeDialog("Fermer")
                // Retrace the path already crossed; walking further down can meet a wall.
                waitTicks(2)
                holdDirection("Haut", true); try { waitTicks(30) } finally { holdDirection("Haut", false) }
                await("movement after save settled") { frame()?.map?.characters?.firstOrNull { it.id == -2 }?.moving == false }
                assertTrue(savedPosition != frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y })
                press("Menu"); clickNativeDialog("Réglages du jeu")
                clickNativeDialog("Charger une sauvegarde native")
                clickNativeDialog("Emplacement 0")
                await("restored position") { frame()?.map?.characters?.firstOrNull { it.id == -2 }?.let {
                    kotlin.math.abs(it.x - savedPosition.first) < .001f && kotlin.math.abs(it.y - savedPosition.second) < .001f
                } == true }
                checkpoint("loaded")
                val gameSave = File(saveDirectory, "slot-1.astrawolf")
                val gameSavedMap = frame()!!.map!!.map.name
                val gameSavedPosition = frame()!!.map!!.characters.first { it.id == -2 }.let { it.x to it.y }
                // The original Common217 menu selects Save after exactly one DOWN.
                // This exercises commands220/222/221, independently of Astra's save menu.
                waitTicks(30); tap("Retour")
                await("original game menu") { frame()?.pictures?.any {
                    it.path?.endsWith("Picture/Menu_EN.png") == true && it.opacity == 255
                } == true }
                checkpoint("game-menu")
                tap("Bas"); waitTicks(30); tap("Valider")
                await("original save slot") { frame()?.pictures?.any { it.id == 10300 && it.opacity == 255 } == true }
                waitTicks(30); checkpoint("game-save-slot"); tap("Valider")
                await("original game Save") { gameSave.isFile && gameSave.length() > 100 }
                // Subsequent222 writes are part of the game's save protocol.
                waitTicks(90); checkpoint("game-saved")
                val gameSaveDigest = WolfNativeSaveStore.digest(gameSave.readBytes())
                val gameSavedData = AndroidWolfAssetSource(context, Uri.fromFile(original)).use { source ->
                    // The host fingerprints the metadata read while loading the game, not an empty source.
                    WolfRuntime.load(source)
                    WolfNativeSaveStore(context, id, source.metadataFingerprint).readData("slot:1")
                }
                assertNotNull("The game's own Save must include a complete native snapshot", gameSavedData.snapshot)
                closeActivity(); launch()
                await("English title after restart", 60_000) { frame()?.pictures?.count {
                    it.path?.endsWith("Picture/TitleMenu_EN.png") == true && it.opacity == 255
                } == 3 }
                waitTicks(30); checkpoint("restart-title")
                // Existing language settings and the save catalogue are read from disk.
                // The original title preselects Load; no variables or state are injected.
                tap("Valider")
                await("original Load slot") { frame()?.pictures?.any { it.id == 10300 && it.opacity == 255 } == true }
                waitTicks(30); checkpoint("game-load-slot"); tap("Valider")
                await("original game Load after restart", 60_000) { frame()?.map?.let { scene ->
                    scene.map.name == gameSavedMap && scene.characters.firstOrNull { it.id == -2 }?.let {
                        kotlin.math.abs(it.x - gameSavedPosition.first) < .001f &&
                            kotlin.math.abs(it.y - gameSavedPosition.second) < .001f
                    } == true
                } == true }
                waitTicks(90); checkpoint("game-loaded")
                val gameLoadedData = AndroidWolfAssetSource(context, Uri.fromFile(original)).use { source ->
                    WolfRuntime.load(source)
                    WolfNativeSaveStore(context, id, source.metadataFingerprint).readData("slot:1")
                }
                // Common220 @0xda327 writes variable271=0 with222 before220 Load.
                // The author's explicit write changes the envelope, not its full snapshot.
                assertArrayEquals("Load must preserve the original full snapshot", gameSavedData.snapshot, gameLoadedData.snapshot)
                assertEquals(gameSavedData.strings, gameLoadedData.strings)
                assertEquals("Only the original game's declared pre-load override changes",
                    gameSavedData.numbers + (2_000_271 to 0), gameLoadedData.numbers)
                evidence.put("gameSaveBytes", gameSave.length()).put("gameSaveSha256", gameSaveDigest)
                    .put("gameLoadedFileSha256", WolfNativeSaveStore.digest(gameSave.readBytes()))
                    .put("originalGameMenuSaveRestartLoad", true)
                val diagnostic = report()
                assertTrue(diagnostic.contains("Première image native affichée")); assertFalse(diagnostic.contains("Secours Winlator"))
                assertTrue(diagnostic.contains("Police Wolf 0 chargée : Aozora Mincho Regular"))
                assertFalse(File(context.filesDir, "wolf-games/${WolfNativeSaveStore.digest(id.toByteArray())}/game").exists())
                evidence.put("result", "PASS").put("diagnostic", diagnostic)
        } catch (failure: Throwable) {
            evidence.put("result", "FAIL").put("error", failure.toString()); throw failure
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<WolfNativeActivity>().forEach { it.finish() }
            }
            output.writeText(evidence.toString(2))
            assertEquals("Original archive changed", originalDigest, WolfNativeSaveStore.digest(archive.readBytes()))
            context.getSharedPreferences("wolf_game_options", Context.MODE_PRIVATE).edit()
                .remove(WolfNativeSaveStore.digest(id.toByteArray())).commit()
            WolfNativeLaunchPreferences(context, id).clear()
            if (saveDirectory.exists()) {
                check(saveDirectory.canonicalFile.parentFile!!.parentFile == File(context.filesDir, "wolf-native").canonicalFile)
                saveDirectory.parentFile!!.deleteRecursively()
            }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
