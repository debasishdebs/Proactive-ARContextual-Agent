package com.rokid.context.voice.android

import android.content.Context
import android.os.Build
import android.speech.ModelDownloadListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The on-device recognizer's language packs (Live Fit `SpeechPacks`): query on API 33+, download on 34+. */
object SpeechPacks {
    data class Packs(val supported: List<String>, val installed: Set<String>, val pending: Set<String>)
    enum class Download { SUCCESS, SCHEDULED, FAILED, UNSUPPORTED }

    private const val QUERY_TIMEOUT_MS = 5_000L
    private const val DOWNLOAD_TIMEOUT_MS = 60_000L

    /** Null when the platform can't answer (below 33, no on-device recognizer, an error or a timeout). */
    suspend fun query(context: Context): Packs? {
        if (Build.VERSION.SDK_INT < 33) return null
        return withContext(Dispatchers.Main) { withTimeoutOrNull(QUERY_TIMEOUT_MS) { query33(context) } }
    }

    @RequiresApi(33)
    private suspend fun query33(context: Context): Packs? {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return null
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.checkRecognitionSupport(SpeechIntents.anyLanguage(), ContextCompat.getMainExecutor(context), object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            val installed = support.installedOnDeviceLanguages.toSet()
                            val pending = support.pendingOnDeviceLanguages.toSet()
                            if (cont.isActive) cont.resume(Packs((support.supportedOnDeviceLanguages + installed + pending).distinct(), installed, pending))
                        }
                        override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
                    })
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    suspend fun download(context: Context, tag: String): Download {
        if (Build.VERSION.SDK_INT < 34) return Download.UNSUPPORTED
        // Neither callback may ever come: after DOWNLOAD_TIMEOUT_MS the pack counts as not available.
        return withContext(Dispatchers.Main) { withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) { download34(context, tag) } ?: Download.FAILED }
    }

    @RequiresApi(34)
    private suspend fun download34(context: Context, tag: String): Download {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return try {
            suspendCancellableCoroutine { cont ->
                recognizer.triggerModelDownload(SpeechIntents.base(tag), ContextCompat.getMainExecutor(context), object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = Unit
                    override fun onSuccess() { if (cont.isActive) cont.resume(Download.SUCCESS) }
                    override fun onScheduled() { if (cont.isActive) cont.resume(Download.SCHEDULED) }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(Download.FAILED) }
                })
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }
}
