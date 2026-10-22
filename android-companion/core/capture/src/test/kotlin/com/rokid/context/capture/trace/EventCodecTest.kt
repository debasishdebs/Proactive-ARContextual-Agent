package com.rokid.context.capture.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventCodecTest {
    private val lines = TraceFixtures.goldenPayload.lines().drop(1).filter { it.isNotEmpty() }

    @Test fun roundTripEveryEventType() {
        assertEquals(17, TraceFixtures.events.size)
        assertEquals(16, TraceFixtures.events.map { it::class }.toSet().size) // Episode appears twice (open and close)
        TraceFixtures.events.forEachIndexed { i, e ->
            assertEquals(lines[i], EventCodec.encode(e), "encode $i")
            assertEquals(e, EventCodec.decode(lines[i]), "decode $i")
        }
    }

    @Test fun floatBitsExact() {
        val odd = listOf(-0.0f, 0.0f, Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 1.17549435E-38f, 0.1f, 9.80665f)
        for (f in odd) {
            val e = TraceEvent.Gyro(5, 5, f, f, f)
            val back = EventCodec.decode(EventCodec.encode(e)) as TraceEvent.Gyro
            assertEquals(f.toRawBits(), back.x.toRawBits(), "$f")
        }
        assertNotEquals(EventCodec.encode(TraceEvent.Gyro(5, 5, 0.0f, 0f, 0f)), EventCodec.encode(TraceEvent.Gyro(5, 5, -0.0f, 0f, 0f)))
        assertEquals("""["g",5,5,-2147483648,0,0]""", EventCodec.encode(TraceEvent.Gyro(5, 5, -0.0f, 0f, 0f)))
    }

    @Test fun nonFiniteFloatsRefusedBothWays() {
        assertFailsWith<IllegalArgumentException> { TraceEvent.Gyro(1, 1, Float.NaN, 0f, 0f) }
        assertFailsWith<IllegalArgumentException> { TraceEvent.Score(1, 1, 0, 0, Float.POSITIVE_INFINITY, true, com.rokid.context.core.model.MotionState.STILL, false, false, null, false, 0) }
        val nanBits = Float.NaN.toRawBits()
        assertFailsWith<TraceFormatException> { EventCodec.decode("""["g",1,1,$nanBits,0,0]""") }
        assertFailsWith<TraceFormatException> { EventCodec.decode("""["g",1,1,NaN,0,0]""") }
    }

    @Test fun strictDecodeRefusesEverythingNonCanonical() {
        val bad = listOf(
            """["pr", 5]""", """["pr",+5]""", """["pr",05]""", """["pr",5.0]""", """["pr",5e0]""", """["pr","5"]""", """["pr",-1]""",
            """["pr",5,1]""", """["pr"]""", """["zz",5]""", """[]""", """{"a":1}""", """["pr",5]x""", """ ["pr",5]""", "[\"pr\",5]\n",
            """["cx",1,"MARS"]""", """["cx",1,"rest"]""", """["rs",1,"true"]""", """["rs",1,1]""", """["ep",1,true,"TIMER",null]""", """["ep",1,true,"bogus",null]""",
            """["g",1,1,[1],0,0]""", """["g",1,1,{"a":1},0,0]""", """["g",1,1,1.5,0,0]""", """["g",1,1,2147483648,0,0]""", """["g",1,0,0,0]""", """["g",1,-1,0,0,0]""", """["pr",9007199254740992]""",
            """["gt",1,1,"keep","bad reason",true,1]""", """["gt",1,1,"skip","BLUR",false,null]""", """["gt",1,1,"keep",""]""", """["gt",1,1,"keep","",true,null]""", """["gt",1,1,"keep","",false,5]""", """["gt",1,1,"skip","BLUR",true,5]""", """["gt",1,1,"keep","BLUR",true,1]""", """["gt",1,1,"skip","",true,null]""", """["gt",1,1,"skip","MARS",true,null]""",
            """["ep",1,true,"timer",null,null,"ok"]""", """["ep",1,false,"timer",null,null,null]""", """["ep",1,false,"timer",null,null,"OK"]""", """["dk",1,1001]""", """["gt",1,1,"maybe","",true,null]""", """["pl",1,1,"XYZ","SHADOW"]""", """["pl",1,1,"abc","SOMETIMES"]""",
            """["sc",1,1,"0123","0123456789abcdef",0,true,"STILL",false,false,null,false,0]""", """["gp",1,65,0,true]""", """["bo",1,33]""", """["cl",1,""]""",
            "[\"cl\",1,\"a" + '\\' + "u0041\"]",   // a JSON escape of a plain letter: valid JSON, not canonical
        )
        for (b in bad) assertFailsWith<TraceFormatException>(b) { EventCodec.decode(b) }
    }

    @Test fun overlongLineRefused() {
        val line = """["cl",1,"${"x".repeat(600)}"]"""
        assertFailsWith<TraceFormatException> { EventCodec.decode(line) }
    }

    @Test fun everyEncodedLineIsShortAndAscii() {
        for (e in TraceFixtures.events) {
            val s = EventCodec.encode(e)
            assertTrue(s.length in 5..TraceBounds.MAX_LINE_CHARS && s.all { it.code in 0x20..0x7e }, s)
        }
    }
}
