package com.rokid.context.memory.forget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Forget scope setting: Photo (default) | Moment | Scene, validated, one accessor. */
class ForgetScopeTest {
    @After fun reset() { ForgetScopeSetting.provider = { ForgetScope.PHOTO } }

    @Test fun theDefaultIsPhoto() = assertEquals(ForgetScope.PHOTO, ForgetScopeSetting.get())

    @Test fun aStoredNameParsesAndAnythingElseIsPhoto() {
        assertEquals(ForgetScope.MOMENT, ForgetScope.parse("MOMENT")); assertEquals(ForgetScope.SCENE, ForgetScope.parse("SCENE")); assertEquals(ForgetScope.PHOTO, ForgetScope.parse("PHOTO"))
        listOf(null, "", "scene", "Scene ", "ALL", "7").forEach { assertEquals("$it", ForgetScope.PHOTO, ForgetScope.parse(it)) }
    }

    @Test fun theEntryNeverForgetsLessThanItsOwnLevel() {
        assertEquals(ForgetScope.MOMENT, ForgetScope.PHOTO.atLeast(ForgetScope.MOMENT)); assertEquals(ForgetScope.SCENE, ForgetScope.MOMENT.atLeast(ForgetScope.SCENE))
        assertEquals(ForgetScope.SCENE, ForgetScope.SCENE.atLeast(ForgetScope.MOMENT)); assertEquals(ForgetScope.PHOTO, ForgetScope.PHOTO.atLeast(ForgetScope.PHOTO))
    }

    @Test fun theAccessorReadsTheProviderAndAFailingProviderIsPhoto() {
        ForgetScopeSetting.provider = { ForgetScope.SCENE }; assertEquals(ForgetScope.SCENE, ForgetScopeSetting.get())
        ForgetScopeSetting.provider = { error("boom") }; assertEquals(ForgetScope.PHOTO, ForgetScopeSetting.get())
    }
}
