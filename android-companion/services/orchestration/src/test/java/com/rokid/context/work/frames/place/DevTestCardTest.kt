package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.place.CardDelivery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Developer "Send test card to glasses": one canned generic card over the real holder, nothing persistent, 10 s limit, content-free log tokens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DevTestCardTest {
    private var now = 100_000L
    private var dev = true
    private var linked = true
    private val holder = PlaceCardHolder()
    private fun sender() = DevTestCardSender(holder, { now }, { dev }, { linked })

    @Test fun rowIsVisibleOnlyInDeveloperModeAndDisabledWhenNotConnected() {
        assertFalse(DevTestCard.row(developerMode = false, connected = true).visible)
        assertFalse(DevTestCard.row(false, false).visible)
        val on = DevTestCard.row(true, true); assertTrue(on.visible && on.enabled)
        val off = DevTestCard.row(true, false); assertTrue(off.visible); assertFalse(off.enabled); assertEquals(DevTestCard.NOT_CONNECTED_NOTE, off.note)
    }

    @Test fun sendsExactlyOneCannedGenericCard() {
        assertEquals(DevTestCardSender.Outcome.SENT, sender().send())
        val c = holder.card.value!!
        assertEquals("Test card", c.title); assertEquals("Glasses wake test", c.line)
        assertEquals(DevTestCard.CARD, c.copy(cardId = "")) // the fixed card, only the holder's instance id added
        assertTrue(c.cardId.isNotEmpty())
        assertEquals(PlaceConfidence.NEARBY, c.confidence)
    }

    @Test fun refusedWhenDeveloperOffOrNotConnectedAndPublishesNothing() {
        val s = sender()
        dev = false; assertEquals(DevTestCardSender.Outcome.REFUSED_DEVELOPER_OFF, s.send()); assertNull(holder.card.value)
        dev = true; linked = false; assertEquals(DevTestCardSender.Outcome.REFUSED_NOT_CONNECTED, s.send()); assertNull(holder.card.value)
        linked = true; assertEquals(DevTestCardSender.Outcome.SENT, s.send()); assertNotNull(holder.card.value)
    }

    @Test fun rateLimitedToOncePerTenSecondsAndARefusalDoesNotRestartIt() {
        val s = sender()
        assertEquals(DevTestCardSender.Outcome.SENT, s.send()); val first = holder.card.value!!.cardId
        now += 9_999; assertEquals(DevTestCardSender.Outcome.REFUSED_RATE, s.send()); assertEquals(first, holder.card.value!!.cardId)
        now += 1; assertEquals(DevTestCardSender.Outcome.SENT, s.send()); assertTrue(holder.card.value!!.cardId != first)
        linked = false; now += 1_000; assertEquals(DevTestCardSender.Outcome.REFUSED_NOT_CONNECTED, s.send())
        linked = true; assertEquals(DevTestCardSender.Outcome.REFUSED_RATE, s.send()) // the not-connected refusal did not move the clock; the send at +10 s did
    }

    @Test fun writesNothingPersistent() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val cdb = com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx)
        try {
            val history = PlaceCardHistory(cdb, clock = { 1_000L }, newId = { "h1" })
            sender().send()
            val id = holder.card.value!!.cardId
            // what the Hub does with any delivered card: it reports SENT, and on expiry NOT_SENT_* if still pending
            history.markDelivery(id, CardDelivery.SENT); history.markIfPending(id, CardDelivery.NOT_SENT_BUSY)
            assertTrue("no history row", history.recent().isEmpty())
            assertEquals(0, cdb.placeCardLogDao().cardLogCount())
        } finally { cdb.close() }
    }

    @Test fun logIsTheTwoLiteralTokensOnly() {
        val src = File(File("").absoluteFile, "src/main/java/com/rokid/context/work/frames/place/DevTestCard.kt").readText()
        val calls = Regex("""PcLog\.\w\(([^)]*)\)""").findAll(src).map { it.groupValues[1] }.toList()
        assertEquals(listOf("TAG, \"dev test card sent\"", "TAG, \"dev test card refused\""), calls)
        assertFalse("no interpolation in a log line", calls.any { '$' in it })
    }

    @Test fun onlyTheDeveloperScreenWiringNamesTheSenderNeverTheDumpImportOrCanvasApis() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val users = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.extension == "kt" && "/src/main/" in it.path && ("DevTestCard" in it.readText()) }.map { it.name }.toSet()
        assertEquals(setOf("DevTestCard.kt", "DevTestCardGraph.kt", "Diagnostics.kt", "SettingsDestinations.kt"), users)
    }
}
