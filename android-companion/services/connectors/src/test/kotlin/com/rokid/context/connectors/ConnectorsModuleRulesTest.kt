package com.rokid.context.connectors

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R-01: `:services:connectors` sits below orchestration and the apps; it may never depend on them. */
class ConnectorsModuleRulesTest {
    @Test fun connectorsDependsOnNoAppModule() {
        val gradle = listOf(File("build.gradle.kts"), File("services/connectors/build.gradle.kts")).first { it.isFile }
        val deps = Regex("project\\(\"([^\"]+)\"\\)").findAll(gradle.readText()).map { it.groupValues[1] }.toSet()
        assertTrue(":services:memory" in deps, "parsed $deps")
        val banned = setOf(":phone", ":glasses", ":services:orchestration", ":services:voice")
        assertEquals(emptySet(), deps intersect banned)
    }
}
