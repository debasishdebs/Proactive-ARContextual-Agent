package com.rokid.context.watch.motion

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MonitoringSwitchTest {
    private class FakeClient : MonitoringClient {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var failRegister = false
        override suspend fun register() { calls += "register"; gate?.await(); if (failRegister) error("boom") }
        override suspend fun unregister() { calls += "unregister"; gate?.await() }
    }

    private class Rig {
        val client = FakeClient(); var want = true; var alarm = false
        val sw = MonitoringSwitch(client, { alarm = true }, { alarm = false }, { want })
    }

    @Test fun registersAndArmsTheAlarmOnce() = runTest {
        val r = Rig(); r.sw.reconcile(); r.sw.reconcile()
        assertEquals(listOf("register"), r.client.calls); assertTrue(r.alarm)
    }

    @Test fun offUnregistersAndCancelsTheAlarm() = runTest {
        val r = Rig(); r.sw.reconcile(); r.want = false; r.sw.reconcile()
        assertEquals(listOf("register", "unregister"), r.client.calls); assertFalse(r.alarm); assertFalse(r.sw.registered)
    }

    @Test fun rapidOffThenOnEndsRegisteredWithTheAlarm() = runTest {
        val r = Rig(); r.sw.reconcile()
        r.client.gate = CompletableDeferred()
        r.want = false
        val off = launch { r.sw.reconcile() }
        yield() // the unregister is in flight
        r.want = true
        val on = launch { r.sw.reconcile() }
        yield()
        r.client.gate!!.complete(Unit)
        off.join(); on.join()
        assertTrue(r.sw.registered, "registered"); assertTrue(r.alarm, "alarm armed")
        assertEquals("register", r.client.calls.last(), "last call")
    }

    @Test fun onThenOffEndsUnregistered() = runTest {
        val r = Rig(); r.want = false; r.sw.reconcile()
        r.client.gate = CompletableDeferred()
        r.want = true
        val on = launch { r.sw.reconcile() }
        yield()
        r.want = false
        val off = launch { r.sw.reconcile() }
        yield(); r.client.gate!!.complete(Unit)
        on.join(); off.join()
        assertFalse(r.sw.registered); assertFalse(r.alarm); assertEquals("unregister", r.client.calls.last())
    }

    @Test fun aFailedRegisterLeavesNoAlarmAndRetriesNextTime() = runTest {
        val r = Rig(); r.client.failRegister = true; r.sw.reconcile()
        assertFalse(r.sw.registered); assertFalse(r.alarm)
        r.client.failRegister = false; r.sw.reconcile()
        assertTrue(r.sw.registered); assertTrue(r.alarm)
    }
}
