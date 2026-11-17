package io.github.pca.nexusbridge

import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusPin
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import io.github.pca.nexusport.NexusAction
import io.github.pca.nexusport.NexusResult

/** The real [NexusBusClient]: a thin map onto the Rokid Nexus SDK (sdk-v0.27.0). No state, no logging of content, no retries. Exercised on the emulator probe, not in JVM tests. */
class SdkNexusBusClient(
    private val client: () -> NexusPluginClient?,
    private val tts: () -> NexusTtsSession?,
) : NexusBusClient {

    private inline fun guarded(block: () -> NexusSdkResult): Int = try { map(block()) } catch (_: IllegalArgumentException) { NexusResult.INVALID } catch (_: RuntimeException) { NexusResult.ERROR }

    override fun showNotice(title: String, body: String, ttlMs: Long, wake: Boolean, actions: List<NexusAction>): Int = guarded {
        val c = client() ?: return NexusResult.NOT_REGISTERED
        c.showNotice(
            NexusNotice(
                title = title, body = body, ttlMs = ttlMs, wakeDisplay = wake,
                interactive = actions.isNotEmpty(),
                actions = actions.map { NexusNoticeAction(id = it.id, glyph = glyph(it.id), label = it.label) },
            ),
        )
    }

    override fun hideNotice(): Int = guarded { (client() ?: return NexusResult.NOT_REGISTERED).hideNotice() }

    override fun showPin(title: String, lines: List<String>, ttlMs: Long): Int = guarded {
        (client() ?: return NexusResult.NOT_REGISTERED).showPin(NexusPin(title = title, lines = lines, ttlMs = ttlMs))
    }

    override fun hidePin(): Int = guarded { (client() ?: return NexusResult.NOT_REGISTERED).hidePin() }

    override fun speak(text: String): Int = guarded { (tts() ?: return NexusResult.NOT_REGISTERED).speak(text) }

    override fun stopSpeech(): Int = guarded { (tts() ?: return NexusResult.NOT_REGISTERED).stop() }

    override fun forgetNotices(onAccepted: () -> Unit): Int = guarded { (client() ?: return NexusResult.NOT_REGISTERED).forgetNotices(onAccepted) }

    private fun glyph(actionId: String) = when (actionId) {
        "mark" -> "camera"
        "more" -> "phone"
        else -> "cancel"
    }

    private fun map(r: NexusSdkResult): Int = when (r) {
        NexusSdkResult.SENT -> NexusResult.OK
        NexusSdkResult.NOT_REGISTERED -> NexusResult.NOT_REGISTERED
        NexusSdkResult.CAPABILITY_NOT_GRANTED -> NexusResult.NOT_GRANTED
        NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> NexusResult.NOT_AVAILABLE
        NexusSdkResult.INVALID_PAYLOAD -> NexusResult.INVALID
        NexusSdkResult.SURFACE_BUSY -> NexusResult.BUSY
        NexusSdkResult.IMAGE_RATE_LIMITED, NexusSdkResult.TTS_RATE_LIMITED -> NexusResult.RATE_LIMITED
    }
}
