package com.rokid.context.phone.ui.glasses

import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.ui.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeGlassesStatusSourceTest {
    @Test fun startsOfflineWithNoFrame() {
        val fake = FakeGlassesStatusSource()
        assertEquals(LinkState.OFFLINE, fake.status.value.link)
        assertNull(fake.status.value.frame)
        assertNull(fake.status.value.glassesBatteryPct)
    }

    @Test fun emitReplacesTheStatusAndReconnectsAreCounted() {
        val fake = FakeGlassesStatusSource()
        fake.emit(FakeGlassesStatusSource.connected(Fixtures.stateFrame()))
        assertEquals(LinkState.CONNECTED, fake.status.value.link)
        assertEquals(82, fake.status.value.glassesBatteryPct)
        fake.requestReconnect(); fake.requestReconnect()
        assertEquals(2, fake.reconnectRequests)
    }
}
