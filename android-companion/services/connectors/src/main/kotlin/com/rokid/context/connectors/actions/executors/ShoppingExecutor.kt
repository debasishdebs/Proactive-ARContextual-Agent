package com.rokid.context.connectors.actions.executors

import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ShoppingDao
import com.rokid.context.memory.db.ShoppingItemEntity

/** Rows `"<proposalId>#<i>"` inserted with IGNORE: a retry after a crash adds nothing new. Undo deletes this proposal's rows. */
class ShoppingExecutor(private val dao: ShoppingDao, private val clock: () -> Long = System::currentTimeMillis) : ActionExecutor {
    override suspend fun execute(p: ProposalEntity): ExecResult {
        val s = ActionParams.decode(ActionType.SHOPPING_ADD, p.paramsJson) as? ActionParams.Shopping ?: return ExecResult.Failed(FailCode.UNKNOWN)
        val now = clock()
        dao.insertIgnore(s.items.mapIndexed { i, it -> ShoppingItemEntity("${p.id}#$i", it.name.trim(), it.qty, it.unit, false, now, p.id) })
        return ExecResult.Done("shopping:${s.items.size}")
    }

    override suspend fun undo(p: ProposalEntity): Boolean {
        dao.deleteForProposal(p.id)
        return true
    }
}
