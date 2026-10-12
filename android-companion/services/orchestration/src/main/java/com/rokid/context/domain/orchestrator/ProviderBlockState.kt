package com.rokid.context.domain.orchestrator

sealed class ProviderBlockState {
    data object Healthy : ProviderBlockState()

    data object Blocked : ProviderBlockState()

    companion object {
        const val REASON_PROVIDER_BLOCKED = "ProviderBlocked"
    }
}
