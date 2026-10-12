package com.rokid.context.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Global Constraint: every keyed client is redirect-free, so every client comes from Http.modelClient. */
class HttpClientRulesTest {
    @Test fun okHttpClientsAreOnlyBuiltInHttpKt() {
        val hits = File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name != "Http.kt" }
            .filter { it.readText().contains("OkHttpClient.Builder()") }.map { it.name }.toList()
        assertTrue("clients built outside Http.kt: $hits", hits.isEmpty())
    }
}
