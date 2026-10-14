package com.rokid.context.connectors.actions

import com.rokid.context.ai.contract.ProposedAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class ActionParamsTest {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test fun noteRoundTripsThroughItsEncoding() {
        val p = ActionParams.from(ProposedAction.NoteAdd("  the car is on level 3 "), zone)
        assertEquals(ActionParams.Note("the car is on level 3"), p)
        assertEquals(ActionType.NOTE_ADD, p.type)
        assertNull(p.firesAtMs)
        assertEquals(p, ActionParams.decode(ActionType.NOTE_ADD, ActionParams.encode(p)))
    }

    @Test fun noteTextIsCappedAt500Chars() {
        val p = ActionParams.from(ProposedAction.NoteAdd("x".repeat(600)), zone) as ActionParams.Note
        assertEquals(ActionParams.MAX_NOTE, p.text.length)
    }

    @Test fun aCorruptedNoteRowDecodesToNull() {
        assertNull(ActionParams.decode(ActionType.NOTE_ADD, """{"items":[]}"""))
        assertNull(ActionParams.decode(ActionType.NOTE_ADD, "not json"))
    }

    @Test fun noteCopy() {
        assertEquals("Save note?", ActionCopy.confirmTitle(ActionType.NOTE_ADD))
        assertEquals("the car is on level 3", ActionCopy.summary(ActionParams.Note("the car is on level 3"), zone))
        assertEquals("Notes are off. Turn them on in the phone app: Settings → Actions.", ActionCopy.offNotice(ActionType.NOTE_ADD))
        assertEquals("Shopping list is off. Turn it on in the phone app: Settings → Actions.", ActionCopy.offNotice(ActionType.SHOPPING_ADD))
        assertEquals("Reminders are off. Turn them on in the phone app: Settings → Actions.", ActionCopy.offNotice(ActionType.REMINDER))
        assertEquals("Calendar is off. Turn it on in the phone app: Settings → Actions.", ActionCopy.offNotice(ActionType.CALENDAR_EVENT))
    }
}
