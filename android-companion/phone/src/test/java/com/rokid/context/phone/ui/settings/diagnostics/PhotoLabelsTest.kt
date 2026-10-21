package com.rokid.context.phone.ui.settings.diagnostics

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import android.app.Application
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhotoLabelsTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun labels() = PhotoLabels(File(tmp.root, "photo-eval"))

    @Test fun addAppendsAndMergesTheSameQuery() {
        val l = labels()
        l.add("blue mug", "f1", small = true)
        l.add("keys", "f2", small = false)
        l.add("blue mug", "f3", small = true)   // same query text: one more relevant photo
        l.add("blue mug", "f1", small = true)   // the same pair twice changes nothing
        val a = JSONArray(File(tmp.root, "photo-eval/queries.json").readText())
        assertEquals(2, a.length())
        assertEquals(setOf("f1", "f3"), a.getJSONObject(0).getJSONArray("frameIds").let { f -> (0 until f.length()).map { f.getString(it) }.toSet() })
        assertTrue(a.getJSONObject(0).getBoolean("small")); assertFalse(a.getJSONObject(1).getBoolean("small"))
        assertEquals(PhotoLabels.Counts(queries = 2, small = 1, photos = 3), l.counts())
    }

    @Test fun blankInputIsIgnoredAndMissingFileCountsZero() {
        val l = labels()
        assertEquals(PhotoLabels.Counts(0, 0, 0), l.counts())
        l.add("  ", "f1", false); l.add("q", " ", false)
        assertEquals(PhotoLabels.Counts(0, 0, 0), l.counts())
    }

    @Test fun clearDeletesQueriesAndResult() {
        val l = labels()
        l.add("q", "f1", true)
        File(tmp.root, "photo-eval/result.json").writeText("{}")
        l.clear()
        assertFalse(File(tmp.root, "photo-eval/queries.json").exists()); assertFalse(File(tmp.root, "photo-eval/result.json").exists())
        assertEquals(PhotoLabels.Counts(0, 0, 0), l.counts())
    }
}
