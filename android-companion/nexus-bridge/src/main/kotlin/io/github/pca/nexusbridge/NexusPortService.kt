package io.github.pca.nexusbridge

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import io.github.pca.nexusport.NexusPort
import io.github.pca.nexusport.PackageSignerSource
import io.github.pca.nexusport.PcaSignerTrust
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.NexusSkillHost
import io.github.pca.nexusport.NexusPortCallback
import io.github.pca.nexusport.NexusPortVersion

/**
 * PCA phone app -> bridge. Exported behind the SIGNATURE permission BIND_NEXUS_PORT (an unrelated app cannot bind it), and every call re-checks that the caller is signed with this APK's key, or with a certificate pinned in
 * [PcaSignerTrust.PINNED] (empty by default) (defence in depth: the permission is checked at bind time only; no callback is ever accepted from an unverified process). Binding it also binds the Nexus plugin service in-process, so the plugin is alive while PCA is connected.
 */
class NexusPortService : Service() {
    private val keepPlugin = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }
    private var pluginBound = false

    private fun callerAllowed(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        if (packageManager.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH) return true
        // Not the same key: only a caller whose EVERY package is signed with a certificate PINNED in code is let in (PcaSignerTrust.PINNED is empty by default, so this is a no unless a release pins one).
        if (PcaSignerTrust.PINNED.isEmpty()) return false
        val source = PackageSignerSource(packageManager)
        val pkgs = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return pkgs.isNotEmpty() && pkgs.all { PcaSignerTrust.trusted(source, packageName, it) }
    }

    private inline fun <T> guard(block: () -> T): T {
        if (!callerAllowed()) throw SecurityException("caller is not signed with the PCA key")
        return block()
    }

    private val stub = object : NexusPort.Stub() {
        override fun apiVersion(): Int = guard { NexusPortVersion.CURRENT }

        override fun setCallback(callback: NexusPortCallback?) = guard {
            val r = BridgeHost.renderer
            if (callback == null) { r.events = null; return@guard }
            r.resetEpoch()
            r.events = object : BridgeEvents {
                override fun onAction(slot: Int, actionId: String) { runCatching { callback.onAction(slot, actionId) } }
                override fun onSpeechDone() { runCatching { callback.onSpeechDone() } }
                override fun onForgetAccepted() = Unit      // the legacy acknowledgement carries no generation; the phone only sends the generation-carrying request
                override fun onForgetAcceptedAt(generation: Long) { runCatching { callback.onForgetAcceptedAt(generation) } }
                override fun onListening(active: Boolean) { runCatching { callback.onListening(active) } }
                override fun onAskTranscript(text: String) { runCatching { callback.onAskTranscript(text) } }
                override fun onAskFailed(reason: Int) { runCatching { callback.onAskFailed(reason) } }
            }
            // PCA's process died: nothing may stay on the glasses on its behalf.
            runCatching { callback.asBinder().linkToDeath({ BridgeHost.renderer.hideAll(); BridgeHost.renderer.setFeatures(0); BridgeHost.renderer.events = null; BridgeHost.skillHost = null; BridgeHost.listenAuth = null }, 0) }
            Unit
        }

        override fun setFeatures(mask: Int): Int = guard { BridgeHost.renderer.setFeatures(mask) }

        override fun show(slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, actionIds: Array<out String>?, actionLabels: Array<out String>?): Int = guard {
            BridgeHost.renderer.show(slot, title, text, ttlMs, wake, actionIds?.toList().orEmpty(), actionLabels?.toList().orEmpty())
        }

        override fun showAt(epoch: Long, slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, actionIds: Array<out String>?, actionLabels: Array<out String>?): Int = guard {
            BridgeHost.renderer.showAt(epoch, slot, title, text, ttlMs, wake, actionIds?.toList().orEmpty(), actionLabels?.toList().orEmpty())
        }

        override fun speakAt(epoch: Long, text: String?): Int = guard { BridgeHost.renderer.speakAt(epoch, text) }
        override fun tripAt(epoch: Long): Int = guard { BridgeHost.renderer.tripAt(epoch) }
        override fun forgetHistoryAt(generation: Long): Int = guard { BridgeHost.renderer.forgetHistoryAt(generation) }

        override fun hide(slot: Int): Int = guard { BridgeHost.renderer.hide(slot) }
        override fun speak(text: String?): Int = guard { BridgeHost.renderer.speak(text) }
        override fun stopSpeech(): Int = guard { BridgeHost.renderer.stopSpeech() }
        override fun forgetHistory(): Int = guard { BridgeHost.renderer.forgetHistory() }

        override fun stopListening(): Int = guard { BridgeHost.renderer.stopListening() }

        override fun setSkillHost(host: NexusSkillHost?): Int = guard {
            if (host == null) { BridgeHost.skillHost = null; BridgeHost.listenAuth = null; return@guard NexusResult.OK }
            BridgeHost.listenAuth = { try { host.authorizeListen() } catch (_: RemoteException) { false } catch (_: RuntimeException) { false } }
            BridgeHost.skillHost = SkillHostClient { opId -> try { host.invoke(opId) } catch (_: RemoteException) { null } catch (_: RuntimeException) { null } }
            NexusResult.OK
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        if (!pluginBound) pluginBound = bindService(Intent(this, BridgeNexusService::class.java), keepPlugin, Context.BIND_AUTO_CREATE)
        return stub
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // PCA let go: leave nothing drawn.
        BridgeHost.renderer.hideAll()
        BridgeHost.renderer.setFeatures(0)
        BridgeHost.renderer.events = null
        BridgeHost.skillHost = null; BridgeHost.listenAuth = null
        if (pluginBound) { unbindService(keepPlugin); pluginBound = false }
        return false
    }
}
