package com.rokid.context.capture.plan

import kotlin.test.Test
import kotlin.test.assertEquals

/** Schema-1 plans (no exprs) keep their exact canonical config text and hash after planSchema 2 landed (X1, F2). */
class Schema1HashUnchangedTest {
    @Test fun identityConfigTextAndHashAreFrozen() {
        val c = PlanResolver.identity()
        assertEquals(
            """{"ctxBound":true,"gate":{"bigChange":22,"hamming":10,"ringCompare":"all"},"glance":{"bigTurnRad":0.8,"bridgeMs":250,"minBoutMs":300,"minBoutRad":0.26,"noveltyMinRad":0.3,"noveltyWindowMs":20000,"onsetRadS":0.6,"releaseRadS":0.3,"settleMs":1500},"phoneFusion":{"enterRestMs":120000,"enterUnknownMs":60000,"enterVehicleMs":30000,"fastConfirmGapMs":45000,"fastKmh":25,"fastTtlMs":120000,"hintStillTtlMs":120000,"stillFrac":0.85,"stillFracWithImage":0.7,"switchDriverMs":60000,"vehicleHoldMs":180000},"policy":{"FLIGHT":{"capPerMin":1,"forceSettle":true,"nudges":true,"timerPhotoGapsMs":{"quietMs":300000,"settledMs":120000,"stillCapMs":300000,"walkMs":120000}},"REST":{"capPerMin":2,"forceSettle":true,"nudges":true,"timerPhotoGapsMs":{"quietMs":60000,"settledMs":30000,"stillCapMs":120000,"walkMs":30000}},"VEHICLE_DRIVING":{"capPerMin":null,"forceSettle":false,"nudges":false,"timerPhotoGapsMs":{"quietMs":0,"settledMs":0,"stillCapMs":0,"walkMs":0}},"VEHICLE_PASSENGER":{"capPerMin":null,"forceSettle":false,"nudges":true,"timerPhotoGapsMs":{"quietMs":0,"settledMs":0,"stillCapMs":0,"walkMs":0}}}}""",
            CanonicalJson.encode(PlanJson.configJson(c)),
        )
        assertEquals("f5782e0d8610", PlanJson.configHash(c))
    }
}
