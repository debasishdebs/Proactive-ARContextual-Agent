package com.rokid.context.ui.migration

data class MigrationStatus(
    val active: Boolean,
    val progressPercent: Int,
)

object MigrationStatusCard {
    fun isVisible(status: MigrationStatus): Boolean = status.active
}
