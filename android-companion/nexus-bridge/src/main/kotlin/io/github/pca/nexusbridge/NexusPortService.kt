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
import io.github.pca.nexusport.NexusPort
import io.github.pca.nexusport.NexusPortCallback
import io.github.pca.nexusport.NexusPortVersion

/**
 * PCA phone app -> bridge. Exported behind the SIGNATURE permission BIND_NEXUS_PORT (an unrelated app cannot bind it), and every call re-checks that the caller is signed with this APK's key (defence in
 * depth: the permission is checked at bind time only). Binding it also binds the Nexus plugin service in-process, so the plugin is alive while PCA is connected.
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
        return packageManager.checkSignatures(Process.myUid(), uid) == PackageManager.SIGNATURE_MATCH
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
            r.events = object : BridgeEvents {
                override fun onAction(slot: Int, actionId: String) { runCatching { callback.onAction(slot, actionId) } }
                override fun onSpeechDone() { runCatching { callback.onSpeechDone() } }
                override fun onForgetAccepted() { runCatching { callback.onForgetAccepted() } }
            }
            // PCA's process died: nothing may stay on the glasses on its behalf.
            runCatching { callback.asBinder().linkToDeath({ BridgeHost.renderer.hideAll(); BridgeHost.renderer.setFeatures(0); BridgeHost.renderer.events = null }, 0) }
            Unit
        }

        override fun setFeatures(mask: Int): Int = guard { BridgeHost.renderer.setFeatures(mask) }

        override fun show(slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, actionIds: Array<out String>?, actionLabels: Array<out String>?): Int = guard {
            BridgeHost.renderer.show(slot, title, text, ttlMs, wake, actionIds?.toList().orEmpty(), actionLabels?.toList().orEmpty())
        }

        override fun hide(slot: Int): Int = guard { BridgeHost.renderer.hide(slot) }
        override fun speak(text: String?): Int = guard { BridgeHost.renderer.speak(text) }
        override fun stopSpeech(): Int = guard { BridgeHost.renderer.stopSpeech() }
        override fun forgetHistory(): Int = guard { BridgeHost.renderer.forgetHistory() }
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
        if (pluginBound) { unbindService(keepPlugin); pluginBound = false }
        return false
    }
}
