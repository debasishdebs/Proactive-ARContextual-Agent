package com.rokid.context.connectors.actions.executors

import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.memory.db.NoteDao
import com.rokid.context.memory.db.NoteEntity
import com.rokid.context.memory.db.ProposalEntity

/** One row `"<proposalId>#0"` inserted with IGNORE: a retry after a crash adds nothing new. Undo deletes that row. */
class NoteExecutor(private val dao: NoteDao, private val clock: () -> Long = System::currentTimeMillis) : ActionExecutor {
    override suspend fun execute(p: ProposalEntity): ExecResult {
        val n = ActionParams.decode(ActionType.NOTE_ADD, p.paramsJson) as? ActionParams.Note ?: return ExecResult.Failed(FailCode.UNKNOWN)
        dao.insertIgnore(NoteEntity(rowId(p.id), n.text, clock()))
        return ExecResult.Done("note:1")
    }

    override suspend fun undo(p: ProposalEntity): Boolean {
        dao.delete(rowId(p.id))
        return true
    }

    companion object {
        fun rowId(proposalId: String) = "$proposalId#0"
    }
}
