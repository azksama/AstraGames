package fr.astragames.app.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface TextTranslator : AutoCloseable {
    suspend fun prepare(wifiOnly: Boolean)
    suspend fun translate(text: String): String
}

internal class LocalTranslator(source: String, target: String) : TextTranslator {
    private val client = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(requireNotNull(TranslateLanguage.fromLanguageTag(source)))
        .setTargetLanguage(requireNotNull(TranslateLanguage.fromLanguageTag(target))).build())

    override suspend fun prepare(wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        client.downloadModelIfNeeded(conditions).awaitResult()
    }
    override suspend fun translate(text: String): String = client.translate(text).awaitResult()
    override fun close() = client.close()
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
