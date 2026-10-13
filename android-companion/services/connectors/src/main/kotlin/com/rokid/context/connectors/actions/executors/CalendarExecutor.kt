package com.rokid.context.connectors.actions.executors

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.memory.db.ProposalEntity
import java.time.ZoneId

/**
 * Inserts one event tagged `UID_2445 = "pc-<proposalId>"`; an existing tagged event is reused, so a re-run never inserts
 * twice. Calendar = the primary visible calendar with access ≥ CONTRIBUTOR. A revoked permission is NO_PERMISSION.
 */
class CalendarExecutor(private val resolver: ContentResolver, private val zone: () -> ZoneId = ZoneId::systemDefault) : ActionExecutor {
    override suspend fun execute(p: ProposalEntity): ExecResult = try {
        val c = ActionParams.decode(ActionType.CALENDAR_EVENT, p.paramsJson) as? ActionParams.Calendar
        when {
            c == null -> ExecResult.Failed(FailCode.UNKNOWN)
            else -> existing(p.id)?.let { ExecResult.Done("event:$it") } ?: insert(p.id, c)
        }
    } catch (_: SecurityException) {
        ExecResult.Failed(FailCode.NO_PERMISSION)
    }

    override suspend fun undo(p: ProposalEntity): Boolean = try {
        val id = p.createdRef?.removePrefix("event:")?.toLongOrNull() ?: existing(p.id)
        id == null || resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) >= 0
    } catch (_: SecurityException) {
        false
    }

    private fun insert(proposalId: String, c: ActionParams.Calendar): ExecResult {
        val calendar = writableCalendar() ?: return ExecResult.Failed(FailCode.NO_CALENDAR)
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendar)
            put(CalendarContract.Events.TITLE, c.title)
            put(CalendarContract.Events.DTSTART, c.startAtMs)
            put(CalendarContract.Events.DTEND, c.endAtMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone().id)
            c.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            put(CalendarContract.Events.UID_2445, uid(proposalId))
        }
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return ExecResult.Failed(FailCode.UNKNOWN)
        return ExecResult.Done("event:${ContentUris.parseId(uri)}")
    }

    private fun existing(proposalId: String): Long? = resolver.query(
        CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID), "${CalendarContract.Events.UID_2445} = ?", arrayOf(uid(proposalId)), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun writableCalendar(): Long? = resolver.query(
        CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
        null, "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun uid(proposalId: String) = "pc-$proposalId"
}
