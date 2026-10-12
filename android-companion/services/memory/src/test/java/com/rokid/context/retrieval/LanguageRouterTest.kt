package com.rokid.context.retrieval

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRouterTest {
    private val router = LanguageRouter()

    @Test
    fun autoRouteChoosesMultilingualFirst() {
        assertEquals("multilingual-primary", router.choosePath("hi->en", manualOverride = null))
        assertEquals("multilingual-primary", router.choosePath("or->or", manualOverride = null))
    }

    @Test
    fun manualOverridePerLanguagePairIsHonored() {
        assertEquals("indic-fallback-or-or", router.choosePath("or->or", manualOverride = "indic-fallback-or-or"))
    }

    @Test
    fun responseLanguageFollowsDetectedInputWhenConfigDiffers() {
        assertEquals("hi", router.responseLanguage(detectedInput = "hi", configuredOutput = "en"))
        assertEquals("en", router.responseLanguage(detectedInput = "en", configuredOutput = "en"))
    }
}
