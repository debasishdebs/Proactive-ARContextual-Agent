package com.rokid.context.phone.canvas.api

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentLocationImport
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.canvas.server.HttpResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MomentLocationRoutesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private var dev = true; private var on = true; private var blocked = false; private var now = 1_000_000L
    private val sec = 1_761_000_000L
    private val capture = sec * 1000 + 5_000
    private val id = MomentId.format(sec + 5)
    private val header = MomentLocationImport.HEADER

    private fun routes() = MomentLocationRoutes({ dev }, { on }, { blocked }, MomentLocationImport(db, { 1L }, { on }, { emptyList() }, { 0L }), { now })
    private fun call(r: MomentLocationRoutes, body: String = header, m: String = "POST", path: String = MomentLocationRoutes.PATH, tooLarge: Boolean = false) =
        runBlocking { r.handle(HttpRequest(m, path, emptyMap(), emptyMap(), body.toByteArray(), bodyTooLarge = tooLarge)) }
    private fun json(r: HttpResponse) = Json.parseToJsonElement(String(r.body)).jsonObject
    private fun code(r: HttpResponse) = json(r)["error"]?.jsonPrimitive?.content
    private fun line(m: String = id) = "$m\t$capture\tFRAME\tf1\t12.0\t77.0\t50.0\t30000\tSTATIONARY\tINTERPOLATED"

    @Test fun otherPathIsNotThisGroupsAndOtherMethodIs405() {
        assertNull(call(routes(), path = "/api/moments/locations"))
        assertEquals(405, call(routes(), m = "GET")!!.status)
    }

    @Test fun developerModeOffIs403() { dev = false; val r = call(routes())!!; assertEquals(403, r.status); assertEquals("developer_mode_off", code(r)) }

    @Test fun settingOffIs409AndZoneActiveIs409() {
        on = false; val off = call(routes())!!; assertEquals(409, off.status); assertEquals("setting_off", code(off))
        on = true; blocked = true
        val r = call(routes())!!; assertEquals(409, r.status); assertEquals("zone_active", code(r))
    }

    @Test fun secondCallWithinTenSecondsIs429AndLaterIsFine() {
        val r = routes()
        assertEquals(200, call(r)!!.status)
        val again = call(r)!!; assertEquals(429, again.status); assertEquals("rate_limited", code(again))
        now += MomentLocationRoutes.MIN_INTERVAL_MS; assertEquals(200, call(r)!!.status)
    }

    @Test fun busyIs409WhileOneRuns() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = MomentLocationRoutes({ true }, { true }, { gate.await(); false }, MomentLocationImport(db, { 1L }, { true }, { emptyList() }, { 0L }), { now })
        val first = async(kotlinx.coroutines.Dispatchers.Default) { slow.handle(HttpRequest("POST", MomentLocationRoutes.PATH, emptyMap(), emptyMap(), header.toByteArray())) }
        kotlinx.coroutines.delay(200)
        val second = slow.handle(HttpRequest("POST", MomentLocationRoutes.PATH, emptyMap(), emptyMap(), header.toByteArray()))!!
        assertEquals(409, second.status); assertEquals("busy", code(second))
        gate.complete(Unit); assertEquals(200, first.await()!!.status)
    }

    @Test fun tooLargeBodyOrTooManyRowsIs400() {
        val a = call(routes(), tooLarge = true)!!; assertEquals(400, a.status); assertEquals("too_large", code(a))
        now += 20_000
        val rows = (0..MomentLocationRoutes.MAX_ROWS).joinToString("\n") { line() }
        val b = call(routes(), "$header\n$rows")!!; assertEquals(400, b.status); assertEquals("too_large", code(b))
    }

    @Test fun badHeaderIs400() {
        for (h in listOf("", "momentId\tatMs", header.replace("method", "meth"), " $header")) {
            val r = call(routes(), h)!!; assertEquals(h, 400, r.status); assertEquals("bad_header", code(r))
        }
    }

    @Test fun answerIsCountsOnlyAndNeverEchoesTheBody() = runBlocking {
        db.frameDao().insertIgnore(FrameEntity("f1", capture, "/f1.jpg", "rokid_glasses", phash = "pf1"))
        db.frameMetaDao().upsert(FrameMetaEntity("f1", null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = capture))
        MomentBuilder(db).build(listOf(id))
        val body = "$header\n${line()}\nnot a row\n${line("00000001")}\n"
        val r = call(routes(), body)!!
        assertEquals(200, r.status)
        val j = json(r)
        assertEquals(3, j["received"]!!.jsonPrimitive.content.toInt()); assertEquals(1, j["written"]!!.jsonPrimitive.content.toInt())
        assertEquals(setOf("received", "written", "skipped"), j.keys)
        val sk = j["skipped"]!!.jsonObject
        assertEquals("2", sk["bad_row"]!!.jsonPrimitive.content)
        val text = String(r.body)
        assertFalse(text.contains("12.0")); assertFalse(text.contains(id)); assertTrue(text.contains("written"))
    }
}
