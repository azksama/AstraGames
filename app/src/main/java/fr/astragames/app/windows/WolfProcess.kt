package fr.astragames.app.windows

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

internal class WolfProcess(private val context: Context, private val runtime: File, private val storage: WolfGameStorage,
                  socket: String, sharedMemorySocket: String, private val diagnostics: WolfDiagnosticSession? = null) {
    private val arm = android.os.Build.SUPPORTED_ABIS.first() == "arm64-v8a"
    private val wine = File(runtime, "proton/bin/wine")
    private val box = File(runtime, "box64/usr/bin/box64")
    private val temporary = File(context.cacheDir, "wolf-tmp").apply { mkdirs() }
    private val pulseSocket = File(temporary, "pulse.sock")
    private var audio: Process? = null
    private var wineServer: Process? = null
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
        "BOX64_NOBANNER" to "0",
        // LOG=2 traces every wrapped libc call and can rotate away the actual Wine error.
        "BOX64_LOG" to "1",
        "BOX64_SHOWSEGV" to "1",
        "BOX64_SHOWBT" to "1",
        "BOX64_DYNAREC_LOG" to "0",
        "DISPLAY" to ":0",
        "LANG" to "ja_JP.UTF-8",
        "LC_ALL" to "ja_JP.UTF-8",
        "FONTCONFIG_FILE" to fontConfig.path,
        "WINE_DISABLE_FULLSCREEN_HACK" to "1",
        "WINEDLLOVERRIDES" to "winemenubuilder.exe,mscoree,mshtml=d",
        "WINEDEBUG" to if (diagnostics?.verbose == true) "-all,err+all,warn+all,+timestamp,+pid,+tid,+seh,+loaddll" else "-all,err+all"
    ) + mapOf("PULSE_SERVER" to "unix:${pulseSocket.path}", "PULSE_LATENCY_MSEC" to "40")

    private fun startAudio() {
        diagnostics?.stage("Démarrage audio")
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
            .directory(directory).redirectErrorStream(true)
            .apply { if (diagnostics == null) redirectOutput(File(storage.directory, "audio.log")) }
            .apply { environment().putAll(mapOf("HOME" to directory.path, "TMPDIR" to temporary.path,
                "LD_LIBRARY_PATH" to "${runtime.path}/libs/usr/lib:${directory.path}/modules:/system/lib64")) }.start()
        diagnostics?.capture(audio!!, "audio")
        for (attempt in 0 until 40) {
            if (pulseSocket.exists()) return
            if (!audio!!.isAlive) {
                diagnostics?.exited(audio!!, "Audio", audio!!.exitValue())
                error("Le moteur audio n’a pas démarré (${audio!!.exitValue()}). Consultez le rapport de diagnostic.")
            }
            Thread.sleep(50)
        }
        error("Le moteur audio ne répond pas.")
    }

    private fun start(executable: File, args: List<String>, append: Boolean = true,
                      stream: String = "runtime", workingDirectory: File = storage.game): Process {
        val command = listOf("/system/bin/linker64") + (if (arm) listOf(box.path) else emptyList()) + executable.path + args
        diagnostics?.event("Commande : ${command.joinToString(" ")}")
        diagnostics?.event("Dossier de travail : ${workingDirectory.path}; journal=$stream")
        return ProcessBuilder(command)
            .directory(workingDirectory).redirectErrorStream(true)
            .apply { if (diagnostics == null) redirectOutput(if (append) ProcessBuilder.Redirect.appendTo(log) else ProcessBuilder.Redirect.to(log)) }
            .apply { environment().putAll(environment) }.start()
            .also { diagnostics?.capture(it, stream) }
    }

    fun initialize() {
        diagnostics?.event("Environnement runtime :\n${environment.entries.joinToString("\n") { "${it.key}=${it.value}" }}")
        for (file in listOf(wine, File(wine.parentFile, "wineserver"), File(context.applicationInfo.nativeLibraryDir, "libastra_exec_bridge.so")) + if (arm) listOf(box) else emptyList()) {
            diagnostics?.event("Binaire ${file.path} : présent=${file.isFile}, taille=${file.length()}, lisible=${file.canRead()}")
            check(file.isFile && file.canRead()) { "Binaire runtime manquant ou illisible : ${file.name}" }
        }
        if (arm && diagnostics?.verbose == true) {
            diagnostics.stage("Test de démarrage de Box64 (-v), avant Wine")
            val probe = ProcessBuilder("/system/bin/linker64", box.path, "-v").directory(storage.game)
                .redirectErrorStream(true).apply { environment().putAll(environment) }.start()
            diagnostics.capture(probe, "startup")
            try {
                check(probe.waitFor(15, TimeUnit.SECONDS)) { "Box64 ne répond pas au test de démarrage (15 secondes)." }
                diagnostics.exited(probe, "Test Box64", probe.exitValue())
                check(probe.exitValue() == 0) { "Box64 ne démarre pas : ${WolfDiagnosticSession.exitDescription(probe.exitValue())}. Consultez le rapport."
                }
            } finally { if (probe.isAlive) probe.destroyForcibly() }
        }
        startAudio()
        storage.prefix.mkdirs()
        diagnostics?.stage("Démarrage du serveur Windows")
        // A previous daemon may still hold the prefix lock while refusing clients.
        // Stop only this prefix and wait for its lock before owning a foreground server.
        serverCommand("-k", "Nettoyage du serveur Windows", requireSuccess = false)
        serverCommand("-w", "Attente du serveur Windows")
        wineServer = start(File(wine.parentFile, "wineserver"), listOf("-f", "-p60"), stream = "server")
        diagnostics?.event("Serveur Windows supervisé : premier plan, persistance 60 secondes sans client")
        if (File(storage.prefix, ".astra-ready").isFile) { diagnostics?.event("Préfixe Windows déjà initialisé"); return }
        diagnostics?.stage(if (arm) "Initialisation Box64 / Wine (wineboot)" else "Initialisation Wine x86_64 (wineboot)")
        val boot = start(wine, listOf("wineboot", "-u"), false, stream = "startup")
        try {
            check(boot.waitFor(150, TimeUnit.SECONDS)) { "Initialisation Windows interrompue après 150 secondes. Consultez le rapport de diagnostic." }
            diagnostics?.exited(boot, "wineboot", boot.exitValue())
            check(boot.exitValue() == 0) { "Échec de l’initialisation Box64 / Wine : ${WolfDiagnosticSession.exitDescription(boot.exitValue())}." }
        } finally { if (boot.isAlive) boot.destroyForcibly() }
        check(File(storage.prefix, "drive_c/windows/syswow64/kernel32.dll").isFile) { "Composants Windows 32 bits manquants." }
        File(storage.prefix, ".astra-ready").writeText(WolfRuntimeInstaller.REVISION)
    }

    fun launch(executable: String): Process {
        check(wineServer?.isAlive == true) { "Le serveur Windows s’est arrêté avant le jeu. Consultez le journal server." }
        diagnostics?.stage(if (arm) "Lancement du jeu via Box64 / Wine" else "Lancement du jeu via Wine x86_64")
        val file = safeFile(storage.game, executable)
        check(file.isFile && file.extension.equals("exe", true)) { "Exécutable Windows introuvable : $executable" }
        return start(wine, listOf(file.path), workingDirectory = requireNotNull(file.parentFile))
    }
    fun stop() {
        try {
            serverCommand("-k", "Arrêt wineserver", requireSuccess = false)
            serverCommand("-w", "Fin wineserver")
        } finally {
            wineServer?.let { if (it.isAlive) it.destroyForcibly() }
            wineServer = null
            audio?.destroy(); audio = null
        }
    }
    private fun serverCommand(option: String, label: String, requireSuccess: Boolean = true) {
        val command = start(File(wine.parentFile, "wineserver"), listOf(option), stream = "shutdown")
        try {
            check(command.waitFor(15, TimeUnit.SECONDS)) { "$label : délai de 15 secondes dépassé." }
            diagnostics?.exited(command, label, command.exitValue())
            check(!requireSuccess || command.exitValue() == 0) { "$label : code de sortie ${command.exitValue()}." }
        } finally { if (command.isAlive) command.destroyForcibly() }
    }
}
