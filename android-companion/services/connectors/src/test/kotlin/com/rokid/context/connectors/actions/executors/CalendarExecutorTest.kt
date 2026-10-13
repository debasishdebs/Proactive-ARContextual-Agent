package com.rokid.context.connectors.actions.executors

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.ActionParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.time.ZoneId

/** In-memory CalendarContract: calendars (id, visible, access, primary) and events (id, calendar, uid). */
class FakeCalendarProvider : ContentProvider() {
    var calendars = mutableListOf(arrayOf<Any?>(7L, 1, CalendarContract.Calendars.CAL_ACCESS_OWNER, 1))
    val events = mutableMapOf<Long, ContentValues>()
    var inserts = 0
    var deny = false
    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        if (deny) throw SecurityException("denied")
        return if (uri.toString().startsWith(CalendarContract.Calendars.CONTENT_URI.toString())) {
            MatrixCursor(arrayOf(CalendarContract.Calendars._ID)).apply {
                calendars.filter { it[1] == 1 && (it[2] as Int) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR }
                    .sortedByDescending { it[3] as Int }.forEach { addRow(arrayOf(it[0])) }
            }
        } else {
            MatrixCursor(arrayOf(CalendarContract.Events._ID)).apply {
                events.filter { it.value.getAsString(CalendarContract.Events.UID_2445) == args?.firstOrNull() }.keys.forEach { addRow(arrayOf(it)) }
            }
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        if (deny) throw SecurityException("denied")
        inserts++
        val id = 100L + inserts
        events[id] = ContentValues(values)
        return ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = if (events.remove(ContentUris.parseId(uri)) != null) 1 else 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CalendarExecutorTest {
    private val provider = Robolectric.buildContentProvider(FakeCalendarProvider::class.java).create(CalendarContract.AUTHORITY).get()
    private val exec = CalendarExecutor(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver) { ZoneId.of("UTC") }
    private val p = proposal("p1", ActionParams.Calendar("Dentist", 1_000_000, 2_800_000, null))

    @Test fun existingUidIsReused() = runBlocking {
        val first = exec.execute(p)
        assertEquals(first, exec.execute(p))
        assertEquals(1, provider.inserts)
        assertEquals("event:101", (first as ExecResult.Done).createdRef)
        val v = provider.events.getValue(101)
        assertEquals("pc-p1", v.getAsString(CalendarContract.Events.UID_2445))
        assertEquals(7L, v.getAsLong(CalendarContract.Events.CALENDAR_ID))
        assertEquals(2_800_000L, v.getAsLong(CalendarContract.Events.DTEND))
    }

    @Test fun securityExceptionIsNoPermission() = runBlocking {
        provider.deny = true
        assertEquals(ExecResult.Failed(FailCode.NO_PERMISSION), exec.execute(p))
    }

    @Test fun noWritableCalendarIsNoCalendar() = runBlocking {
        provider.calendars = mutableListOf(arrayOf<Any?>(3L, 1, CalendarContract.Calendars.CAL_ACCESS_READ, 1))
        assertEquals(ExecResult.Failed(FailCode.NO_CALENDAR), exec.execute(p))
        assertEquals(0, provider.inserts)
    }

    @Test fun undoDeletesTheEvent() = runBlocking {
        val ref = (exec.execute(p) as ExecResult.Done).createdRef
        assertTrue(exec.undo(p.copy(createdRef = ref)))
        assertTrue(provider.events.isEmpty())
    }
}
