package com.rokid.context.orchestration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTriggersTest {
    @Test fun actionCommandsMatchInAnyCase() {
        listOf(
            "Note that the car is on level 3", "ok, NOTE THAT wifi is 1234", "make a note: call the plumber", "Make a note to call mum",
            "Remember that the spare key is in the drawer", "add milk to my shopping list", "Add eggs and bread to the shopping list",
            "add oat milk to shopping list", "remind me to call mom at 6", "Remind me tomorrow about the dentist",
        ).forEach { assertTrue(it, ActionTriggers.matches(it)) }
    }

    @Test fun questionsDoNotMatch() {
        listOf(
            "where are my keys", "what's on my shopping list", "do you remember where I parked", "what did I note yesterday",
            "notes from the meeting?", "add up 2 and 3", "remind", "", "keynote that went well",
        ).forEach { assertFalse(it, ActionTriggers.matches(it)) }
    }
}
