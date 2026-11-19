package com.rokid.context.ai.resolve

import com.rokid.context.core.net.Backend
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetState
import com.rokid.context.core.net.ResolveReason
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slice 1.4.3: the resolver's decision table end to end (setting x network x what is bound), the cache, and its log lines. */
class ModelResolverTest {
    private class Fake(val label: String, var bound: Boolean = true, var fp: String = "fp1", var failCreate: Boolean = false, val gate: CompletableDeferred<Unit>? = null) : BackendFactory {
        var creates = 0; val closed = mutableListOf<Any>(); var boundAsked = 0
        override suspend fun bound(): Boolean { boundAsked++; return bound }
        override suspend fun fingerprint() = fp
        override suspend fun label() = label
        override suspend fun create(): Any { creates++; gate?.await(); if (failCreate) error("model file unreadable: /secret/path"); return Any() }
        override fun close(instance: Any) { closed += instance }
    }

    private var mode = ModelPrivacy.AUTO
    private var net = NetState.VALIDATED
    private val logs = mutableListOf<String>()
    private val online = Fake("remote:AZURE_OPENAI")
    private val offline = Fake("device:LLAMACPP_LOCAL")
    private val role = ResolverRole.PLACE_CONFIRM
    private fun resolver(withOffline: Boolean = false, now: () -> Long = { 0L }) = ModelResolver(
        mode = { mode }, net = { net },
        factories = buildMap { put(role to Backend.ONLINE, online); if (withOffline) put(role to Backend.OFFLINE, offline) },
        log = { logs += it }, nowMs = now,
    )

    // ---- decision table through the resolver (only ONLINE is wired in 1.4.3) ----

    @Test fun autoWithANetworkResolvesOnline() = runTest {
        val r = resolver().resolve(role) as Resolved.Ready
        assertEquals(Backend.ONLINE, r.backend); assertEquals("remote:AZURE_OPENAI", r.label); assertFalse(r.cacheHit); assertEquals(ModelPrivacy.AUTO, r.mode)
    }

    @Test fun autoWithAFailedNetworkCheckStillResolvesOnlineLikeToday() = runTest {
        net = NetState.UNKNOWN
        assertEquals(Backend.ONLINE, (resolver().resolve(role) as Resolved.Ready).backend)
    }

    @Test fun autoWithNoNetworkAndNoOfflineModelIsTypedUnavailableAndNothingIsCreated() = runTest {
        net = NetState.NONE
        val r = resolver().resolve(role) as Resolved.Unavailable
        assertEquals(ResolveReason.NO_NETWORK_NO_LOCAL_MODEL, r.reason); assertEquals(0, online.creates)
    }

    @Test fun offlineOnlyNeverResolvesOnlineAndNeverCreatesTheOnlineInstanceForEveryNetworkState() = runTest {
        mode = ModelPrivacy.OFFLINE_ONLY
        for (n in NetState.entries) {
            net = n
            val r = resolver().resolve(role) as Resolved.Unavailable
            assertEquals(n.name, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL, r.reason); assertEquals(ModelPrivacy.OFFLINE_ONLY, r.mode)
        }
        assertEquals("the online factory was never asked to build anything", 0, online.creates)
    }

    @Test fun anOfflineSlotThatIsFilledResolvesOfflineUnderOfflineOnlyAndOnlyWhenOnlineIsDown() = runTest {
        val res = resolver(withOffline = true)
        mode = ModelPrivacy.OFFLINE_ONLY
        assertEquals(Backend.OFFLINE, (res.resolve(role) as Resolved.Ready).backend)
        mode = ModelPrivacy.AUTO
        assertEquals(Backend.ONLINE, (res.resolve(role) as Resolved.Ready).backend)
        net = NetState.NONE
        assertEquals(Backend.OFFLINE, (res.resolve(role) as Resolved.Ready).backend)
    }

    @Test fun anUnreadableSettingFailsClosedAndAFailingNetworkCheckIsUnknown() = runTest {
        val res = ModelResolver(mode = { error("prefs") }, net = { error("cm") }, factories = mapOf((role to Backend.ONLINE) to online), log = { logs += it })
        assertEquals(ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL, (res.resolve(role) as Resolved.Unavailable).reason)
        assertTrue(logs.single().contains("mode=OFFLINE_ONLY net=UNKNOWN"))
    }

    @Test fun aRoleWithNoFactoryIsNotBound() = runTest {
        assertEquals(ResolveReason.NOT_BOUND, (resolver().resolve(ResolverRole.PLACE_EMBED) as Resolved.Unavailable).reason)
    }

    @Test fun peekDecidesWithoutCreatingOrLogging() = runTest {
        val pick = resolver().peek(role)
        assertEquals(Backend.ONLINE, pick.backend); assertEquals(0, online.creates); assertTrue(logs.isEmpty())
        mode = ModelPrivacy.OFFLINE_ONLY
        assertEquals(ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL, resolver().peek(role).reason)
    }

    // ---- cache ----

    @Test fun theInstanceIsReusedPerRoleAndBackend() = runTest {
        val res = resolver()
        val a = res.resolve(role) as Resolved.Ready; val b = res.resolve(role) as Resolved.Ready
        assertSame(a.instance, b.instance); assertFalse(a.cacheHit); assertTrue(b.cacheHit); assertEquals(1, online.creates)
        assertEquals(0L, b.loadMs)
    }

    @Test fun aChangedFingerprintRebuildsAndReleasesTheOldInstance() = runTest {
        val res = resolver()
        val a = res.resolve(role) as Resolved.Ready
        online.fp = "fp2"
        val b = res.resolve(role) as Resolved.Ready
        assertNotSame(a.instance, b.instance); assertEquals(2, online.creates); assertEquals(listOf(a.instance), online.closed)
    }

    @Test fun invalidateDropsAndReleasesOnlyTheNamedRole() = runTest {
        val res = ModelResolver({ mode }, { net }, mapOf((role to Backend.ONLINE) to online, (ResolverRole.PLACE_EMBED to Backend.ONLINE) to Fake("device:X").also { }), { logs += it })
        val a = res.resolve(role) as Resolved.Ready
        res.resolve(ResolverRole.PLACE_EMBED)
        res.invalidate(role)
        assertEquals(listOf(a.instance), online.closed)
        val again = res.resolve(role) as Resolved.Ready
        assertFalse(again.cacheHit)
        res.invalidate()
    }

    @Test fun switchingTheModeAndBackKeepsTheOnlineInstanceBecauseTheKeyIsBackend() = runTest {
        val res = resolver()
        val a = res.resolve(role) as Resolved.Ready
        mode = ModelPrivacy.OFFLINE_ONLY; res.resolve(role)
        mode = ModelPrivacy.AUTO
        assertSame(a.instance, (res.resolve(role) as Resolved.Ready).instance); assertEquals(1, online.creates)
    }

    @Test fun twoCallersAtOnceBuildTheInstanceOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow = Fake("device:LLAMACPP_LOCAL", gate = gate)
        val res = ModelResolver({ ModelPrivacy.OFFLINE_ONLY }, { NetState.NONE }, mapOf((role to Backend.OFFLINE) to slow), { logs += it })
        val both = (1..3).map { async { res.resolve(role) } }
        gate.complete(Unit)
        val ready = both.awaitAll().map { it as Resolved.Ready }
        assertEquals(1, slow.creates); assertEquals(1, ready.map { it.instance }.toSet().size)
        assertEquals(1, ready.count { !it.cacheHit })
    }

    @Test fun aFailedBuildIsATypedUnavailableAndTheNextCallTriesAgain() = runTest {
        val res = resolver()
        online.failCreate = true
        assertEquals(ResolveReason.LOAD_FAILED, (res.resolve(role) as Resolved.Unavailable).reason)
        online.failCreate = false
        assertTrue(res.resolve(role) is Resolved.Ready); assertEquals(2, online.creates)
    }

    @Test fun anUnreadableBoundCheckCountsAsNotBound() = runTest {
        val broken = object : BackendFactory {
            override suspend fun bound(): Boolean = error("db")
            override suspend fun fingerprint() = "x"; override suspend fun label() = "x"; override suspend fun create(): Any = Any()
        }
        val res = ModelResolver({ ModelPrivacy.AUTO }, { NetState.VALIDATED }, mapOf((role to Backend.ONLINE) to broken), { logs += it })
        assertEquals(ResolveReason.NOT_BOUND, (res.resolve(role) as Resolved.Unavailable).reason)
    }

    // ---- logs: which backend ran and why, content-free ----

    @Test fun everyResolutionLogsOneLineWithBackendAndReasonAndNothingElse() = runTest {
        val res = resolver()
        res.resolve(role); res.resolve(role)
        mode = ModelPrivacy.OFFLINE_ONLY; res.resolve(role)
        net = NetState.NONE; mode = ModelPrivacy.AUTO; res.resolve(role)
        assertEquals("resolve role=PLACE_CONFIRM mode=AUTO net=VALIDATED backend=ONLINE label=remote:AZURE_OPENAI cache=miss loadMs=0 reason=ok", logs[0])
        assertEquals("resolve role=PLACE_CONFIRM mode=AUTO net=VALIDATED backend=ONLINE label=remote:AZURE_OPENAI cache=hit loadMs=0 reason=ok", logs[1])
        assertEquals("resolve role=PLACE_CONFIRM mode=OFFLINE_ONLY net=VALIDATED backend=none label=- cache=- loadMs=-1 reason=offline_only_no_local_model", logs[2])
        assertEquals("resolve role=PLACE_CONFIRM mode=AUTO net=NONE backend=none label=- cache=- loadMs=-1 reason=no_network_no_local_model", logs[3])
    }

    @Test fun aFailedBuildLogsTheClassNameNeverTheMessage() = runTest {
        online.failCreate = true
        resolver().resolve(role)
        val line = logs.single()
        assertTrue(line, line.contains("reason=load_failed err=IllegalStateException"))
        assertFalse(line, line.contains("secret") || line.contains("unreadable") || line.contains("/"))
    }

    @Test fun logLinesUseOnlyTheFixedVocabulary() = runTest {
        val res = resolver(withOffline = true)
        for (m in ModelPrivacy.entries) for (n in NetState.entries) { mode = m; net = n; res.resolve(role) }
        val fixed = Regex("""resolve role=\w+ mode=(AUTO|OFFLINE_ONLY) net=(VALIDATED|NONE|UNKNOWN) backend=(ONLINE|OFFLINE|none) label=(-|(remote|device|lan):[A-Z_]+) cache=(hit|miss|-) loadMs=-?\d+ reason=[a-z_]+""")
        assertEquals(6, logs.size)
        for (l in logs) assertTrue(l, fixed.matches(l))
    }
}
