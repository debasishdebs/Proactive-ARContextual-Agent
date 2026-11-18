package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.cancelScope
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Stands in for the Storage Access Framework: documents are byte arrays under content URIs; grants are recorded. */
class FakeMapFiles(override val importDir: File) : MapFiles {
    val docs = mutableMapOf<String, ByteArray>()
    val names = mutableMapOf<String, String>()
    val granted = mutableSetOf<String>()
    var refuseGrant = false
    override fun grant(uri: String) { if (refuseGrant) throw SecurityException("no"); granted += uri }
    override fun release(uri: String) { granted -= uri }
    override fun open(uri: String): ByteSource {
        if (uri !in granted || uri !in docs) throw PmTilesException(PmTilesException.Reason.UNREADABLE)
        return ArrayByteSource(docs.getValue(uri))
    }
    override fun describe(uri: String) = docs[uri]?.let { (names[uri] ?: "map.pmtiles") to it.size.toLong() }
    override fun copy(uri: String, dest: File): Long { val b = docs[uri] ?: throw java.io.IOException(); dest.writeBytes(b); return b.size.toLong() }
}

class OfflineMapViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val vms = mutableListOf<OfflineMapViewModel>()
    @After fun tearDown() = vms.forEach { it.cancelScope() }

    private val good = PmTilesFixture.build(PmTilesFixture.sample())
    private val u1 = "content://docs/tree/maps%3Asl.pmtiles"
    private val u2 = "content://docs/tree/maps%3Ahyd.pmtiles"

    private fun files() = FakeMapFiles(File(tmp.root, "offline_maps")).also { it.docs[u1] = good; it.docs[u2] = good; it.names[u1] = "sl.pmtiles" }
    private fun vm(f: MapFiles, store: OfflineMapStore = OfflineMapStore()) = OfflineMapViewModel(f, store, main.dispatcher).also { vms += it }

    @Test fun noSelectionUsesThePlainCanvas() {
        val v = vm(files())
        assertNull(v.ui.value.selection); assertNull(v.reader.value); assertFalse(v.ui.value.ready); assertFalse(v.ui.value.busy)
    }

    @Test fun linkingReadsInPlaceKeepsAReadOnlyGrantAndNeverCopies() {
        val f = files(); val store = OfflineMapStore()
        val v = vm(f, store)
        v.link(u1)
        assertTrue(v.ui.value.ready); assertNotNull(v.reader.value)
        assertEquals(MapSelection(MapMode.LINKED, u1, "sl.pmtiles"), store.get())
        assertEquals(setOf(u1), f.granted)
        assertEquals(good.size.toLong(), v.ui.value.sizeBytes)
        assertEquals(0, v.ui.value.minZoom); assertEquals(2, v.ui.value.maxZoom)
        assertFalse("nothing copied", f.importDir.exists() && f.importDir.listFiles().orEmpty().isNotEmpty())
        assertArrayEquals(PmTilesFixture.png(11), v.reader.value!!.tile(1, 0, 1))
    }

    @Test fun relinkingReleasesTheOldGrantAndKeepsTheNewOne() {
        val f = files(); val v = vm(f)
        v.link(u1); v.link(u2)
        assertEquals(setOf(u2), f.granted)
        v.link(u2) // same file again keeps its grant
        assertEquals(setOf(u2), f.granted)
    }

    @Test fun aCorruptFileFailsClosedToTheCanvasAndKeepsNothing() {
        val f = files(); f.docs[u1] = good.copyOf(good.size - 5); val store = OfflineMapStore()
        val v = vm(f, store)
        v.link(u1)
        assertNull(v.reader.value); assertFalse(v.ui.value.ready); assertNull(store.get())
        assertTrue(v.ui.value.messageIsError)
        assertEquals(PmTilesException.Reason.CORRUPT.words, v.ui.value.message)
        assertTrue("grant dropped again", f.granted.isEmpty())
    }

    @Test fun aFileThatIsNotPmtilesAndVectorTilesAreRefusedInWords() {
        val f = files(); f.docs[u1] = ByteArray(500); f.docs[u2] = PmTilesFixture.build(PmTilesFixture.sample(), type = 1)
        val v = vm(f)
        v.link(u1); assertEquals(PmTilesException.Reason.NOT_PMTILES.words, v.ui.value.message)
        v.link(u2); assertEquals(PmTilesException.Reason.VECTOR_TILES.words, v.ui.value.message)
        assertNull(v.reader.value)
    }

    @Test fun aBadLinkKeepsTheWorkingMapAndItsGrant() {
        val f = files(); f.docs[u2] = ByteArray(300); val v = vm(f)
        v.link(u1); v.link(u2)
        assertTrue(v.ui.value.ready); assertEquals(setOf(u1), f.granted); assertEquals(u1, v.ui.value.selection?.uri)
        assertTrue(v.ui.value.messageIsError)
    }

    @Test fun onlyContentUrisAreAccepted() {
        val f = files(); val v = vm(f)
        for (bad in listOf("https://tiles.example/x.pmtiles", "http://x/y", "file:///sdcard/x.pmtiles", "/sdcard/x.pmtiles", "")) {
            v.link(bad); assertEquals(OfflineMapWords.NOT_URI, v.ui.value.message); v.importCopy(bad)
        }
        assertTrue(f.granted.isEmpty()); assertNull(v.reader.value)
        assertTrue(MapUri.accepted(u1)); assertFalse(MapUri.accepted("HTTP://x"))
    }

    @Test fun aRefusedGrantLeavesTheCanvas() {
        val f = files().also { it.refuseGrant = true }; val store = OfflineMapStore()
        val v = vm(f, store); v.link(u1)
        assertNull(store.get()); assertNull(v.reader.value); assertEquals(OfflineMapWords.CANT_OPEN, v.ui.value.message)
    }

    @Test fun aSavedChoiceIsRestoredAndALostGrantFailsClosedKeepingTheRowToRemove() {
        val f = files(); val store = OfflineMapStore()
        vm(f, store).link(u1)
        // a new session: same grant -> restored
        assertTrue(vm(f, store).ui.value.ready)
        // the document was moved: canvas, with the plain message, and the selection stays so it can be removed
        f.docs.remove(u1)
        val v = vm(f, store)
        assertNull(v.reader.value); assertEquals(u1, v.ui.value.selection?.uri); assertTrue(v.ui.value.messageIsError)
        v.remove()
        assertNull(store.get()); assertTrue(f.granted.isEmpty()); assertNull(v.ui.value.selection)
    }

    @Test fun importCopiesIntoThePrivateFolderShowsItsSizeAndDeleteRemovesIt() {
        val f = files(); val store = OfflineMapStore(); val v = vm(f, store)
        v.importCopy(u1)
        val copy = File(f.importDir, OfflineMapViewModel.IMPORT_NAME)
        assertTrue(v.ui.value.ready); assertTrue(copy.isFile); assertEquals(good.size.toLong(), copy.length())
        assertEquals(good.size.toLong(), v.ui.value.sizeBytes)
        assertEquals(MapMode.IMPORTED, store.get()?.mode); assertNull(store.get()?.uri)
        assertTrue("no grant is kept for a copy", f.granted.isEmpty())
        assertFalse(File(f.importDir, "map.pmtiles.tmp").exists())
        v.remove()
        assertFalse(copy.exists()); assertNull(store.get()); assertNull(v.reader.value)
    }

    @Test fun aCorruptImportLeavesNoFileAndKeepsThePreviousMap() {
        val f = files(); f.docs[u2] = good.copyOf(good.size - 5); val v = vm(f)
        v.importCopy(u1)
        v.importCopy(u2)
        assertTrue(v.ui.value.messageIsError)
        val names = f.importDir.list().orEmpty().toList()
        assertEquals(listOf(OfflineMapViewModel.IMPORT_NAME), names)
        assertEquals(MapMode.IMPORTED, v.ui.value.selection?.mode)
        assertTrue(v.ui.value.ready)
    }

    @Test fun linkingAfterAnImportDeletesTheCopy() {
        val f = files(); val v = vm(f)
        v.importCopy(u1); v.link(u2)
        assertFalse(File(f.importDir, OfflineMapViewModel.IMPORT_NAME).exists())
        assertEquals(MapMode.LINKED, v.ui.value.selection?.mode)
    }

    @Test fun sizesAreShownInPlainUnits() {
        assertEquals("512 B", OfflineMapWords.size(512)); assertEquals("3 KB", OfflineMapWords.size(3 * 1024))
        assertEquals("55.0 MB", OfflineMapWords.size(55L shl 20)); assertEquals("1.5 GB", OfflineMapWords.size((1.5 * (1L shl 30)).toLong()))
    }
}
