package com.rokid.context.retrieval

class RetrievalPlanner(
    private val router: LanguageRouter = LanguageRouter(),
) {
    fun resolve(languagePair: String, manualOverride: String?): String =
        router.choosePath(languagePair, manualOverride)
}
