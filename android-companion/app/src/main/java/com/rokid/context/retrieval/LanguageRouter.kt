package com.rokid.context.retrieval

class LanguageRouter {
    fun choosePath(languagePair: String, manualOverride: String?): String {
        return manualOverride ?: autoPath(languagePair)
    }

    private fun autoPath(languagePair: String): String = when {
        languagePair.startsWith("or") || languagePair.startsWith("hi") -> "multilingual-primary"
        else -> "multilingual-primary"
    }

    fun responseLanguage(detectedInput: String, configuredOutput: String): String {
        return if (detectedInput.equals(configuredOutput, ignoreCase = true)) configuredOutput else detectedInput
    }
}
