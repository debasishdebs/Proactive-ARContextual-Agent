package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.forget.lifecycle.ForgetChild
import com.rokid.context.memory.forget.lifecycle.ForgetPolicy
import com.rokid.context.memory.forget.lifecycle.ForgetSourceKind
import com.rokid.context.memory.forget.lifecycle.PolicyAction
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget redesign STEP 1, conformance: the PIN row of the policy table against the real [PinMomentLinks] rules, for EVERY source kind. An explicit kind runs the real explicit cascade (or the Delete all wipe
 * cascade) around a removal of the pin's Moment; a system kind runs the real sweep after the same removal. The table says FORGET (the pin is deleted) or DE_LINK (the pin stays, its link is cleared); the code must agree.
 * No database: the Moment is "alive" or "gone" through a fake lookup, exactly the seam `PinMomentLinksTest` uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetPolicyPinConformanceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val raw get() = ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE)
    private val store get() = UserPinStore(raw)
    private val momentId = "00000001"
    private var live: Set<String> = setOf(momentId)
    private val alive: suspend (List<String>) -> Set<String> = { l -> l.filter { it in live }.toSet() }
    private val save: suspend (List<UserPlace>) -> Boolean = { l -> store.write(l) }
    private val all: suspend (List<String>) -> Set<String> = { it.toSet() }

    @Before fun setUp() {
        raw.edit().clear().commit(); live = setOf(momentId)
        assertEquals(true, store.write(listOf(UserPlace("user:00000000-0000-0000-0000-000000000001", "Pin", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = momentId))))
    }
    @After fun tearDown() { raw.edit().clear().commit() }

    private fun pins() = store.pins()

    /** Removes the Moment the way [kind] does, then returns what became of the pin: DELETED, or the link cleared (DE_LINKED), or untouched. */
    private suspend fun outcomeOf(kind: ForgetSourceKind): PolicyAction? {
        when (kind) {
            ForgetSourceKind.SINGLE, ForgetSourceKind.MULTI, ForgetSourceKind.RANGE, ForgetSourceKind.SCOPE ->
                PinMomentLinks.explicitStore(store, alive, save, all, { _: Unit -> true }, {}, { live = emptySet() }, {}, null, { false })
            ForgetSourceKind.DELETE_ALL -> {
                val op = PinMomentLinks.beginWipeStore(store, alive, save, false) { false }
                try { live = emptySet(); PinMomentLinks.wipeCommittedStore(store, op); PlacePinning.underPinLock { PinMomentLinks.resolveStore(store, alive, save, op) { false } } } finally { PinMomentLinks.endWipe(op) }
            }
            ForgetSourceKind.SYSTEM_EXPIRY, ForgetSourceKind.SYSTEM_SWEEP, ForgetSourceKind.SYSTEM_PURGE -> { live = emptySet(); PinMomentLinks.sweepGuarded(store, alive, save) { false } }
        }
        val p = pins()
        return when { p.isEmpty() -> PolicyAction.FORGET; p.single().momentId == null -> PolicyAction.DE_LINK; else -> null }
    }

    @Test fun everySourceKindDoesWhatThePinRowSays() = runBlocking<Unit> {
        for (kind in ForgetSourceKind.entries) {
            setUp()
            assertEquals("PIN x $kind: the table says ${ForgetPolicy.action(ForgetChild.PIN, kind)} (${ForgetPolicy.rule(ForgetChild.PIN, kind).at})", ForgetPolicy.action(ForgetChild.PIN, kind), outcomeOf(kind))
            assertNull("no arming is left behind by $kind", store.arming())
        }
    }

    @Test fun aSystemRemovalNeverDeletesThePinEvenWhenTheMomentIsGone() = runBlocking<Unit> {
        for (kind in ForgetSourceKind.entries.filter { !it.explicit }) {
            setUp(); outcomeOf(kind)
            assertEquals("$kind must keep the pin", 1, pins().size)
        }
    }
}
