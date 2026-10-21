package com.rokid.context.phone.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.rokid.context.core.model.context.Evidence
import java.util.concurrent.atomic.AtomicInteger

/**
 * `phone_airplane` (internal; the hub runs it while `phone_activity` or `phone_speed` is on): `Airplane(on)` on every change of
 * the airplane-mode setting, plus its value at start (spec §5). The first value of a run always emits. Counts only.
 */
class AirplaneSource(
    private val sinkFor: (String) -> EvidenceSink,
    private val time: ObsTime = ObsTime(),
    private val platform: Platform,
) : ContextSource {
    interface Platform {
        fun isOn(): Boolean
        fun register(): Boolean
        fun unregister()
    }

    override val id = ID
    private val accepted = AtomicInteger()
    override val events: Int get() = accepted.get()
    private var sink: EvidenceSink? = null
    private var last: Boolean? = null

    override fun available(): Boolean = true

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        sink = sinkFor(ID); last = null
        if (!runCatching { platform.register() }.getOrDefault(false)) { sink = null; return false }
        onAirplane(runCatching { platform.isOn() }.getOrDefault(false)) // registered first, so a change in between is not missed
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        if (sink == null) return
        sink = null; last = null
        runCatching { platform.unregister() }
    }

    /** Emits only when the value changed (the first value of a run counts as a change). */
    @Synchronized
    fun onAirplane(on: Boolean) {
        val out = sink ?: return
        if (last == on) return
        last = on
        accepted.incrementAndGet()
        out.accept(Evidence.Airplane(time.toWall(time.nowElapsed()), on))
    }

    companion object {
        const val ID = "phone_airplane"

        fun create(context: Context, sinkFor: (String) -> EvidenceSink): AirplaneSource {
            lateinit var self: AirplaneSource
            self = AirplaneSource(sinkFor, platform = AndroidPlatform(context.applicationContext) { self })
            return self
        }
    }

    private class AndroidPlatform(private val ctx: Context, private val source: () -> AirplaneSource) : Platform {
        private var receiver: BroadcastReceiver? = null

        override fun isOn() = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

        override fun register(): Boolean {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { source().onAirplane(runCatching { isOn() }.getOrDefault(i.getBooleanExtra("state", false))) }
            }
            // A protected system broadcast: NOT_EXPORTED still receives it and no other app can inject one.
            ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
            return true
        }

        override fun unregister() { receiver?.let { runCatching { ctx.unregisterReceiver(it) } }; receiver = null }
    }
}
