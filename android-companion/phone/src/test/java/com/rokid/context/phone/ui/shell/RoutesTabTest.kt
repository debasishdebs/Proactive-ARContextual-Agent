package com.rokid.context.phone.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTabTest {
    private val answer = "ask:a1"

    @Test fun anAnswerOpenedFromHomeKeepsHomeLit() {
        assertEquals(Routes.Tab.HOME, Routes.tabFor("preview/{ref}", answer))
        assertEquals(Routes.Tab.HOME, Routes.tabFor("preview/{ref}", answer, openedFrom = Routes.Tab.HOME))
    }

    @Test fun anAnswerOpenedFromActivityKeepsActivityLit() {
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor("preview/{ref}", answer, openedFrom = Routes.Tab.ACTIVITY))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor("preview/{ref}", "nudge:n1", openedFrom = Routes.Tab.ACTIVITY))
    }

    @Test fun otherItemsAreActivityAndSettingsStaysSettings() {
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor("preview/{ref}", "moment:m1", openedFrom = Routes.Tab.HOME))
        assertEquals(Routes.Tab.ACTIVITY, Routes.tabFor("list/{kind}?ids={ids}&scope={scope}"))
        assertEquals(Routes.Tab.SETTINGS, Routes.tabFor(Routes.SETTINGS_PRIVACY, openedFrom = Routes.Tab.ACTIVITY))
    }
}
