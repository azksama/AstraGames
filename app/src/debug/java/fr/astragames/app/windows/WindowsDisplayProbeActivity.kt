package fr.astragames.app.windows

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import java.io.File

/** Opt-in graphical research fixture. Never included in a release build. */
class WindowsDisplayProbeActivity : Activity() {
    lateinit var server: XServer
    private lateinit var display: XServerView
    private lateinit var component: XServerComponent
    private var child: Process? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = File(filesDir, "wolf-probe")
        val fonts = File(root, "fonts.conf").apply { writeText("<?xml version=\"1.0\"?><!DOCTYPE fontconfig SYSTEM \"fonts.dtd\"><fontconfig><dir>${root.path}/fonts</dir><dir>/system/fonts</dir><cachedir>${root.path}/font-cache</cachedir><alias binding=\"strong\"><family>MS Gothic</family><prefer><family>IPAexGothic</family></prefer></alias></fontconfig>") }
        val socket = UnixSocketConfig.create(root.path, "/tmp/.X11-unix/X0")
        server = XServer(this, ScreenInfo(800, 600))
        display = XServerView(this, server)
        server.renderer = display.renderer
        setContentView(display)
        display.setOnTouchListener { view, event ->
            server.injectPointerMove((event.x / view.width * 800).toInt(), (event.y / view.height * 600).toInt())
            if (event.actionMasked == MotionEvent.ACTION_DOWN) server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            true
        }
        component = XServerComponent(server, socket)
        component.start()
        val wine = File(root, "proton/bin/wine")
        val command = intent.getStringExtra("command") ?: "notepad"
        val work = File(root, "game").apply { mkdirs() }
        child = ProcessBuilder("/system/bin/linker64", wine.path, command)
            .directory(work).redirectErrorStream(true).redirectOutput(File(root, "display.log"))
            .apply {
                environment().putAll(mapOf(
                    "WINEDLLPATH" to File(root, "proton/lib/wine").path,
                    "WINEPREFIX" to File(root, "prefix-wolf").path,
                    "LANG" to "ja_JP.UTF-8",
                    "LC_ALL" to "ja_JP.UTF-8",
                    "HOME" to root.path,
                    "WINESERVER" to File(root, "proton/bin/wineserver").path,
                    "WINELOADER" to wine.path,
                    "LD_PRELOAD" to File(root, "libastra_exec_bridge.so").path,
                    "LD_LIBRARY_PATH" to File(root, "libs/usr/lib").path,
                    "ASTRA_RUNTIME_ROOT" to File(root, "proton").path,
                    "ASTRA_WINE_LOADER" to wine.path,
                    "ASTRA_X11_SOCKET" to socket.path,
                    "DISPLAY" to ":0",
                    "WINE_DISABLE_FULLSCREEN_HACK" to "1",
                    "FONTCONFIG_FILE" to fonts.path,
                    "XDG_CACHE_HOME" to File(root, "cache").path,
                    "WINEDLLOVERRIDES" to "winemenubuilder.exe,mscoree,mshtml=d",
                    "WINEDEBUG" to "+x11drv,+seh,+process"
                ))
            }.start()
    }
    override fun onDestroy() {
        child?.destroyForcibly()
        component.stop()
        super.onDestroy()
    }
}
