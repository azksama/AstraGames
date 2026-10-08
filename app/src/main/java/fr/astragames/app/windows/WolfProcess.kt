package fr.astragames.app.windows

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class WolfProcess(private val context: Context, private val runtime: File, private val storage: WolfGameStorage,
                  socket: String, sharedMemorySocket: String) {
    private val arm = android.os.Build.SUPPORTED_ABIS.first() == "arm64-v8a"
    private val wine = File(runtime, "proton/bin/wine")
    private val box = File(runtime, "box64/usr/bin/box64")
    private val temporary = File(context.cacheDir, "wolf-tmp").apply { mkdirs() }
    private val pulseSocket = File(temporary, "pulse.sock")
    private var audio: Process? = null
    private val fontConfig = File(storage.directory, "fonts.conf").apply {
        writeText("""<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig><dir>${runtime.path}/fonts</dir><dir>/system/fonts</dir><cachedir>${storage.directory.path}/font-cache</cachedir><alias binding="strong"><family>MS Gothic</family><prefer><family>IPAexGothic</family></prefer></alias></fontconfig>""")
    }
    val log = File(storage.directory, "runtime.log")
    private val environment = mapOf(
        "WINEDLLPATH" to File(runtime, "proton/lib/wine").path,
        "WINEPREFIX" to storage.prefix.path,
        "HOME" to storage.directory.path,
        "TMPDIR" to temporary.path,
        "PATH" to "${wine.parent}:${box.parent}:/system/bin",
        "WINESERVER" to File(wine.parentFile, "wineserver").path,
        "WINELOADER" to wine.path,
        "LD_PRELOAD" to File(context.applicationInfo.nativeLibraryDir, "libastra_exec_bridge.so").path,
        "LD_LIBRARY_PATH" to "${runtime.path}/libs/usr/lib:/system/lib64",
        "ASTRA_RUNTIME_ROOT" to runtime.path,
        "ASTRA_WINE_LOADER" to wine.path,
        "ASTRA_BOX64" to box.path,
        "ASTRA_X11_SOCKET" to socket,
        "ANDROID_SYSVSHM_SERVER" to sharedMemorySocket,
        "BOX64_LD_LIBRARY_PATH" to "${runtime.path}/proton/lib/wine/x86_64-unix:${runtime.path}/libs/usr/lib",
        "BOX64_PATH" to wine.parent,
        "BOX64_DYNAREC" to "1",
        "BOX64_MMAP32" to "1",
        "BOX64_NOBANNER" to "1",
        "DISPLAY" to ":0",
        "LANG" to "ja_JP.UTF-8",
        "LC_ALL" to "ja_JP.UTF-8",
        "FONTCONFIG_FILE" to fontConfig.path,
        "WINE_DISABLE_FULLSCREEN_HACK" to "1",
        "WINEDLLOVERRIDES" to "winemenubuilder.exe,mscoree,mshtml=d",
        "WINEDEBUG" to "-all,err+all"
    ) + mapOf("PULSE_SERVER" to "unix:${pulseSocket.path}", "PULSE_LATENCY_MSEC" to "40")

    private fun startAudio() {
        pulseSocket.delete()
        val directory = File(runtime, "pulse")
        val config = File(directory, "astra.pa").apply { writeText("""
            load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=0 socket="${pulseSocket.path}"
            load-module module-aaudio-sink volume=1.0 performance_mode=1
            set-default-sink AAudioSink
        """.trimIndent()) }
        audio = ProcessBuilder("/system/bin/linker64", File(runtime, "libs/usr/lib/libpulseaudio.so").path,
            "--system=false", "--disable-shm=true", "--fail=true", "-n", "--file=${config.path}",
            "--dl-search-path=${directory.path}/modules", "--daemonize=false", "--use-pid-file=false", "--exit-idle-time=-1")
            .directory(directory).redirectErrorStream(true).redirectOutput(File(storage.directory, "audio.log"))
            .apply { environment().putAll(mapOf("HOME" to directory.path, "TMPDIR" to temporary.path,
                "LD_LIBRARY_PATH" to "${runtime.path}/libs/usr/lib:${directory.path}/modules:/system/lib64")) }.start()
        for (attempt in 0 until 40) {
            if (pulseSocket.exists()) return
            check(audio!!.isAlive) { "Le moteur audio n’a pas démarré. Consultez le journal audio." }
            Thread.sleep(50)
        }
        error("Le moteur audio ne répond pas.")
    }

    private fun start(executable: File, args: List<String>, append: Boolean = true): Process =
        ProcessBuilder(listOf("/system/bin/linker64") + (if (arm) listOf(box.path) else emptyList()) + executable.path + args)
            .directory(storage.game).redirectErrorStream(true)
            .redirectOutput(if (append) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log))
            .apply { environment().putAll(environment) }.start()

    fun initialize() {
        startAudio()
        if (File(storage.prefix, ".astra-ready").isFile) return
        storage.prefix.mkdirs()
        val boot = start(wine, listOf("wineboot", "-u"), false)
        try {
            check(boot.waitFor(150, TimeUnit.SECONDS)) { "Initialisation Windows trop longue. Réessayez ; le journal est conservé." }
            check(boot.exitValue() == 0) { "Échec de l’initialisation Windows (${boot.exitValue()})." }
        } finally { if (boot.isAlive) boot.destroyForcibly() }
        check(File(storage.prefix, "drive_c/windows/syswow64/kernel32.dll").isFile) { "Composants Windows 32 bits manquants." }
        File(storage.prefix, ".astra-ready").writeText(WolfRuntimeInstaller.REVISION)
    }

    fun launch(executable: String): Process {
        val file = safeFile(storage.game, executable)
        check(file.isFile && file.extension.equals("exe", true)) { "Exécutable Windows introuvable : $executable" }
        return start(wine, listOf(file.path))
    }
    fun stop() {
        try {
            val server = start(File(wine.parentFile, "wineserver"), listOf("-k"))
            try { server.waitFor(8, TimeUnit.SECONDS) } finally { if (server.isAlive) server.destroyForcibly() }
        } finally { audio?.destroy(); audio = null }
    }
}
