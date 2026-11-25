package com.rokid.context.canvas.plan

import com.rokid.context.memory.forget.DeletionGraph
import com.rokid.context.memory.forget.lifecycle.Families
import com.rokid.context.memory.forget.lifecycle.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Forget lifecycle M1: the Place Guide and pause-zone surface is accounted for in the registry. Every preference file whose name starts with `pause`, `zone` or `place` that any main source names as a PREFS constant must be owned by a
 * family and KEPT by Delete all (pause zones and the Place Guide switch are settings: never deleted by a Forget or Delete all); the published PlaceCard is an in-memory holder that a Forget of its photo, a range Forget and Delete all withdraw.
 */
class PlaceSurfaceInventoryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val mainSources = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }.filter { it.extension == "kt" && "/src/main/" in it.path }.toList()

    @Test fun everyPauseZonePlaceOrZonePrefsConstantIsRegisteredAndKept() {
        val decl = Regex("""const val (\w*PREFS\w*)\s*=\s*"((?:pause|zone|place)\w*)"""")
        val found = mainSources.flatMap { f -> decl.findAll(f.readText()).map { it.groupValues[2] } }.toSet()
        assertTrue("scan broken: $found", found.containsAll(listOf("pause_zones", "pause_zone_state", "place_guide")))
        val owned = Families.ALL.flatMap { it.prefs }.toSet()
        assertEquals("privacy preference files with no family: ${found - owned}", emptySet<String>(), found - owned)
        found.forEach { assertEquals("$it must survive Delete all", DeletionGraph.Fate.KEPT, DeletionGraph.PREFS.single { e -> e.name == it }.fate) }
    }

    @Test fun thePublishedCardIsAnInMemoryHolderWithdrawnByForgetAndDeleteAll() {
        val holders = Families.of(Family.PLACE_CARD).holders
        assertEquals(listOf("PlaceCardHolder", "PinReferenceVectors", "PlaceShadow", "PackMomentStats"), holders) // the card, and the shadow's in-memory derived vectors (cleared through ShadowCaches)
        val card = mainSources.single { it.name == "PlaceCard.kt" }.readText()
        assertTrue("the holder class exists", "class PlaceCardHolder" in card && "never stored" in card)
        val graph = mainSources.single { it.name == "PlaceGraph.kt" }.readText()
        listOf("onFramesForgotten", "onRangeForgotten", "onDeleteAll").forEach { assertTrue("the card is withdrawn in $it", Regex("override suspend fun $it\\(.*\\{[^}]*port\\.(withdrawFrames|clear)").containsMatchIn(graph)) }
    }
}
