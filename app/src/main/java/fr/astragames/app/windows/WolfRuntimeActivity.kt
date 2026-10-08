package fr.astragames.app.windows

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.winlator.widget.XServerView
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.first
import java.io.File

/** Astra-owned display, input and session lifecycle. No exported entry point. */
class WolfRuntimeActivity : FragmentActivity() {
    companion object {
        fun intent(context: Context, id: String, uri: String, executable: String, title: String) =
            Intent(context, WolfRuntimeActivity::class.java).putExtra("id", id).putExtra("source", uri)
                .putExtra("executable", executable).putExtra("title", title)
    }
    private var server: XServer? = null
    private var display: XServerView? = null
    private var x11: XServerComponent? = null
    private var shm: SysVSharedMemoryComponent? = null
    private var session: Job? = null
    private var process: Process? = null
    private val pressed = mutableSetOf<XKeycode>()
    private val keySources = mutableMapOf<String, Set<XKeycode>>()
    private lateinit var optionStore: WolfGameOptions
    private var gameOptions = WolfOptions()
    private var touchInput: WolfTouchInput? = null
    private var fpsJob: Job? = null
    private var fpsLabel: TextView? = null
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private lateinit var controls: WolfTouchControls
    private var closing = false
    private var gameStorage: WolfGameStorage? = null
    private var diagnostic: WolfDiagnosticSession? = null
    private var diagnosticOverlay: ComposeView? = null
    private var lockOverlay: ComposeView? = null
    private var settings: fr.astragames.app.settings.AstraSettings? = null
    private val settingsRepository by lazy { (application as fr.astragames.app.AstraApplication).container.settings }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch {
            runCatching { gameStorage?.exportSaves(requireNotNull(contentResolver.openOutputStream(uri, "wt"))) }
                .onFailure { android.widget.Toast.makeText(this@WolfRuntimeActivity, it.message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restored activity must not automatically rerun the game that just killed the process.
        // MainActivity recovers the original report after the app has been unlocked.
        if (savedInstanceState != null) {
            startActivity(Intent(this, fr.astragames.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        optionStore = WolfGameOptions(this, requireNotNull(intent.getStringExtra("id")))
        gameOptions = optionStore.read()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24)
            text = "Préparation du moteur Wolf…"
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeSession() }
        })
        lifecycleScope.launch {
            settings = settingsRepository.settings.first()
            begin()
        }
    }

    private fun progress(message: String) {
        diagnostic?.stage(message)
        runOnUiThread { if (!isDestroyed) status.text = message }
    }
    private fun begin() {
        session = lifecycleScope.launch {
            var runner: WolfProcess? = null
            var storage: WolfGameStorage? = null
            var failure: String? = null
            var synchronized = false
            try {
                diagnostic = withContext(Dispatchers.IO) {
                    WolfDiagnostics(this@WolfRuntimeActivity).begin(intent.getStringExtra("title") ?: "Wolf RPG", intent.getStringExtra("executable") ?: "?")
                }
                val id = requireNotNull(intent.getStringExtra("id"))
                val uri = requireNotNull(intent.getStringExtra("source"))
                val executable = requireNotNull(intent.getStringExtra("executable"))
                val runtime = WolfRuntimeInstaller(this@WolfRuntimeActivity).install(::progress)
                storage = WolfGameStorage(this@WolfRuntimeActivity, id, uri)
                gameStorage = storage
                storage.prepare(executable, ::progress)
                val sockets = File(cacheDir, "wolf-sockets").apply { mkdirs() }
                val xSocket = UnixSocketConfig.create(sockets.path, "/x/X0")
                val shmSocket = UnixSocketConfig.create(sockets.path, "/shm/SM0")
                diagnostic?.stage("Initialisation de l’affichage X11 et de la mémoire partagée")
                setupDisplay()
                x11 = XServerComponent(server, xSocket).also { it.start() }
                shm = SysVSharedMemoryComponent(server, shmSocket).also { it.start() }
                diagnostic?.event("Options : profil=${gameOptions.performance}; écran=${gameOptions.resolution}; lissage=${gameOptions.smooth}; limite=${gameOptions.maxFps}")
                runner = WolfProcess(this@WolfRuntimeActivity, runtime, storage, xSocket.path, shmSocket.path, diagnostic, gameOptions)
                progress("Initialisation de Windows… Le premier lancement peut prendre quelques minutes.")
                runInterruptible(Dispatchers.IO) { runner.initialize() }
                status.visibility = View.GONE
                controls.visibility = View.VISIBLE
                startFpsCounter()
                val result = runInterruptible(Dispatchers.IO) {
                    process = runner.launch(executable)
                    process!!.waitFor().also { diagnostic?.exited(process!!, "Jeu", it) }
                }
                if (result != 0 && !closing) failure = "Le jeu s’est arrêté : ${WolfDiagnosticSession.exitDescription(result)}. Consultez le rapport de diagnostic."
            } catch (cancelled: CancellationException) { diagnostic?.event("Lancement annulé / fermeture demandée"); throw cancelled }
            catch (error: Throwable) {
                failure = error.message ?: "Le lancement a échoué."
                runCatching { diagnostic?.failure("Échec du lancement", error) }
            }
            finally {
                withContext(NonCancellable) {
                fpsJob?.cancel(); fpsJob = null
                releaseKeys()
                withContext(Dispatchers.IO) {
                    runCatching { runner?.stop() }.onFailure { diagnostic?.failure("Échec de l’arrêt du runtime", it) }
                    process?.let { if (it.isAlive) it.destroyForcibly() }
                    if (storage != null && runner != null) {
                        runCatching { diagnostic?.gameErrorLog(safeFile(storage.game, requireNotNull(intent.getStringExtra("executable"))).parentFile!!) }
                        val syncStarted = android.os.SystemClock.elapsedRealtime()
                        runCatching { storage.synchronize() }.onSuccess { conflicts ->
                            synchronized = conflicts == 0
                            if (conflicts > 0) failure = listOfNotNull(failure, "$conflicts fichier(s) ont changé dans le dossier source. Les sauvegardes restent dans Astra pour éviter un écrasement.").joinToString("\n")
                        }.onFailure {
                            diagnostic?.failure("Synchronisation des sauvegardes", it)
                            failure = listOfNotNull(failure, it.message).joinToString("\n")
                        }
                        diagnostic?.event("Synchronisation terminée en ${android.os.SystemClock.elapsedRealtime() - syncStarted} ms")
                    }
                }
                runCatching { x11?.stop() }.onFailure { diagnostic?.failure("Arrêt X11", it) }; x11 = null
                runCatching { shm?.stop() }.onFailure { diagnostic?.failure("Arrêt mémoire partagée", it) }; shm = null
                withContext(Dispatchers.IO) {
                    runCatching { diagnostic?.finish(failure ?: if (closing) "Fermeture demandée" else "Partie terminée") }
                }
                if (!isDestroyed) {
                    if (closing && failure == null) finish()
                    else showResult(failure ?: if (synchronized) "Partie terminée. Sauvegardes synchronisées." else "Partie terminée.")
                }
                }
            }
        }
    }

    private fun setupDisplay() {
        server = XServer(this, ScreenInfo(gameOptions.resolution))
        display = XServerView(this, server).also { view ->
            server!!.renderer = view.renderer
            view.renderer.setForceWindowsFullscreen(true)
            view.renderer.setSmoothScaling(gameOptions.smooth)
            view.setMaxFps(gameOptions.maxFps)
            root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            touchInput = WolfTouchInput({ x, y -> server?.injectPointerMove(x, y) }, { down ->
                if (down) server?.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                else server?.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }, { updateKeys("touch", it) }, ::focusGame)
            view.setOnTouchListener { _, event ->
                val transform = view.renderer.viewTransformation
                if ((::controls.isInitialized && controls.editing) || lockOverlay != null) { touchInput?.cancel(); true }
                else touchInput?.touch(event, WolfViewport(transform.viewOffsetX, transform.viewOffsetY,
                    transform.viewWidth, transform.viewHeight, server!!.screenInfo.width.toInt(), server!!.screenInfo.height.toInt()), gameOptions.directionalTouch) ?: true
            }
        }
        controls = WolfTouchControls(this, { key, down ->
            if (down) focusGame()
            val current = keySources["controls"].orEmpty()
            updateKeys("controls", if (down) current + key else current - key)
        }, ::closeSession, optionStore, {
            releaseKeys()
            showWolfOptions(this, optionStore) {
                gameOptions = optionStore.read(); controls.reload(); updateControlLayout()
                display?.renderer?.setSmoothScaling(gameOptions.smooth); display?.setMaxFps(gameOptions.maxFps)
            }
        }, ::releaseKeys).apply { visibility = View.GONE }
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        fpsLabel = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xAA17121E.toInt()); textSize = 12f; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(fpsLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply { leftMargin = dp(8); topMargin = dp(8) })
        updateControlLayout()
        lockOverlay?.bringToFront()
    }
    private fun updateControlLayout() {
        val portrait = resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE
        (display?.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.bottomMargin = if (portrait) dp(maxOf(144, 156 * gameOptions.size / 100) + 28) else 0
            display?.layoutParams = it
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        releaseKeys()
        updateControlLayout()
    }
    private fun focusGame() {
        val current = server ?: return
        current.lockAll().use {
            val target = current.windowManager.rootWindow.children.lastOrNull { it.isRenderable && !it.isDesktopWindow }
            if (target != null) current.windowManager.setFocus(target, com.winlator.xserver.WindowManager.FocusRevertTo.PARENT)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun updateKeys(source: String, keys: Set<XKeycode>) {
        keySources[source] = keys
        val wanted = keySources.values.flatten().toSet()
        (pressed - wanted).forEach { server?.injectKeyRelease(it) }
        (wanted - pressed).forEach { server?.injectKeyPress(it) }
        pressed.clear(); pressed.addAll(wanted)
    }
    private fun releaseKeys() {
        touchInput?.cancel()
        if (::controls.isInitialized) controls.releaseAll()
        pressed.forEach { server?.injectKeyRelease(it) }; pressed.clear(); keySources.clear()
    }
    private fun startFpsCounter() {
        fpsJob = lifecycleScope.launch {
            var previous = display?.renderer?.contentFrameCount ?: 0L
            var started = android.os.SystemClock.elapsedRealtime()
            var seconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = android.os.SystemClock.elapsedRealtime()
                val total = display?.renderer?.contentFrameCount ?: previous
                val fps = (total - previous) * 1000f / (now - started).coerceAtLeast(1)
                fpsLabel?.text = "Affichage : %.1f FPS".format(java.util.Locale.ROOT, fps)
                fpsLabel?.visibility = if (gameOptions.showFps) View.VISIBLE else View.GONE
                if (++seconds % 10 == 0) withContext(Dispatchers.IO) { diagnostic?.event("Images reçues affichées : %.1f FPS".format(java.util.Locale.ROOT, fps)) }
                previous = total; started = now
            }
        }
    }
    private fun closeSession() {
        if (closing) return
        closing = true
        if (session?.isActive != true) { finish(); return }
        status.visibility = View.VISIBLE
        progress("Fermeture du jeu et synchronisation des sauvegardes…")
        process?.destroy()
        session?.cancel()
    }
    private fun showResult(message: String) {
        root.removeAllViews()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply { text = message; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        layout.addView(Button(this).apply { text = "Retour à Astra"; setOnClickListener { finish() } })
        if (diagnostic != null) layout.addView(Button(this).apply { text = "Voir le rapport de diagnostic"; setOnClickListener { showDiagnostic() } })
        if (gameStorage != null) layout.addView(Button(this).apply { text = "Exporter les sauvegardes"; setOnClickListener { exportPicker.launch("Astra-Wolf-sauvegardes.zip") } })
        root.addView(layout, FrameLayout.LayoutParams(-1, -1))
        lockOverlay?.let { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }
    private fun showDiagnostic() {
        if (diagnosticOverlay != null || lockOverlay != null) return
        diagnosticOverlay = ComposeView(this).apply {
            setContent {
                fr.astragames.app.ui.theme.AstraTheme {
                    androidx.compose.runtime.CompositionLocalProvider(fr.astragames.app.ui.LocalAppLanguage provides (settings?.language ?: fr.astragames.app.settings.AppLanguage.ENGLISH)) {
                    fr.astragames.app.ui.WolfDiagnosticsDialog(initialReport = diagnostic?.directory, onDismiss = {
                        diagnosticOverlay?.let(root::removeView); diagnosticOverlay = null
                    })
                    }
                }
            }
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }
    private fun hardwareKey(event: KeyEvent): Boolean {
        if (lockOverlay == null && event.keyCode != KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) focusGame()
            val gamepad = when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> XKeycode.KEY_ENTER
                KeyEvent.KEYCODE_BUTTON_B -> XKeycode.KEY_ESC
                KeyEvent.KEYCODE_DPAD_LEFT -> XKeycode.KEY_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> XKeycode.KEY_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> XKeycode.KEY_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> XKeycode.KEY_DOWN
                else -> null
            }
            if (gamepad != null) {
                val held = keySources["gamepad"].orEmpty()
                updateKeys("gamepad", if (event.action == KeyEvent.ACTION_DOWN) held + gamepad else held - gamepad)
                return true
            }
            if (server?.keyboard?.onKeyEvent(event) == true) return true
        }
        return false
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent) = hardwareKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent) = hardwareKey(event) || super.onKeyUp(keyCode, event)
    private fun unlockRuntime() { lockOverlay?.let(root::removeView); lockOverlay = null }
    private fun lockRuntime() {
        val current = settings ?: return
        if (lockOverlay != null || !(current.lockPinEnabled || current.lockBiometricEnabled)) return
        diagnosticOverlay?.let(root::removeView); diagnosticOverlay = null
        releaseKeys()
        lockOverlay = ComposeView(this).apply {
            setContent {
                fr.astragames.app.ui.theme.AstraTheme(hue = current.accentHue) {
                    fr.astragames.app.ui.LockContent(current.lockBiometricEnabled, current.lockPinEnabled, {
                        val prompt = BiometricPrompt(this@WolfRuntimeActivity, ContextCompat.getMainExecutor(this@WolfRuntimeActivity),
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlockRuntime() }
                            })
                        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Astra")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
                    }) { pin, result -> lifecycleScope.launch {
                        val accepted = settingsRepository.verifyPin(pin)
                        result(accepted)
                        if (accepted) unlockRuntime()
                    } }
                }
            }
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
    }
    override fun onStop() { if (!isChangingConfigurations && settings?.lockOnBackground == true) lockRuntime(); super.onStop() }
    override fun onPause() { releaseKeys(); display?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); display?.onResume() }
    override fun onDestroy() { process?.destroy(); session?.cancel(); super.onDestroy() }
}
