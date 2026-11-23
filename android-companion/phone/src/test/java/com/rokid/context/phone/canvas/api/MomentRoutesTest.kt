package com.rokid.context.phone.canvas.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MomentRoutesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private var dev = true
    private var now = 1_000_000L
    private val seen = ArrayList<Triple<Long, Long, Long>>()
    private val none = MomentBuilder.Report.NONE
    private val fake: suspend (Long, Long, Long) -> MomentBuilder.DayReport = { f, t, n -> seen += Triple(f, t, n); MomentBuilder.DayReport(MomentBuilder.Report(1, 2, 3), true, MomentBuilder.Report(4, 5, 6)) }
    private fun routes(rebuild: suspend (Long, Long, Long) -> MomentBuilder.DayReport = fake) = MomentRoutes({ dev }, rebuild, { ZoneOffset.UTC as ZoneId }, { now })
    private fun call(r: MomentRoutes, m: String = "POST", path: String = "/api/moments/rebuild", day: String? = "2026-10-25") =
        runBlocking { r.handle(HttpRequest(m, path, if (day == null) emptyMap() else mapOf("day" to day), emptyMap(), ByteArray(0))) }
    private fun json(r: HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject
    private fun code(r: HttpResponse) = json(r)["error"]?.jsonPrimitive?.content

    @Test fun otherPathsAreNotThisGroupsAndOtherMethodsAre405() {
        assertNull(call(routes(), path = "/api/moments"))
        assertNull(call(routes(), path = "/api/moments/rebuild/x"))
        assertEquals(405, call(routes(), m = "GET")!!.status)
    }

    @Test fun developerModeOffIs403AndNothingRuns() {
        dev = false
        val r = call(routes())!!
        assertEquals(403, r.status); assertEquals("developer_mode_off", code(r)); assertTrue(seen.isEmpty())
    }

    @Test fun aMissingOrMalformedDayIs400() {
        for (d in listOf(null, "", "2026-13-01", "2026-02-30", "25-10-2026", "2026-10-25x", "../x")) {
            val r = call(routes(), day = d)!!
            assertEquals("day=$d", 400, r.status); assertEquals("bad_request", code(r))
        }
        assertTrue(seen.isEmpty())
    }

    @Test fun aDayRunsTheRebuildOverThatLocalDayAndAnswersCountsOnly() {
        val r = call(routes())!!
        assertEquals(200, r.status)
        val from = java.time.LocalDate.parse("2026-10-25").atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(listOf(Triple(from, from + 86_400_000L - 1, now)), seen)
        val j = json(r)
        assertEquals("2026-10-25", j["day"]!!.jsonPrimitive.content)
        assertEquals("""{"stored":1,"deleted":2,"rejected":3,"done":true}""", j["catchUp"].toString())
        assertEquals("""{"stored":4,"deleted":5,"rejected":6}""", j["window"].toString())
    }

    @Test fun aSecondCallWithinTheIntervalIs429AndALaterOneRuns() {
        val r = routes()
        assertEquals(200, call(r)!!.status)
        now += MomentRoutes.MIN_INTERVAL_MS - 1
        val limited = call(r)!!
        assertEquals(429, limited.status); assertEquals("rate_limited", code(limited)); assertEquals(1, seen.size)
        now += 1
        assertEquals(200, call(r)!!.status); assertEquals(2, seen.size)
    }

    @Test fun aFailureIs500WithoutItsText() {
        val r = call(routes { _, _, _ -> throw IllegalStateException("secret text") })!!
        assertEquals(500, r.status); assertTrue(!String(r.body).contains("secret"))
    }

    @Test fun theRealBuilderTurnsAMarkOfThatDayIntoAMarkMoment() = runBlocking<Unit> {
        val day = java.time.LocalDate.parse("2026-10-25"); val at = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 5 * 3_600_000L + 500
        val e = DeletionEpoch("i", 1, 0)
        db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("vm:$at:g", "MOMENT", "voice", "MOMENT", at, provenance = "clock:receipt", createdAt = 1), DaoFence(0, e, { 0 }, { e }))
        val builder = MomentBuilder(db, enabled = { true }, clock = { 7 })
        val r = call(routes { f, t, n -> builder.rebuildDay(f, t, n) })!!
        assertEquals(200, r.status)
        assertEquals(1, db.momentDao().allMoments().size)
        assertEquals("""{"stored":1,"deleted":0,"rejected":0}""", json(r)["window"].toString())
        assertEquals("the catch-up window (7 days before the fake now) holds nothing: no marker", "false", json(r)["catchUp"]!!.jsonObject["done"]!!.jsonPrimitive.content)
    }

    /** P1: the day rebuild also rebuilds the Scenes of that day and reports them; the repair route is Developer-only, rate-limited, off (404) without a repairer, and answers counts only. */
    @Test fun theDayRebuildReportsItsScenes() {
        val sc = MomentRoutes({ dev }, fake, { ZoneOffset.UTC as ZoneId }, { now }, scenes = { _, _ -> com.rokid.context.canvas.moment.SceneBuilder.Report(7, 8, 9) })
        assertEquals("""{"stored":7,"removed":8,"unchanged":9}""", json(call(sc)!!)["scenes"].toString())
        assertNull("no scene rebuilder: no scenes key", json(call(routes(), day = "2026-10-25")!!)["scenes"])
    }

    @Test fun theRepairRouteRunsTheRepairAndAnswersCountsOnly() {
        val ran = ArrayList<Long>()
        val r = MomentRoutes({ dev }, fake, { ZoneOffset.UTC as ZoneId }, { now }, repair = { n -> ran += n; com.rokid.context.canvas.moment.MomentRepair.Report(30, 4, 100, 90, 12) })
        val res = call(r, path = "/api/moments/repair", day = null)!!
        assertEquals(200, res.status); assertEquals(listOf(now), ran)
        assertEquals("""{"daysChecked":30,"daysRepaired":4,"missing":100,"momentsStored":90,"scenesStored":12}""", json(res).toString())
        now += MomentRoutes.MIN_INTERVAL_MS - 1
        assertEquals(429, call(r, path = "/api/moments/repair", day = null)!!.status)
        dev = false
        assertEquals(403, call(r, path = "/api/moments/repair", day = null)!!.status); assertEquals(1, ran.size)
    }

    @Test fun theRepairRouteDoesNotExistWithoutARepairer() { assertNull(call(routes(), path = "/api/moments/repair", day = null)) }
}
