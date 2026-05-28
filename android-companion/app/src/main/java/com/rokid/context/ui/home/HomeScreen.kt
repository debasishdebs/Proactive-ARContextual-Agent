package com.rokid.context.ui.home

data class HomeCard(
    val id: String,
    val title: String,
    val enabled: Boolean,
)

object HomeScreen {
    fun buildDefaultCards(showMigrationCard: Boolean): List<HomeCard> = listOf(
        HomeCard(id = "status", title = "Status", enabled = true),
        HomeCard(id = "queues", title = "Queues", enabled = true),
        HomeCard(id = "memory", title = "Memory", enabled = true),
        HomeCard(id = "config", title = "Config", enabled = true),
        HomeCard(id = "migration", title = "Migration", enabled = showMigrationCard),
    )
}
