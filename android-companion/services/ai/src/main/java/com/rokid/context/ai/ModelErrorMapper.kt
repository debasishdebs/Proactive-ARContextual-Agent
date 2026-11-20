package com.rokid.context.ai

import com.rokid.context.core.model.UserFacingError
import com.rokid.context.core.model.UserFacingErrors
import java.io.IOException

/** Model failures → what people see (spec §6.3). By type and [FailureKind] only; Diagnostics logs keep the detail. */
object ModelErrorMapper {
    fun toUserFacing(t: Throwable): UserFacingError = when (t) {
        is OfflineOnlyRefusedException -> if (t.fallbackKind != null) UserFacingErrors.AiUnavailable else UserFacingErrors.AiNeedsOnDevice
        is ModelCallException -> when (t.kind) {
            FailureKind.NOT_SET -> UserFacingErrors.AiNotSet
            FailureKind.AUTH -> UserFacingErrors.AiKeyRejected
            FailureKind.REJECTED -> UserFacingErrors.AiRequestRejected
            FailureKind.INVALID_OUTPUT, FailureKind.TRANSIENT -> UserFacingErrors.AiUnavailable
        }
        is IOException -> UserFacingErrors.AiUnavailable
        else -> UserFacingErrors.Unknown
    }
}
