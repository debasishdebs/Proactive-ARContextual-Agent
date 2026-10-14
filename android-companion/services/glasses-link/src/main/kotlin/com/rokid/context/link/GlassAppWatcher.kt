package com.rokid.context.link

import android.content.Context
import android.util.Log
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.Wire
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * Watches the glasses' foreground app while no CUSTOM_APP session is open (ported from Live Fit). Each CxrSession owns
 * its own CXRLink and unbinds it on close, so after a close on the glasses nothing is listening; this keeps a
 * separate, session-less CXRLink bound only while the link is "closed on glasses" and reports our app's resume.
 * [CXRLink.configCXRSession] is local state only: no session is opened and nothing is sent to the glasses.
 */
internal class GlassAppWatcher(
    private val app: Context,
    private val glassesPkg: String,
    /** Debug-only probe (PcLinkProbe): log each custom command received here, and send one `pc_hello_request` on bind. */
    private val probe: Boolean = false,
    private val linkState: () -> String = { "?" },
    private val onOurAppResumed: () -> Unit,
) {
    private var link: CXRLink? = null

    val active: Boolean get() = link != null

    fun start(token: String) {
        if (link != null) return
        var built: CXRLink? = null
        try {
            val l = CXRLink(app)
            built = l
            l.configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, glassesPkg))
            l.setCXRLinkCbk(if (probe) ProbeLinkCbk { probeHello(l) } else NoLinkCbk) // CXRLink dereferences it on every service callback
            if (probe) l.setCXRCustomCmdCbk(ICustomCmdCbk { cmd, bytes ->
                // Counts only: the payload can carry a photo or the wearer's voice.
                Log.i(PROBE_TAG, "rx cmd=$cmd bytes=${bytes?.size ?: 0} state=${linkState()}")
            })
            l.setCXRGlassAppCbk(object : IGlassAppCbk {
                override fun onGlassAppResume(resumed: Boolean) {
                    Log.i(CxrGlassesLink.TAG, "watcher: glasses app resume ours=$resumed")
                    if (resumed) onOurAppResumed()
                }
                override fun onInstallAppResult(ok: Boolean) = Unit
                override fun onUnInstallAppResult(ok: Boolean) = Unit
                override fun onOpenAppResult(ok: Boolean) = Unit
                override fun onStopAppResult(ok: Boolean) = Unit
                override fun onQueryAppResult(ok: Boolean) = Unit
            })
            if (!l.connect(token)) {
                Log.w(CxrGlassesLink.TAG, "watcher: bind failed")
                runCatching { l.disconnect() } // Phase 8 C4: a failed bind must not leak the CXRLink
                return
            }
            link = l
            Log.i(CxrGlassesLink.TAG, "watcher: watching for our app reopened on glasses")
        } catch (e: Exception) {
            Log.w(CxrGlassesLink.TAG, "watcher: start failed (${e.javaClass.simpleName})")
            if (link == null) built?.let { runCatching { it.disconnect() } }
        }
    }

    fun stop() {
        val l = link ?: return
        link = null
        try { l.disconnect() } catch (e: Exception) { Log.w(CxrGlassesLink.TAG, "watcher: stop failed (${e.javaClass.simpleName})") }
        Log.i(CxrGlassesLink.TAG, "watcher: stopped")
    }

    /** Probe: one harmless `pc_hello_request` (the glasses answer with `pc_hello`); logs only the result code. */
    private fun probeHello(l: CXRLink) {
        val code = try {
            l.sendCustomCmd(Channels.HELLO_REQUEST, Caps().apply { write(Wire.encode(HelloRequest())) }, ByteArray(0))?.toString() ?: "null"
        } catch (e: Exception) {
            e.javaClass.simpleName
        }
        Log.i(PROBE_TAG, "tx cmd=${Channels.HELLO_REQUEST} result=$code state=${linkState()}")
    }

    /** Like [NoLinkCbk], but reports the Hi Rokid service bind (onServiceConnected) to [onBound]. */
    private class ProbeLinkCbk(private val onBound: () -> Unit) : ICXRLinkCbk by NoLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            Log.i(PROBE_TAG, "bound=$connected")
            if (connected) onBound()
        }
    }

    private object NoLinkCbk : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) = Unit
        override fun onGlassBtConnected(connected: Boolean) = Unit
        override fun onGlassDeviceInfo(info: GlassInfo) = Unit
        override fun onGlassWearingStatus(wearing: Boolean) = Unit
        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
        override fun onGlassLauncherResume() = Unit
    }

    companion object { const val PROBE_TAG = "PcLinkProbe" }
}
