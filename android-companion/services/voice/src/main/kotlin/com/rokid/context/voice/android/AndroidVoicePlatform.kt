package com.rokid.context.voice.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.rokid.context.voice.ExternalAudioProbe
import com.rokid.context.voice.PcmFeeder
import com.rokid.context.voice.ProbeFeed
import com.rokid.context.voice.ProbeOutcome
import com.rokid.context.voice.SttListener
import com.rokid.context.voice.SttResult
import com.rokid.context.voice.SttSessionCore
import com.rokid.context.voice.VoicePlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [VoicePlatform] on Android. The probe streams the bundled clip at real-time pace through a pinned, non-blocking pipe. */
class AndroidVoicePlatform(context: Context, private val io: CoroutineDispatcher = Dispatchers.IO) : VoicePlatform {
    private val app = context.applicationContext
    override val sdk: Int = Build.VERSION.SDK_INT

    override fun onDeviceAvailable(): Boolean =
        sdk >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)

    override fun micGranted(): Boolean = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override suspend fun installedPacks(): Set<String>? = SpeechPacks.query(app)?.installed

    override fun fingerprint(): String {
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }.getOrDefault(0L)
        return "${Build.FINGERPRINT}|$version"
    }

    override suspend fun runProbe(localeTag: String): ProbeOutcome? = if (sdk < 33) null else probe(localeTag)

    /**
     * The pipe is pinned and measured first ([AndroidPipes]); through a pipe too big to tell a reader from a non-reader
     * the probe isn't run at all (null: NOT_VERIFIED, never UNSUPPORTED), and a feed pipe that can't be pinned to the
     * measured size throws (NOT_VERIFIED too). The read end stays open until the recognizer is destroyed (preflight F1:
     * it parcels the intent later; phase 0's working probe kept it open), so `peerClosed` can't come from the recognizer
     * closing its copy. A recognizer that errored decides nothing ([ProbeFeed.judge]).
     */
    @RequiresApi(33)
    private suspend fun probe(localeTag: String): ProbeOutcome? {
        val clip = withContext(io) { app.assets.open(ExternalAudioProbe.ASSET).use { it.readBytes() } }
        val capacity = withContext(io) { AndroidPipes.SHARED.capacity() }
        if (!ProbeFeed.canDecide(clip.size, capacity)) {
            Log.i(TAG, "probe skipped: capacity=$capacity clip=${clip.size}")
            return null
        }
        var pipe: AndroidPipes.Feed? = null
        var feeder: PcmFeeder? = null
        var recognizer: SpeechRecognizer? = null
        val final = CompletableDeferred<SttResult>()
        val core = SttSessionCore(object : SttListener {
            override fun onPartial(text: String) = Unit // the verdict is byte-only; text proves nothing
            override fun onFinal(result: SttResult) { final.complete(result) }
        }) { false }
        try {
            // Opened on this thread (non-blocking syscalls) so no dispatch can drop the fds between open and try.
            val p = AndroidPipes.SHARED.openFeed().also { pipe = it }
            val f = PcmFeeder(p.sink, maxPendingBytes = clip.size + ExternalAudioProbe.SILENCE_TAIL_BYTES, capacityBytes = p.capacityBytes)
                .also { feeder = it }
            withContext(Dispatchers.Main) {
                val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
                recognizer = r // destroyed in finally even if starting it throws
                r.setRecognitionListener(RecognitionBridge(core))
                r.startListening(SpeechIntents.external(localeTag, p.read))
            }
            val outcome = withContext(io) { ProbeFeed.stream(f, clip) }
            val ending = withTimeoutOrNull(FINAL_WAIT_MS) { final.await() }
            return ProbeFeed.judge(ending, outcome).also { if (it == null) Log.i(TAG, "probe undecided: recognizer ${ending?.javaClass?.simpleName}") }
        } finally {
            withContext(NonCancellable) {
                feeder?.close() ?: pipe?.sink?.close()
                withContext(Dispatchers.Main) { recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } } }
                pipe?.let { runCatching { it.read.close() } }
            }
        }
    }

    private companion object { const val TAG = "PcVoice"; const val FINAL_WAIT_MS = 4_000L }
}
