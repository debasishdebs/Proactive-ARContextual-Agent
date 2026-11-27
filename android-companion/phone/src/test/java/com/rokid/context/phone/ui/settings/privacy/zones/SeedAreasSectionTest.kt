package com.rokid.context.phone.ui.settings.privacy.zones

import android.app.Application
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.AREA_PARTIAL
import com.rokid.context.memory.place.AREA_SEEDING
import com.rokid.context.memory.place.CELL_OK
import com.rokid.context.memory.place.CELL_PARTIAL
import com.rokid.context.phone.place.SeedAreaState
import com.rokid.context.phone.place.SeedPhase
import com.rokid.context.phone.place.SeedProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The shared seeded-areas section (Pause zones and Map zones): every state in words, the header, the truthful state of an old-build area, and the section on the Pause zones screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SeedAreasSectionTest {
    @get:Rule val compose = createComposeRule()
    private fun area(id: String = "seed:1", status: String = AREA_DONE, name: String? = null, seed: SeedAreaState? = null, failed: Int = 0, cancelled: Boolean = false, noOsm: Int = 0) =
        SeedAreaInfo(id, 27.0, 88.0, 27.05, 88.05, status, name, seed, 12, 300_000, 1_700_000_000_000, failed, cancelled, noOsm)

    private fun text(a: SeedAreaInfo) = SeedAreaList.row(0, a, java.time.ZoneOffset.UTC)

    @Test fun doneAndDoneWithoutOsmExtras() {
        assertTrue(text(area()).startsWith("Seeded area 1 · Done · 12 places · "))
        assertTrue(text(area(noOsm = 3)).contains("Done · 3 cells without OSM extras"))
        assertTrue(text(area()).contains("0.3 MB") && text(area()).endsWith("2023-11-14"))
    }

    @Test fun runningWaitingAndCancelledUseTheSharedSeedText() {
        val running = SeedAreaState(SeedPhase.RUNNING, 25, counts = SeedProgress(done = 525, total = 2100, okCells = 200, failCells = 325, partial = 12))
        assertTrue(text(area(status = AREA_SEEDING, seed = running)).contains("Seeding places 25% done (525 of 2100)"))
        val waiting = SeedAreaState(SeedPhase.WAITING_NETWORK, 10, counts = SeedProgress(done = 10, total = 100))
        assertTrue(text(area(status = AREA_SEEDING, seed = waiting)).contains("waiting for a network"))
        assertTrue(text(area(status = AREA_PARTIAL, cancelled = true)).contains("Cancelled: seed again to resume"))
    }

    @Test fun failedNamesTheCountAndTheRetryButton() {
        val t = text(area(status = AREA_PARTIAL, failed = 3))
        assertTrue(t, t.contains("FAILED: 3 cells did not seed: tap Retry failed cells"))
        assertFalse(t.contains("will retry"))
    }

    @Test fun headerCountsAreasAndPlaces() {
        assertEquals("2 seeded areas · 24 places stored", SeedAreaList.header(listOf(area(), area("seed:2"))))
        assertEquals("1 seeded area · 12 places stored", SeedAreaList.header(listOf(area())))
    }

    private val wanted = listOf("a", "b", "c")

    @Test fun oldBuildPartialWithEveryCellStoredReadsDoneEvenWhenSomeArePartial() {
        val t = SeedAreaList.truth(AREA_PARTIAL, false, null, 0, wanted, mapOf("a" to CELL_OK, "b" to CELL_PARTIAL, "c" to CELL_OK))
        assertEquals(AREA_DONE, t.status); assertEquals(0, t.failed); assertEquals(1, t.noOsm)
    }

    @Test fun oldBuildPartialWithMissingCellsSaysHowManyAndPromisesNoAutomaticRetry() {
        val t = SeedAreaList.truth(AREA_PARTIAL, false, null, 0, wanted, mapOf("a" to CELL_OK))
        assertEquals(AREA_PARTIAL, t.status); assertEquals(2, t.failed)
        val line = SeedAreaList.statusText(area(status = t.status, failed = t.failed))
        assertTrue(line, line.contains("2 cells did not seed: tap Retry failed cells")); assertFalse(line.contains("retry automatically") || line.contains("will retry"))
    }

    @Test fun anAreaWithNoReadableCellsAndNoWorkJustSaysNotFinished() {
        val t = SeedAreaList.truth(AREA_PARTIAL, false, null, 0, wanted, null)
        assertEquals(0, t.failed)
        assertEquals("Not finished: tap Seed again", SeedAreaList.statusText(area(status = t.status)))
    }

    @Test fun liveWorkIsLeftAlone_andCancelledOrFailedRunsKeepTheirState() {
        assertEquals(AREA_SEEDING, SeedAreaList.truth(AREA_SEEDING, true, SeedPhase.RUNNING, 0, wanted, mapOf("a" to CELL_OK)).status)
        assertTrue(SeedAreaList.truth(AREA_PARTIAL, false, SeedPhase.CANCELLED, 0, wanted, mapOf("a" to CELL_OK)).cancelled)
        assertEquals(5, SeedAreaList.truth(AREA_PARTIAL, false, SeedPhase.FAILED, 5, wanted, mapOf("a" to CELL_OK)).failed)
    }

    private val infos = listOf(area("seed:1", name = "Sikkim"), area("seed:2", status = AREA_PARTIAL, failed = 2))
    private fun ui() = SeedMapUiState(areas = SeedOverlays.of(infos), rows = SeedAreaList.rows(infos), header = SeedAreaList.header(infos))

    @Test fun pauseZonesScreenShowsTheSharedSectionWithEveryControl() {
        val log = mutableListOf<String>()
        val panel = SeedPanel(ui(), false, { log += "seed" }, {}, {}, { log += "remove:$it" }, {}, {}, {}, rename = { id, n -> log += "rename:$id:$n" }, retryFailed = { log += "retry:$it" })
        val on = ZonesCallbacks({}, {}, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {})
        compose.setContent { PcTheme { ZonesScreen(ZonesUiState(), on, null, null, panel, networkOnline = false) } }
        compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag("seed_header"))
        compose.onNodeWithTag("seed_header").assertExists()
        assertEquals("2 seeded areas · 24 places stored", ui().header)
        compose.onNodeWithTag("seed_area_seed:1").assertExists()
        compose.onNodeWithTag("zones_screen").performScrollToNode(hasTestTag("seed_retry_seed:2"))
        compose.onNodeWithTag("seed_retry_seed:2").performClick()
        compose.onNodeWithTag("seed_again_seed:1").performClick()
        compose.onNodeWithTag("seed_remove_seed:2").performClick()
        compose.onNodeWithTag("seed_rename_seed:1").assertExists()
        assertEquals(listOf("retry:seed:2", "seed", "remove:seed:2"), log)
    }

    @Test fun theSectionIsOneComposableForBothScreens() {
        val src = java.io.File("src/main/java/com/rokid/context/phone/ui/settings").walkTopDown().filter { it.extension == "kt" }
        val users = src.filter { it.readText().contains("SeededAreasContent(") && it.name != "SeedAreasSection.kt" }.map { it.name }.toSet()
        assertEquals(setOf("ZonesScreen.kt", "MapZonesScreen.kt"), users)
    }
}
