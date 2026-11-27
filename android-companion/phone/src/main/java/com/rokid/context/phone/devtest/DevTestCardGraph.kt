package com.rokid.context.phone.devtest

import android.content.Context
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.carriesData
import com.rokid.context.phone.canvas.DeveloperModeStore
import com.rokid.context.work.frames.place.DevTestCard
import com.rokid.context.work.frames.place.DevTestCardSender
import com.rokid.context.work.frames.place.PlaceGraph

/** Wires the Developer "Send test card to glasses" action: the real card holder, the Developer mode flag and the glasses link state. One instance, so the 10 s limit holds across screens. */
class DevTestCardGraph private constructor(private val sender: DevTestCardSender) {
    /** Sends the canned card (or refuses); returns the one fixed line to show. */
    fun sendNow(): String = DevTestCard.message(sender.send())

    companion object {
        @Volatile private var instance: DevTestCardGraph? = null
        fun get(app: Context, glasses: GlassesStatusSource): DevTestCardGraph = instance ?: synchronized(this) {
            instance ?: DevTestCardGraph(DevTestCardSender(
                PlaceGraph.port, android.os.SystemClock::elapsedRealtime,
                { DeveloperModeStore.get(app.applicationContext).enabled.value }, { glasses.status.value.link.carriesData },
            )).also { instance = it }
        }
    }
}
