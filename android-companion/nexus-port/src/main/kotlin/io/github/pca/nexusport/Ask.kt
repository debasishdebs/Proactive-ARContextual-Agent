package io.github.pca.nexusport

/** Why a Nexus speech session ended without a transcript (wire codes of [NexusPortCallback.onAskFailed]). */
object AskFail {
    const val DENIED_BUSY = 1
    const val NO_SPEECH = 2
    const val OTHER = 3
}

/**
 * Why an assist-button ask was refused or cancelled. NOTHING is delivered to Nexus for it (no notice, no speech): [text] is a fixed, content-free line the PHONE may show on its own screen.
 * [ZONE_PAUSED] is the zone-fence case (a pause zone was approached, entered or went unreadable mid-ask).
 */
enum class AskRefusal(val text: String) {
    ZONE_PAUSED("Paused near a private place"),
    PAUSED("Capture is paused"),
    NOT_WORN("Glasses not worn"),
    DISABLED("Voice ask is off"),
    CANCELLED("Cancelled"),
}

/**
 * The phone app's side of the assist-button ask (T5), implemented over PCA's voice backend (`VoiceGraph`: text in, text out). Called at most once per ask, off the binder thread's lock; never throws.
 * [text] is the transcript the user said: it must NEVER be logged (PcLog rules).
 */
interface NexusAskHandler {
    /**
     * Run the question through PCA's own gates and voice backend, then hand the answer to `NexusHudSink.show(HudEvent.AskAnswer(ref = [askId], ...))` (same sink, same gates; TTS only when the
     * user's TTS switch is on). Do not retry. An answer for a cancelled ask is dropped by the sink ([DropReason.TRIPPED]).
     */
    fun ask(askId: String, text: String)

    /** The ask will not be answered on Nexus: a zone, pause or not-worn trip mid-ask, the feature switched off, or a newer ask. Nothing was delivered. Stop the work if you can. */
    fun refused(askId: String, why: AskRefusal)
}
