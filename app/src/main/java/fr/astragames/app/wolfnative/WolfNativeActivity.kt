package fr.astragames.app.wolfnative

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.winlator.xserver.XKeycode
import fr.astragames.app.windows.*
import fr.astragames.wolf.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** One owner for VM mutations. Android input and rendering never perform game-file I/O. */
class WolfNativeActivity : FragmentActivity() {
    companion object {
        fun intent(context: Context, id: String, uri: String, executable: String, title: String) =
            Intent(context, WolfNativeActivity::class.java).putExtra("id", id).putExtra("source", uri)
                .putExtra("executable", executable).putExtra("title", title)
    }
    private val owner = Executors.newSingleThreadScheduledExecutor { Thread(it, "wolf-native-session").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val inputs = ConcurrentLinkedQueue<(WolfRuntime) -> Unit>()
    private val heldInputs = mutableMapOf<NativeKey, MutableSet<String>>() // Main-thread sources.
    private val pendingFrame = AtomicReference<NativeFrame?>()
    private var tickTask: ScheduledFuture<*>? = null
    private var runtime: WolfRuntime? = null
    private var source: AndroidWolfAssetSource? = null
    private var resources: WolfNativeResources? = null
    private var audio: WolfNativeAudio? = null
    private var saves: WolfNativeSaveStore? = null
    private var diagnostic: WolfDiagnosticSession? = null
    private lateinit var root: FrameLayout
    private lateinit var loading: WolfLoadingView
    private lateinit var controls: WolfTouchControls
    private var display: WolfNativeView? = null
    private lateinit var options: WolfGameOptions
    @Volatile private var currentOptions = WolfOptions()
    private var settings: fr.astragames.app.settings.AstraSettings? = null
    private val settingsRepository by lazy { (application as fr.astragames.app.AstraApplication).container.settings }
    private var lockOverlay: ComposeView? = null
    @Volatile private var closed = false
    @Volatile private var paused = true
    @Volatile private var focused = false
    @Volatile private var menuPaused = false
    @Volatile private var failed = false
    @Volatile private var ready = false
    private var renderQueued = false
    private var nextDisplayAt = 0L
    private var gameFps = 60
    private var textColors: Map<Int, Int> = emptyMap()
    private var diagnosticFinished = false // Owner-thread only.
    private var phase = 1
    private var phaseCounter: Runnable? = null
    private var nativeStarted = 0L
    private var tickCount = 0L
    private var totalTickNs = 0L
    private var maxTickNs = 0L
    private val dialogs = mutableSetOf<AlertDialog>()
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !closed) onOwner {
            try { saves?.export(requireNotNull(contentResolver.openOutputStream(uri, "wt"))) }
            catch (error: Exception) { notice("Export impossible", error.message.orEmpty()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (savedInstanceState != null) {
            startActivity(Intent(this, fr.astragames.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish(); return
        }
        options = WolfGameOptions(this, intent.getStringExtra("id").orEmpty())
        currentOptions = options.read()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        loading = WolfLoadingView(this, ::closeSession, ::showDiagnostic)
        controls = WolfTouchControls(this, { code, pressed -> nativeKey(code)?.let { key -> key("controls", key, pressed) } },
            ::closeSession, options, ::showMenu, ::releaseInput, ::menuVisibility).apply { visibility = View.GONE }
        root.addView(loading, FrameLayout.LayoutParams(-1, -1)); root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controls.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            lockOverlay?.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { closeSession() } })
        nativeStarted = SystemClock.elapsedRealtime()
        phaseCounter = object : Runnable {
            override fun run() {
                if (closed || failed || ready) return
                loading.tick()
                if (SystemClock.elapsedRealtime() - nativeStarted > 180_000) fail("Aucune première image native après 180 secondes.")
                else main.postDelayed(this, 1000)
            }
        }
        main.postDelayed(requireNotNull(phaseCounter), 1000)
        lifecycleScope.launch {
            try { settings = withTimeout(8000) { settingsRepository.settings.first() }; onOwner(::prepare) }
            catch (error: Exception) { if (!closed) fail("Lecture des réglages impossible : ${error.message}") }
        }
    }

    private fun prepare() {
        if (closed) return
        try {
            diagnostic = WolfDiagnostics(this).begin(intent.getStringExtra("title") ?: "Wolf RPG", intent.getStringExtra("executable") ?: "Game.exe", "wolf-native-kotlin-1")
            stage(1, "Accès au dossier d’origine…")
            if (currentOptions.runtime == WolfRuntimeMode.AUTO) {
                val windows = WolfGameStorage(this, requireNotNull(intent.getStringExtra("id")), requireNotNull(intent.getStringExtra("source")))
                if (windows.prefix.isDirectory || java.io.File(windows.directory, "working-mode.json").isFile) {
                    fallback("Ce jeu possède déjà une session Windows. Automatique conserve son moteur et ses sauvegardes ; le moteur natif peut être choisi explicitement dans les réglages.")
                    return
                }
            }
            val assets = AndroidWolfAssetSource(this, android.net.Uri.parse(requireNotNull(intent.getStringExtra("source")))).also { source = it }
            if (closed) { assets.close(); return }
            diagnostic?.event(assets.description)
            val vm = WolfRuntime.load(assets) { stage((phase + 1).coerceAtMost(5), it) }.also { runtime = it }
            stage(6, "Vérification des fonctions du jeu…")
            if (currentOptions.runtime == WolfRuntimeMode.AUTO) {
                val capabilities = vm.inspectCapabilities(includeAllMaps = true)
                if (!capabilities.supported) { fallback("Fonctions non encore compatibles :\n${capabilities.description()}"); return }
            }
            saves = WolfNativeSaveStore(this, requireNotNull(intent.getStringExtra("id")), assets.metadataFingerprint)
            gameFps = vm.game.config.fps
            vm.game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(12)?.let { colors ->
                textColors = colors.rows.associate { row -> row.id to Color.rgb(
                    (colors.number(row.id, 0) ?: 255).coerceIn(0, 255),
                    (colors.number(row.id, 1) ?: 255).coerceIn(0, 255),
                    (colors.number(row.id, 2) ?: 255).coerceIn(0, 255)) }
            }
            audio = WolfNativeAudio(this, assets, java.io.File(cacheDir, "wolf-native-audio"), ::fail,
                onPlaybackEvent = { diagnostic?.event("Audio natif : $it") })
            stage(7, "Préparation des premières ressources…")
            main.post {
                if (closed || failed) return@post
                val textures = WolfNativeResources(assets, { main.post { display?.invalidate() } }, ::fail).also { resources = it }
                val view = WolfNativeView(this, textures, WolfNativeView.Callbacks(
                    key = { code, pressed -> key("display", code, pressed) },
                    pointer = { x, y, pressed -> input { it.pointer(x, y, pressed) } },
                    choose = { choice -> input { it.choose(choice) } }, firstFrame = ::firstFrame, error = ::fail,
                )).also { display = it }
                configureView(); root.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
                if (settings?.lockOnBackground == true && paused) lockRuntime()
                stage(8, "Attente de la première image du jeu…")
                onOwner { if (!failed) {
                    publish(vm.frame()); tickTask = owner.scheduleAtFixedRate(::advance, 0, 1_000_000_000L / gameFps, TimeUnit.NANOSECONDS)
                } }
            }
        } catch (error: Exception) {
            if (closed) return
            diagnostic?.failure("Préparation native interrompue", error)
            if (currentOptions.runtime == WolfRuntimeMode.AUTO) fallback(error.message ?: error.javaClass.simpleName)
            else fail(error.message ?: "Impossible de lire ce jeu Wolf.")
        }
    }
    private fun advance() {
        if (closed || failed || paused || !focused || menuPaused) return
        try {
            val vm = runtime ?: return
            var count = 0
            while (count++ < 256) (inputs.poll() ?: break)(vm)
            val begin = System.nanoTime(); vm.tick()
            val elapsed = System.nanoTime() - begin
            tickCount++; totalTickNs += elapsed; maxTickNs = maxOf(maxTickNs, elapsed)
            for (effect in vm.drainEffects()) when (effect) {
                is NativeEffect.Audio -> audio?.apply(effect, gameFps)
                is NativeEffect.Save -> try {
                    saves!!.write(effect.slot, vm.snapshot()); vm.completeHostRequest(true)
                    diagnostic?.event("Sauvegarde native ${effect.slot} terminée")
                } catch (error: Exception) { vm.completeHostRequest(false); diagnostic?.failure("Sauvegarde native ${effect.slot}", error) }
                is NativeEffect.Load -> try { vm.restore(saves!!.read(effect.slot)); vm.completeHostRequest(true) }
                    catch (error: Exception) { vm.completeHostRequest(false); diagnostic?.failure("Chargement natif ${effect.slot}", error) }
                is NativeEffect.Diagnostic -> diagnostic?.event(effect.message)
                NativeEffect.Quit -> main.post(::closeSession)
            }
            if (tickCount % (gameFps * 10) == 0L) diagnostic?.event("Ticks natifs : moyenne=${totalTickNs / tickCount / 1_000_000.0} ms; max=${maxTickNs / 1_000_000.0} ms; ticks=$tickCount")
            val now = System.nanoTime()
            if (now >= nextDisplayAt) { nextDisplayAt = now + 1_000_000_000L / currentOptions.maxFps; publish(vm.frame()) }
        } catch (error: Exception) {
            diagnostic?.failure("Exécution du jeu natif", error); fail(error.message ?: "Le moteur natif s’est arrêté.")
        }
    }
    private fun publish(frame: NativeFrame) {
        pendingFrame.set(frame)
        synchronized(pendingFrame) {
            if (renderQueued) return
            renderQueued = true
            main.post {
                synchronized(pendingFrame) { renderQueued = false }
                if (!closed && !failed) pendingFrame.getAndSet(null)?.let { display?.submitFrame(it) }
            }
        }
    }
    private fun stage(step: Int, text: String) {
        phase = step; diagnostic?.stage(text)
        main.post { if (!closed && !ready && !failed) loading.update(step, text) }
    }
    private fun firstFrame() {
        if (closed || failed || ready) return
        ready = true; loading.visibility = View.GONE; controls.visibility = View.VISIBLE
        diagnostic?.stage("Première image native affichée · commandes activées")
        diagnostic?.event("Démarrage natif : ${SystemClock.elapsedRealtime() - nativeStarted} ms; ${source?.filesRead} fichiers lus; ${source?.directoriesRead} répertoires consultés; ${source?.bytesRead} octets; aucun transfert général")
    }
    private fun input(action: (WolfRuntime) -> Unit) { if (!closed && !failed && ready && !paused && focused && !menuPaused && lockOverlay == null && inputs.size < 512) inputs.offer(action) }
    private fun key(origin: String, code: NativeKey, pressed: Boolean) {
        if (pressed && (closed || failed || paused || !focused || menuPaused || lockOverlay != null)) return
        val logical = when (code) { NativeKey.Z, NativeKey.SPACE -> NativeKey.ACCEPT; NativeKey.X -> NativeKey.BACK; else -> code }
        val origins = heldInputs.getOrPut(logical) { mutableSetOf() }
        val wasPressed = origins.isNotEmpty()
        if (pressed) origins.add(origin) else origins.remove(origin)
        val isPressed = origins.isNotEmpty()
        if (wasPressed != isPressed) input { it.key(logical, isPressed) }
        if (!isPressed) heldInputs.remove(logical)
    }
    private fun onOwner(action: () -> Unit) {
        if (closed) return
        try { owner.execute { if (!closed) action() } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* Activity closed during dispatch. */ }
    }
    private fun releaseInput() {
        if (::controls.isInitialized) controls.releaseAll()
        display?.releaseInput(); heldInputs.clear(); inputs.clear()
        if (!closed) inputs.offer(WolfRuntime::releaseInput)
    }
    private fun configureView() {
        currentOptions = options.read()
        display?.configure(WolfNativeView.Config(mode = WolfNativeView.DisplayMode.valueOf(currentOptions.imageMode.name),
            smooth = currentOptions.smooth, pinchZoom = currentOptions.pinchZoom, directionalTouch = currentOptions.directionalTouch,
            showFps = currentOptions.showFps, textColors = textColors,
            backKey = nativeKey(options.binding("back", XKeycode.KEY_ESC)) ?: NativeKey.BACK))
        controls.reload()
    }
    private fun showMenu() {
        if (closed || failed || !ready || lockOverlay != null) return
        releaseInput()
        showDialog(AlertDialog.Builder(this).setTitle("Wolf · moteur Android")
            .setItems(arrayOf("Réglages du jeu", "Sauvegarder · emplacement 0", "Charger une sauvegarde native", "Exporter les sauvegardes natives", "Diagnostic", "Relancer avec Winlator")) { _, index -> when (index) {
                0 -> trackDialog(showWolfOptions(this, options, ::configureView))
                1 -> onOwner { try { saves!!.write(0, runtime!!.snapshot()); notice("Sauvegarde native", "Emplacement 0 enregistré dans Astra.") } catch (error: Exception) { notice("Sauvegarde impossible", error.message.orEmpty()) } }
                2 -> onOwner {
                    val slots = saves?.slots().orEmpty()
                    main.post {
                        if (closed || lockOverlay != null) return@post
                        if (slots.isEmpty()) notice("Sauvegardes natives", "Aucune sauvegarde native pour ce jeu.")
                        else showDialog(AlertDialog.Builder(this).setTitle("Charger une sauvegarde native").setItems(slots.map { "Emplacement $it" }.toTypedArray()) { _, row ->
                            onOwner { try { runtime!!.restore(saves!!.read(slots[row])); publish(runtime!!.frame()) } catch (error: Exception) { notice("Chargement impossible", error.message.orEmpty()) } }
                        }.setNegativeButton("Annuler", null))
                    }
                }
                3 -> exportPicker.launch("Astra-Wolf-Saves-Natives.zip")
                4 -> showDiagnostic()
                5 -> fallback("Relance Winlator demandée. La progression native reste dans ses sauvegardes séparées.")
            } }.setNegativeButton("Fermer", null))
    }
    private fun trackDialog(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog); menuPaused = true; releaseInput(); audio?.pause()
        dialog.setOnDismissListener { dialogs.remove(dialog); menuPaused = dialogs.isNotEmpty(); resumeAudioIfActive() }
        return dialog
    }
    private fun menuVisibility(shown: Boolean) {
        menuPaused = shown || dialogs.isNotEmpty()
        if (shown) { releaseInput(); audio?.pause() } else resumeAudioIfActive()
    }
    private fun resumeAudioIfActive() { if (!closed && !failed && !paused && focused && !menuPaused && lockOverlay == null) audio?.resume() }
    private fun showDialog(builder: AlertDialog.Builder) { trackDialog(builder.create()).show() }
    private fun dismissDialogs() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); menuPaused = false }
    private fun notice(title: String, message: String) = main.post {
        if (!closed && !isDestroyed && lockOverlay == null) showDialog(AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Fermer", null))
    }
    private fun showDiagnostic() {
        if (lockOverlay != null) return
        val report = diagnostic ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val text = runCatching { WolfDiagnostics(this@WolfNativeActivity).report(report.directory) }.getOrElse { it.message.orEmpty() }
            main.post {
                if (!closed && !isDestroyed && lockOverlay == null) showDialog(AlertDialog.Builder(this@WolfNativeActivity).setTitle("Diagnostic Wolf natif")
                    .setMessage(text.take(24_000)).setPositiveButton("Fermer", null)
                    .setNeutralButton("Partager le rapport complet") { _, _ -> startActivity(Intent.createChooser(WolfDiagnostics(this@WolfNativeActivity).shareIntent(report.directory), "Diagnostic Wolf")) })
            }
        }
    }
    private fun fail(reason: String) { main.post {
        if (closed || failed || isDestroyed) return@post
        failed = true; ready = false; tickTask?.cancel(false); releaseInput()
        controls.visibility = View.GONE; loading.visibility = View.GONE
        onOwner { audio?.close(); finishDiagnostic("Moteur natif arrêté : $reason") }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(0xEE141018.toInt()); setPadding(32, 32, 32, 32)
            addView(TextView(this@WolfNativeActivity).apply { text = "Ce jeu demande une fonction Wolf encore incompatible\n\n$reason"; setTextColor(Color.WHITE); textSize = 17f })
            addView(Button(this@WolfNativeActivity).apply { text = "Lancer avec Winlator"; setOnClickListener { fallback(reason) } })
            addView(Button(this@WolfNativeActivity).apply { text = "Diagnostic"; setOnClickListener { showDiagnostic() } })
            addView(Button(this@WolfNativeActivity).apply { text = "Fermer"; setOnClickListener { closeSession() } })
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, -1)); lockOverlay?.bringToFront()
    } }
    private fun fallback(reason: String) { main.post {
        if (closed || isDestroyed) return@post
        if (!WolfRuntimeInstaller.supportedAbi()) { fail("Secours Winlator indisponible sur cette architecture.\n$reason"); return@post }
        val next = WolfRuntimeActivity.intent(this, requireNotNull(intent.getStringExtra("id")), requireNotNull(intent.getStringExtra("source")),
            intent.getStringExtra("executable") ?: "Game.exe", intent.getStringExtra("title") ?: "Wolf RPG").putExtra("nativeFallbackReason", reason.take(12_000))
        closeResources("Secours Winlator : $reason"); startActivity(next); finish()
    } }
    private fun closeResources(result: String) {
        if (closed) return
        closed = true; tickTask?.cancel(false); phaseCounter?.let(main::removeCallbacks); dismissDialogs(); releaseInput()
        source?.close()
        display?.close(); resources?.close()
        owner.execute { audio?.close(); finishDiagnostic(result); runtime = null }
        owner.shutdown()
    }
    private fun finishDiagnostic(result: String) {
        if (diagnosticFinished) return
        diagnostic?.finish(result); diagnosticFinished = true
    }
    private fun closeSession() { closeResources("Session native fermée par l’utilisateur"); finish() }
    private fun lockRuntime() {
        val current = settings ?: return
        if (lockOverlay != null || !(current.lockPinEnabled || current.lockBiometricEnabled)) return
        paused = true; dismissDialogs(); releaseInput(); audio?.pause()
        lockOverlay = ComposeView(this).apply {
            setContent {
                fr.astragames.app.ui.theme.AstraTheme(hue = current.accentHue) {
                    fr.astragames.app.ui.LockContent(current.lockBiometricEnabled, current.lockPinEnabled, {
                        BiometricPrompt(this@WolfNativeActivity, ContextCompat.getMainExecutor(this@WolfNativeActivity), object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlockRuntime() }
                        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Astra")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
                    }) { pin, result -> lifecycleScope.launch { val accepted = settingsRepository.verifyPin(pin); result(accepted); if (accepted) unlockRuntime() } }
                }
            }
        }.also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        ViewCompat.requestApplyInsets(root)
    }
    private fun unlockRuntime() { lockOverlay?.let(root::removeView); lockOverlay = null; paused = false; resumeAudioIfActive() }
    override fun onPause() { paused = true; releaseInput(); audio?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); if (lockOverlay == null) { paused = false; resumeAudioIfActive() } }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        if (!hasFocus) { releaseInput(); audio?.pause() } else resumeAudioIfActive()
    }
    override fun onStop() { if (!isChangingConfigurations && settings?.lockOnBackground == true) lockRuntime(); super.onStop() }
    override fun onDestroy() { closeResources("Activité native fermée"); super.onDestroy() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        androidKey(keyCode)?.let { key -> key("keyboard:$keyCode", key, true); return true }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        androidKey(keyCode)?.let { key -> key("keyboard:$keyCode", key, false); return true }
        return super.onKeyUp(keyCode, event)
    }
    private fun nativeKey(code: XKeycode): NativeKey? = when (code) {
        XKeycode.KEY_UP -> NativeKey.UP; XKeycode.KEY_DOWN -> NativeKey.DOWN; XKeycode.KEY_LEFT -> NativeKey.LEFT; XKeycode.KEY_RIGHT -> NativeKey.RIGHT
        XKeycode.KEY_ENTER -> NativeKey.ACCEPT; XKeycode.KEY_ESC -> NativeKey.BACK; XKeycode.KEY_SPACE -> NativeKey.SPACE
        XKeycode.KEY_SHIFT_L -> NativeKey.SHIFT; XKeycode.KEY_CTRL_L -> NativeKey.CONTROL
        XKeycode.KEY_Z -> NativeKey.Z; XKeycode.KEY_X -> NativeKey.X; XKeycode.KEY_C -> NativeKey.C
        XKeycode.KEY_A -> NativeKey.A; XKeycode.KEY_S -> NativeKey.S; XKeycode.KEY_D -> NativeKey.D; XKeycode.KEY_W -> NativeKey.W
        XKeycode.KEY_F1 -> NativeKey.F1; XKeycode.KEY_F5 -> NativeKey.F5; XKeycode.KEY_F12 -> NativeKey.F12
        else -> null
    }
    private fun androidKey(code: Int): NativeKey? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> NativeKey.UP; KeyEvent.KEYCODE_DPAD_DOWN -> NativeKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> NativeKey.LEFT; KeyEvent.KEYCODE_DPAD_RIGHT -> NativeKey.RIGHT
        KeyEvent.KEYCODE_ENTER -> NativeKey.ACCEPT; KeyEvent.KEYCODE_ESCAPE -> NativeKey.BACK; KeyEvent.KEYCODE_SPACE -> NativeKey.SPACE
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> NativeKey.SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> NativeKey.CONTROL
        KeyEvent.KEYCODE_Z -> NativeKey.Z; KeyEvent.KEYCODE_X -> NativeKey.X; KeyEvent.KEYCODE_C -> NativeKey.C
        KeyEvent.KEYCODE_A -> NativeKey.A; KeyEvent.KEYCODE_S -> NativeKey.S; KeyEvent.KEYCODE_D -> NativeKey.D; KeyEvent.KEYCODE_W -> NativeKey.W
        KeyEvent.KEYCODE_F1 -> NativeKey.F1; KeyEvent.KEYCODE_F5 -> NativeKey.F5; KeyEvent.KEYCODE_F12 -> NativeKey.F12
        else -> null
    }
}
