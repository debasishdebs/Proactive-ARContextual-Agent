package com.rokid.context.ai.resolve

import com.rokid.context.ai.OfflineOnlyDns
import com.rokid.context.core.net.ModelPrivacy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guard: the process-wide Offline-only state is only ever set by the app (ContextApp, AiGraph.get). No test in this module may leave it on OFFLINE_ONLY or on a fake, because every later test builds its
 * HTTP clients through it. If this fails, find the test that set [OfflineOnlyDns.globalMode] and restore it in an @After.
 */
class OfflineOnlyStaticsTest {
    @Test fun theSharedModeReaderIsStillTheAutoDefault() {
        assertEquals(ModelPrivacy.AUTO, OfflineOnlyDns.globalMode())
    }
}
