package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The map makes zero network calls. The offline map code (this package) and the two map files of the zones screen may not name a network class, a URL scheme or a
 * downloader, and the only content they open comes from the content resolver or the app's own files folder. INTERNET stays declared for other features.
 */
class MapNoNetworkTest {
    private val base = "src/main/java/com/rokid/context/phone/ui/settings/privacy/zones"
    private val files: List<File> = File("$base/tiles").listFiles { f -> f.extension == "kt" }.orEmpty().toList() + File("$base/MapViewport.kt") + File("$base/ZonesScreen.kt")

    private val forbidden = listOf(
        "java.net", "javax.net", "okhttp", "HttpURLConnection", "HttpsURLConnection", "URL(", "URI.create", "Socket", "WebView", "DownloadManager",
        "android.net.http", "retrofit", "ktor", "http://", "https://", "ftp://", "ConnectivityManager", "InetAddress", "NetworkRequest", "WorkManager", "Coil", "ImageRequest",
    )

    @Test fun theMapSourcesNameNoNetworkCapability() {
        assertTrue("scan is broken: ${files.map { it.name }}", files.size >= 6 && files.all { it.isFile })
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> forbidden.firstOrNull { it in line }?.let { "${f.name}:${i + 1} $it" } } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun onlyContentUrisAreEverOpened() {
        val src = File("$base/tiles/AndroidMapFiles.kt").readText()
        assertTrue("open() guards the scheme", Regex("""fun open\(uri: String\): ByteSource \{\s*if \(!MapUri\.accepted\(uri\)\)""").containsMatchIn(src))
        assertTrue("grant() guards the scheme", "require(MapUri.accepted(uri))" in src)
        assertTrue("no read-write or write mode on a picked document", "\"w\"" !in src && "\"rw\"" !in src)
        assertTrue("no storage permission", !File("src/main/AndroidManifest.xml").readText().contains("READ_EXTERNAL_STORAGE") && !File("src/main/AndroidManifest.xml").readText().contains("MANAGE_EXTERNAL_STORAGE"))
    }

    @Test fun logLinesAreLiteralWithNoNameOrPath() {
        val logs = files.flatMap { f -> f.readLines().filter { "PcLog." in it } }
        assertTrue(logs.isNotEmpty())
        for (l in logs) assertTrue("literal only: $l", Regex("""PcLog\.i\(TAG, "[a-z ]+"\)""").containsMatchIn(l))
    }
}
