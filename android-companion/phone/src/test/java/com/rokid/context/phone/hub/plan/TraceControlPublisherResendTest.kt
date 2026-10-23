package com.rokid.context.phone.hub.plan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.LinkState
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.memory.db.DeletionEpochStore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TraceControlPublisherResendTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun ResendTransmitsExactlyOneTraceCtlPerAttemptWithTheRealPublisher() = runTest {
        val prefs = ctx.getSharedPreferences("resend_collect", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val epoch = DeletionEpochStore(ctx.getSharedPreferences("resend_epoch", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        var t = 1_000_000L // a real clock moves, so every refresh() is a new value the flow publishes
        val link = FakeGlassesLink(); link.setState(LinkState.CONNECTED)
        val pub = TraceControlPublisher(link, CollectSession(prefs, epoch) { t += 1_000L; t }, backgroundScope)
        pub.start(); runCurrent()
        repeat(3) { pub.resend(); runCurrent() }
        assertEquals("one transmission per attempt (the running flow collector must not send a second copy)", 3, link.sent.count { it.first == Channels.TRACE_CTL })
    }
}
