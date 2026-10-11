package fr.astragames.app.wolfnative

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import fr.astragames.wolf.NativeEffect
import java.io.Closeable
import java.util.Locale
import kotlin.math.pow

/** Android's installed voices replace Windows SAPI; original scripts keep their asynchronous contract. */
internal class WolfNativeSpeech(
    private val context: Context,
    private val onPlaying: (Long, Boolean) -> Unit,
    private val onVoices: (Long, List<String>) -> Unit,
    private val onDiagnostic: (String) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var initializing = false
    private var available: Boolean? = null
    private var closed = false
    private var current: Long? = null
    private val pending = ArrayDeque<() -> Unit>() // Main-thread owned.

    fun speak(effect: NativeEffect.Speech) = main.post {
        if (closed) return@post
        current?.let { onPlaying(it, false) }; current = effect.requestId
        engine?.stop()
        withEngine {
            if (closed || current != effect.requestId) return@withEngine
            val service = engine
            if (available != true || service == null) { onPlaying(effect.requestId, false); return@withEngine }
            try {
                val voices = service.voices.orEmpty().sortedBy { it.name }
                effect.voice?.takeIf(String::isNotBlank)?.let { requested ->
                    val exact = voices.firstOrNull { it.name.equals(requested, true) }
                    val language = legacyLanguage(requested)
                    val localized = language?.let { tag -> voices.firstOrNull {
                        !it.isNetworkConnectionRequired && it.locale.language.equals(tag.language, true) &&
                            (tag.country.isEmpty() || it.locale.country.equals(tag.country, true))
                    } }
                    if (exact != null || localized != null) service.voice = exact ?: localized
                    else onDiagnostic("Voix Wolf « $requested » indisponible ; voix Android par défaut utilisée.")
                }
                service.setSpeechRate(2.0.pow(effect.speed.coerceIn(-10, 10) / 10.0).toFloat())
                val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, effect.volume.coerceIn(0, 100) / 100f) }
                val result = service.speak(effect.text, TextToSpeech.QUEUE_FLUSH, parameters, effect.requestId.toString())
                if (result != TextToSpeech.SUCCESS) {
                    onPlaying(effect.requestId, false); onDiagnostic("La synthèse vocale Android a refusé ce texte.")
                }
            } catch (error: Exception) {
                onPlaying(effect.requestId, false); onDiagnostic("Synthèse vocale Wolf : ${error.message}")
            }
        }
    }

    fun voices(effect: NativeEffect.SpeechVoices) = main.post {
        if (!closed) withEngine {
            val names = if (available == true) runCatching { engine?.voices.orEmpty().map { it.name }.distinct().sorted() }
                .getOrElse { onDiagnostic("Lecture des voix Android impossible : ${it.message}"); emptyList() } else emptyList()
            if (!closed) onVoices(effect.requestId, names)
        }
    }

    fun stop() = main.post {
        current?.let { onPlaying(it, false) }; current = null
        engine?.stop()
    }

    private fun withEngine(action: () -> Unit) {
        if (available != null) { action(); return }
        pending.addLast(action)
        if (initializing) return
        initializing = true
        main.postDelayed({
            if (!closed && available == null) {
                available = false
                onDiagnostic("Le moteur vocal Android ne répond pas après 10 secondes.")
                while (pending.isNotEmpty()) pending.removeFirst().invoke()
            }
        }, 10_000)
        engine = TextToSpeech(context.applicationContext) { status -> main.post {
            if (closed) { engine?.shutdown(); pending.clear(); return@post }
            if (available != null) return@post
            available = status == TextToSpeech.SUCCESS
            if (available != true) onDiagnostic("Synthèse vocale Android indisponible : installer ou activer un moteur vocal dans Android.")
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { update(utteranceId, true) }
                override fun onDone(utteranceId: String?) { update(utteranceId, false) }
                @Deprecated("Legacy Android callback")
                override fun onError(utteranceId: String?) { update(utteranceId, false) }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    update(utteranceId, false)
                    main.post { if (!closed) onDiagnostic("Synthèse vocale Android : erreur $errorCode.") }
                }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { update(utteranceId, false) }
            })
            while (pending.isNotEmpty()) pending.removeFirst().invoke()
        } }
    }

    private fun update(id: String?, playing: Boolean) = main.post {
        val request = id?.toLongOrNull()
        if (!closed && request != null && current == request) onPlaying(request, playing)
    }

    override fun close() { main.post {
        if (!closed) { stop(); closed = true; current = null; pending.clear(); engine?.stop(); engine?.shutdown(); engine = null }
    } }

    private fun legacyLanguage(name: String): Locale? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower == "jp" || lower == "ja" || lower.contains("haruka") || lower.contains("ayumi") || lower.contains("ichiro") -> Locale.JAPAN
            lower == "en" || lower.contains("zira") || lower.contains("david") -> Locale.US
            lower == "fr" || lower.contains("hortense") -> Locale.FRANCE
            lower.matches(Regex("[a-z]{2,3}[-_][a-z]{2}")) -> Locale.forLanguageTag(name.replace('_', '-'))
            else -> null
        }
    }
}
