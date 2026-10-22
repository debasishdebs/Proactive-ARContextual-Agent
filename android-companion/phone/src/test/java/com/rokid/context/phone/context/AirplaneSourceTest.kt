package com.rokid.context.phone.context

import com.rokid.context.core.model.context.Evidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirplaneSourceTest {
    private val clock = FakeObsClock()
    private val got = ArrayList<Evidence>()
    private var initial = false
    private var registered = 0
    private var unregistered = 0

    private fun src() = AirplaneSource(
        sinkFor = { id -> assertEquals("phone_airplane", id); EvidenceSink { got += it } },
        time = clock.time(),
        platform = object : AirplaneSource.Platform {
            override fun isOn() = initial
            override fun register(): Boolean { registered++; return true }
            override fun unregister() { unregistered++ }
        },
    )

    @Test fun idAndAvailable() { assertEquals("phone_airplane", src().id); assertTrue(src().available()) }

    @Test fun onOnOffEmitsTwo() {
        val s = src(); initial = true; assertTrue(s.start())
        assertEquals(1, got.size) // the initial read: on
        s.onAirplane(true); s.onAirplane(false)
        assertEquals(listOf(true, false), got.map { (it as Evidence.Airplane).on })
        assertEquals(2, s.events)
    }

    @Test fun pureOnOnOffEmitsTwoEvidences() {
        val s = src(); initial = false; s.start()
        got.clear()
        s.onAirplane(false) // same as the initial state
        s.onAirplane(true); s.onAirplane(true); s.onAirplane(false)
        assertEquals(listOf(true, false), got.map { (it as Evidence.Airplane).on })
    }

    @Test fun startReadsTheSettingOnce() {
        initial = true; val s = src(); s.start()
        assertEquals(Evidence.Airplane(clock.wall(), true), got.single())
        assertEquals(1, registered)
    }

    @Test fun stopUnregistersAndDropsLateCallbacks() {
        val s = src(); s.start(); got.clear(); s.stop()
        assertEquals(1, unregistered)
        s.onAirplane(true); assertTrue(got.isEmpty())
    }

    @Test fun aRestartRepublishesTheCurrentState() {
        val s = src(); s.start(); s.stop(); got.clear()
        initial = true; s.start()
        assertEquals(true, (got.single() as Evidence.Airplane).on)
    }
}
