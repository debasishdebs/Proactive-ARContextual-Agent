package com.rokid.context.capture.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [ChunkCodec.scan] (the streaming check a crash recovery uses) must accept and refuse what [ChunkCodec.decode] does about the chunk, and report the same counts and times. */
class ChunkScanTest {
    private val id = TraceFixtures.ID
    private fun code(r: Result<*>): String? = (r.exceptionOrNull() as? TraceFormatException)?.code
    private fun both(text: String, seq: Int = 0): Pair<Result<DecodedChunk>, Result<ScannedChunk>> {
        val sha = TraceFixtures.sha(text.toByteArray()); val b64 = TraceFixtures.gzipB64(text.toByteArray())
        return ChunkCodec.decode(id, seq, sha, b64) to ChunkCodec.scan(id, seq, sha, b64)
    }

    @Test fun scanAgreesWithDecodeOnTheGoldenChunk() {
        val enc = ChunkCodec.encode(id, 0, TraceFixtures.events)
        val d = ChunkCodec.decode(id, 0, enc.sha256, enc.b64).getOrThrow()
        val s = ChunkCodec.scan(id, 0, enc.sha256, enc.b64).getOrThrow()
        assertEquals(d.events.size, s.eventCount); assertEquals(d.rawBytes, s.rawBytes); assertEquals(d.sha256, s.sha256)
        assertEquals(d.firstTMs, s.firstTMs); assertEquals(d.lastTMs, s.lastTMs); assertEquals(d.seq, s.seq)
    }

    @Test fun scanRefusesWhatDecodeRefusesAboutTheChunk() {
        val h = "[\"PCT1\",\"$id\",0,2]"
        val ok = "[\"cx\",1000,\"UNKNOWN\"]"
        val cases = listOf(
            "$h\n$ok\n[\"rs\",2000,true]\n",           // good
            "$h\n$ok\n[\"rs\",999,true]\n",             // not monotonic
            "$h\n$ok\n",                                // line count
            "$h\n$ok\n[\"rs\",2000,true]\n$ok\n",       // line count
            "$h\n$ok\n\n[\"rs\",2000,true]\n",          // blank line
            "$h\r\n$ok\n[\"rs\",2000,true]\n",          // CR
            "$h\n$ok\n[\"rs\",2000,true]",              // no trailing newline
            "[\"PCT1\",\"$id\",1,2]\n$ok\n[\"rs\",2000,true]\n", // header seq mismatch
            "$h\n$ok\n[\"zz\",2000,true]\n",            // unknown tag
            "$h\n$ok\n[\"rs\",02000,true]\n",           // leading zero
            "$h\n$ok\n[\"rs\",2000,true\n",             // not closed
            "$h\n",                                      // no events
        )
        for (c in cases) {
            val (d, s) = both(c)
            assertEquals(d.isSuccess, s.isSuccess, "decode=${code(d)} scan=${code(s)} for ${c.replace("\n", "|")}")
        }
        // a flipped checksum and a bad base64 are the same failures
        val enc = ChunkCodec.encode(id, 0, TraceFixtures.events)
        assertEquals("sha_mismatch", code(ChunkCodec.scan(id, 0, "0".repeat(64), enc.b64)))
        assertEquals("b64_invalid", code(ChunkCodec.scan(id, 0, enc.sha256, enc.b64.dropLast(1))))
        assertTrue(ChunkCodec.scan(id, 1, enc.sha256, enc.b64).isFailure, "wrong seq")
    }

    @Test fun theHandWrittenIntegerAndHexChecksKeepTheRegexSemantics() {
        val regexInt = Regex("-?(0|[1-9][0-9]{0,15})")
        val hexRe = Regex("[0-9a-f]{16}")
        val ints = listOf("0", "-0", "7", "10", "01", "00", "+1", "1.0", "", "-", "1234567890123456", "12345678901234567", "9a", " 1", "1e3")
        for (v in ints) {
            val line = "[\"bo\",1000,$v]"
            val accepted = try { EventCodec.decode(line); true } catch (e: TraceFormatException) { false }
            // decode also re-encodes; "-0" matches the regex but fails the canonical re-encode, so compare only where the regex alone would accept AND the value is canonical
            val canonical = regexInt.matches(v) && v != "-0" && (v.toLongOrNull()?.let { it in Int.MIN_VALUE..Int.MAX_VALUE } ?: false)
            assertEquals(canonical, accepted, "int '$v'")
        }
        for (h in listOf("0123456789abcdef", "0123456789ABCDEF", "0123456789abcde", "0123456789abcdef0", "0123456789abcdeg", "")) {
            val line = "[\"sc\",1000,1,\"$h\",\"0000000000000000\",0,false,\"STILL\",false,false,null,false,0]"
            val accepted = try { EventCodec.decode(line); true } catch (e: TraceFormatException) { false }
            assertEquals(hexRe.matches(h), accepted, "hex '$h'")
        }
    }

    // ---- the strict event grammar: scan refuses exactly what decode refuses (Codex review 1, finding 1) ----

    private fun decodeOk(line: String) = try { EventCodec.decode(line); true } catch (e: TraceFormatException) { false }
    private fun scanOk(line: String) = try { EventCodec.scanLine(line, 0, line.length); true } catch (e: TraceFormatException) { false }

    @Test fun aChecksumValidChunkWithABadEventIsRefusedByScanLikeDecode() {
        val h = "[\"PCT1\",\"$id\",0,2]"
        for (bad in listOf("[\"rs\",1000,garbage]", "[\"rs\",1000]", "[\"pr\",-0]", "[\"rs\",1000,true,1]", "[\"pr\",1000,1]")) {
            val (d, s) = both("$h\n[\"cx\",1000,\"REST\"]\n$bad\n")
            assertTrue(d.isFailure, "decode refuses $bad"); assertTrue(s.isFailure, "scan refuses $bad")
        }
    }

    @Test fun scanLineAgreesWithDecodeOnEveryTagAndOnMutatedFields() {
        val golden = TraceFixtures.goldenPayload.lines().drop(1).filter { it.isNotEmpty() }
        assertEquals(16, golden.map { it.substring(2, it.indexOf('"', 2)) }.toSet().size, "all 16 tags are covered")
        val junk = listOf("garbage", "-0", "01", "null", "true", "false", "\"x\"", "\"\"", "99999999999999999", "-1", "1.0", "2147483648", "-2147483649", "2147483647", "\"0123456789abcdeg\"", "\"STILL\"", "\"still\"", "1099511627776", "1099511627775", "1073741824", "[]", "\"a\\\\b\"", "65", "33", "101")
        var n = 0
        for (line in golden) {
            assertTrue(decodeOk(line) && scanOk(line), "the golden line is accepted by both: $line")
            val parts = line.substring(1, line.length - 1).split(',')
            for (i in parts.indices) {
                // drop field i, and add a field after i
                listOf(parts.filterIndexed { j, _ -> j != i }, parts.take(i + 1) + "1" + parts.drop(i + 1)).forEach { p ->
                    val l = "[" + p.joinToString(",") + "]"; n++
                    assertEquals(decodeOk(l), scanOk(l), "count: $l")
                }
                for (j in junk) {
                    val l = "[" + parts.mapIndexed { k, v -> if (k == i) j else v }.joinToString(",") + "]"; n++
                    assertEquals(decodeOk(l), scanOk(l), "field $i -> $j: $l")
                }
            }
        }
        assertTrue(n > 1000, "enough cases: $n")
    }

    @Test fun scanLineAgreesWithDecodeOnRandomLines() {
        val rnd = java.util.Random(20261023)
        val golden = TraceFixtures.goldenPayload.lines().drop(1).filter { it.isNotEmpty() }
        val alphabet = "[]\",-0123456789truefalsnl\\ aAZ"
        repeat(20_000) {
            val base = golden[rnd.nextInt(golden.size)].toCharArray()
            repeat(1 + rnd.nextInt(3)) { base[rnd.nextInt(base.size)] = alphabet[rnd.nextInt(alphabet.length)] }
            val l = String(base)
            assertEquals(decodeOk(l), scanOk(l), "random line: $l")
        }
    }
}
