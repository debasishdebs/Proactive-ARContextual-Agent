package com.rokid.context.phone.ui.settings.mapzones

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.orchestration.forget.DeveloperFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The new Map zones screen adds no network path and writes no log line (so no coordinate or name can reach a log), and the Developer flag is OFF by default. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MapZonesGuardTest {
    private val files: List<File> = File("src/main/java/com/rokid/context/phone/ui/settings/mapzones").listFiles { f -> f.extension == "kt" }.orEmpty().toList()

    @Test fun noNetworkClassOrUrlInTheMapZonesSources() {
        val forbidden = listOf("java.net", "javax.net", "okhttp", "HttpURLConnection", "HttpsURLConnection", "URL(", "URI.create", "Socket", "WebView", "DownloadManager", "retrofit", "ktor", "http://", "https://", "ConnectivityManager", "InetAddress", "WorkManager", "Coil", "ImageRequest")
        assertTrue("scan is broken: ${files.map { it.name }}", files.size >= 6)
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> forbidden.firstOrNull { it in line }?.let { "${f.name}:${i + 1} $it" } } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun noLogCallAtAll() {
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (Regex("""\b(PcLog|Log)\.[a-z]+\(|println\(|Timber""").containsMatchIn(line)) "${f.name}:${i + 1}" else null } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun theOnlyPauseStoreCallIsTheSingleUpsert() {
        val src = File("src/main/java/com/rokid/context/phone/ui/settings/mapzones/MapZonesModel.kt").readText()
        assertEquals(1, Regex("""editor\.upsert\(""").findAll(src).count())
        for (other in listOf("setEnabled(", "remove(", "addPreset(", "ZoneStore", "ZoneController", "ZoneEvaluator")) assertFalse("MapZonesModel must not use $other", "editor.$other" in src || other in src.replace("fun ", ""))
    }

    @Test fun flagIsOffByDefault() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("mapZonesV2", DeveloperFlags.MAP_ZONES_V2)
        assertFalse(DeveloperFlags.mapZonesV2(ctx))
        DeveloperFlags.setMapZonesV2(ctx, true); assertTrue(DeveloperFlags.mapZonesV2(ctx))
        DeveloperFlags.setMapZonesV2(ctx, false); assertFalse(DeveloperFlags.mapZonesV2(ctx))
    }
}
