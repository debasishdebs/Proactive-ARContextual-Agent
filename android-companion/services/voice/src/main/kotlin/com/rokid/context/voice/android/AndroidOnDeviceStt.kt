package com.rokid.context.voice.android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import com.rokid.context.voice.FallbackGuard
import com.rokid.context.voice.PcmFeeder
import com.rokid.context.voice.SpeechEngine
import com.rokid.context.voice.SttListener
import com.rokid.context.voice.SttResult
import com.rokid.context.voice.SttSession
import com.rokid.context.voice.SttSessionCore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device recognizer only, never the network one (spec §5.1.1), adapted from Live Fit `AndroidOnDeviceStt`.
 * Glasses audio goes through a non-blocking pipe ([PcmFeeder]) and every output is checked by [FallbackGuard]; the
 * phone-mic session lets the on-device recognizer use the phone mic itself. Recognizers live on Main only.
 */
class AndroidOnDeviceStt(context: Context) : SpeechEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pumps = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "stt-feed").apply { isDaemon = true } }

    override fun startExternal(localeTag: String, listener: SttListener): SttSession {
        if (Build.VERSION.SDK_INT < 33) return failed(listener)
        val pipe = try {
            val capacity = AndroidPipes.capacity()
            AndroidPipes.open().let { (read, sink) -> Triple(read, sink, capacity) }
        } catch (e: Exception) {
            Log.w(TAG, "audio pipe unavailable (${e.javaClass.simpleName})"); return failed(listener)
        }
        val read = pipe.first
        val feeder = PcmFeeder(pipe.second, capacityBytes = pipe.third)
        val core = SttSessionCore(listener) { FallbackGuard.suspects(feeder.meter()) }
        val pump = pumps.scheduleWithFixedDelay({ runCatching { feeder.pump() } }, PUMP_MS, PUMP_MS, TimeUnit.MILLISECONDS)
        val recognizer = AtomicReference<SpeechRecognizer?>()
        val released = AtomicBoolean(false)
        fun release() {
            if (!released.compareAndSet(false, true)) return
            pump.cancel(false)
            pumps.execute { feeder.close() }
            main.post {
                recognizer.getAndSet(null)?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
                runCatching { read.close() } // preflight F1: only once the recognizer is gone (it parcels the intent later)
            }
        }
        core.onFinished = ::release
        main.post {
            if (released.get()) return@post
            val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrElse {
                Log.w(TAG, "recognizer unavailable (${it.javaClass.simpleName})"); core.onError(SttSessionCore.ERROR_CLIENT); return@post
            }
            recognizer.set(r)
            r.setRecognitionListener(RecognitionBridge(core))
            r.startListening(SpeechIntents.external(localeTag, read)) // read stays open until release() (preflight F1)
        }
        return object : SttSession {
            override fun feed(pcm: ByteArray) = feeder.offer(pcm)
            // Drain, then EOF: the recognizer finishes. The 20 ms pump keeps running past the drain deadline (it is only
            // checked inside pump), so a recognizer that stopped reading still gets EOF.
            override fun end() { pumps.execute { feeder.finish() } }
            override fun cancel() { main.post { core.cancel() } }
        }
    }

    override fun startPhoneMic(localeTag: String, listener: SttListener): SttSession {
        if (Build.VERSION.SDK_INT < 31) return failed(listener)
        val core = SttSessionCore(listener) { false }
        val recognizer = AtomicReference<SpeechRecognizer?>()
        val released = AtomicBoolean(false)
        core.onFinished = {
            if (released.compareAndSet(false, true)) main.post { recognizer.getAndSet(null)?.let { runCatching { it.cancel() }; runCatching { it.destroy() } } }
        }
        main.post {
            if (released.get()) return@post
            val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrElse {
                Log.w(TAG, "recognizer unavailable (${it.javaClass.simpleName})"); core.onError(SttSessionCore.ERROR_CLIENT); return@post
            }
            recognizer.set(r)
            r.setRecognitionListener(RecognitionBridge(core))
            r.startListening(SpeechIntents.base(localeTag))
        }
        return object : SttSession {
            override fun feed(pcm: ByteArray) = Unit // the recognizer records the phone mic itself
            override fun end() { main.post { recognizer.get()?.let { runCatching { it.stopListening() } } } }
            override fun cancel() { main.post { core.cancel() } }
        }
    }

    private fun failed(listener: SttListener): SttSession {
        main.post { listener.onFinal(SttResult.Failed) }
        return object : SttSession { override fun feed(pcm: ByteArray) = Unit; override fun end() = Unit; override fun cancel() = Unit }
    }

    private companion object { const val TAG = "PcVoice"; const val PUMP_MS = 20L }
}
