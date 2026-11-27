package com.rokid.context.phone.ui.settings.place

import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.place.PackStats
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.phone.place.PhotoResult
import com.rokid.context.work.frames.place.ManualPlace
import com.rokid.context.work.frames.place.PhotoFault
import com.rokid.context.work.frames.place.PlaceFix
import com.rokid.context.work.frames.place.PlacePinning
import com.rokid.context.work.frames.place.PreparedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Developer "Add place manually" photo section in the view model: gating, fetch and pick, preview, save with and without a photo, a failed Moment write, and the indexing line. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualPhotoViewModelTest {
    private val d = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(d) }
    @After fun after() { Dispatchers.resetMain() }

    private val photo = PreparedPhoto(ByteArray(2048), 640, 480)

    private class Control : PlaceGuideControl {
        override var enabled = false
        var stored = listOf<UserPlace>()
        var ids = 0
        var fetched = 0; var read = 0; var added = 0
        var fetchAnswer: PhotoResult = PhotoResult.Fail(PhotoFault.FAILED)
        var readAnswer: PhotoResult = PhotoResult.Fail(PhotoFault.FAILED)
        var addOk = true
        var addedFor: Pair<String, String>? = null
        var indexed: Map<String, Boolean> = emptyMap()
        override suspend fun seed(box: com.rokid.context.memory.place.GeoBox) = Result.success(SeedOutcome("x"))
        override suspend fun summary() = ""
        override suspend fun stats() = PackStats(pinned = stored.size, seeded = 3, fetched = 0, bytes = 0, budgetBytes = 1)
        override val pinning = PlacePinning(
            fix = { PlaceFix(1.0, 2.0, 1_000, 10f) }, zoneBlocked = { false }, zones = { emptyList<PauseZone>() }, nearby = { _, _ -> emptyList() },
            load = { stored }, save = { stored = it; true }, elapsed = { 5_000L }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) },
        )
        override suspend fun fetchPhoto(link: String): PhotoResult { fetched++; return fetchAnswer }
        override suspend fun readPhoto(uri: String): PhotoResult { read++; return readAnswer }
        override suspend fun addReferencePhoto(pinId: String, photo: PreparedPhoto, description: String): Boolean { added++; addedFor = pinId to description; return addOk }
        override suspend fun momentsOf(ids: List<String>): Map<String, MomentChoice> = ids.associateWith { MomentChoice(it, "/p", 1L) }
        override suspend fun referenceIndexed(momentIds: List<String>): Map<String, Boolean> = indexed.filterKeys { it in momentIds }
    }

    private fun fill(vm: PlaceGuideViewModel) =
        vm.updateManual { it.copy(name = "Office", note = "Main building", kindIndex = ManualPlace.KINDS.indexOf(PlaceKinds.ATTRACTION), coordinates = "12.9716, 77.5946") }

    @Test fun theViewModelRefusesWithoutDeveloperMode() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d, developerMode = false)
        vm.fetchManualPhoto(); vm.pickManualPhoto("content://x"); d.scheduler.advanceUntilIdle()
        assertEquals(0, c.fetched); assertEquals(0, c.read); assertNull(vm.state.value.manualForm)
        vm.openManual(); assertNull(vm.state.value.manualForm); vm.saveManual(); d.scheduler.advanceUntilIdle(); assertEquals(0, c.added); assertTrue(c.stored.isEmpty())
    }

    @Test fun theDialogAndItsPhotoSectionExistOnlyUnderDeveloperMode() {
        val src = listOf("src/main/java", "../phone/src/main/java").map { File(it, "com/rokid/context/phone/ui/settings/place/PlaceGuideSettings.kt") }.first { it.isFile }.readText()
        assertTrue(src.contains("if (state.developerMode) state.manualForm?.let { d -> ManualPlaceDialog(d, on) }"))
        assertEquals("the photo section is built in one place, inside the dialog", 1, Regex("ManualPhotoSection\\(d, on\\)").findAll(src).count())
        assertTrue(src.contains("OpenDocument()")); assertFalse("no storage permission and no media picker", src.contains("READ_EXTERNAL_STORAGE"))
    }

    @Test fun fetchPutsAPreviewInTheFormAndClearsTheLink() = runTest(d) {
        val c = Control().also { it.fetchAnswer = PhotoResult.Ok(photo) }; val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); vm.updateManual { it.copy(photoLink = "https://example.com/a.jpg") }
        vm.fetchManualPhoto(); d.scheduler.advanceUntilIdle()
        val f = vm.state.value.manualForm!!
        assertEquals(1, c.fetched); assertEquals(photo, f.photo); assertEquals("", f.photoLink); assertFalse(f.photoBusy); assertNull(f.error)
        assertEquals("Photo ready: 640 x 480, 2 KB", PlaceGuideModel.photoSize(photo))
        vm.removeManualPhoto(); assertNull(vm.state.value.manualForm!!.photo)
    }

    @Test fun aRefusedFetchShowsTheFixedReasonAndKeepsNothing() = runTest(d) {
        val c = Control().also { it.fetchAnswer = PhotoResult.Fail(PhotoFault.OFFLINE_ONLY) }; val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); vm.fetchManualPhoto(); d.scheduler.advanceUntilIdle(); assertEquals(0, c.fetched) // an empty link is not sent
        vm.updateManual { it.copy(photoLink = "https://example.com/a.jpg") }; vm.fetchManualPhoto(); d.scheduler.advanceUntilIdle()
        assertEquals(PhotoFault.OFFLINE_ONLY.text, vm.state.value.manualForm!!.error); assertNull(vm.state.value.manualForm!!.photo)
        vm.updateManual { it }; assertNull("any edit clears the line", vm.state.value.manualForm!!.error)
    }

    @Test fun thePickerResultIsCheckedAndACancelDoesNothing() = runTest(d) {
        val c = Control().also { it.readAnswer = PhotoResult.Ok(photo) }; val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); vm.pickManualPhoto(null); d.scheduler.advanceUntilIdle(); assertEquals(0, c.read); assertNull(vm.state.value.manualForm!!.photo)
        vm.pickManualPhoto("content://doc/1"); d.scheduler.advanceUntilIdle(); assertEquals(photo, vm.state.value.manualForm!!.photo)
        c.readAnswer = PhotoResult.Fail(PhotoFault.NOT_AN_IMAGE); vm.pickManualPhoto("content://doc/2"); d.scheduler.advanceUntilIdle()
        assertEquals("a bad replacement keeps the earlier photo and says why", photo, vm.state.value.manualForm!!.photo); assertEquals(PhotoFault.NOT_AN_IMAGE.text, vm.state.value.manualForm!!.error)
    }

    @Test fun savingWithAPhotoMakesThePinThenOneReferenceMomentWithTheDescription() = runTest(d) {
        val c = Control().also { it.fetchAnswer = PhotoResult.Ok(photo) }; val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm); vm.updateManual { it.copy(photoLink = "https://example.com/a.jpg") }; vm.fetchManualPhoto(); d.scheduler.advanceUntilIdle()
        vm.saveManual(); d.scheduler.advanceUntilIdle()
        assertNull(vm.state.value.manualForm); assertEquals(1, c.stored.size); assertEquals(1, c.added)
        assertEquals(c.stored.single().id to "Main building", c.addedFor); assertEquals(PlaceGuideModel.PHOTO_ADDED, vm.state.value.pinMessage); assertFalse(vm.state.value.pinMessageIsError)
    }

    @Test fun aFailedMomentWriteLeavesThePinWithoutALinkAndSaysSo() = runTest(d) {
        val c = Control().also { it.addOk = false }; val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm); vm.updateManual { it.copy(photo = photo) }; vm.saveManual(); d.scheduler.advanceUntilIdle()
        assertEquals(1, c.stored.size); assertNull(c.stored.single().momentId); assertEquals(PlaceGuideModel.PHOTO_NOT_ADDED, vm.state.value.pinMessage); assertTrue(vm.state.value.pinMessageIsError)
    }

    @Test fun withoutAPhotoNothingIsAddedAndABadFormWritesNoMoment() = runTest(d) {
        val c = Control(); val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm); vm.saveManual(); d.scheduler.advanceUntilIdle(); assertEquals(0, c.added); assertEquals("Place added.", vm.state.value.pinMessage)
        vm.openManual(); fill(vm); vm.updateManual { it.copy(coordinates = "95, 10", photo = photo) }; vm.saveManual(); d.scheduler.advanceUntilIdle()
        assertEquals("an invalid form never reaches the Moment writer", 0, c.added); assertEquals(1, c.stored.size)
        assertNotNull(vm.state.value.manualForm!!.error); assertFalse(vm.state.value.manualForm!!.photoBusy); assertEquals(photo, vm.state.value.manualForm!!.photo)
    }

    @Test fun cancelBeforeSaveWritesNothing() = runTest(d) {
        val c = Control().also { it.readAnswer = PhotoResult.Ok(photo) }; val vm = PlaceGuideViewModel(c, d, developerMode = true)
        vm.openManual(); fill(vm); vm.pickManualPhoto("content://doc/1"); d.scheduler.advanceUntilIdle(); vm.cancelManual()
        assertNull(vm.state.value.manualForm); assertEquals(0, c.added); assertTrue(c.stored.isEmpty())
    }

    @Test fun thePinRowSaysWaitingOrIndexedFromTheCountsOnly() = runTest(d) {
        val m1 = com.rokid.context.memory.moment.MomentId.format(100); val m2 = com.rokid.context.memory.moment.MomentId.format(200)
        val c = Control()
        c.stored = listOf(UserPlace("user:00000000-0000-0000-0000-000000000001", "A", PlaceKinds.ATTRACTION, 1.0, 2.0, 60, null, "", m1, 1L), UserPlace("user:00000000-0000-0000-0000-000000000002", "B", PlaceKinds.ATTRACTION, 1.0, 2.0, 60, null, "", m2, 1L))
        c.indexed = mapOf(m1 to false, m2 to true)
        val vm = PlaceGuideViewModel(c, d, developerMode = true); d.scheduler.advanceUntilIdle()
        assertEquals(mapOf(m1 to false, m2 to true), vm.state.value.refIndexed)
        assertEquals("Photo added, waiting to be indexed", PlaceGuideModel.PHOTO_WAITING); assertEquals("Reference photo indexed", PlaceGuideModel.PHOTO_READY)
        val plain = PlaceGuideViewModel(c, d, developerMode = false); d.scheduler.advanceUntilIdle()
        assertTrue("without Developer mode nothing is read", plain.state.value.refIndexed.isEmpty())
    }
}
