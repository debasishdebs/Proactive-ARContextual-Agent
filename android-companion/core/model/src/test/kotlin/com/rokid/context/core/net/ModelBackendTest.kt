package com.rokid.context.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Slice 1.4.3: the pure decision table (setting x network x availability), the fail-closed parse, the reach rule and the thresholds. */
class ModelBackendTest {
    private fun pick(m: ModelPrivacy, n: NetState, online: Boolean, offline: Boolean) = BackendDecision.decide(m, n, online, offline)

    // ---- the full decision table: 2 modes x 3 network states x online? x offline? = 24 rows ----

    @Test fun autoWithANetworkAndAnOnlineRouteIsOnlineWhetherOrNotAnOfflineModelExists() {
        for (n in listOf(NetState.VALIDATED, NetState.UNKNOWN)) for (off in listOf(false, true)) {
            assertEquals(BackendPick(Backend.ONLINE, ResolveReason.OK), pick(ModelPrivacy.AUTO, n, online = true, offline = off), "$n off=$off")
        }
    }

    @Test fun autoWithNoNetworkFallsToOfflineOrSaysNoNetwork() {
        assertEquals(BackendPick(Backend.OFFLINE, ResolveReason.OK), pick(ModelPrivacy.AUTO, NetState.NONE, online = true, offline = true))
        assertEquals(BackendPick(Backend.OFFLINE, ResolveReason.OK), pick(ModelPrivacy.AUTO, NetState.NONE, online = false, offline = true))
        assertEquals(BackendPick(null, ResolveReason.NO_NETWORK_NO_LOCAL_MODEL), pick(ModelPrivacy.AUTO, NetState.NONE, online = true, offline = false))
        assertEquals(BackendPick(null, ResolveReason.NO_NETWORK_NO_LOCAL_MODEL), pick(ModelPrivacy.AUTO, NetState.NONE, online = false, offline = false))
    }

    @Test fun autoWithANetworkButNothingBoundIsNotBound() {
        assertEquals(BackendPick(null, ResolveReason.NOT_BOUND), pick(ModelPrivacy.AUTO, NetState.VALIDATED, online = false, offline = false))
        assertEquals(BackendPick(Backend.OFFLINE, ResolveReason.OK), pick(ModelPrivacy.AUTO, NetState.VALIDATED, online = false, offline = true))
    }

    @Test fun offlineOnlyNeverPicksOnlineWhateverTheNetworkOrTheOnlineRouteSay() {
        for (n in NetState.entries) for (online in listOf(false, true)) {
            assertNotEquals(Backend.ONLINE, pick(ModelPrivacy.OFFLINE_ONLY, n, online, offline = false).backend, "$n online=$online")
            assertNotEquals(Backend.ONLINE, pick(ModelPrivacy.OFFLINE_ONLY, n, online, offline = true).backend, "$n online=$online")
        }
    }

    @Test fun offlineOnlyWithoutALocalModelIsTypedUnavailableAndWithOneIsOffline() {
        for (n in NetState.entries) for (online in listOf(false, true)) {
            assertEquals(BackendPick(null, ResolveReason.OFFLINE_ONLY_NO_LOCAL_MODEL), pick(ModelPrivacy.OFFLINE_ONLY, n, online, offline = false))
            assertEquals(BackendPick(Backend.OFFLINE, ResolveReason.OK), pick(ModelPrivacy.OFFLINE_ONLY, n, online, offline = true))
        }
    }

    // ---- settings ----

    @Test fun nothingStoredIsAutoAndAnythingUnknownFailsClosedToOfflineOnly() {
        assertEquals(ModelPrivacy.AUTO, ModelPrivacy.parse(null))
        assertEquals(ModelPrivacy.AUTO, ModelPrivacy.DEFAULT)
        assertEquals(ModelPrivacy.AUTO, ModelPrivacy.parse("AUTO"))
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacy.parse("OFFLINE_ONLY"))
        for (junk in listOf("", "auto", "Auto", "offline", "ONLINE", "true", " AUTO", "AUTO ")) assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacy.parse(junk), junk)
    }

    // ---- reach ----

    @Test fun offlineOnlyAllowsThePhoneAndTheLanButNeverARemoteEndpoint() {
        assertTrue(ModelPrivacyPolicy.allows(ModelPrivacy.OFFLINE_ONLY, EndpointReach.DEVICE))
        assertTrue(ModelPrivacyPolicy.allows(ModelPrivacy.OFFLINE_ONLY, EndpointReach.LAN))
        assertFalse(ModelPrivacyPolicy.allows(ModelPrivacy.OFFLINE_ONLY, EndpointReach.REMOTE))
        for (r in EndpointReach.entries) assertTrue(ModelPrivacyPolicy.allows(ModelPrivacy.AUTO, r))
    }

    // ---- thresholds ----

    @Test fun theOnlineFloorIsTodaysFiftyPercentAndTheOfflineOneIsItsOwnConstant() {
        assertEquals(0.5f, BackendThresholds.minConfidence(Backend.ONLINE))
        assertEquals(50, BackendThresholds.minConfidencePct(Backend.ONLINE))
        assertEquals(0.7f, BackendThresholds.minConfidence(Backend.OFFLINE))
        assertEquals(70, BackendThresholds.minConfidencePct(Backend.OFFLINE))
        assertEquals(1, BackendThresholds.VERSION)
    }

    @Test fun everyReasonHasALowerCaseTokenWithNoSpaces() {
        for (r in ResolveReason.entries) assertTrue(r.token == r.name.lowercase() && !r.token.contains(' '), r.token)
    }
}
