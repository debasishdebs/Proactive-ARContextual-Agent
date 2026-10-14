package com.rokid.context.phone.ui.settings.connectors

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.db.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NotesListTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.of("UTC")
    private val older = NoteEntity("p1#0", "car on level 3", ZonedDateTime.of(2026, 10, 12, 9, 5, 0, 0, zone).toInstant().toEpochMilli())
    private val newer = NoteEntity("p2#0", "wifi code 1234", ZonedDateTime.of(2026, 10, 14, 18, 30, 0, 0, zone).toInstant().toEpochMilli())

    private fun show(
        notes: List<NoteEntity>, deleted: MutableList<String> = mutableListOf(), restored: MutableList<NoteEntity> = mutableListOf(),
        copied: MutableList<String> = mutableListOf(),
    ) = compose.setContent {
        PcTheme { NotesScreen(notes, onDelete = { deleted += it }, onRestore = { restored += it }, onCopy = { copied += it }, back = {}, zone = zone) }
    }

    @Test fun emptyStateShown() {
        show(emptyList())
        compose.onNodeWithText("No notes yet. Say \"note that…\" to your glasses.").assertIsDisplayed()
    }

    @Test fun listsNotesNewestFirstWithTheirDates() {
        show(listOf(older, newer))
        compose.onNodeWithText("wifi code 1234").assertIsDisplayed()
        compose.onNodeWithText("Wed 14 Oct, 18:30").assertIsDisplayed()
        compose.onNodeWithText("Mon 12 Oct, 09:05").assertIsDisplayed()
        val top = compose.onNodeWithTag("note_row_p2#0").fetchSemanticsNode().positionInRoot.y
        val below = compose.onNodeWithTag("note_row_p1#0").fetchSemanticsNode().positionInRoot.y
        assertTrue(top < below)
    }

    @Test fun deleteRemovesTheNoteAndOffersUndo() {
        val deleted = mutableListOf<String>()
        val restored = mutableListOf<NoteEntity>()
        show(listOf(older), deleted, restored)
        compose.onNodeWithTag("note_delete_p1#0").performClick()
        compose.waitForIdle()
        assertEquals(listOf("p1#0"), deleted)
        compose.onNodeWithText("Note deleted").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(listOf(older), restored)
    }

    @Test fun copyPutsTheTextOnTheClipboard() {
        val copied = mutableListOf<String>()
        show(listOf(older), copied = copied)
        compose.onNodeWithTag("note_copy_p1#0").performClick()
        compose.waitForIdle()
        assertEquals(listOf("car on level 3"), copied)
        compose.onNodeWithText("Copied").assertIsDisplayed()
    }
}
