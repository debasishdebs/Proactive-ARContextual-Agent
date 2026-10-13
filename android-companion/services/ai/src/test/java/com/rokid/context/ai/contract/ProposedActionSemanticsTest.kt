package com.rokid.context.ai.contract

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Phase 5 T5 carry: action bounds are checked after decoding too, so a loosened or ignored schema can't pass them. */
class ProposedActionSemanticsTest {
    private val now = LocalDateTime.of(2026, 10, 12, 10, 0)
    private val ctx = SemanticContext(emptySet(), now)
    private fun problems(a: ProposedAction) = RoleContracts.semanticProblems(AnswerOutput("ok", emptyList(), a), ctx)

    @Test fun shoppingQuantitiesNamesAndCountsAreChecked() {
        assertEquals(emptyList<String>(), problems(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", 2.0, "l"), ShoppingLine("eggs", null, null)))))
        assertEquals(listOf("proposedAction.items[0].qty must be more than 0"), problems(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", 0.0, null)))))
        assertEquals(listOf("proposedAction.items[0].qty must be more than 0"), problems(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", Double.NaN, null)))))
        assertEquals(listOf("proposedAction.items[1].name is empty"), problems(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", 1.0, null), ShoppingLine(" ", 1.0, null)))))
        assertEquals(listOf("proposedAction.items must hold 1\u201310 items"), problems(ProposedAction.ShoppingAdd(emptyList())))
        assertEquals(listOf("proposedAction.items must hold 1\u201310 items"), problems(ProposedAction.ShoppingAdd(List(11) { ShoppingLine("x$it", null, null) })))
    }

    @Test fun aReminderNeedsATitle() =
        assertEquals(listOf("proposedAction.title is empty"), problems(ProposedAction.Reminder(" ", now.plusHours(1), null)))
}
