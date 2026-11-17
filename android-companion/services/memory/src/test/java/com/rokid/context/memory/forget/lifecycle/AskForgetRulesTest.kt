package com.rokid.context.memory.forget.lifecycle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.forget.AskSourceStates
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.GoldenWorld
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle M5 (rulings 2026-11-17): A1 (an answer is hit by ALL its stored dependencies, ruling F4: conservative, never citation-only, and becomes "Answered from a forgotten fact": the question stays, the answer is erased, no automatic re-answer), A2 (a history
 * item is a dependency), N1 (a hit nudge is erased). With the flag OFF the rows behave as v1 (every stored dependency redacts in place, the source states are never touched).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskForgetRulesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val w = GoldenWorld(db)
    @After fun close() { ForgetV2.testOverride = null; Leases.shared.revokeAll(); db.close() }

    private fun live(cited: List<String>, history: List<String> = emptyList()) = AskSourceStates.live(cited, history)
    private suspend fun ask(id: String, cited: List<String>, deps: List<String> = emptyList(), history: List<String> = emptyList(), source: String = "phone_typed", states: String? = live(cited, history)) {
        val json = json(cited, deps)
        db.askDao().upsert(AskEntity(id, 5, "question $id", "answer $id", json, null, source, false, states))
    }
    /** `AskSources.encodeWithDeps`: the cited array plus a trailing {"dependsOn": [...]} of the other inputs. */
    private fun json(cited: List<String>, deps: List<String>): String {
        val a = org.json.JSONArray(cited)
        if (deps.isNotEmpty()) a.put(org.json.JSONObject().put("dependsOn", org.json.JSONArray(deps)))
        return a.toString()
    }
    private suspend fun row(id: String) = db.askDao().byId(id)!!
    private suspend fun seed() {
        w.seed()
        ask("aCited", listOf("mA"))                               // cites the forgotten fact
        ask("aRetrieved", listOf("mFar"), deps = listOf("mA"))    // retrieved the forgotten fact but never cited it (the over-redaction case)
        ask("aHistory", listOf("mFar"), history = listOf("nudge:nCite")) // used the earlier nudge nCite (A2)
        ask("aEval", listOf("mFar"), deps = listOf("mA"), source = AskSourceStates.EVAL_SOURCE, states = null)
        ask("aFramed", listOf("frame:fA"))                        // cited the photo itself
        ask("aUnrelated", listOf("mFar"))                         // supplied nothing that is forgotten
        // a migrated v16 answer: no states, a nudge-history dependency stored only in dependsOn (it keeps its protection)
        ask("aMigratedHistory", listOf("mFar"), deps = listOf("nudge:nCite"), states = null)
    }

    @Test fun flagOnAnAnswerIsHitByEverySuppliedInputAndKeepsItsQuestion() = runBlocking {
        seed(); ForgetV2.testOverride = true
        w.service.forgetFact("mA")
        val hit = row("aCited")
        assertTrue(hit.redacted); assertEquals(ForgetService.REDACTED, hit.answer); assertEquals("[]", hit.sourcesJson)
        assertEquals("the question stays", "question aCited", hit.question)
        assertEquals(AskSourceStates.SOURCE_FORGOTTEN, AskSourceStates.parse(hit.sourceStates)?.state); assertTrue(AskSourceStates.isForgotten(hit)); assertFalse(AskSourceStates.isReasked(hit))
        val dep = row("aRetrieved")
        assertTrue("retrieved but not cited is STILL a dependency (F4)", dep.redacted); assertEquals(AskSourceStates.SOURCE_FORGOTTEN, AskSourceStates.parse(dep.sourceStates)?.state)
        assertEquals("question aRetrieved", dep.question)
        assertFalse("an answer that supplied nothing forgotten is untouched", row("aUnrelated").redacted)
    }

    @Test fun flagOffEveryStoredDependencyRedactsInPlaceAndTheStatesAreNeverTouched() = runBlocking {
        seed(); ForgetV2.testOverride = false
        w.service.forgetFact("mA")
        assertTrue(row("aCited").redacted); assertTrue("v1: the dependency redacts it too", row("aRetrieved").redacted)
        assertEquals("v1 never writes the state", AskSourceStates.LIVE, AskSourceStates.parse(row("aCited").sourceStates)?.state)
        assertFalse(AskSourceStates.isForgotten(row("aCited")))
    }

    @Test fun nudgeIsErasedOnAndRedactedOff() = runBlocking {
        for (on in listOf(true, false)) {
            val d = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                val world = GoldenWorld(d); world.seed(); ForgetV2.testOverride = on
                world.service.forgetFact("mA")
                val n = d.nudgeDao().byIds(listOf("nCite")).firstOrNull()
                if (on) assertNull("N1: a hit nudge has no row", n) else { assertNotNull(n); assertTrue(n!!.redacted) }
                assertNotNull("the unrelated nudge stays", d.nudgeDao().byIds(listOf("nKeep")).firstOrNull())
                assertFalse("its proposal is dead either way", d.actionDao().nudgeLive("nCite"))
            } finally { d.close() }
        }
    }

    @Test fun historyItemsAreDependenciesAndFollowAnErasedNudge() = runBlocking {
        seed(); ForgetV2.testOverride = true
        w.service.forgetFact("mA") // erases nCite (N1), so the answer that used it (A2) is hit by the item, not by a memory
        assertTrue(row("aHistory").redacted); assertTrue(AskSourceStates.isForgotten(row("aHistory")))
        assertTrue("a v16 answer with the nudge only in dependsOn is protected too", row("aMigratedHistory").redacted); assertTrue(AskSourceStates.isForgotten(row("aMigratedHistory")))
        assertFalse(row("aUnrelated").redacted)
    }

    @Test fun aPhotoCiteIsACitedSource() = runBlocking {
        seed(); ForgetV2.testOverride = true
        w.service.forgetMoment("fA")
        assertTrue(row("aFramed").redacted)
    }

    @Test fun aDeveloperEvalAskKeepsTheV1RuleAndHasNoState() = runBlocking {
        seed(); ForgetV2.testOverride = true
        w.service.forgetFact("mA")
        val e = row("aEval")
        assertTrue("eval asks are unaffected by the new rules: v1 redaction", e.redacted); assertNull(e.sourceStates); assertFalse(AskSourceStates.isForgotten(e))
    }

    @Test fun anAskWrittenBeforeV17HasNoStatesAndMatchesByItsStoredDependencies() = runBlocking {
        w.seed(); ForgetV2.testOverride = true
        ask("legacyCited", listOf("mA"), states = null); ask("legacyDep", listOf("mFar"), deps = listOf("mA"), states = null)
        w.service.forgetFact("mA")
        assertTrue(row("legacyCited").redacted); assertEquals(AskSourceStates.SOURCE_FORGOTTEN, AskSourceStates.parse(row("legacyCited").sourceStates)?.state)
        assertTrue("dependsOn keeps its protection after migration", row("legacyDep").redacted); assertEquals(AskSourceStates.SOURCE_FORGOTTEN, AskSourceStates.parse(row("legacyDep").sourceStates)?.state)
    }

    @Test fun thePlannerPreviewEqualsTheDeleteSetWithTheFlagOn() = runBlocking {
        seed(); ForgetV2.testOverride = true
        val plan = ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA")), Policy.User)
        val planned = plan.refs(Family.ANSWER); val plannedNudges = plan.refs(Family.NUDGE, Fate.ERASE)
        assertEquals(setOf("aCite", "aDep", "aCited", "aRetrieved", "aHistory", "aMigratedHistory", "aEval"), planned) // conservative (F4): the v1 asks plus the history ones; aEval keeps the v1 rule
        assertEquals(setOf("nCite"), plannedNudges); assertTrue(plan.refs(Family.NUDGE, Fate.REBUILD).isEmpty())
        val all = listOf("aCite", "aDep", "aFrame", "aKeep", "aCited", "aRetrieved", "aHistory", "aMigratedHistory", "aEval", "aFramed", "aUnrelated")
        w.service.forgetFact("mA")
        val redacted = db.askDao().byIds(all).filter { it.redacted }.map { it.id }.toSet()
        assertEquals("what the preview named is exactly what the delete redacted", planned, redacted)
        assertNull(db.nudgeDao().byIds(listOf("nCite")).firstOrNull())
    }

    @Test fun theFlagOffPlanIsTheV1Plan() = runBlocking {
        seed(); ForgetV2.testOverride = false
        val plan = ForgetPlanner(db).plan(setOf(Ref(Family.MEMORY, "mA")), Policy.User)
        assertTrue("v1: the retrieved-only ask is in the set", "aRetrieved" in plan.refs(Family.ANSWER)); assertEquals(setOf("nCite"), plan.refs(Family.NUDGE, Fate.REBUILD))
    }

    @Test fun markReaskedOnlyChangesAnAnswerInTheForgottenState() = runBlocking {
        seed(); ForgetV2.testOverride = true
        assertFalse("a live answer is never marked", com.rokid.context.memory.AskRepository(db.askDao()).markReasked("aRetrieved", "new1"))
        w.service.forgetFact("mA")
        assertTrue(com.rokid.context.memory.AskRepository(db.askDao()).markReasked("aCited", "new1"))
        val r = row("aCited"); assertTrue(AskSourceStates.isReasked(r)); assertEquals("new1", AskSourceStates.parse(r.sourceStates)?.reaskedAs); assertEquals("question aCited", r.question)
    }

    @Test fun theStatesJsonIsIdsAndTokensOnly() {
        val j = AskSourceStates.live(listOf("m1", "frame:f1", "m1"), listOf("nudge:n1"))
        assertEquals(AskSourceStates.States(AskSourceStates.LIVE, listOf("m1", "frame:f1"), listOf("nudge:n1")), AskSourceStates.parse(j))
        assertNull(AskSourceStates.parse(null)); assertNull(AskSourceStates.parse("")); assertNull(AskSourceStates.parse("not json")); assertNull(AskSourceStates.parse("{\"v\":2,\"state\":\"LIVE\"}")); assertNull(AskSourceStates.parse("{\"v\":1,\"state\":\"WHAT\"}"))
        assertEquals("{\"v\":1,\"state\":\"SOURCE_FORGOTTEN\"}", AskSourceStates.forgotten())
    }

}
