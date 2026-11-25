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

    @Test fun p23AndDensityFlagsAreOffByDefaultAndNothingNewWritesAPauseZone() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        assertFalse(DeveloperFlags.mapZonesP23(ctx)); assertFalse(DeveloperFlags.mapMomentsDensity(ctx))
        DeveloperFlags.setMapZonesP23(ctx, true); assertTrue(DeveloperFlags.mapZonesP23(ctx)); DeveloperFlags.setMapZonesP23(ctx, false); assertFalse(DeveloperFlags.mapZonesP23(ctx))
        DeveloperFlags.setMapMomentsDensity(ctx, true); assertTrue(DeveloperFlags.mapMomentsDensity(ctx)); DeveloperFlags.setMapMomentsDensity(ctx, false); assertFalse(DeveloperFlags.mapMomentsDensity(ctx))
        // The new files never call the zone store: the only upsert stays in MapZonesModel.kt (checked above), and none of them mention the store or the controller.
        for (n in listOf("MapEdit.kt", "MapDensity.kt", "MapCells.kt")) {
            val src = File("src/main/java/com/rokid/context/phone/ui/settings/mapzones/$n").readText()
            for (w in listOf("upsert(", "ZoneStore", "ZoneController", "ZoneEvaluator", "setEnabled(", "addPreset(")) assertFalse("$n must not use $w", w in src)
        }
        // The density layer reads only a position and a time: it never touches a Moment id, text or photo.
        val d = File("src/main/java/com/rokid/context/phone/ui/settings/mapzones/MapCells.kt").readText() + File("src/main/java/com/rokid/context/phone/ui/settings/mapzones/MapDensity.kt").readText()
        for (w in listOf("momentId", "anchorId", "frameDao", "MemoryGraph")) assertFalse("density must not use $w", w in d)
    }
}
