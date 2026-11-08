package fr.astragames.app.mkxpz

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.libsdl.app.SDLActivity
import java.io.File

/** SDL/Ruby own this process; a new launch gets a fresh VM after shutdown. */
class MkxpzActivity : SDLActivity() {
    private val held = mutableSetOf<Int>()
    private val clickPulses = mutableMapOf<Int, Any>()
    private val controlButtons = mutableListOf<View>()
    private var valid = false
    private var lockOnReturn = false
    private var locked = false
    private var unlockPending = false
    private var gameContent: View? = null
    private var lockContent: View? = null

    override fun getLibraries() = MkxpzRuntime.libraries

    override fun onCreate(savedInstanceState: Bundle?) {
        lockOnReturn = intent.getBooleanExtra("lockOnReturn", false)
        locked = lockOnReturn && savedInstanceState != null
        val root = File(filesDir, "mkxpz-games").canonicalFile
        val requested = intent.getStringExtra("bootstrap")?.let(::File)?.canonicalFile
        valid = requested != null && requested.toPath().startsWith(root.toPath()) &&
            requested.name == "bootstrap" && File(requested, "mkxp.json").isFile
        if (valid) GAME_PATH = requested!!.absolutePath
        android.util.Log.i("AstraMkxpz", "Game process page size: " +
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE))
        // SDL must not start with an invalid path, even from a restored Android task.
        if (!valid) {
            // SDL catches this in loadLibraries and shows a recoverable initialization error.
            GAME_PATH = ""
        }
        super.onCreate(savedInstanceState)
        if (!valid) { finish(); return }
        if (mBrokenLibraries) return
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { confirmExit() }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 0)
        }
        fun key(label: String, code: Int) {
            var clickFromTouch = false
            controls.addView(Button(this).apply {
                text = label
                contentDescription = label
                isFocusable = false
                setOnClickListener {
                    // Accessibility activates performClick without touch events.
                    // A touch release already sent its native down/up pair below.
                    if (clickFromTouch || locked || !held.add(code)) return@setOnClickListener
                    val pulse = Any()
                    clickPulses[code] = pulse
                    onNativeKeyDown(code)
                    postDelayed({
                        // A physical press or lifecycle release cancels this pulse;
                        // an old callback must never release a newer held key.
                        if (clickPulses[code] === pulse) {
                            clickPulses.remove(code)
                            if (held.remove(code)) onNativeKeyUp(code)
                        }
                    }, 100)
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            view.isPressed = !locked
                            clickPulses.remove(code)
                            if (!locked && held.add(code)) onNativeKeyDown(code)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.isPressed = false
                            clickPulses.remove(code)
                            if (held.remove(code)) onNativeKeyUp(code)
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                clickFromTouch = true
                                try { view.performClick() } finally { clickFromTouch = false }
                            }
                        }
                    }
                    true
                }
                controlButtons += this
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        key("◀", KeyEvent.KEYCODE_DPAD_LEFT)
        key("▲", KeyEvent.KEYCODE_DPAD_UP)
        key("▼", KeyEvent.KEYCODE_DPAD_DOWN)
        key("▶", KeyEvent.KEYCODE_DPAD_RIGHT)
        key("B", KeyEvent.KEYCODE_ESCAPE)
        key("A", KeyEvent.KEYCODE_ENTER)
        controls.addView(Button(this).apply {
            text = "Quitter"
            isFocusable = false
            setOnClickListener { confirmExit() }
        }, LinearLayout.LayoutParams(0, -1, 1.5f))
        // Keep controls outside the surface so touch never hides game content.
        val surfaceLayout = mLayout
        (surfaceLayout.parent as? android.view.ViewGroup)?.removeView(surfaceLayout)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(surfaceLayout, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
        }
        column.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        gameContent = column
        val lockedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(TextView(this@MkxpzActivity).apply {
                text = "Session MKXP-Z verrouillée"
                textSize = 22f
                gravity = Gravity.CENTER
            })
            addView(Button(this@MkxpzActivity).apply {
                text = "Déverrouiller"
                setOnClickListener { requestUnlock() }
            })
            addView(Button(this@MkxpzActivity).apply {
                text = "Quitter"
                setOnClickListener { confirmExit() }
            })
        }
        lockContent = lockedView
        setContentView(FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(-1, -1))
            addView(lockedView, FrameLayout.LayoutParams(-1, -1))
        })
        showLockState()
        column.requestApplyInsets()
    }

    override fun loadLibraries() {
        check(valid) { "Dossier MKXP-Z invalide. Relancez le jeu depuis Astra." }
        super.loadLibraries()
    }

    override fun onStart() {
        super.onStart()
        // The Android port defers SDL startup to its host activity.
        if (valid && !mBrokenLibraries) resumeNativeThread()
    }

    override fun resumeNativeThread() {
        // SDL also requests resume from onResume. Keep mIsResumedCalled false while
        // locked so its focus/surface callbacks cannot resume native execution either.
        if (!locked) super.resumeNativeThread()
    }

    override fun onStop() {
        if (valid && !mBrokenLibraries && lockOnReturn && !isChangingConfigurations && !isFinishing) {
            locked = true
            releaseKeys()
            pauseNativeThread()
            showLockState()
        }
        super.onStop()
    }

    private fun showLockState() {
        gameContent?.visibility = if (locked) View.INVISIBLE else View.VISIBLE
        gameContent?.importantForAccessibility = if (locked) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        lockContent?.visibility = if (locked) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    private fun requestUnlock() {
        if (!locked || unlockPending) return
        unlockPending = true
        try {
            startActivityForResult(Intent(this, MkxpzUnlockActivity::class.java), UNLOCK_REQUEST)
        } catch (_: Exception) {
            unlockPending = false
            Toast.makeText(this, "Déverrouillage indisponible. Réessayez depuis cette session.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("SDL Activity uses the platform result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != UNLOCK_REQUEST) return
        unlockPending = false
        if (resultCode == RESULT_OK) {
            locked = false
            showLockState()
            if (valid && !mBrokenLibraries) resumeNativeThread()
        }
    }

    @android.annotation.SuppressLint("GestureBackNavigation") // API 26–32 bridge only; API 33+ uses the registered callback.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!locked) return super.dispatchKeyEvent(event)
        if (android.os.Build.VERSION.SDK_INT < 33 && event.keyCode == KeyEvent.KEYCODE_BACK) {
            // Let the legacy Activity track/cancel the key and reach onBackPressed.
            // Current Android delivers Back to OnBackInvokedCallback instead.
            return super.dispatchKeyEvent(event)
        }
        // Dispatch only through the visible Android lock controls, never SDL's input.
        window.superDispatchKeyEvent(event)
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!locked) return super.dispatchGenericMotionEvent(event)
        // Preserve accessibility hover and mouse navigation of the visible controls.
        // The hidden game surface cannot receive them, and no SDL fallback is called.
        window.superDispatchGenericMotionEvent(event)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Toast.makeText(this, "Quittez le jeu en cours avant d'en ouvrir un autre.", Toast.LENGTH_LONG).show()
    }

    private fun releaseKeys() {
        controlButtons.forEach { it.isPressed = false }
        clickPulses.clear()
        held.forEach { onNativeKeyUp(it) }
        held.clear()
    }
    override fun onPause() { releaseKeys(); super.onPause() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) releaseKeys()
        super.onWindowFocusChanged(hasFocus)
    }

    @Deprecated("Android back bridge")
    @android.annotation.SuppressLint("GestureBackNavigation") // API 26–32; API 33+ callback registered above.
    override fun onBackPressed() = confirmExit()

    private fun confirmExit() {
        releaseKeys()
        AlertDialog.Builder(this).setTitle("Quitter le jeu ?")
            .setMessage("Sauvegardez dans le jeu avant de quitter. Les sauvegardes restent dans la copie privée d'Astra.")
            .setNegativeButton("Continuer", null)
            .setPositiveButton("Quitter") { _, _ -> finish() }.show()
    }

    override fun onDestroy() {
        releaseKeys()
        super.onDestroy()
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val UNLOCK_REQUEST = 6031
        @JvmField var GAME_PATH: String = ""
        @JvmStatic fun getSystemLanguage(): String = java.util.Locale.getDefault().toString()
        @JvmStatic fun inMultiWindow(activity: android.app.Activity): Boolean = activity.isInMultiWindowMode
        @Suppress("DEPRECATION")
        private fun vibrator() = getContext()?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        @JvmStatic fun hasVibrator(): Boolean = vibrator()?.hasVibrator() == true
        @JvmStatic fun vibrate(milliseconds: Int) {
            if (milliseconds > 0) vibrator()?.vibrate(android.os.VibrationEffect.createOneShot(
                milliseconds.toLong().coerceAtMost(10_000), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
        @JvmStatic fun vibrateStop() { vibrator()?.cancel() }
        fun intent(context: Context, bootstrap: File, lockOnReturn: Boolean = false) = Intent(context, MkxpzActivity::class.java)
            .putExtra("bootstrap", bootstrap.absolutePath).putExtra("lockOnReturn", lockOnReturn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
