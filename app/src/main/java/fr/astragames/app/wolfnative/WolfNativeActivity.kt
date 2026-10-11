package fr.astragames.app.wolfnative

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
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
    private val inputManager by lazy { getSystemService(InputManager::class.java) }
    private var inputDevicesObserved = false
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateControllerDevice()
        override fun onInputDeviceChanged(deviceId: Int) = updateControllerDevice()
        override fun onInputDeviceRemoved(deviceId: Int) { releaseInput(); updateControllerDevice() }
    }
    private val inputs = ConcurrentLinkedQueue<(WolfRuntime) -> Unit>()
    private val heldInputs = mutableMapOf<NativeKey, MutableSet<String>>() // Main-thread sources.
    private val heldPhysicalInputs = mutableMapOf<Int, MutableSet<String>>()
    private val pendingFrame = AtomicReference<NativeFrame?>()
    private var tickTask: ScheduledFuture<*>? = null
    private var runtime: WolfRuntime? = null
    private var source: AndroidWolfAssetSource? = null
    private var resources: WolfNativeResources? = null
    private var audio: WolfNativeAudio? = null
    private var speech: WolfNativeSpeech? = null
    private var files: WolfNativeFiles? = null
    private var downloads: WolfNativeDownloads? = null
    private var saves: WolfNativeSaveStore? = null
    private var launchPreferences: WolfNativeLaunchPreferences? = null
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
    @Volatile private var textInputActive = false
    private var menuOverlayShown = false
    @Volatile private var failed = false
    @Volatile private var ready = false
    private var renderQueued = false
    private val framePacer = WolfNativeFramePacer()
    private var gameFps = 60
    private var textColors: Map<Int, Int> = emptyMap()
    private var fontPaths: Map<Int, String> = emptyMap()
    private var availableFontIds: Set<Int> = setOf(0)
    private var pictureBehaviorVersion = 2
    private var diagnosticFinished = false // Owner-thread only.
    private var phase = 1
    private var phaseCounter: Runnable? = null
    private var nativeStarted = 0L
    private var tickCount = 0L
    private var continuationCount = 0L
    private var videoMasterPercentage: Int? = null // Owner thread; repeated Sys100 writes need no UI dispatch.
    private var totalTickNs = 0L
    private var maxTickNs = 0L
    private var nextRenderMetricAt = 0L // Main thread; keep diagnostics bounded.
    private val dialogs = mutableSetOf<AlertDialog>()
    private val blockingDialogs = mutableSetOf<AlertDialog>()
    private val textInputConfirmations = mutableMapOf<Long, () -> Unit>()
    private val textInputEdits = mutableMapOf<Long, (String) -> Unit>()
    private val pictureMeasurements = mutableMapOf<Long, Runnable>() // Main-thread requests.
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && !closed) onOwner {
            try { saves?.export(requireNotNull(contentResolver.openOutputStream(uri, "wt")), files) }
            catch (error: Exception) { notice("Export impossible", error.message.orEmpty()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        inputManager.registerInputDeviceListener(inputDeviceListener, main)
        inputDevicesObserved = true
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        loading = WolfLoadingView(this, ::closeSession, ::showDiagnostic)
        controls = WolfTouchControls(this, { code, pressed ->
            val mapped = nativeKey(code)
            if (mapped != null) key("controls:${code.name}", mapped, pressed, fr.astragames.app.windows.WolfKeyboard.scan(code))
            else fr.astragames.app.windows.WolfKeyboard.scan(code)?.let { physicalKey("controls:${code.name}", it, pressed) }
        },
            ::closeSession, options, ::showMenu, ::releaseInput, ::menuVisibility).apply { visibility = View.GONE }
        root.addView(loading, FrameLayout.LayoutParams(-1, -1)); root.addView(controls, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            controls.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            loading.setPadding(safe.left, safe.top, safe.right, safe.bottom)
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
            val fileSource = WolfNativeFiles(this, requireNotNull(intent.getStringExtra("id")), assets).also { files = it }
            downloads = WolfNativeDownloads(fileSource)
            val vm = WolfRuntime.load(fileSource) { stage((phase + 1).coerceAtMost(5), it) }.also { runtime = it }
            vm.windowActive(focused && !paused)
            updateControllerDevice()
            launchPreferences = WolfNativeLaunchPreferences(this, requireNotNull(intent.getStringExtra("id"))).also {
                vm.initialNextLaunchScale(it.nextWindowScalePermille)
            }
            stage(6, "Vérification des fonctions du jeu…")
            val capabilities = vm.inspectCapabilities(includeAllMaps = true)
            diagnostic?.event("Audit natif : ${capabilities.scannedMapCount} cartes inspectées ; ${capabilities.issues.size} incompatibilités ; complet=${capabilities.inspectedAllMaps}")
            if (!capabilities.supported) {
                diagnostic?.event("Incompatibilités natives détectées avant lancement :\n${capabilities.description(Int.MAX_VALUE)}")
                if (currentOptions.runtime == WolfRuntimeMode.AUTO) { fallback("Fonctions non encore compatibles :\n${capabilities.description()}"); return }
                fail("Fonctions non encore compatibles :\n${capabilities.description()}"); return
            }
            saves = WolfNativeSaveStore(this, requireNotNull(intent.getStringExtra("id")), assets.metadataFingerprint)
            gameFps = vm.game.config.fps
            pictureBehaviorVersion = if ((vm.game.config.engineCompatibilityVersion ?: 0) >= 300) 3 else 2
            val configuredFonts = listOf(vm.game.config.font) + vm.game.config.subFonts
            availableFontIds = configuredFonts.mapIndexedNotNull { index, font ->
                index.takeIf { index == 0 || font.isNotBlank() }
            }.toSet()
            fontPaths = configuredFonts.mapIndexedNotNull { index, font ->
                if (font.isBlank()) return@mapIndexedNotNull null
                if (!listOf(".ttf", ".otf", ".ttc", ".ttf.wolfx", ".otf.wolfx", ".ttc.wolfx").any { font.endsWith(it, true) }) {
                    return@mapIndexedNotNull WolfNativeResources.fontOverrideReference(font)?.let { index to it }
                }
                val path = WolfParser.normalizePath(font)
                val resolved = if (assets.exists(path)) path else "Data/$path".takeIf(assets::exists)
                require(resolved != null) { "Fichier de police du jeu introuvable : $font" }
                index to resolved
            }.toMap()
            vm.game.databases[WolfDatabaseKind.SYSTEM]?.types?.getOrNull(12)?.let { colors ->
                textColors = colors.rows.associate { row -> row.id to Color.rgb(
                    (colors.number(row.id, 0) ?: 255).coerceIn(0, 255),
                    (colors.number(row.id, 1) ?: 255).coerceIn(0, 255),
                    (colors.number(row.id, 2) ?: 255).coerceIn(0, 255)) }
            }
            audio = WolfNativeAudio(this, fileSource, java.io.File(cacheDir, "wolf-native-audio"), ::fail,
                onPlaybackEvent = { diagnostic?.event("Audio natif : $it") })
            speech = WolfNativeSpeech(this,
                onPlaying = { request, playing -> onOwner { if (!closed && !failed && runtime === vm) vm.speechPlaying(request, playing) } },
                onVoices = { request, voices -> onOwner { if (!closed && !failed && runtime === vm) vm.completeSpeechVoices(request, voices) } },
                onDiagnostic = { message -> onOwner { diagnostic?.event(message) } })
            stage(7, "Préparation des premières ressources…")
            main.post {
                if (closed || failed) return@post
                val fontCache = java.io.File(cacheDir, "wolf-native-fonts")
                val textures = WolfNativeResources(fileSource, { main.post { display?.invalidate() } }, ::fail,
                    fontCacheDirectory = fontCache).also { resources = it }
                configuredFonts.forEachIndexed { index, font ->
                    if (font.isNotBlank() && fontPaths[index] == WolfNativeResources.fontOverrideReference(font)) textures.resolveFontOverride(font) { available ->
                        onOwner { diagnostic?.event(if (available) "Police initiale $index chargée : $font"
                            else "Police initiale $index « $font » indisponible : ${textures.fontUnavailableReason(font)} ; police Android de remplacement") }
                    }
                }
                val view = WolfNativeView(this, textures, WolfNativeView.Callbacks(
                    key = { code, pressed -> key("display", code, pressed) },
                    pointer = { x, y, pressed -> input { it.pointer(x, y, pressed) } },
                    choose = { choice -> input { it.choose(choice) } }, firstFrame = ::firstFrame, error = ::fail,
                    notice = { message -> onOwner { diagnostic?.event(message) } },
                    videoState = { id, state -> input { it.videoState(id, state) } },
                    renderMetrics = { fps, drawMs ->
                        onOwner { runtime?.renderedFps(fps.coerceAtLeast(0.0).toInt()) }
                        val now = SystemClock.elapsedRealtime()
                        if (now >= nextRenderMetricAt) {
                            nextRenderMetricAt = now + 10_000
                            onOwner { diagnostic?.event("Rendu natif : fps=$fps; préparation Canvas=$drawMs ms; écran=${currentOptions.maxFps} FPS maximum; jeu=$gameFps FPS") }
                        }
                    },
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
    private fun advance() = advanceTick(resumeOnly = false)
    private fun advanceTick(resumeOnly: Boolean) {
        if (closed || failed || paused || !focused && !textInputActive || menuPaused) return
        try {
            val vm = runtime ?: return
            if (resumeOnly && !vm.hasPendingTick) return
            var count = 0
            while (count++ < 256) (inputs.poll() ?: break)(vm)
            val begin = System.nanoTime()
            val previousTicks = vm.ticks
            var continuations = 0
            do {
                vm.tick()
                val effects = vm.drainEffects()
                for (effect in effects) when (effect) {
                is NativeEffect.Audio -> audio?.apply(effect, gameFps)
                is NativeEffect.AudioGain -> {
                    audio?.setGlobalGain(effect.channel, effect.percentage)
                    if (effect.channel == 0 && videoMasterPercentage != effect.percentage) {
                        videoMasterPercentage = effect.percentage
                        main.post { if (!closed) display?.setVideoAudioMaster(effect.percentage) }
                    }
                }
                is NativeEffect.AudioCache -> audio?.cache(effect)
                is NativeEffect.Speech -> speech?.speak(effect)
                is NativeEffect.SpeechVoices -> speech?.voices(effect)
                NativeEffect.StopSpeech -> speech?.stop()
                is NativeEffect.FileOperation -> files?.execute(effect) { result -> onOwner {
                    if (!failed && runtime === vm) try { vm.completeFileOperation(effect.requestId, result.getOrThrow()) }
                    catch (error: Exception) { diagnostic?.failure("Fichier Wolf ${effect.path}", error); fail(error.message ?: "Opération de fichier Wolf échouée") }
                } }
                is NativeEffect.Download -> downloads?.execute(effect, progress = { progress -> onOwner {
                    if (!failed && runtime === vm) vm.downloadProgress(effect.requestId, progress)
                } }) { result -> onOwner {
                    if (!failed && runtime === vm) vm.completeDownload(effect.requestId, result)
                } }
                is NativeEffect.CancelDownload -> downloads?.cancel(effect.requestId)
                is NativeEffect.NextLaunchScale -> {
                    launchPreferences?.setNextWindowScale(effect.permille)
                    diagnostic?.event("Fenêtre Wolf au prochain lancement : ${effect.permille / 10.0}% ; Android adapte la fenêtre à l’écran et conserve le mode d’affichage choisi.")
                }
                is NativeEffect.MeasurePicture -> measurePicture(vm, effect)
                is NativeEffect.FontOverride -> resources?.resolveFontOverride(effect.name) { available -> onOwner {
                    if (failed || runtime !== vm) return@onOwner
                    if (available && effect.name.isNotBlank()) diagnostic?.event("Police Wolf ${effect.index} chargée : ${effect.name}")
                    else if (!available) diagnostic?.event("Police Wolf « ${effect.name} » indisponible : ${resources?.fontUnavailableReason(effect.name)} ; police initiale conservée")
                    try { vm.completeFontOverride(effect.requestId, available) }
                    catch (error: Exception) { diagnostic?.failure("Changement de police Wolf", error); fail(error.message ?: "Changement de police Wolf impossible") }
                } }
                is NativeEffect.MaskingImage -> resources?.loadVisibilityImage(effect.path) { result ->
                    onOwner {
                        if (failed || runtime !== vm) return@onOwner
                        try { vm.completeMaskImage(effect.requestId, result.getOrThrow()) }
                        catch (error: Exception) { diagnostic?.failure("Masque de visibilité ${effect.path}", error); fail(error.message ?: "Masque de visibilité illisible") }
                    }
                }
                is NativeEffect.TextInput -> textInput(vm, effect)
                is NativeEffect.ConfirmTextInput -> main.post { textInputConfirmations[effect.requestId]?.invoke() }
                is NativeEffect.UpdateTextInput -> main.post { textInputEdits[effect.requestId]?.invoke(effect.text) }
                is NativeEffect.WindowTitle -> main.post {
                    title = effect.title
                    @Suppress("DEPRECATION")
                    setTaskDescription(android.app.ActivityManager.TaskDescription(effect.title))
                }
                is NativeEffect.Save -> try {
                    saves!!.writeData(effect.key ?: "slot:${effect.slot}", WolfSaveData(snapshot = vm.snapshot()))
                    vm.completeHostRequest(true)
                    diagnostic?.event("Sauvegarde native ${effect.slot} terminée")
                } catch (error: Exception) { vm.completeHostRequest(false); diagnostic?.failure("Sauvegarde native ${effect.slot}", error) }
                is NativeEffect.Load -> try {
                    val key = effect.key ?: "slot:${effect.slot}"
                    if (saves!!.exists(key)) { vm.restoreSaveData(saves!!.readData(key)); vm.completeHostRequest(true) }
                    else vm.completeHostRequest(false)
                }
                    catch (error: Exception) { vm.completeHostRequest(false); diagnostic?.failure("Chargement natif ${effect.slot}", error) }
                is NativeEffect.ReadSaveValue -> {
                    val store = requireNotNull(saves)
                    if (!store.exists(effect.key)) vm.completeSaveValue(effect.requestId, false)
                    else {
                        val data = store.readData(effect.key)
                        val hasOverride = if (effect.stringValue) effect.variableCode in data.strings else effect.variableCode in data.numbers
                        vm.completeSaveValue(effect.requestId, true, data.numbers[effect.variableCode] ?: 0,
                            data.strings[effect.variableCode].orEmpty(), if (hasOverride) null else data.snapshot)
                    }
                }
                is NativeEffect.WriteSaveValue -> {
                    val store = requireNotNull(saves)
                    val data = if (store.exists(effect.key)) store.readData(effect.key) else WolfSaveData()
                    val text = effect.text
                    // Mutable database fields belong to a full game snapshot. Wolf's partial
                    // save files deliberately ignore this category instead of creating an overlay.
                    val updated = if (effect.variableCode in 1_100_000_000..1_199_999_999 && data.snapshot == null) data
                        else if (text != null) data.copy(strings = data.strings + (effect.variableCode to text))
                        else data.copy(numbers = data.numbers + (effect.variableCode to requireNotNull(effect.number)))
                    store.writeData(effect.key, updated); vm.completeSaveWrite(effect.requestId, true)
                }
                is NativeEffect.Diagnostic -> diagnostic?.event(effect.message)
                NativeEffect.Quit -> main.post(::closeSession)
                }
                continuations++
                // Synchronous completions continue the SAME tick. A pending asynchronous
                // request yields immediately; its owner callback resumes it when ready.
                // The VM still shares its instruction budget across these continuations.
                if (effects.isEmpty()) break
            } while (!closed && !failed && vm.hasPendingTick && continuations < 32 && System.nanoTime() - begin < 4_000_000L)
            val elapsed = System.nanoTime() - begin
            val advanced = (vm.ticks - previousTicks).coerceAtLeast(0)
            tickCount += advanced
            continuationCount += continuations
            totalTickNs += elapsed; maxTickNs = maxOf(maxTickNs, elapsed)
            if (advanced > 0 && tickCount % (gameFps * 10) == 0L) {
                diagnostic?.event("Ticks natifs : moyenne=${totalTickNs / tickCount / 1_000_000.0} ms; max=${maxTickNs / 1_000_000.0} ms; ticks=$tickCount; reprises=$continuationCount")
            }
            val now = System.nanoTime()
            if (framePacer.shouldPublish(now, currentOptions.maxFps)) publish(vm.frame())
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
    private fun measurePicture(vm: WolfRuntime, effect: NativeEffect.MeasurePicture) = main.post {
        if (closed || failed) return@post
        val deadline = SystemClock.elapsedRealtime() + 30_000
        val request = object : Runnable {
            override fun run() {
                if (closed || failed) { pictureMeasurements.remove(effect.requestId); return }
                try {
                    val measured = display?.measurePicture(effect.picture, effect.fontWeight, effect.fontOverrides)
                    if (measured != null) {
                        pictureMeasurements.remove(effect.requestId)
                        onOwner { if (!failed && runtime === vm) try {
                            vm.completePictureMeasure(effect.requestId, measured.first, measured.second)
                        } catch (error: Exception) {
                            diagnostic?.failure("Lecture des dimensions de l’image ${effect.picture.id}", error)
                            fail(error.message ?: "Les dimensions de l’image n’ont pas pu être transmises au jeu.")
                        } }
                    } else if (SystemClock.elapsedRealtime() >= deadline) {
                        pictureMeasurements.remove(effect.requestId)
                        fail("La mesure de l’image ${effect.picture.id} n’a pas pu se terminer après 30 secondes.")
                    } else main.postDelayed(this, 32)
                } catch (error: Exception) {
                    pictureMeasurements.remove(effect.requestId)
                    fail("Mesure de l’image ${effect.picture.id} impossible : ${error.message}")
                }
            }
        }
        pictureMeasurements.put(effect.requestId, request)?.let(main::removeCallbacks)
        request.run()
    }
    private fun textInput(vm: WolfRuntime, effect: NativeEffect.TextInput) = main.post {
        if (closed || failed || isDestroyed) return@post
        val editor = WolfDialogs.field(this, "Texte du jeu").apply {
            effect.fontName?.let { name -> this@WolfNativeActivity.resources?.namedTypeface(name)?.let { typeface = it } }
            setText(effect.initialText)
            setSelectAllOnFocus(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // Wolf's bound is in Unicode characters; Android's LengthFilter counts UTF-16 units.
            filters = arrayOf(android.text.InputFilter { input, start, end, destination, dstart, dend ->
                val retained = destination.subSequence(0, dstart).toString() + destination.subSequence(dend, destination.length)
                val available = (effect.maxLength - retained.codePointCount(0, retained.length)).coerceAtLeast(0)
                val entered = input.subSequence(start, end).toString()
                if (entered.codePointCount(0, entered.length) <= available) null
                else entered.substring(0, entered.offsetByCodePoints(0, available))
            })
        }
        var answered = false
        fun complete(value: String?) {
            if (answered) return
            answered = true
            textInputConfirmations.remove(effect.requestId)
            textInputEdits.remove(effect.requestId)
            onOwner { if (!failed && runtime === vm) try { vm.completeTextInput(effect.requestId, value) }
            catch (error: Exception) { diagnostic?.failure("Saisie du jeu", error); fail(error.message ?: "Saisie impossible") } }
        }
        val content = WolfDialogs.column(this).apply { addView(editor, LinearLayout.LayoutParams(-1, -2)) }
        val builder = WolfDialogs.builder(this, "Saisie du jeu").setView(content)
            .setCancelable(effect.cancellable).setPositiveButton("Valider") { _, _ -> complete(editor.text.toString()) }
        if (effect.cancellable) builder.setNegativeButton("Annuler") { _, _ -> complete(null) }
            .setOnCancelListener { complete(null) }
        val dialog = trackDialog(builder.create(), pauseGame = false)
        textInputConfirmations[effect.requestId] = { complete(editor.text.toString()); dialog.dismiss() }
        textInputEdits[effect.requestId] = { value ->
            if (editor.text.toString() != value) {
                val atEnd = editor.selectionEnd == editor.length()
                val cursor = editor.selectionStart.coerceAtLeast(0)
                editor.setText(value)
                editor.setSelection(if (atEnd) editor.length() else cursor.coerceAtMost(editor.length()))
            }
        }
        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                onOwner { if (!failed && runtime === vm) vm.editedText(effect.requestId, value) }
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        textInputActive = true
        dialog.setOnShowListener { editor.requestFocus(); dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE) }
        WolfDialogs.show(dialog)
    }
    private fun cancelPictureMeasurements() {
        pictureMeasurements.values.forEach(main::removeCallbacks)
        pictureMeasurements.clear()
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
    private fun key(origin: String, code: NativeKey, pressed: Boolean, scan: Int? = WolfNativeKeyboard.scan(code)) {
        if (pressed && (closed || failed || paused || !focused || menuPaused || lockOverlay != null)) return
        scan?.let { physicalKey(origin, it, pressed) }
        val logical = when (code) { NativeKey.Z, NativeKey.SPACE -> NativeKey.ACCEPT; NativeKey.X -> NativeKey.BACK; else -> code }
        val origins = heldInputs.getOrPut(logical) { mutableSetOf() }
        val wasPressed = origins.isNotEmpty()
        if (pressed) origins.add(origin) else origins.remove(origin)
        val isPressed = origins.isNotEmpty()
        if (wasPressed != isPressed) input { it.logicalKey(logical, isPressed) }
        if (!isPressed) heldInputs.remove(logical)
    }
    private fun physicalKey(origin: String, scan: Int, pressed: Boolean) {
        val origins = heldPhysicalInputs.getOrPut(scan) { mutableSetOf() }
        val wasPressed = origins.isNotEmpty()
        if (pressed) origins.add(origin) else origins.remove(origin)
        val isPressed = origins.isNotEmpty()
        if (wasPressed != isPressed) input { it.physicalKey(scan, isPressed) }
        if (!isPressed) heldPhysicalInputs.remove(scan)
    }
    private fun onOwner(action: () -> Unit) {
        if (closed) return
        try { owner.execute {
            if (!closed) {
                action()
                if (!closed && !failed && runtime?.hasPendingTick == true) advanceTick(resumeOnly = true)
            }
        } }
        catch (_: java.util.concurrent.RejectedExecutionException) { /* Activity closed during dispatch. */ }
    }
    private fun releaseInput() {
        if (::controls.isInitialized) controls.releaseAll()
        display?.releaseInput(); heldInputs.clear(); heldPhysicalInputs.clear(); inputs.clear()
        if (!closed) inputs.offer(WolfRuntime::releaseInput)
    }
    private fun configureView() {
        currentOptions = options.read()
        display?.configure(WolfNativeView.Config(mode = WolfNativeView.DisplayMode.valueOf(currentOptions.imageMode.name),
            smooth = currentOptions.smooth, pinchZoom = currentOptions.pinchZoom, directionalTouch = currentOptions.directionalTouch,
            showFps = currentOptions.showFps, textColors = textColors, fontPaths = fontPaths, availableFontIds = availableFontIds,
            pictureBehaviorVersion = pictureBehaviorVersion,
            backKey = nativeKey(options.binding("back", XKeycode.KEY_ESC)) ?: NativeKey.BACK))
        controls.reload()
    }
    private fun showMenu() {
        if (closed || failed || !ready || lockOverlay != null) return
        releaseInput()
        showDialog(WolfDialogs.builder(this, "Wolf · moteur Android")
            .setAdapter(WolfDialogs.rows(this, listOf("Réglages du jeu", "Sauvegarder · emplacement 0", "Charger une sauvegarde native", "Exporter les sauvegardes natives", "Diagnostic", "Relancer avec Winlator"))) { _, index -> when (index) {
                0 -> trackDialog(showWolfOptions(this, options, ::configureView))
                1 -> onOwner { try { saves!!.write(0, runtime!!.snapshot()); notice("Sauvegarde native", "Emplacement 0 enregistré dans Astra.") } catch (error: Exception) { notice("Sauvegarde impossible", error.message.orEmpty()) } }
                2 -> onOwner {
                    val slots = saves?.slots().orEmpty()
                    main.post {
                        if (closed || lockOverlay != null) return@post
                        if (slots.isEmpty()) notice("Sauvegardes natives", "Aucune sauvegarde native pour ce jeu.")
                        else showDialog(WolfDialogs.builder(this, "Charger une sauvegarde native").setAdapter(WolfDialogs.rows(this, slots.map { "Emplacement $it" })) { _, row ->
                            onOwner { try { runtime!!.restoreSaveData(saves!!.readData("slot:${slots[row]}")); publish(runtime!!.frame()) } catch (error: Exception) { notice("Chargement impossible", error.message.orEmpty()) } }
                        }.setNegativeButton("Annuler", null))
                    }
                }
                3 -> exportPicker.launch("Astra-Wolf-Saves-Natives.zip")
                4 -> showDiagnostic()
                5 -> fallback("Relance Winlator demandée. La progression native reste dans ses sauvegardes séparées.")
            } }.setNegativeButton("Fermer", null))
    }
    private fun trackDialog(dialog: AlertDialog, pauseGame: Boolean = true): AlertDialog {
        dialogs.add(dialog)
        if (pauseGame) blockingDialogs.add(dialog)
        menuPaused = menuOverlayShown || blockingDialogs.isNotEmpty()
        releaseInput()
        if (pauseGame) suspendMedia()
        dialog.setOnDismissListener {
            dialogs.remove(dialog); blockingDialogs.remove(dialog)
            if (!pauseGame) textInputActive = textInputConfirmations.isNotEmpty()
            menuPaused = menuOverlayShown || blockingDialogs.isNotEmpty(); resumeAudioIfActive()
        }
        return dialog
    }
    private fun menuVisibility(shown: Boolean) {
        menuOverlayShown = shown; menuPaused = shown || blockingDialogs.isNotEmpty()
        if (shown) { releaseInput(); suspendMedia() } else resumeAudioIfActive()
    }
    private fun suspendMedia() { audio?.pause(); display?.suspendVideo(true) }
    private fun resumeAudioIfActive() {
        if (!closed && !failed && !paused && (focused || textInputActive) && !menuPaused && lockOverlay == null) {
            audio?.resume(); display?.suspendVideo(false)
        }
    }
    private fun showDialog(builder: AlertDialog.Builder) { WolfDialogs.show(trackDialog(builder.create())) }
    private fun dismissDialogs() {
        textInputConfirmations.clear(); textInputEdits.clear(); textInputActive = false
        dialogs.toList().forEach { it.dismiss() }; dialogs.clear(); blockingDialogs.clear(); menuOverlayShown = false; menuPaused = false
    }
    private fun notice(title: String, message: String) = main.post {
        if (!closed && !isDestroyed && lockOverlay == null) showDialog(WolfDialogs.builder(this, title).setMessage(message).setPositiveButton("Fermer", null))
    }
    private fun showDiagnostic() {
        if (lockOverlay != null) return
        val report = diagnostic ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val text = runCatching { WolfDiagnostics(this@WolfNativeActivity).report(report.directory) }.getOrElse { it.message.orEmpty() }
            main.post {
                if (!closed && !isDestroyed && lockOverlay == null) showDialog(WolfDialogs.builder(this@WolfNativeActivity, "Diagnostic Wolf natif")
                    .setMessage(text.take(24_000)).setPositiveButton("Fermer", null)
                    .setNeutralButton("Partager le rapport complet") { _, _ -> startActivity(Intent.createChooser(WolfDiagnostics(this@WolfNativeActivity).shareIntent(report.directory), "Diagnostic Wolf")) })
            }
        }
    }
    private fun fail(reason: String) { main.post {
        if (closed || failed || isDestroyed) return@post
        failed = true; ready = false; tickTask?.cancel(false); cancelPictureMeasurements(); releaseInput()
        display?.suspendVideo(true)
        controls.visibility = View.GONE; loading.visibility = View.GONE
        onOwner { downloads?.close(); audio?.close(); speech?.close(); files?.close(); finishDiagnostic("Moteur natif arrêté : $reason") }
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
        closed = true; tickTask?.cancel(false); phaseCounter?.let(main::removeCallbacks); cancelPictureMeasurements(); dismissDialogs(); releaseInput()
        if (inputDevicesObserved) { inputManager.unregisterInputDeviceListener(inputDeviceListener); inputDevicesObserved = false }
        downloads?.close(); files?.close()
        source?.close()
        display?.close(); resources?.close()
        owner.execute { audio?.close(); speech?.close(); finishDiagnostic(result); runtime = null }
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
        paused = true; dismissDialogs(); releaseInput(); suspendMedia()
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
    override fun onPause() { paused = true; onOwner { runtime?.windowActive(false) }; releaseInput(); suspendMedia(); speech?.stop(); super.onPause() }
    override fun onResume() { super.onResume(); if (lockOverlay == null) { paused = false; onOwner { runtime?.windowActive(focused) }; resumeAudioIfActive() } }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); focused = hasFocus
        onOwner { runtime?.windowActive(hasFocus && !paused) }
        if (!hasFocus) { releaseInput(); if (!textInputActive) suspendMedia() } else resumeAudioIfActive()
    }
    override fun onStop() { if (!isChangingConfigurations && settings?.lockOnBackground == true) lockRuntime(); super.onStop() }
    override fun onDestroy() { closeResources("Activité native fermée"); super.onDestroy() }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKey(keyCode, true)) return true
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (hardwareKey(keyCode, false)) return true
        return super.onKeyUp(keyCode, event)
    }
    private fun hardwareKey(code: Int, pressed: Boolean): Boolean {
        val scan = WolfNativeKeyboard.scan(code)
        val button = WolfNativeKeyboard.controllerButton(code)
        if (scan == null && button == null) return false
        if (pressed && (closed || failed || paused || !focused || menuPaused || lockOverlay != null)) return true
        val origin = "hardware:$code"
        // Wolf resolves hardware through Sys52..57. A fixed semantic alias here would
        // confirm/cancel even after the game has reassigned that physical key/button.
        scan?.let { physicalKey(origin, it, pressed) }
        button?.let { input { runtime -> runtime.controllerButton(it, pressed) } }
        return true
    }
    private fun updateControllerDevice() {
        val pad = InputDevice.getDeviceIds().sorted().asSequence().mapNotNull(InputDevice::getDevice)
            .firstOrNull { !it.isVirtual && (it.supportsSource(InputDevice.SOURCE_GAMEPAD) || it.supportsSource(InputDevice.SOURCE_JOYSTICK)) }
        // Android provides the device name, not Wolf's Windows driver classification.
        // UNKNOWN is the documented value for an unclassified attached controller.
        onOwner { runtime?.controllerDevice(pad?.name.orEmpty(), if (pad == null) "" else "UNKNOWN") }
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
}
