package com.rokid.context.ai

import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import java.io.IOException
import java.util.Locale

/** Model failures → what people see (spec §6.3). Never echoes provider text; Diagnostics logs keep the detail. */
object ModelErrorMapper {
    /** Config problems raised by ModelRouter itself (bindings, deleted endpoints, missing address/model/key). */
    private val NOT_SET = listOf("no model is set", "no model set", "no address set", "no model file set", "no api key saved", "was deleted")
    private val AUTH = Regex("request failed: (401|403)\\b|api_key is missing|api key is missing")

    fun toUserFacing(t: Throwable): UserFacingError {
        val m = t.message.orEmpty().lowercase(Locale.US)
        return when {
            t is ModelCallException && NOT_SET.any { it in m } -> UserFacingErrors.AiNotSet
            ProviderErrors.isPermanent(t) && AUTH.containsMatchIn(m) -> UserFacingErrors.AiKeyRejected
            ProviderErrors.isPermanent(t) -> UserFacingErrors.AiRequestRejected
            t is ModelCallException || t is IOException -> UserFacingErrors.AiUnavailable
            else -> UserFacingErrors.Unknown
        }
    }
}
