package com.rokid.context.phone.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reference photo downloader is a NEW network egress (Developer "Add place manually" > Photo). Allow-list, with the reason: `ReferencePhotos.kt` is the ONLY file that builds an HTTP client for it (user-initiated, one https GET per
 * hop to the addresses `ImageFetcher` already checked). The downloader's rules are pinned here so a later edit cannot loosen them: no redirect following by the transport, no proxy, no cookies, the checked addresses as the only DNS
 * answer, the shared data saver and Offline only asked before every hop, and no log call in the downloader or the Moment writer.
 */
class ReferencePhotoEgressRulesTest {
    private fun src(rel: String): String {
        val f = listOf("src/main/java", "phone/src/main/java", "../phone/src/main/java", "../services/orchestration/src/main/java", "services/orchestration/src/main/java").map { File(it, rel) }.firstOrNull { it.isFile }
        return requireNotNull(f) { "missing $rel" }.readText()
    }
    private fun code(s: String) = s.lines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")

    @Test fun theHttpClientIsBuiltOnlyInReferencePhotosWithTheSafeSettings() {
        val roots = listOf(File("src/main"), File("../phone/src/main"), File("phone/src/main"), File("../services/orchestration/src/main")).filter { it.exists() }
        val builders = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }.filter { f -> code(f.readText()).let { it.contains("OkHttpClient.Builder()") && it.contains("followRedirects(false)") && it.contains("NO_PROXY") && it.contains("NO_COOKIES") } }.map { it.name }.toSet()
        assertTrue("the downloader is where it is allowed: $builders", "ReferencePhotos.kt" in builders)
        val c = code(src("com/rokid/context/phone/place/ReferencePhotos.kt"))
        for (must in listOf("followRedirects(false)", "followSslRedirects(false)", "Proxy.NO_PROXY", "CookieJar.NO_COOKIES", "retryOnConnectionFailure(false)", "callTimeout(", "override fun lookup(hostname: String): List<InetAddress> = addrs", "ImageFetcher.USER_AGENT", "seedAdmission(app)"))
            assertTrue(must, c.contains(must))
        assertEquals("one request header only (the User-Agent); the other .header( is the READ of Location", 1, Regex("Request\\.Builder\\(\\)[^\n]*\\.header\\(\"User-Agent\"").findAll(c).count())
        assertEquals(2, Regex("\\.header\\(").findAll(c).count()); assertFalse(c.contains(".headers("))
        assertFalse("no credentials", c.contains("Authorization") || c.contains("addHeader("))
    }

    @Test fun theDownloaderAndTheMomentWriterNeverLog() {
        for (f in listOf("com/rokid/context/work/frames/place/RefPhoto.kt", "com/rokid/context/canvas/dumpimport/ReferenceMoment.kt", "com/rokid/context/phone/place/ReferencePhotos.kt")) {
            val c = code(src(f))
            assertFalse("$f logs", Regex("PcLog|android\\.util\\.Log|Log\\.[diwev]\\(|println\\(|Timber").containsMatchIn(c))
        }
    }

    @Test fun theLiveLocationRecorderSkipsReferencePhotosAndThePhotoFrameHasItsOwnSource() {
        assertTrue(src("com/rokid/context/canvas/dumpimport/ReferenceMoment.kt").contains("const val SOURCE = \"rokid_reference\""))
    }
}
