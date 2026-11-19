package com.rokid.context.memory.db

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * Activity pagination A9 (laptop half): a synthetic 100,000-moment database (plain test data) is walked with the real keyset DAOs. Measures the first page, reaching row 1,500, a full walk, the
 * day -> hour breakdown build and the heap growth, PRINTS every number (grep `PAGINATION-PERF`) and asserts only generous bounds, so it is not flaky on a slow CI box. Real phone CPU/storage,
 * SQLCipher and Compose scrolling are NOT measured here (see docs/superpowers/reviews/2026-11/activity-pagination-timing-2026-11-19.md).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActivityPaginationPerfTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L
    private val days = 100
    private val perDay = 1_000 // 100 days x 1,000 = 100,000 memories (98,000 visible: every 50th needs review)
    private val end = 1_800_000_000_000L - 1_800_000_000_000L % day + day // a UTC midnight, exclusive end
    private val start = end - days * day

    private fun out(s: String) = println("PAGINATION-PERF $s")
    private inline fun <T> timed(block: () -> T): Pair<T, Double> { val t = System.nanoTime(); val r = block(); return r to (System.nanoTime() - t) / 1e6 }
    private fun f1(d: Double) = "%.1f".format(d)
    private fun heapMb(): Double { repeat(3) { System.gc() }; return Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1048576.0 } }
    private fun plan(sql: String, vararg args: String): List<String> =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c -> buildList { while (c.moveToNext()) add(c.getString(3).replace("SEARCH TABLE ", "SEARCH ").replace("SCAN TABLE ", "SCAN ")) } }

    private fun seed() {
        val w = db.openHelper.writableDatabase
        w.beginTransaction()
        try {
            var n = 0
            for (d in 0 until days) for (k in 0 until perDay) {
                val at = start + d * day + (k * (day / perDay)) + (n % 7)
                val fid = if (n % 5 != 0) "f$n" else null // 80% of moments have a photo, shown at the photo's time
                if (fid != null) w.insert("frame", 0, ContentValues().apply { put("id", fid); put("ts", at - 3_000L); put("path", "/x/$fid.jpg"); put("source", "rokid_glasses"); put("payload", "") })
                w.insert("memory", 0, ContentValues().apply {
                    put("id", "m%06d".format(n)); put("frameId", fid); put("text", "synthetic moment $n"); put("type", "short_term"); put("tags", ""); put("tier", "SHORT")
                    put("confidence", 0.8); put("reviewState", if (n % 50 == 0) "NEEDS_REVIEW" else "OK"); put("trusted", 0); put("createdAt", at); put("derived", 0); put("searchText", "")
                })
                n++
            }
            // 30,000 nudges and 30,000 marks over the same 100 days, for the plan checks of those two keyset queries (the plan, not the page time, is what they assert).
            for (i in 0 until 30_000) {
                val at = start + i * (days * day / 30_000)
                w.insert("nudge", 0, ContentValues().apply { put("id", "n%06d".format(i)); put("ts", at); put("text", "n"); put("why", "w"); put("sourceIdsJson", "[]"); put("priority", 1); put("hash", "h$i"); put("shownOn", "phone"); put("redacted", 0) })
                w.insert("canvas_label", 0, ContentValues().apply { put("labelId", "l%06d".format(i)); put("kind", "MOMENT"); put("source", "tap"); put("name", "X"); put("atMs", at); put("provenance", ""); put("deletionCounter", 0); put("createdAt", at) })
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
        runBlocking {
            // 5,000 real answers and 10,000 developer eval asks NEWER than every real one: the worst case for the `source != 'canvas_eval'` skip.
            repeat(5_000) { db.askDao().insertIgnore(AskEntity("a$it", start + it * (days * day / 5_000), "q", "a", "[]", null, "phone_typed", false)) }
            repeat(10_000) { db.askDao().insertIgnore(AskEntity("e$it", end - 1 - it, "q", "a", "[]", null, "canvas_eval", false)) }
        }
    }

    @Test fun hundredThousandMomentsStayWithinBounds() = runBlocking<Unit> {
        val (_, seedMs) = timed { seed() }
        out("seed 100,000 moments + 80,000 frames + 5,000 answers + 10,000 eval asks: ${"%.0f".format(seedMs)} ms (not an app path)")
        val v = db.memoryViewDao()
        val visible = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM memory WHERE reviewState = 'OK'").use { it.moveToFirst(); it.getInt(0) }
        out("visible moments=$visible")

        // 1. first page (cold, then 20 warm runs)
        val (p1, cold) = timed { v.visiblePageIn(start, end, end, "", 100) }
        assertEquals(100, p1.size)
        val warm = (1..20).map { timed { v.visiblePageIn(start, end, end, "", 100) }.second }.sorted()
        out("first page of 100: cold=${f1(cold)} ms, warm median=${f1(warm[10])} ms, warm max=${f1(warm.last())} ms")

        // 2. row 1,500 by paging (15 pages of 100), then a FULL walk: no duplicate, nothing skipped, heap growth with only one page of entities kept
        val heap0 = heapMb()
        var ck = end; var ci = ""; var rows = 0; var tTo1500 = 0.0; var worst = 0.0; var total = 0.0
        val seen = HashSet<String>(visible * 2)
        var last: List<MemoryEntity> = emptyList()
        while (true) {
            val (p, ms) = timed { v.visiblePageIn(start, end, ck, ci, 100) }
            if (p.isEmpty()) break
            rows += p.size; total += ms; if (ms > worst) worst = ms
            p.forEach { assertTrue("duplicate ${it.id}", seen.add(it.id)) }
            if (rows <= 1_500) tTo1500 = total
            last = p; ck = p.last().createdAt; ci = p.last().id
            if (p.size < 100) break
        }
        val heap1 = heapMb()
        assertEquals("every visible moment reached exactly once", visible, rows)
        out("row 1,500 reached by 15 keyset pages: ${"%.0f".format(tTo1500)} ms total; full walk ${rows / 100} pages: ${"%.0f".format(total)} ms, slowest page ${f1(worst)} ms")
        // a jump straight to the oldest page by its cursor (keyset: no OFFSET, so it costs one page)
        val (deep, deepMs) = timed { v.visiblePageIn(start, end, last.first().createdAt + 1, "", 100) }
        out("jump to the oldest rows by cursor: ${f1(deepMs)} ms (${deep.size} rows)")
        out("heap before walk=${f1(heap0)} MB, after walking $rows rows (ids kept for the duplicate check + one page of entities)=${f1(heap1)} MB, growth=${f1(heap1 - heap0)} MB")

        // 3. the broken-down view of the newest day: hour counts, then page 1 and 2 of the busiest hour and the Earlier-photos group
        val dayFrom = end - day
        val (times, timesMs) = timed { v.visibleMomentTimes(dayFrom, end) }
        val perHour = times.groupBy { Instant.ofEpochMilli(it).atZone(zone).hour }.mapValues { it.value.size }
        val (hour, count) = perHour.maxByOrNull { it.value }!!
        val hFrom = dayFrom + hour * 3_600_000L; val hTo = hFrom + 3_600_000L
        val (hp1, hp1Ms) = timed { v.momentPageIn(dayFrom, end, hFrom, hTo, hTo, "", 25) }
        val (hp2, hp2Ms) = timed { v.momentPageIn(dayFrom, end, hFrom, hTo, hp1.last().shownAt, hp1.last().memory.id, 25) }
        val (earlier, earlierMs) = timed { v.momentPageIn(dayFrom, end, Long.MIN_VALUE, dayFrom, dayFrom, "", 25) }
        out("breakdown, newest day: ${times.size} moments in ${perHour.size} hours; hour counts ${f1(timesMs)} ms; busiest hour $hour ($count): page1 ${f1(hp1Ms)} ms, page2 ${f1(hp2Ms)} ms; Earlier-photos group ${f1(earlierMs)} ms (${earlier.size} rows)")
        assertEquals(25, hp1.size); assertTrue(hp2.isNotEmpty())
        val (_, allDaysMs) = timed { (0 until days).forEach { d -> v.visibleMomentTimes(start + d * day, start + (d + 1) * day).groupBy { Instant.ofEpochMilli(it).atZone(zone).hour } } }
        out("hour counts for every one of the 100 days (worst case, user expands them all): ${"%.0f".format(allDaysMs)} ms total")

        // 4. answers with 10,000 newer eval asks in the way (design section 3: eval-ask skip, target < 50 ms on device)
        val (a1, askMs) = timed { db.askDao().pageIn(start, end, end, "", 100) }
        out("answers first page behind 10,000 newer eval asks: ${f1(askMs)} ms (${a1.size} rows)")
        assertEquals(100, a1.size); assertTrue(a1.none { it.source == "canvas_eval" })

        // 5. query plans of the ACTUAL five keyset queries (SQL text read from the @Query annotations in the DAO sources, so it cannot drift), with a cursor in the MIDDLE of the 100,000-row data:
        // each must SEARCH its table through the time index with a range on BOTH sides and never SCAN the table.
        val mid = start + 50 * day + 12_345
        val midArgs = mapOf("fromMs" to start, "toMs" to end, "cKey" to mid, "cId" to "m050000", "n" to 100L)
        val five = listOf(
            Triple("frames", QuerySource.of("ViewDaos.kt", "interface FrameViewDao", "pageIn"), "frame" to "ts"),
            Triple("memories", QuerySource.of("ViewDaos.kt", "abstract class MemoryViewDao", "visiblePageIn"), "memory" to "createdAt"),
            Triple("answers", QuerySource.of("ViewDaos.kt", "interface AskDao", "pageIn"), "ask" to "ts"),
            Triple("nudges", QuerySource.of("ViewDaos.kt", "interface NudgeDao", "pageIn"), "nudge" to "ts"),
            Triple("marks", QuerySource.of("MomentDao.kt", "abstract class MomentDao", "marksPage"), "canvas_label" to "atMs"),
        )
        for ((name, q, tc) in five) {
            assertTrue("$name: the real query has the cKey bound: ${q.sql}", q.sql.contains("<= :cKey"))
            val (qs, qargs) = q.bind(midArgs)
            val p = plan(qs, *qargs)
            out("plan $name (cursor mid-range): $p")
            val (table, col) = tc
            val search = p.firstOrNull { it.startsWith("SEARCH $table ") && it.contains(" USING ") }
            assertTrue("$name must SEARCH $table through an index: $p", search != null)
            assertTrue("$name must use an index on $col with a lower bound: $search", search!!.contains("INDEX") && search.contains("$col>?"))
            assertTrue("$name needs a range constraint on BOTH sides of $col: $search", search.contains("$col<?"))
            assertTrue("$name must never SCAN $table: $p", p.none { it.startsWith("SCAN $table") })
        }
        // momentPageIn (hour/day window, no cKey bound by design): real SQL, must not scan memory
        val (mq, margs) = QuerySource.of("ViewDaos.kt", "abstract class MemoryViewDao", "momentPageIn").bind(mapOf("dayFromMs" to start, "dayToMs" to end, "hFromMs" to start, "hToMs" to end, "cKey" to mid, "cId" to "m050000", "n" to 25L))
        val mp = plan(mq, *margs); out("plan momentPageIn: $mp")
        assertTrue("momentPageIn must not scan memory: $mp", mp.none { it.startsWith("SCAN m") })

        // very generous bounds (measured: worst page 16 ms on a laptop; these only catch a pathological regression and must not flake under load; the phone allowance lives in the review doc, not here)
        assertTrue("first page cold < 10 s: $cold", cold < 10_000)
        assertTrue("warm first page < 5 s: ${warm[10]}", warm[10] < 5_000)
        assertTrue("row 1,500 < 5 s: $tTo1500", tTo1500 < 5_000)
        assertTrue("slowest page < 10 s: $worst", worst < 10_000)
        assertTrue("hour counts for a day < 10 s: $timesMs", timesMs < 10_000)
        assertTrue("hour page < 10 s: $hp1Ms", hp1Ms < 10_000)
        assertTrue("answers behind eval asks < 10 s: $askMs", askMs < 10_000)
        assertTrue("heap growth < 300 MB: ${heap1 - heap0}", heap1 - heap0 < 300)
    }

    /** The `@Query` SQL of one DAO function read from its Kotlin source (Room keeps no runtime copy); `:name` parameters are kept so they can be bound in order. */
    class QuerySource private constructor(val sql: String) {
        /** `?` for every `:name`, plus the values in order of appearance (the order Room binds them in). */
        fun bind(values: Map<String, Any>): Pair<String, Array<String>> {
            val names = mutableListOf<String>()
            val q = Regex(":([A-Za-z]+)").replace(sql) { names += it.groupValues[1]; "?" }
            return q to names.map { values.getValue(it).toString() }.toTypedArray()
        }
        companion object {
            fun of(file: String, anchor: String, fn: String): QuerySource {
                val rel = "src/main/java/com/rokid/context/memory/db/$file"
                val f = listOf(rel, "services/memory/$rel", "android-companion/services/memory/$rel").map { java.io.File(it) }.first { it.exists() }
                val src = f.readText()
                val anchorAt = src.indexOf(anchor); require(anchorAt >= 0) { "no $anchor in $file" }
                val fnAt = src.indexOf("fun $fn(", anchorAt); require(fnAt >= 0) { "no $fn after $anchor" }
                val qAt = src.lastIndexOf("@Query(", fnAt)
                val lits = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(src.substring(qAt, fnAt)).map { it.groupValues[1] }.toList()
                require(lits.isNotEmpty()) { "no SQL for $fn" }
                return QuerySource(lits.joinToString(""))
            }
        }
    }

    /**
     * The `AND key <= :cKey` bound is only a planner hint: it must NEVER change a result. On a small dataset full of timestamp ties (and eval asks, review-needed memories, INTENT labels), random cursors (inside the
     * range, outside it, on a tie, with an unknown id, the first-page cursor) give exactly the rows of a reference query written here WITHOUT the bound, for all five queries (marks tie-break on labelId ASC).
     */
    @Test fun cKeyBoundNeverChangesResults() = runBlocking<Unit> {
        val rnd = java.util.Random(20261119)
        val w = db.openHelper.writableDatabase
        val stamps = (0 until 15).map { 1_000L + it * 100 } // 15 distinct instants, ~20 rows each: heavy ties
        val ids = ArrayList<String>()
        w.beginTransaction()
        try {
            for (i in 0 until 300) {
                val ts = stamps[rnd.nextInt(stamps.size)]; val id = "x%04d".format(rnd.nextInt(10_000)) + "_$i"; ids += id
                w.insert("frame", 0, ContentValues().apply { put("id", id); put("ts", ts); put("path", "/x/$id"); put("source", "rokid_glasses"); put("payload", "") })
                w.insert("memory", 0, ContentValues().apply {
                    put("id", id); putNull("frameId"); put("text", "t"); put("type", "short_term"); put("tags", ""); put("tier", "SHORT"); put("confidence", 0.8)
                    put("reviewState", if (i % 7 == 0) "NEEDS_REVIEW" else "OK"); put("trusted", 0); put("createdAt", ts); put("derived", 0); put("searchText", "")
                })
                w.insert("ask", 0, ContentValues().apply { put("id", id); put("ts", ts); put("question", "q"); put("answer", "a"); put("sourcesJson", "[]"); put("source", if (i % 5 == 0) "canvas_eval" else "phone_typed"); put("redacted", 0) })
                w.insert("nudge", 0, ContentValues().apply { put("id", id); put("ts", ts); put("text", "n"); put("why", "w"); put("sourceIdsJson", "[]"); put("priority", 1); put("hash", "h_$id"); put("shownOn", "phone"); put("redacted", 0) })
                w.insert("canvas_label", 0, ContentValues().apply { put("labelId", id); put("kind", if (i % 6 == 0) "INTENT" else "MOMENT"); put("source", "tap"); put("name", "X"); put("atMs", ts); put("provenance", ""); put("deletionCounter", 0); put("createdAt", ts) })
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }

        val from = 1_200L; val to = 2_200L
        fun refIds(sql: String, args: Array<Any?>): List<String> = w.query(sql, args).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        // reference queries: the same filter and order, the cursor test WITHOUT the `<= :cKey` bound
        val refFrame = "SELECT id FROM frame WHERE ts >= ? AND ts < ? AND (ts < ? OR (ts = ? AND id < ?)) ORDER BY ts DESC, id DESC LIMIT ?"
        val refMemory = "SELECT id FROM memory WHERE createdAt >= ? AND createdAt < ? AND reviewState = 'OK' AND (createdAt < ? OR (createdAt = ? AND id < ?)) ORDER BY createdAt DESC, id DESC LIMIT ?"
        val refAsk = "SELECT id FROM ask WHERE ts >= ? AND ts < ? AND source != 'canvas_eval' AND (ts < ? OR (ts = ? AND id < ?)) ORDER BY ts DESC, id DESC LIMIT ?"
        val refNudge = "SELECT id FROM nudge WHERE ts >= ? AND ts < ? AND (ts < ? OR (ts = ? AND id < ?)) ORDER BY ts DESC, id DESC LIMIT ?"
        val refMark = "SELECT labelId FROM canvas_label WHERE kind = 'MOMENT' AND atMs >= ? AND atMs < ? AND (atMs < ? OR (atMs = ? AND labelId > ?)) ORDER BY atMs DESC, labelId ASC LIMIT ?"

        val v = db.memoryViewDao(); val fv = db.frameViewDao(); val a = db.askDao(); val nd = db.nudgeDao(); val md = db.momentDao()
        var nonEmpty = 0
        repeat(400) { round ->
            val cKey = when (round % 4) { 0 -> stamps[rnd.nextInt(stamps.size)]; 1 -> to; 2 -> rnd.nextInt(3_000).toLong() - 200; else -> from + rnd.nextInt(1_000) }
            val cId = when (rnd.nextInt(4)) { 0 -> ""; 1 -> "zzzz"; 2 -> ids[rnd.nextInt(ids.size)]; else -> "x%04d".format(rnd.nextInt(10_000)) }
            val n = intArrayOf(1, 3, 17, 100, 1_000)[rnd.nextInt(5)]
            val args = arrayOf<Any?>(from, to, cKey, cKey, cId, n.toLong())
            val ctx = "cursor=($cKey,'$cId') n=$n"
            assertEquals("frames $ctx", refIds(refFrame, args), fv.pageIn(from, to, cKey, cId, n).map { it.id })
            assertEquals("memories $ctx", refIds(refMemory, args), v.visiblePageIn(from, to, cKey, cId, n).map { it.id })
            assertEquals("answers $ctx", refIds(refAsk, args), a.pageIn(from, to, cKey, cId, n).map { it.id })
            assertEquals("nudges $ctx", refIds(refNudge, args), nd.pageIn(from, to, cKey, cId, n).map { it.id })
            val marks = md.marksPage(from, to, cKey, cId, n).map { it.labelId }
            assertEquals("marks $ctx", refIds(refMark, args), marks)
            if (marks.isNotEmpty()) nonEmpty++
        }
        assertTrue("the random cursors must exercise real pages, not only empty ones ($nonEmpty)", nonEmpty > 100)
        out("keyset invariant: 400 random cursors x 5 queries equal to the unbounded reference ($nonEmpty rounds with a non-empty marks page)")
    }
}
