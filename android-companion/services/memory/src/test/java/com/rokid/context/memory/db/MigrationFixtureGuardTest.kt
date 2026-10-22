package com.rokid.context.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationFixtureGuardTest {
    private var cleans = 0; private var markerWrites = 0; private var markerDeletes = 0
    private fun guard(pkg: String, db: Boolean, marker: Boolean) =
        MigrationFixtureGuard(pkg, { db }, { marker }, { markerWrites++ }, { markerDeletes++ }, { cleans++ })

    private fun runRefused(g: MigrationFixtureGuard) { try { g.acquire() } catch (_: IllegalStateException) { } finally { g.release() } } // JUnit: @After runs after a failed @Before

    @Test fun anUnownedDatabaseIsNeverDeletedByARefusedRun() {
        val g = guard("com.rokid.context.services.memory.test", db = true, marker = false)
        runRefused(g)
        assertEquals(0, cleans); assertEquals(0, markerWrites); assertEquals(0, markerDeletes); assertFalse(g.owned)
    }

    @Test fun theWrongPackageNeverDeletesAnything() {
        val g = guard("com.rokid.context", db = false, marker = false)
        runRefused(g)
        assertEquals(0, cleans); assertEquals(0, markerWrites); assertEquals(0, markerDeletes)
    }

    @Test fun anOwnedRunCleansBeforeAndAfter() {
        val g = guard("a.b.test", db = false, marker = false)
        g.acquire(); assertTrue(g.owned); g.release()
        assertEquals(2, cleans); assertEquals(1, markerWrites); assertEquals(1, markerDeletes); assertFalse(g.owned)
    }

    @Test fun aFixtureLeftByAnEarlierRunWithItsMarkerIsOwned() {
        val g = guard("a.b.test", db = true, marker = true)
        g.acquire(); g.release()
        assertEquals(2, cleans)
    }
}
