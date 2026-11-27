package com.rokid.context.phone.place

import com.rokid.context.phone.ui.settings.place.PlaceGuideModel
import com.rokid.context.phone.ui.settings.place.PlaceHistoryModel
import com.rokid.context.phone.ui.settings.privacy.zones.AreaPhotos
import com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaInfo
import com.rokid.context.phone.ui.settings.privacy.zones.SeedAreaList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B2 reference photos: the words (counts only, credit line) and the source rules of the NEW network egress. Allow-list with the reason: the ONLY new egress is `UrlConnectionPlaceHttp.getBytes` (one thumbnail host,
 * https, no redirect, size cap, the shared pause and gates) plus the Commons file-info and Wikipedia/Wikidata calls through the same client; the stored images and their vectors are read only by the matcher's reference path and
 * the credit line, never by a model call, and nothing in these files logs a name, a title, a file name or a URL.
 */
class PlacePhotoRulesTest {
    private val roots = listOf("src/main", "../phone/src/main", "../services/connectors/src/main", "../services/orchestration/src/main", "../services/memory/src/main").map(::File).filter { it.exists() }
    private fun all() = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }.distinctBy { it.canonicalPath }
    private fun code(f: File) = f.readLines().filter { l -> l.trimStart().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/*") } }.joinToString("\n")
    private fun named(n: String) = requireNotNull(all().firstOrNull { it.name == n }) { "missing $n" }

    @Test fun thePhotoHostAllowListIsExactlyTheUploadHostAndEveryOtherHostIsFixedInTheSource() {
        val c = code(named("PlaceHttp.kt"))
        assertTrue(c.contains("val HOSTS: Set<String> = setOf(UPLOAD_HOST)")); assertTrue(c.contains("const val UPLOAD_HOST = \"upload.wikimedia.org\""))
        assertTrue("https only", c.contains("u.protocol == \"https\" && u.host in PlaceImageRules.HOSTS && u.userInfo == null")); assertTrue(c.contains("c.instanceFollowRedirects = false"))
        val lits = Regex("\"https://[^\"/]*").findAll(code(named("PlaceImages.kt"))).map { it.value }.toSet()
        // thumb.wikimedia.org is only the SOURCE of a rewrite to the upload host (Commons answers thumbnail links there); it is never downloaded from.
        assertEquals(setOf("\"https://\$wikidata", "\"https://\$wiki", "\"https://\$commons", "\"https://\${PlaceImageRules.COMMONS_HOST}", "\"https://thumb.wikimedia.org", "\"https://\${PlaceImageRules.UPLOAD_HOST}"), lits)
    }

    @Test fun theEmbedWorkerRetriesTheWaitsInsteadOfFinishingWithWorkLeft() {
        val c = code(named("PlacePhotoWorker.kt"))
        assertTrue(c.contains("EmbedEnd.NO_SPACE -> if (runAttemptCount < MAX_WAITS) Result.retry()"))
        assertTrue("a finished embed run asks the fetch phase again (budget may be free)", c.contains("if (end == EmbedEnd.DONE) runCatching { PlacePhotoWork(applicationContext).ensureIfNeeded(pack) }"))
    }

    @Test fun theEmbedWorkUsesANewUniqueNameSoAStaleChargingOnlyWorkOfAnOlderBuildIsNotKept() {
        val w = code(named("PlacePhotoWorker.kt"))
        assertTrue("new name", w.contains("const val UNIQUE = \"place_photo_embed_v2\""))
        assertTrue("the old name is cancelled once", w.contains("const val LEGACY_UNIQUE = \"place_photo_embed\"") && w.contains("cancelUniqueWork(PlaceEmbedWorker.LEGACY_UNIQUE)"))
        assertTrue("one line when it starts", w.contains("\"photo embed start "))
    }

    @Test fun storedPhotosAndVectorsAreNeverReadByAModelCall() {
        val users = all().filter { f -> code(f).let { it.contains("imageVectorsFor(") || it.contains("imagesOf(") } }.map { it.name }.toSet()
        // owner spec 28 Nov: PlaceL2Refs.kt is the ONE place a seeded thumbnail is read into a Layer 2 model request. The matcher reference path, the pack, the embed glue, the credit line and the fetcher: none of them builds a model request
        assertEquals(setOf("PlacePack.kt", "PlaceSamplers.kt", "PlaceImages.kt", "AndroidPlaceGuideControl.kt", "PlaceRefPhotos.kt", "PlacesDb.kt", "PlaceL2Refs.kt").sorted(), users.sorted().filter { it != "PlaceImagesTest.kt" })
        for (f in all().filter { it.name in setOf("PlaceMatchCall.kt", "PlaceResolvers.kt", "PlaceVision.kt") }) assertFalse(f.name, code(f).let { it.contains("imagesOf(") || it.contains("imageVectorsFor(") })
    }

    @Test fun thePhotoFilesLogOnlyFixedTokensAndCounts() {
        for (n in listOf("PlaceImages.kt", "PlaceRefPhotos.kt")) assertFalse("$n must not log", Regex("PcLog|android\\.util\\.Log|println\\(").containsMatchIn(code(named(n))))
        val w = code(named("PlacePhotoWorker.kt"))
        for (l in w.lines().filter { it.contains("PcLog.i(") }) assertFalse(l, Regex("\\.(name|title|author|licence|filePage|jpeg|id)\\b|\\$\\{?(id|name|url|file)").containsMatchIn(l))
    }

    @Test fun everyPhotoRequestIsAskedThroughTheFreeWifiGateAndTheUniqueWorkRequiresUnmetered() {
        val w = code(named("PlacePhotoWorker.kt"))
        assertTrue(w.contains("SeedAdmission(admit)")); assertTrue(w.contains("NetworkType.UNMETERED"))
        assertFalse("matching has no charging rule", w.contains("setRequiresCharging")); assertFalse("the Embed now button is gone", w.contains("embedNow"))
    }

    private fun area(photos: AreaPhotos?, places: Int? = 980) = SeedAreaInfo("a", 1.0, 2.0, 3.0, 4.0, "done", places = places, photos = photos)

    @Test fun theListLineIsCountsOnlyAndNamesTheWaitOnlyWhileIncomplete() {
        assertEquals("Photos 412 of 980 (waiting for free Wi-Fi)", SeedAreaList.photosText(area(AreaPhotos(375, 37, 980, "waiting for free Wi-Fi"))))
        assertEquals("Photos: 980 of 980, 100 without a photo", SeedAreaList.photosText(area(AreaPhotos(880, 100, 980, "waiting for free Wi-Fi"))))
        assertNull(SeedAreaList.photosText(area(null))); assertNull(SeedAreaList.photosText(area(AreaPhotos(0, 0, 0))))
        assertTrue(SeedAreaList.photosIncomplete(listOf(area(AreaPhotos(1, 0, 5))))); assertFalse(SeedAreaList.photosIncomplete(listOf(area(AreaPhotos(5, 0, 5)))))
        assertTrue(SeedAreaList.row(0, area(AreaPhotos(1, 0, 5))).contains("Photos 1 of 5"))
        assertEquals("Matching 3 of 10", SeedAreaList.photosText(area(AreaPhotos(880, 100, 980, null, matched = 3, toMatch = 10))))
    }

    @Test fun thePlaceGuideLineAndTheCreditLineAreSmallAndHonest() {
        assertEquals("Place photos for matching: 412 places have a photo (13.2 MB)", PlaceGuideModel.refPhotoLine(412, 13_841_203))
        assertEquals("Place photos for matching: 1 place has a photo (0.0 MB)", PlaceGuideModel.refPhotoLine(1, 100))
        assertTrue(PlaceGuideModel.refPhotoLine(1, 1, capped = true).contains("storage is full"))
        assertEquals("Reference photo: Ann, CC BY-SA 4.0, Wikimedia Commons", PlaceHistoryModel.creditLine("Ann", "CC BY-SA 4.0"))
        assertEquals("Reference photo: Wikimedia Commons", PlaceHistoryModel.creditLine(null, " "))
    }

    @Test fun oneStageTextForPlacesPhotosAndMatching() {
        assertEquals("Places", PhotoStageText.line(true, 0, 10, 0, 0, null))
        assertEquals("Photos 4 of 10", PhotoStageText.line(false, 4, 10, 0, 0, null))
        assertEquals("Photos 4 of 10 (waiting for free Wi-Fi)", PhotoStageText.line(false, 4, 10, 0, 0, "waiting for free Wi-Fi"))
        assertEquals("Matching 2 of 8", PhotoStageText.line(false, 10, 10, 2, 8, "waiting for free Wi-Fi")) // all photos decided: the Wi-Fi note no longer applies
        assertNull(PhotoStageText.line(false, 10, 10, 8, 8, null)); assertNull(PhotoStageText.line(false, 0, 0, 0, 0, null))
        assertTrue(AreaPhotos(1, 0, 5).busy); assertFalse(AreaPhotos(1, 0, 5, "waiting for free Wi-Fi").busy); assertTrue(AreaPhotos(5, 0, 5, null, 1, 4).busy); assertFalse(AreaPhotos(5, 0, 5, null, 4, 4).busy)
    }

    @Test fun phaseNotesAreFixedPhrases() {
        assertNull(PlacePhotoText.note(PhotoPhase.RUNNING)); assertEquals("waiting for free Wi-Fi", PlacePhotoText.note(PhotoPhase.WAITING_WIFI)); assertEquals("Offline only", PlacePhotoText.note(PhotoPhase.OFFLINE_ONLY))
    }
}
