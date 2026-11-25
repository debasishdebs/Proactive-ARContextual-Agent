package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v18 WP11a: the `model_attribution` row of a memory, a Moment or Scene derived field or an Ask answer goes with its item on every user path (Forget of a fact, a photo, a Moment, a range, Delete all) and stays while
 * the item stays (photo expiry keeps the caption memory; the sweep keeps what it does not delete). Each path runs on the [GoldenWorld] (rows of [GoldenWorld.seedAttribution]) with `forgetV2` OFF and ON, and the rule is
 * checked as an invariant: afterwards no attribution row names an item that is gone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelAttributionForgetTest {
    private lateinit var w: GoldenWorld
    @After fun close() { ForgetV2.testOverride = null }

    private suspend fun rows(): Set<String> = listOf("MEMORY", "DERIVED", "ANSWER").flatMap { w.db.modelAttributionDao().ofPrefix(it, "") }.map { it.kind + "|" + it.refId }.toSet()
    private suspend fun orphans(): Int = w.db.openHelper.writableDatabase.query(
        "SELECT COUNT(*) FROM model_attribution WHERE (kind = 'MEMORY' AND refId NOT IN (SELECT id FROM memory)) OR (kind = 'ANSWER' AND refId NOT IN (SELECT id FROM ask WHERE redacted = 0)) " +
            "OR (kind = 'DERIVED' AND refId NOT IN (SELECT objectKind || ':' || objectId || ':' || field FROM object_derived))",
    ).use { it.moveToFirst(); it.getInt(0) }

    private fun path(gone: Set<String>, kept: Set<String>, wipe: Boolean = false, op: suspend () -> Unit) = runBlocking {
        for (v2 in listOf(false, true)) {
            ForgetV2.testOverride = v2
            val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                w = GoldenWorld(db); w.seed(); w.seedAttribution()
                val derived = "DERIVED|MOMENT:${w.momA}:SUMMARY"
                val before = rows()
                assertEquals("seeded", 9, before.size); assertTrue(derived in before)
                op()
                val left = rows()
                val all = before.map { it.replace("MOMENT:${w.momA}:SUMMARY", "D") }.toSet()
                val norm = left.map { it.replace("MOMENT:${w.momA}:SUMMARY", "D") }.toSet()
                if (wipe) assertEquals(emptySet<String>(), norm) else { assertEquals("forgetV2=$v2 gone", gone, all - norm); assertTrue("forgetV2=$v2 kept ${kept - norm}", norm.containsAll(kept)) }
                assertEquals("forgetV2=$v2 no attribution row without its item", 0, orphans())
            } finally { db.close() }
        }
    }

    @Test fun forgetFactDeletesTheMemoryTheMomentDerivedFieldAndTheCitingAnswer() = path(setOf("MEMORY|mA", "DERIVED|D", "ANSWER|aCite"), setOf("MEMORY|mFar", "ANSWER|aKeep")) { w.service.forgetFact("mA") }
    @Test fun forgetByPhotoDeletesEveryMemoryOfItItsDerivedFieldAndTheCitingAnswer() = path(setOf("MEMORY|mA", "MEMORY|mBatch", "MEMORY|mMulti", "MEMORY|mPlace", "DERIVED|D", "ANSWER|aCite"), setOf("MEMORY|mFar", "ANSWER|aKeep")) { w.service.forgetMoment("fA") }
    @Test fun momentForgetDeletesTheMomentsMemoriesAndDerivedField() = path(setOf("MEMORY|mA", "MEMORY|mBatch", "MEMORY|mMulti", "MEMORY|mPlace", "DERIVED|D", "ANSWER|aCite"), setOf("MEMORY|mFar", "ANSWER|aKeep")) { w.fx.forget.forgetMoments(listOf(w.momA)) }
    @Test fun deleteAllDeletesEveryRow() = path(emptySet(), emptySet(), wipe = true) { w.db.wipeDao().wipeMemory(1_000) }

    /** Expiry follows the item: the photo goes, the caption memory stays, so its model row stays too. */
    @Test fun photoExpiryKeepsTheRowsOfTheMemoriesItLeaves() = path(emptySet(), setOf("MEMORY|mA", "MEMORY|mBatch", "DERIVED|D", "ANSWER|aCite", "ANSWER|aKeep")) { w.service.expirePhotos(listOf("fA"), requireLinked = true) }
}
