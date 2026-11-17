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
    fun show(cmd: NexusCommand): Int
    fun hide(slot: Slot): Int
    fun speak(text: String): Int
    fun stopSpeech(): Int
    fun forgetHistory(): Int
}

/** Inbound from the bridge: carries ids and codes, never text. */
internal interface NexusEvents {
    fun onAction(slot: Int, actionId: String)
    fun onSpeechDone()
    fun onForgetAccepted()
}

/** The AIDL implementation. A dead binder or any throw is [NexusResult.ERROR]; never an exception. */
internal class AidlNexusTransport(private val port: NexusPort) : NexusTransport {
    private inline fun call(block: () -> Int): Int = try { block() } catch (_: RemoteException) { NexusResult.ERROR } catch (_: RuntimeException) { NexusResult.ERROR }

    private val callback = object : NexusPortCallback.Stub() {
        @Volatile var events: NexusEvents? = null
        override fun onAction(slot: Int, actionId: String?) { if (actionId != null) events?.onAction(slot, actionId) }
        override fun onSpeechDone() { events?.onSpeechDone() }
        override fun onForgetAccepted() { events?.onForgetAccepted() }
    }

    override fun setEvents(events: NexusEvents?) = call {
        callback.events = events
        port.setCallback(if (events == null) null else callback)
        NexusResult.OK
    }
    override fun setFeatures(mask: Int) = call { port.setFeatures(mask) }
    override fun show(cmd: NexusCommand) = call {
        port.show(cmd.slot.code, cmd.title, cmd.text, cmd.ttlMs, cmd.wake, cmd.actions.map { it.id }.toTypedArray(), cmd.actions.map { it.label }.toTypedArray())
    }
    override fun hide(slot: Slot) = call { port.hide(slot.code) }
    override fun speak(text: String) = call { port.speak(text) }
    override fun stopSpeech() = call { port.stopSpeech() }
    override fun forgetHistory() = call { port.forgetHistory() }

    companion object {
        fun from(binder: IBinder): AidlNexusTransport = AidlNexusTransport(NexusPort.Stub.asInterface(binder))
    }
}
