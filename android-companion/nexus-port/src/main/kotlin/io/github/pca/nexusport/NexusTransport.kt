package io.github.pca.nexusport

import android.os.IBinder
import android.os.RemoteException

/**
 * The thin interface between the sink and "wherever Nexus is": today the bridge APK over AIDL. Option B (PCA's traffic over the Nexus bus) replaces the implementation, not the sink or the gates.
 * INTERNAL to this module: nothing else may call it, which is what [NexusHudSink] being the only caller means (NexusChokePointTest checks the source too).
 */
internal interface NexusTransport {
    /** Where the bridge's inbound events go (the sink). Null clears. */
    fun setEvents(events: NexusEvents?): Int
    fun setFeatures(mask: Int): Int
    /** [epoch]: the sink's trip epoch at admission; the bridge drops the show when it knows of a later trip. */
    fun show(cmd: NexusCommand, epoch: Long): Int
    fun hide(slot: Slot): Int
    fun speak(text: String, epoch: Long): Int
    fun stopSpeech(): Int
    /** [generation]: the wipe debt generation this request covers; the acknowledgement comes back with it ([NexusEvents.onForgetAccepted]). */
    fun forgetHistory(generation: Long): Int
    /** Tells the bridge the latest trip epoch. Best effort, never blocks the sink's callers. */
    fun tripped(epoch: Long): Int
    /** T5: stops the Nexus speech-to-text session of an assist-button ask. */
    fun stopListening(): Int
    /** T6: registers (true) or clears (false) the phone's skill host with the bridge. */
    fun setSkillHost(on: Boolean): Int
}

/** Inbound from the bridge: ids and codes, plus the one assist-button transcript (never logged) and the skill call. */
internal interface NexusEvents {
    fun onAction(slot: Int, actionId: String)
    fun onSpeechDone()
    /** The hub accepted the wipe requested with [generation]. */
    fun onForgetAccepted(generation: Long)
    fun onListening(active: Boolean)
    fun onAskTranscript(text: String)
    fun onAskFailed(reason: Int)
    /** A skill call, answered on the binder thread of the bridge's worker (blocking is fine). Returns the [SkillWire] form. */
    fun invokeSkill(opId: String): String
    /** The bridge asks before it opens a Nexus speech session; false = do not open it. Synchronous; the same gates as a delivery. */
    fun authorizeListen(): Boolean
}

/** The AIDL implementation. A dead binder or any throw is [NexusResult.ERROR]; never an exception. */
internal class AidlNexusTransport(private val port: NexusPort) : NexusTransport {
    private inline fun call(block: () -> Int): Int = try { block() } catch (_: RemoteException) { NexusResult.ERROR } catch (_: RuntimeException) { NexusResult.ERROR }

    private val callback = object : NexusPortCallback.Stub() {
        @Volatile var events: NexusEvents? = null
        override fun onAction(slot: Int, actionId: String?) { if (actionId != null) events?.onAction(slot, actionId) }
        override fun onSpeechDone() { events?.onSpeechDone() }
        // the legacy acknowledgement carries no generation: it cannot be correlated with a debt, so it settles nothing (the sink only sends the generation-carrying request)
        override fun onForgetAccepted() = Unit
        override fun onForgetAcceptedAt(generation: Long) { events?.onForgetAccepted(generation) }
        override fun onListening(active: Boolean) { events?.onListening(active) }
        override fun onAskTranscript(text: String?) { if (text != null) events?.onAskTranscript(text) }
        override fun onAskFailed(reason: Int) { events?.onAskFailed(reason) }
    }

    private val skillHost = object : NexusSkillHost.Stub() {
        override fun invoke(opId: String?): String {
            val e = events
            if (e == null || opId == null) return SkillWire.encode(SkillResult.Refused(SkillCodes.UNAVAILABLE))
            return try { e.invokeSkill(opId) } catch (_: RuntimeException) { SkillWire.encode(SkillResult.Refused(SkillCodes.FAILED)) }
        }

        override fun authorizeListen(): Boolean = try { events?.authorizeListen() == true } catch (_: RuntimeException) { false }
    }
    private val events: NexusEvents? get() = callback.events

    override fun setEvents(events: NexusEvents?) = call {
        callback.events = events
        port.setCallback(if (events == null) null else callback)
        NexusResult.OK
    }
    override fun setFeatures(mask: Int) = call { port.setFeatures(mask) }
    override fun show(cmd: NexusCommand, epoch: Long) = call {
        port.showAt(epoch, cmd.slot.code, cmd.title, cmd.text, cmd.ttlMs, cmd.wake, cmd.actions.map { it.id }.toTypedArray(), cmd.actions.map { it.label }.toTypedArray())
    }
    override fun hide(slot: Slot) = call { port.hide(slot.code) }
    override fun speak(text: String, epoch: Long) = call { port.speakAt(epoch, text) }
    override fun stopSpeech() = call { port.stopSpeech() }
    override fun forgetHistory(generation: Long) = call { port.forgetHistoryAt(generation) }
    override fun tripped(epoch: Long) = call { port.tripAt(epoch) }
    override fun stopListening() = call { port.stopListening() }
    override fun setSkillHost(on: Boolean) = call { port.setSkillHost(if (on) skillHost else null) }

    companion object {
        fun from(binder: IBinder): AidlNexusTransport = AidlNexusTransport(NexusPort.Stub.asInterface(binder))
    }
}
