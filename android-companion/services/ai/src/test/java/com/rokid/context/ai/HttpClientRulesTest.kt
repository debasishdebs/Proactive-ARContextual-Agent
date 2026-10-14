package com.rokid.context.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Model downloads carry no key and follow the CDN redirect, but never off https and never under a whole-call cap. */
    @Test fun downloadClientFollowsHttpsRedirectsOnly() {
        val c = Http.downloadClient()
        assertTrue(c.followRedirects)
        assertFalse("no https to http hop", c.followSslRedirects)
        assertEquals(0, c.callTimeoutMillis)
        assertTrue(c.connectTimeoutMillis in 1..30_000 && c.readTimeoutMillis in 1..120_000)
    }
}
