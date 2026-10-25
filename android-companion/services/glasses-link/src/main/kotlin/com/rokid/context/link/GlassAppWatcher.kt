package com.rokid.context.link

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.link.utils.GlassInfo

/**
 * The session-less CXRLink kept bound while our app is out of the glasses' foreground (ported from Live Fit's watcher).
 * Each CxrSession owns its own CXRLink and unbinds it on close, so after GLASSES_EXIT nothing would listen; this one
 * reports our app's resume, and (minimised Option A) carries custom commands both ways as the [BackgroundTransport].
 * [CXRLink.configCXRSession] is local state only, [CXRLink.connect] only binds Hi Rokid's service: no session is opened
 * and appStart is never called, so the HUD is never brought forward. All callbacks arrive on SDK threads.
 */
internal class GlassAppWatcher(
    private val app: Context,
    private val glassesPkg: String,
    /** Debug-only probe (PcLinkProbe): log each custom command received here, by name and size only. */
    private val probe: Boolean = false,
    private val linkState: () -> String = { "?" },
    private val onBound: (Boolean) -> Unit = {},
    private val onCustomCmd: (String, ByteArray?) -> Unit = { _, _ -> },
    /** Fix wave H2: CXR `wearingStatus` (true = worn), reported by the SDK on a SDK thread. */
    private val onWearing: (Boolean) -> Unit = {},
    /** Fix wave I1: a device-info snapshot's `wearingStatus` (parsed: null = unreadable); reported on connect and on every change. */
    private val onWearingSnapshot: (Boolean?) -> Unit = {},
    private val onOurAppResumed: () -> Unit,
) : BackgroundTransport {
    @Volatile private var link: CXRLink? = null

    override val active: Boolean get() = link != null

    override fun start(token: String): Boolean {
        if (link != null) return true
        var built: CXRLink? = null
        try {
            val l = CXRLink(app)
            built = l
            l.configCXRSession(CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, glassesPkg))
            l.setCXRLinkCbk(LinkCbk(onBound, onWearing, onWearingSnapshot, { runCatching { (this.link ?: l).isWearingCheckOn() }.getOrNull() }) { askWearSnapshot(this.link ?: l) }) // CXRLink dereferences it on every service callback
            l.setCXRCustomCmdCbk(ICustomCmdCbk { cmd, bytes ->
                // Counts only: the payload can carry a photo or the wearer's voice.
                if (probe) PcLog.i(PROBE_TAG, "rx cmd=$cmd bytes=${bytes?.size ?: 0} state=${linkState()}")
                onCustomCmd(cmd, bytes)
            })
            l.setCXRGlassAppCbk(object : IGlassAppCbk {
                override fun onGlassAppResume(resumed: Boolean) {
                    PcLog.i(CxrGlassesLink.TAG, "watcher: glasses app resume ours=$resumed")
                    if (resumed) onOurAppResumed()
                }
                override fun onInstallAppResult(ok: Boolean) = Unit
                override fun onUnInstallAppResult(ok: Boolean) = Unit
                override fun onOpenAppResult(ok: Boolean) = Unit
                override fun onStopAppResult(ok: Boolean) = Unit
                override fun onQueryAppResult(ok: Boolean) = Unit
            })
            if (!l.connect(token)) {
                PcLog.w(CxrGlassesLink.TAG, "watcher: bind failed")
                runCatching { l.disconnect() } // Phase 8 C4: a failed bind must not leak the CXRLink
                return false
            }
            link = l
            PcLog.i(CxrGlassesLink.TAG, "watcher: bound in the background (our app out of the glasses' foreground)")
            return true
        } catch (e: Exception) {
            PcLog.w(CxrGlassesLink.TAG, "watcher: start failed (${e.javaClass.simpleName})")
            if (link == null) built?.let { runCatching { it.disconnect() } }
            return false
        }
    }

    /** W1: ask the glasses for a device-info snapshot on every bind, so the wear state is known without waiting for a change. */
    private fun askWearSnapshot(l: CXRLink) {
        runCatching { l.getGlassDeviceInfo() }
            .onFailure { PcLog.w(CxrGlassesLink.TAG, "watcher: device info request failed (${it.javaClass.simpleName})") }
        PcLog.i(CxrGlassesLink.TAG, "watcher: wear check on=${runCatching { l.isWearingCheckOn() }.getOrNull()}")
    }

    override fun send(channel: String, json: String): Int? {
        val l = link ?: return null
        val code = try {
            l.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))
        } catch (e: Exception) {
            PcLog.w(CxrGlassesLink.TAG, "watcher: send $channel threw ${e.javaClass.simpleName}")
            null
        }
        if (probe) PcLog.i(PROBE_TAG, "tx cmd=$channel result=$code state=${linkState()}")
        return code
    }

    override fun stop() {
        val l = link ?: return
        link = null
        try { l.disconnect() } catch (e: Exception) { PcLog.w(CxrGlassesLink.TAG, "watcher: stop failed (${e.javaClass.simpleName})") }
        PcLog.i(CxrGlassesLink.TAG, "watcher: stopped")
    }

    /** Reports the Hi Rokid service bind (onServiceConnected / onServiceDisconnected); everything else is ignored. */
    private class LinkCbk(
        private val onBound: (Boolean) -> Unit,
        private val onWearing: (Boolean) -> Unit,
        private val onSnapshot: (Boolean?) -> Unit,
        private val wearCheckOn: () -> Boolean?,
        private val onBoundHook: () -> Unit,
    ) : ICXRLinkCbk {
        override fun onCXRLConnected(connected: Boolean) {
            onBound(connected)
            if (connected) onBoundHook()
        }
        override fun onGlassBtConnected(connected: Boolean) = Unit
        override fun onGlassDeviceInfo(info: GlassInfo) {
            val raw = info.wearingStatus
            val parsed = WearingStatus.parse(raw)
            PcLog.i(CxrGlassesLink.TAG, "watcher: wearing snapshot raw=${WearingStatus.describe(raw)} parsed=$parsed")
            val apply = WearingStatus.snapshotToApply(parsed, wearCheckOn())
            if (parsed != null && apply == null) PcLog.i(CxrGlassesLink.TAG, "watcher: wearing snapshot ignored (wear check off) raw=${WearingStatus.describe(raw)}")
            onSnapshot(apply)
        }
        override fun onGlassWearingStatus(wearing: Boolean) = onWearing(wearing)
        override fun onGlassAiAssistStart() = Unit
        override fun onGlassAiAssistStop() = Unit
        override fun onGlassAiInterrupt(interrupted: Boolean) = Unit
        override fun onGlassLauncherResume() = Unit
    }

    companion object { const val PROBE_TAG = "PcLinkProbe" }
}
