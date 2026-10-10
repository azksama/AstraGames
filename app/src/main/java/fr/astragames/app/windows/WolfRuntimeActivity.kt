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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Astra-owned display, input and session lifecycle. No exported entry point. */
class WolfRuntimeActivity : FragmentActivity() {
    companion object {
        private val sessionMutex = Mutex()
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
    private lateinit var loading: WolfLoadingView
    @Volatile private var loadingPhase = 1
    private var heartbeat: Job? = null
    private var playable = false
    private lateinit var controls: WolfTouchControls
    private var resultLayout: LinearLayout? = null
    private var safeArea = androidx.core.graphics.Insets.NONE
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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // A restored activity must not automatically rerun the game that just killed the process.
        // MainActivity recovers the original report after the app has been unlocked.
        if (savedInstanceState != null) {
            startActivity(Intent(this, fr.astragames.app.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        // Invalid internal arguments must reach begin()'s durable diagnostic handler.
        optionStore = WolfGameOptions(this, intent.getStringExtra("id").orEmpty())
        gameOptions = optionStore.read()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        loading = WolfLoadingView(this, ::closeSession, ::showDiagnostic)
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            safeArea = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            applySafeArea()
            insets
        }
        immerse()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeSession() }
        })
        heartbeat = lifecycleScope.launch {
            while (true) {
                delay(1000); loading.tick()
                if (!playable && !closing) loading.stalled()?.let { (phase, message) ->
                    launch(Dispatchers.IO) { diagnostic?.loadingDelay(phase, message) }
                }
            }
        }
        begin()
    }

    private fun progress(message: String) {
        val phase = loadingPhase
        runOnUiThread { if (!isDestroyed) loading.update(phase, message) }
        diagnostic?.stage(message)
    }
    private fun progress(phase: Int, message: String) { loadingPhase = phase; progress(message) }
    private fun begin() {
        session = lifecycleScope.launch {
            if (sessionMutex.isLocked) withContext(Dispatchers.IO) { progress(1, "Attente de la fermeture de la session précédente…") }
            try { sessionMutex.withLock { runGame() } }
            catch (cancelled: CancellationException) {
                if (closing && !isDestroyed) { heartbeat?.cancel(); finish() }
                throw cancelled
            }
        }
    }
    private suspend fun runGame() = withContext(Dispatchers.IO) {
            var runner: WolfProcess? = null
            var storage: WolfGameStorage? = null
            var failure: String? = null
            var synchronized = false
            try {
                diagnostic = withContext(Dispatchers.IO) {
                    WolfDiagnostics(this@WolfRuntimeActivity).begin(intent.getStringExtra("title") ?: "Wolf RPG", intent.getStringExtra("executable") ?: "?")
                }
                val id = requireNotNull(intent.getStringExtra("id"))
                intent.getStringExtra("nativeFallbackReason")?.let { diagnostic?.event("Secours Winlator après vérification native : ${it.take(12_000)}") }
                val uri = requireNotNull(intent.getStringExtra("source"))
                val executable = requireNotNull(intent.getStringExtra("executable"))
                progress(1, "Lecture des réglages et récupération de la session précédente…")
                settings = withTimeoutOrNull(8000) { settingsRepository.settings.first() }
                    ?: error("La lecture des réglages ne répond pas. Fermez puis relancez Astra.")
                val stopped = runInterruptible(Dispatchers.IO) { WolfProcessRecovery(this@WolfRuntimeActivity).recover(::progress) }
                diagnostic?.event("Récupération : $stopped ancien(s) processus arrêté(s) ; sauvegardes conservées")
                progress(2, "Vérification du moteur Wolf…")
                val runtime = WolfRuntimeInstaller(this@WolfRuntimeActivity).install(::progress)
                progress(3, "Vérification du dossier et récupération de sa configuration…")
                storage = WolfGameStorage(this@WolfRuntimeActivity, id, uri, preferDirect = gameOptions.storageMode == WolfStorageMode.AUTO)
                gameStorage = storage
                storage.prepare(executable, ::progress)
                diagnostic?.event("Stockage du jeu : ${if (storage.isDirect) "dossier d’origine" else "copie privée"}; dossier=${storage.game.path}; préfixe=${storage.prefix.path}")
                val sockets = File(cacheDir, "wolf-sockets").apply { mkdirs() }
                val xSocket = UnixSocketConfig.create(sockets.path, "/x/X0")
                val shmSocket = UnixSocketConfig.create(sockets.path, "/shm/SM0")
                progress(4, "Initialisation de l’affichage X11 et de la mémoire partagée…")
                withContext(Dispatchers.Main) { setupDisplay() }
                x11 = XServerComponent(server, xSocket).also { it.start() }
                shm = SysVSharedMemoryComponent(server, shmSocket).also { it.start() }
                diagnostic?.event("Options : profil=${gameOptions.performance}; écran=${server!!.screenInfo.width}x${server!!.screenInfo.height}; cadrage=${gameOptions.imageMode}; lissage=${gameOptions.smooth}; limite=${gameOptions.maxFps}")
                runner = WolfProcess(this@WolfRuntimeActivity, runtime, storage, xSocket.path, shmSocket.path, diagnostic, gameOptions, ::progress)
                runInterruptible(Dispatchers.IO) { runner.initialize() }
                storage.captureWindowsSaveBaseline()
                display!!.renderer.awaitGameFrame(File(executable).name)
                process = runInterruptible(Dispatchers.IO) { runner.launch(executable) }
                progress(8, "Chargement du jeu · attente de sa première image…")
                val visible = withTimeoutOrNull(180_000) {
                    while (!display!!.renderer.hasPresentedGameFrame()) {
                        check(process!!.isAlive) { "Le jeu s’est arrêté avant sa première image : ${WolfDiagnosticSession.exitDescription(process!!.exitValue())}. Consultez le rapport de diagnostic." }
                        delay(100)
                    }
                    true
                } ?: false
                check(visible) { "Le jeu n’a affiché aucune image après 180 secondes. Consultez le rapport de diagnostic." }
                diagnostic?.stage("Première image du jeu affichée · commandes activées")
                withContext(Dispatchers.Main) {
                    playable = true
                    loading.visibility = View.GONE
                    heartbeat?.cancel()
                    controls.visibility = View.VISIBLE
                    startFpsCounter()
                }
                val result = runInterruptible(Dispatchers.IO) {
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
                if (closing) progress("Fermeture du jeu et synchronisation des sauvegardes…")
                withContext(Dispatchers.Main) {
                    playable = false
                    heartbeat?.cancel(); heartbeat = null
                    if (::controls.isInitialized) controls.visibility = View.GONE
                    fpsLabel?.visibility = View.GONE
                    fpsJob?.cancel(); fpsJob = null
                    releaseKeys()
                }
                withContext(Dispatchers.IO) {
                    runCatching { runner?.stop() }.onFailure { diagnostic?.failure("Échec de l’arrêt du runtime", it) }
                    process?.let { if (it.isAlive) it.destroyForcibly() }
                    if (storage != null && runner != null) {
                        runCatching { diagnostic?.gameErrorLog(safeFile(storage.game, requireNotNull(intent.getStringExtra("executable"))).parentFile!!) }
                        val syncStarted = android.os.SystemClock.elapsedRealtime()
                        runCatching { storage.synchronize(::progress) }.onSuccess { conflicts ->
                            synchronized = conflicts == 0
                            if (conflicts > 0) failure = listOfNotNull(failure, "$conflicts fichier(s) ont changé dans le dossier source. Les sauvegardes restent dans Astra pour éviter un écrasement.").joinToString("\n")
                        }.onFailure {
                            diagnostic?.failure("Synchronisation des sauvegardes", it)
                            failure = listOfNotNull(failure, it.message).joinToString("\n")
                        }
                        diagnostic?.event("Synchronisation terminée en ${android.os.SystemClock.elapsedRealtime() - syncStarted} ms")
                        runCatching { storage.saveFiles() }.onSuccess { files ->
                            diagnostic?.event("Sauvegardes repérées : ${files.size}\n${files.take(100).joinToString("\n") { it.file.path }}")
                        }.onFailure { diagnostic?.failure("Repérage des sauvegardes", it) }
                    }
                    runCatching { storage?.restoreSourceConfiguration() }.onFailure {
                        diagnostic?.failure("Restauration de la configuration du jeu", it)
                        failure = listOfNotNull(failure, it.message).joinToString("\n")
                    }
                }
                runCatching { x11?.stop() }.onFailure { diagnostic?.failure("Arrêt X11", it) }; x11 = null
                runCatching { shm?.stop() }.onFailure { diagnostic?.failure("Arrêt mémoire partagée", it) }; shm = null
                withContext(Dispatchers.IO) {
                    runCatching { diagnostic?.finish(failure ?: if (closing) "Fermeture demandée" else "Partie terminée") }
                }
                withContext(Dispatchers.Main) {
                    if (!isDestroyed) {
                        if (closing && failure == null) finish()
                        else showResult(failure ?: if (synchronized) "Partie terminée. Sauvegardes synchronisées." else "Partie terminée.")
                    }
                }
                }
            }
    }

    private fun setupDisplay() {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        server = XServer(this, ScreenInfo(gameOptions.windowsResolution(landscape)))
        display = XServerView(this, server).also { view ->
            server!!.renderer = view.renderer
            view.renderer.setForceWindowsFullscreen(true)
            view.renderer.setSmoothScaling(gameOptions.smooth)
            view.renderer.setImageScaleMode(gameOptions.imageMode.ordinal)
            view.setMaxFps(gameOptions.maxFps)
            root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
            touchInput = WolfTouchInput({ x, y -> server?.injectPointerMove(x, y) }, { down ->
                if (down) server?.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
                else server?.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            }, { updateKeys("touch", it) }, ::focusGame, {
                val code = optionStore.binding("back", XKeycode.KEY_ESC)
                updateKeys("gesture", setOf(code))
                root.postDelayed({ updateKeys("gesture", emptySet()) }, 100)
            }, { factor, x, y, dx, dy -> view.renderer.zoomImage(factor, x, y, dx, dy) },
                android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat())
            view.setOnTouchListener { _, event ->
                val transform = view.renderer.viewTransformation
                if (!playable || (::controls.isInitialized && controls.editing) || lockOverlay != null) { touchInput?.cancel(); true }
                else touchInput?.touch(event, WolfViewport(transform.viewOffsetX, transform.viewOffsetY,
                    transform.viewWidth, transform.viewHeight, server!!.screenInfo.width.toInt(), server!!.screenInfo.height.toInt(),
                    view.width, view.height), gameOptions.directionalTouch, gameOptions.pinchZoom) ?: true
            }
        }
        controls = WolfTouchControls(this, { key, down ->
            if (down) focusGame()
            val current = keySources["controls"].orEmpty()
            updateKeys("controls", if (down) current + key else current - key)
        }, ::closeSession, optionStore, {
            releaseKeys()
            showWolfOptions(this, optionStore) {
                val previousMode = gameOptions.imageMode
                gameOptions = optionStore.read(); controls.reload()
                display?.renderer?.setSmoothScaling(gameOptions.smooth); display?.setMaxFps(gameOptions.maxFps)
                if (previousMode != gameOptions.imageMode) display?.renderer?.setImageScaleMode(gameOptions.imageMode.ordinal)
                if (!gameOptions.pinchZoom) display?.renderer?.resetImageZoom()
                diagnostic?.event("Cadrage modifié : ${gameOptions.imageMode}")
            }
        }, ::releaseKeys).apply { visibility = View.GONE }
        root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        fpsLabel = TextView(this).apply { visibility = View.GONE; setTextColor(Color.WHITE); setBackgroundColor(0xAA17121E.toInt()); textSize = 12f; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(fpsLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply { leftMargin = dp(8); topMargin = dp(8) })
        applySafeArea()
        lockOverlay?.bringToFront()
    }
    private fun applySafeArea() {
        if (::controls.isInitialized) controls.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
        fpsLabel?.let { label -> (label.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.leftMargin = safeArea.left + dp(8); it.topMargin = safeArea.top + dp(8); label.layoutParams = it
        } }
        loading.setPadding(safeArea.left + dp(24), safeArea.top + dp(24), safeArea.right + dp(24), safeArea.bottom + dp(24))
        resultLayout?.setPadding(safeArea.left + dp(12), safeArea.top + dp(12), safeArea.right + dp(12), safeArea.bottom + dp(12))
        // Compose overlays already consume their own safe drawing/IME insets.
    }
    private fun immerse() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immerse() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        releaseKeys()
        if (::controls.isInitialized) controls.requestLayout()
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
            display?.renderer?.performanceSnapshot
            var started = android.os.SystemClock.elapsedRealtime()
            var seconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                val now = android.os.SystemClock.elapsedRealtime()
                val total = display?.renderer?.contentFrameCount ?: previous
                val fps = (total - previous) * 1000f / (now - started).coerceAtLeast(1)
                fpsLabel?.text = "Images modifiées : %.1f/s".format(java.util.Locale.ROOT, fps)
                fpsLabel?.visibility = if (gameOptions.showFps) View.VISIBLE else View.GONE
                if (++seconds % 10 == 0) {
                    val metrics = display?.renderer?.performanceSnapshot.orEmpty()
                    withContext(Dispatchers.IO) { diagnostic?.event("Images modifiées affichées : %.1f/s\n%s".format(java.util.Locale.ROOT, fps, metrics)) }
                }
                previous = total; started = now
            }
        }
    }
    private fun closeSession() {
        if (closing) return
        closing = true
        if (session?.isActive != true) { finish(); return }
        playable = false
        if (::controls.isInitialized) controls.visibility = View.GONE
        fpsLabel?.visibility = View.GONE
        loading.visibility = View.VISIBLE
        loading.update(loadingPhase, "Fermeture du jeu et synchronisation des sauvegardes…")
        process?.destroy()
        session?.cancel()
    }
    private fun showResult(message: String) {
        root.removeAllViews()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24) }
        resultLayout = layout
        layout.addView(TextView(this).apply { text = message; textSize = 17f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        layout.addView(Button(this).apply { text = "Retour à Astra"; setOnClickListener { finish() } })
        if (diagnostic != null) layout.addView(Button(this).apply { text = "Voir le rapport de diagnostic"; setOnClickListener { showDiagnostic() } })
        if (gameStorage != null) layout.addView(Button(this).apply { text = "Exporter les sauvegardes"; setOnClickListener { exportPicker.launch("Astra-Wolf-sauvegardes.zip") } })
        root.addView(layout, FrameLayout.LayoutParams(-1, -1))
        applySafeArea()
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
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)); applySafeArea() }
    }
    private fun hardwareKey(event: KeyEvent): Boolean {
        if (playable && lockOverlay == null && event.keyCode != KeyEvent.KEYCODE_BACK) {
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
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)); applySafeArea() }
    }
    override fun onStop() { if (!isChangingConfigurations && settings?.lockOnBackground == true) lockRuntime(); super.onStop() }
    override fun onPause() { releaseKeys(); display?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); display?.onResume() }
    override fun onDestroy() { process?.destroy(); session?.cancel(); super.onDestroy() }
}
