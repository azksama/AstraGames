package fr.astragames.app.windows

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Real downloaded Wine plus an original, separately prepared official Wolf sample. */
class WolfIntegratedRuntimeTest {
    @Test fun gameRunsInsideAstraAndAcceptsControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("wolfIntegration") == "true")
        val context = instrumentation.targetContext
        val sample = InstrumentationRegistry.getArguments().getString("wolfSample") ?: "game"
        require(sample in setOf("game", "game3729"))
        val source = File(context.filesDir, "wolf-probe/$sample")
        assertTrue(File(source, "Game.exe").isFile)
        val intent = android.content.Intent().setClassName(context, "fr.astragames.app.windows.WolfRuntimeActivity")
            .putExtra("id", "official-wolf-test-$sample-beta5").putExtra("source", source.toURI().toString())
            .putExtra("executable", "Game.exe").putExtra("title", "Wolf officiel")
        val diagnostics = WolfDiagnostics(context)
        val previousDebug = diagnostics.enabled
        val store = WolfGameOptions(context, "official-wolf-test-$sample-beta5")
        val previousOptions = store.read()
        store.save(previousOptions.copy(resolution = "auto", imageMode = WolfImageMode.FIT))
        if (InstrumentationRegistry.getArguments().getString("wolfDebug") == "true") diagnostics.enabled = true
        try { ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
            scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
            var ready = false
            for (attempt in 0 until 240) {
                Thread.sleep(1000)
                scenario.onActivity { activity -> ready = buttons(activity.window.decorView).any { it.text == "Valider" && it.isShown } }
                if (ready) break
            }
            assertTrue("The actual runtime never became playable", ready)
            Thread.sleep(10_000)
            screenshot("integrated-title.png")
            scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Valider" }.performClick() }
            Thread.sleep(4000)
            screenshot("integrated-game.png")
            var renderer: com.winlator.renderer.GLRenderer? = null
            scenario.onActivity { activity ->
                renderer = descendants(activity.window.decorView).filterIsInstance<com.winlator.widget.XServerView>().first().renderer
                val field = WolfRuntimeActivity::class.java.getDeclaredField("server").apply { isAccessible = true }
                val server = field.get(activity) as com.winlator.xserver.XServer
                fun describe(window: com.winlator.xserver.Window, level: Int = 0): String =
                    "${" ".repeat(level)}${window.id} ${window.name} ${window.width}x${window.height} at ${window.x},${window.y} render=${window.isRenderable} type=${window.type} decorations=${window.decorations} fullscreen=${window.fullscreenTransformation != null}\n" + window.children.joinToString("") { describe(it, level + 1) }
                File(context.getExternalFilesDir(null), "wolf-windows.txt").writeText(describe(server.windowManager.rootWindow))
                val game = server.windowManager.rootWindow.children.last { it.isRenderable && it.width >= 320 }
                val transform = requireNotNull(game.fullscreenTransformation)
                assertEquals(server.screenInfo.width, transform.width)
                val center = transform.transformPointerCoords((transform.x + transform.width / 2).toShort(), (transform.y + transform.height / 2).toShort())
                assertEquals(game.rootX + game.width / 2, center[0].toInt())
                assertEquals(game.rootY + game.height / 2, center[1].toInt())
            }
            val firstFrame = renderer!!.contentFrameCount
            val frameStart = android.os.SystemClock.elapsedRealtime()
            Thread.sleep(10_000)
            val fps = (renderer!!.contentFrameCount - firstFrame) * 1000.0 / (android.os.SystemClock.elapsedRealtime() - frameStart)
            File(context.getExternalFilesDir(null), "wolf-fps.txt").writeText("Official Wolf sample; emulator x86_64; changed content images/s=$fps")
            assertTrue("No game content frames were presented", fps > 0)
            // The official map starts with a multi-page explanation; finish it before walking.
            repeat(24) {
                scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Valider" }.performClick() }
                Thread.sleep(500)
            }
            screenshot("integrated-before-moving.png")
            // Exercise actual touch buttons while the player moves, rather than counting idle animation.
            val movingStart = renderer!!.contentFrameCount
            val movingTime = android.os.SystemClock.elapsedRealtime()
            for (direction in listOf("Droite", "Gauche", "Bas", "Haut")) {
                var button: Button? = null
                val downTime = android.os.SystemClock.uptimeMillis()
                scenario.onActivity { activity ->
                    button = buttons(activity.window.decorView).first { it.contentDescription == direction }
                    MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 10f, 10f, 0).also { button!!.dispatchTouchEvent(it); it.recycle() }
                }
                Thread.sleep(2000)
                screenshot("integrated-moving-$direction.png")
                scenario.onActivity {
                    MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10f, 10f, 0).also { event -> button!!.dispatchTouchEvent(event); event.recycle() }
                }
            }
            val movingFps = (renderer!!.contentFrameCount - movingStart) * 1000.0 / (android.os.SystemClock.elapsedRealtime() - movingTime)
            File(context.getExternalFilesDir(null), "wolf-fps.txt").appendText("\nTouch movement; changed content images/s=$movingFps; ${renderer!!.performanceSnapshot}")
            assertTrue("No content during touch movement", movingFps > 0)
            val soak = InstrumentationRegistry.getArguments().getString("wolfSoakSeconds")?.toIntOrNull()?.coerceIn(0, 300) ?: 0
            for (elapsed in 0 until soak step 10) {
                val before = renderer!!.contentFrameCount
                scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Valider" }.performClick() }
                Thread.sleep(10_000)
                val after = renderer!!.contentFrameCount
                File(context.getExternalFilesDir(null), "wolf-fps.txt").appendText("\nSoak ${elapsed + 10}s: contentFPS=${(after - before) / 10.0}; ${renderer!!.performanceSnapshot}")
                assertTrue("No content during the soak test", after > before)
            }
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(1500)
            screenshot("integrated-landscape.png")
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<com.winlator.widget.XServerView>().first().setMaxFps(30)
                renderer!!.setSmoothScaling(false)
            }
            Thread.sleep(1000)
            val limitedStart = renderer!!.contentFrameCount
            val limitedTime = android.os.SystemClock.elapsedRealtime()
            Thread.sleep(5000)
            val limitedFps = (renderer!!.contentFrameCount - limitedStart) * 1000.0 / (android.os.SystemClock.elapsedRealtime() - limitedTime)
            assertTrue("30 FPS ceiling ignored: $limitedFps", limitedFps in 1.0..32.0)
            File(context.getExternalFilesDir(null), "wolf-fps.txt").appendText("\nLandscape; standard smoothing; 30 ceiling: $limitedFps")
            scenario.onActivity { activity ->
                descendants(activity.window.decorView).filterIsInstance<com.winlator.widget.XServerView>().first().setMaxFps(60)
                renderer!!.setSmoothScaling(true)
            }
            fun checkModes(landscape: Boolean) {
                for (mode in WolfImageMode.entries) {
                    scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Menu" }.performClick() }
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Réglages du jeu"))
                        .perform(androidx.test.espresso.action.ViewActions.click())
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Cadrage · immédiat"))
                        .perform(androidx.test.espresso.action.ViewActions.scrollTo(), androidx.test.espresso.action.ViewActions.click())
                    screenshot("integrated-mode-choices.png")
                    androidx.test.espresso.Espresso.onData(org.hamcrest.Matchers.equalTo(mode.label))
                        .inRoot(androidx.test.espresso.matcher.RootMatchers.isPlatformPopup())
                        .perform(androidx.test.espresso.action.ViewActions.click())
                    androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Enregistrer"))
                        .perform(androidx.test.espresso.action.ViewActions.click())
                    Thread.sleep(500)
                    assertEquals(mode, store.read().imageMode)
                    scenario.onActivity { activity ->
                        val view = descendants(activity.window.decorView).filterIsInstance<com.winlator.widget.XServerView>().first()
                        val transform = view.renderer.viewTransformation
                        assertEquals("Game must use the full root, including the old control band", activity.window.decorView.height, view.height)
                        val viewport = WolfViewport(transform.viewOffsetX, transform.viewOffsetY, transform.viewWidth,
                            transform.viewHeight, 1280, 960, view.width, view.height)
                        assertEquals(640 to 480, viewport.point(view.width / 2f, view.height / 2f))
                        if (mode == WolfImageMode.FIT) {
                            assertTrue(transform.viewWidth <= view.width && transform.viewHeight <= view.height)
                        } else if (mode == WolfImageMode.FILL) {
                            assertTrue(transform.viewWidth >= view.width && transform.viewHeight >= view.height)
                        } else {
                            assertEquals(view.width, transform.viewWidth)
                            assertEquals(view.height, transform.viewHeight)
                        }
                        val controls = descendants(activity.window.decorView).filterIsInstance<WolfTouchControls>().first()
                        val menu = buttons(controls).first { it.text == "Menu" }
                        assertTrue(menu.x >= controls.paddingLeft && menu.y >= controls.paddingTop)
                        assertTrue(menu.x + menu.width <= controls.width - controls.paddingRight)
                        File(context.getExternalFilesDir(null), "wolf-fps.txt").appendText(
                            "\n${if (landscape) "Landscape" else "Portrait"} $mode: view=${view.width}x${view.height}; image=${transform.viewWidth}x${transform.viewHeight}@${transform.viewOffsetX},${transform.viewOffsetY}")
                    }
                    screenshot("integrated-${if (landscape) "landscape" else "portrait"}-$mode.png")
                }
            }
            checkModes(true)
            scenario.onActivity { activity -> showWolfOptions(activity, WolfGameOptions(activity, "official-wolf-test-$sample-beta5")) {} }
            Thread.sleep(500)
            screenshot("integrated-settings-landscape.png")
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Annuler"))
                .perform(androidx.test.espresso.action.ViewActions.click())
            scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(1500)
            checkModes(false)
            scenario.onActivity { activity -> showWolfOptions(activity, WolfGameOptions(activity, "official-wolf-test-$sample-beta5")) {} }
            Thread.sleep(500)
            screenshot("integrated-settings.png")
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Annuler"))
                .perform(androidx.test.espresso.action.ViewActions.click())
            scenario.onActivity { activity -> buttons(activity.window.decorView).first { it.text == "Menu" }.performClick() }
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Quitter"))
                .perform(androidx.test.espresso.action.ViewActions.click())
            Thread.sleep(5000)
        }
            if (diagnostics.enabled) {
                val report = diagnostics.report(diagnostics.reports().first())
                assertTrue(report.contains("debug=true"))
                assertTrue(report.contains("WINEDEBUG=-all,err+all,warn+seh"))
                assertTrue(report.contains("Lancement du jeu via"))
                assertTrue(report.contains("===== runtime ====="))
                assertTrue(report.contains("Serveur Windows supervisé"))
                assertTrue(report.contains("Rendu Android"))
                assertFalse(report.contains("a wine server seems to be running, but I cannot connect"))
                assertTrue("Normal close must finalize the diagnostic session", report.contains("État : Fermeture demandée"))
            }
        } finally { diagnostics.enabled = previousDebug; store.save(previousOptions) }
    }
    private fun buttons(view: View): List<Button> = if (view is Button) listOf(view) else if (view is ViewGroup) (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) } else emptyList()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.filesDir, "wolf-probe/$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
