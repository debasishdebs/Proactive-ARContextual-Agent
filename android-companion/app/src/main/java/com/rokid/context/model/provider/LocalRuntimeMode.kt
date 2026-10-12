package com.rokid.context.model.provider

import java.util.Locale

enum class LocalRuntimeMode {
    NATIVE_FIRST,
    NATIVE_ONLY,
    OLLAMA_ONLY,
    ;

    companion object {
        fun from(raw: String?): LocalRuntimeMode {
            return when (raw.orEmpty().trim().lowercase(Locale.ROOT)) {
                "native_only" -> NATIVE_ONLY
                "ollama_only" -> OLLAMA_ONLY
                else -> NATIVE_FIRST
            }
        }
    }
}
