package com.rokid.context.memory.forget.lifecycle

import com.rokid.context.memory.forget.DeletionGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Forget lifecycle M1, T2: who deletes what. Every table of `context.db` and `places.db` (Room's exported schema) is owned by EXACTLY ONE family, every [Family] is registered, a reserved family (Audio, Person, Object)
 * owns nothing until its delete is written, the privacy preferences and the in-memory card are accounted for, and the registry agrees with [DeletionGraph]. Adding an entity, a family value or a privacy preference
 * without a decision fails here.
 */
class FamilyInventoryTest {
    private val schemaRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "schemas") }.first { it.isDirectory }
    private fun tablesOf(db: String): List<String> {
        val dir = File(schemaRoot, db)
        val newest = dir.listFiles().orEmpty().filter { it.extension == "json" }.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        return Regex("\"tableName\"\\s*:\\s*\"(\\w+)\"").findAll(newest.readText()).map { it.groupValues[1] }.toList()
    }
    private val contextTables = tablesOf("com.rokid.context.memory.db.ContextDatabase")
    private val placeTables = tablesOf("com.rokid.context.memory.place.PlacesDatabase")
    private val all = contextTables + placeTables

    @Test fun theScanFindsBothDatabases() { assertTrue(contextTables.size >= 50); assertEquals(setOf("place", "cell", "area"), placeTables.toSet()) }

    @Test fun everyTableHasExactlyOneOwner() {
        val owners = all.associateWith { t -> Families.ALL.filter { t in it.tables }.map { it.family } }
        val orphans = owners.filterValues { it.isEmpty() }.keys
        val shared = owners.filterValues { it.size > 1 }
        assertTrue("tables no family owns (assign them in lifecycle/Families.kt and decide their delete in DeletionGraph): $orphans", orphans.isEmpty())
        assertTrue("tables owned by more than one family: $shared", shared.isEmpty())
    }

    @Test fun noFamilyOwnsATableThatIsGone() {
        val stale = Families.ALL.flatMap { f -> f.tables.filter { it !in all }.map { "${f.family}:$it" } }
        assertTrue("families name tables Room no longer has: $stale", stale.isEmpty())
    }

    @Test fun everyFamilyValueIsRegisteredOnce() {
        assertEquals("a Family value without a registered ForgetFamily", Family.entries.toSet(), Families.ALL.map { it.family }.toSet())
        assertEquals("no family registered twice", Families.ALL.size, Families.ALL.map { it.family }.toSet().size)
    }

    @Test fun reservedFamiliesOwnNothingYet() {
        val reserved = Families.ALL.filter { it.reserved }
        assertEquals(setOf(Family.AUDIO, Family.PERSON, Family.OBJECT), reserved.map { it.family }.toSet())
        reserved.forEach { assertTrue("${it.family} got a table: write its dependents and its delete first, then lift 'reserved'", it.tables.isEmpty() && it.prefs.isEmpty() && it.holders.isEmpty()) }
    }

    @Test fun theRegistryAgreesWithTheDeletionGraph() {
        assertEquals("every table is in the deletion graph and the other way round", all.toSet(), DeletionGraph.TABLES.map { it.name }.toSet())
        val prefs = Families.ALL.flatMap { it.prefs }
        assertEquals("no preference file owned twice", prefs.size, prefs.toSet().size)
        val unknown = prefs.filter { p -> DeletionGraph.PREFS.none { it.name == p } }
        assertTrue("family preference files with no Delete all decision in DeletionGraph.PREFS: $unknown", unknown.isEmpty())
    }

    // ---- the Place Guide surface (places.db, the pause zones, the published card) ----

    @Test fun thePlacePackIsNotPhotoDerivedAndDeleteAllKeepsItsSeededAreas() {
        assertEquals(Family.PLACE_PACK, Families.ownerOfTable("place")); assertEquals(Family.PLACE_PACK, Families.ownerOfTable("cell")); assertEquals(Family.PLACE_PACK, Families.ownerOfTable("area"))
        val fate = DeletionGraph.TABLES.associate { it.name to it.fate }
        assertEquals("fetched places and recency are cleared", DeletionGraph.Fate.SCRUBBED, fate["place"]); assertEquals(DeletionGraph.Fate.SCRUBBED, fate["cell"])
        assertEquals("seeded areas are kept", DeletionGraph.Fate.KEPT, fate["area"])
    }

    @Test fun pauseZonePrefsAreNeverDeletedByAForgetOrDeleteAll() {
        listOf("pause_zones", "pause_zone_state", "place_guide", "place_pins", "nexus_host").forEach { name ->
            assertEquals("$name is kept by Delete all", DeletionGraph.Fate.KEPT, DeletionGraph.PREFS.single { it.name == name }.fate)
            assertTrue("$name is owned by a family that no Forget reaches", Families.ALL.single { name in it.prefs }.family in setOf(Family.CONFIG, Family.PLACE_PACK))
        }
        assertTrue("never in the wiped list", DeletionGraph.wipedPrefs.none { it in setOf("pause_zones", "pause_zone_state", "place_guide", "place_pins", "nexus_host") })
    }

    @Test fun theDataSaverSettingsAndTodaysByteCountersAreKeptByDeleteAll() {
        assertEquals(DeletionGraph.Fate.KEPT, DeletionGraph.PREFS.single { it.name == "data_saver" }.fate)
        assertTrue("data_saver is never in the wiped list", "data_saver" !in DeletionGraph.wipedPrefs)
        assertTrue(DeletionGraph.PREFS.single { it.name == "data_saver" }.why.contains("byte counters"))
    }

    @Test fun aPlaceMemoryIsAMemoryAndItsFrameSourceRowIsThePhotos() {
        assertEquals(Family.MEMORY, Families.ownerOfTable("memory")); assertEquals(Family.PHOTO, Families.ownerOfTable("frame_source"))
        assertEquals(listOf("PlaceCardHolder", "PinReferenceVectors", "PlaceShadow"), Families.of(Family.PLACE_CARD).holders)
    }
}
