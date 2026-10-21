package com.rokid.context.phone.context

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.IntentLabel
import com.rokid.context.core.model.context.PhoneActivity
import com.rokid.context.core.model.context.WatchState
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextHubStatsTest {
    @Test fun lineForKnownCountsIsExact() {
        val s = ContextHubStats { 0L }
        repeat(2) { s.count(Evidence.ActivityTransition(0, PhoneActivity.WALKING, true)) }
        repeat(3) { s.count(Evidence.Steps(0, 80)) }
        repeat(4) { s.count(Evidence.Speed(0, 30f, true)) }
        repeat(5) { s.count(Evidence.CarLink(0, CarLinkKind.MY_CAR_BT, true)) }
        s.count(Evidence.CarLink(0, CarLinkKind.CAR_CLASS_BT, false))
        repeat(7) { s.count(Evidence.CarLink(0, CarLinkKind.ANDROID_AUTO, true)) }
        repeat(8) { s.count(Evidence.Airplane(0, true)) }
        repeat(9) { s.count(Evidence.GlassesMotion(0, MotionState.WALKING, 0.1f)) }
        repeat(10) { s.count(Evidence.WatchMotion(0, WatchState.WALKING, 0.9f)) }
        s.count(Evidence.Intent(0, IntentLabel.DRIVING, 0.9f)) // counted by onIntent, not here
        repeat(11) { s.onWatchRejected() }
        repeat(12) { s.onIntent(true) }
        repeat(13) { s.onIntent(false) }
        repeat(14) { s.onIntentSkip() }
        repeat(15) { s.onSend() }
        repeat(16) { s.onChange() }
        s.addLocOnSeconds(170)
        s.phoneBatteryStart = 80; s.phoneBatteryNow = 77
        assertEquals(
            "ctxhub v=1 state=VEHICLE_DRIVING ar=2 steps=3 loc=4 locOnS=170 bt=6 aa=7 air=8 hints=9 watch=10 watchRej=11 " +
                "intent=12 intentFail=13 intentSkip=14 sends=15 changes=16 batt=80->77",
            s.line(ContextState.VEHICLE_DRIVING),
        )
    }

    @Test fun unknownBatteryPrintsNa() {
        assertEquals(true, ContextHubStats { 0L }.line(ContextState.UNKNOWN).endsWith("batt=na->na"))
    }

    @Test fun snapshotCarriesCountsAndStateSeconds() {
        val s = ContextHubStats { 0L }
        s.onSend(); s.onSend(); s.addStateTime(ContextState.REST, 90_000)
        val m = s.snapshot()
        assertEquals(2L, m["sends"]); assertEquals(90L, m["state_REST_s"]); assertEquals(0L, m["hints"])
    }

    @Test fun watchEpochIsCountedAndInTheSnapshot() {
        val s = ContextHubStats { 0L }
        s.onWatchEpoch(); s.onWatchEpoch()
        org.junit.Assert.assertEquals(2L, s.snapshot()["watchEpoch"])
    }
}
