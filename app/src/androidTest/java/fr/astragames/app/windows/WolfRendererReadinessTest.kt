package fr.astragames.app.windows

import androidx.test.core.app.ActivityScenario
import com.winlator.widget.XServerView
import com.winlator.xserver.*
import org.junit.Assert.*
import org.junit.Test

/** Real GLES presentation, without Wine: black and console frames must keep controls gated. */
class WolfRendererReadinessTest {
    @Test fun waitsForAVisibleGameFrameAndUsesTheZoomedViewportForInput() {
        ActivityScenario.launch(fr.astragames.app.MainActivity::class.java).use { scenario ->
            lateinit var server: XServer; lateinit var view: XServerView
            lateinit var console: Window; lateinit var game: Window
            scenario.onActivity { activity ->
                server = XServer(activity, ScreenInfo("800x600"))
                view = XServerView(activity, server)
                server.renderer = view.renderer
                view.renderer.setForceWindowsFullscreen(true); view.renderer.setImageScaleMode(0)
                activity.setContentView(view)
                fun create(id: Int, name: String): Window {
                    val root = server.windowManager.rootWindow
                    val window = server.windowManager.createWindow(id, root, 0, 0, 640, 480,
                        WindowAttributes.WindowClass.INPUT_OUTPUT, null, 0, null)
                    window.addProperty(Property(Atom.WM_CLASS.toInt(), Atom.STRING.toInt(), Property.Format.BYTE_ARRAY, name.toByteArray()))
                    window.content.fillColor(0)
                    return window
                }
                server.lockAll().use {
                    console = create(0x101, "wineconsole.exe")
                    game = create(0x102, "Game.exe")
                    server.windowManager.mapWindow(console)
                    console.content.fillColor(0x2050A0)
                }
                view.renderer.awaitGameFrame("Game.exe")
            }
            Thread.sleep(800)
            assertFalse("Console unlocked the controls", view.renderer.hasPresentedGameFrame())
            scenario.onActivity {
                server.lockAll().use { server.windowManager.unmapWindow(console); server.windowManager.mapWindow(game) }
            }
            Thread.sleep(500)
            assertFalse("Black game frame unlocked the controls", view.renderer.hasPresentedGameFrame())
            scenario.onActivity { server.lockAll().use { game.content.fillColor(0x102050); game.content.fillRect(0, 0, 640, 100, 0xAA4433) } }
            for (attempt in 0 until 30) { if (view.renderer.hasPresentedGameFrame()) break; Thread.sleep(100) }
            assertTrue("Static game image was never presented", view.renderer.hasPresentedGameFrame())
            val before = view.renderer.viewTransformation.viewWidth
            scenario.onActivity { view.renderer.zoomImage(2f, view.width / 2f, view.height / 2f, 0f, 0f) }
            Thread.sleep(300)
            val transform = view.renderer.viewTransformation
            assertEquals(before * 2, transform.viewWidth)
            val viewport = WolfViewport(transform.viewOffsetX, transform.viewOffsetY, transform.viewWidth, transform.viewHeight,
                800, 600, view.width, view.height)
            assertEquals(400 to 300, viewport.point(view.width / 2f, view.height / 2f))
            scenario.onActivity { view.renderer.resetImageZoom() }
            Thread.sleep(250)
            assertEquals(before, transform.viewWidth)
        }
    }
}
