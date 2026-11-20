package com.rokid.context.ai.resolve

import android.content.SharedPreferences
import com.rokid.context.core.net.ModelPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Realistic disk behaviour for the privacy store: a file that loses unflushed apply()s, and one that fails every write. */
class ModelPrivacyDurabilityTest {
    /**
     * A prefs file with a real "disk": [apply] changes memory only (a process kill loses it), [commit] writes through; [failWrites] makes BOTH fail (a full or broken disk). A restart is a new instance over the same [disk].
     * [flushApply] true = apply reaches the disk (the normal case when the process lives a moment longer).
     */
    private class DiskPrefs(val disk: MutableMap<String, Any?> = HashMap(), var failWrites: Boolean = false, var flushApply: Boolean = false, /** Runs at the start of every commit: a newer tap can land between two commits of an older write. */ var onCommit: (() -> Unit)? = null) : SharedPreferences {
        private val mem = HashMap(disk)
        override fun getAll(): MutableMap<String, *> = HashMap(mem)
        override fun getString(k: String?, d: String?) = (mem[k] as? String) ?: d
        override fun getStringSet(k: String?, d: MutableSet<String>?): MutableSet<String>? = d
        override fun getInt(k: String?, d: Int) = (mem[k] as? Int) ?: d
        override fun getLong(k: String?, d: Long) = (mem[k] as? Long) ?: d
        override fun getFloat(k: String?, d: Float) = (mem[k] as? Float) ?: d
        override fun getBoolean(k: String?, d: Boolean) = (mem[k] as? Boolean) ?: d
        override fun contains(k: String?) = mem.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()
            override fun putString(k: String?, v: String?) = apply { ops += { it[k!!] = v } }
            override fun putBoolean(k: String?, v: Boolean) = apply { ops += { it[k!!] = v } }
            override fun remove(k: String?) = apply { ops += { it.remove(k) } }
            override fun clear() = apply { ops += { it.clear() } }
            override fun putStringSet(k: String?, v: MutableSet<String>?) = this
            override fun putInt(k: String?, v: Int) = apply { ops += { it[k!!] = v } }
            override fun putLong(k: String?, v: Long) = apply { ops += { it[k!!] = v } }
            override fun putFloat(k: String?, v: Float) = apply { ops += { it[k!!] = v } }
            // like SharedPreferencesImpl: memory is updated even when the disk write fails; only the disk is skipped
            override fun apply() { ops.forEach { it(mem) }; if (flushApply && !failWrites) { ops.forEach { it(disk) } } }
            override fun commit(): Boolean { onCommit?.invoke(); ops.forEach { it(mem) }; if (failWrites) return false; ops.forEach { it(disk) }; return true }
        }
        /** The process restarts: only what reached the disk is there. */
        fun restart(): DiskPrefs = DiskPrefs(disk)
    }

    private val direct = Executor { it.run() }

    @Test fun beginIsSynchronousSoTheNewModeIsInForceTheMomentItReturnsWhateverHappensNext() {
        val st = ModelPrivacyStore(DiskPrefs(), DiskPrefs(), Executor { /* the writer never runs */ })
        assertEquals(ModelPrivacy.AUTO, st.current())
        st.begin(ModelPrivacy.OFFLINE_ONLY)
        assertEquals("the router, the network guards and the tile check read current()", ModelPrivacy.OFFLINE_ONLY, st.current())
    }

    @Test fun commitAsyncRunsInTheStoresOwnScopeAndCompletes() {
        val main = DiskPrefs(); val side = DiskPrefs()
        val st = ModelPrivacyStore(main, side, direct)
        val done = CountDownLatch(1); var ok = false
        st.commitAsync(st.begin(ModelPrivacy.OFFLINE_ONLY)) { ok = it; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertTrue(ok)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(main.restart(), side.restart(), direct).current())
    }

    @Test fun aWriteThatFullyFailsOnTheMainFileStillReadsBackAsOfflineOnlyBecauseTheSeparateFileRemembersTheIntent() {
        val main = DiskPrefs(); val side = DiskPrefs()
        val st = ModelPrivacyStore(main, side, direct)
        assertTrue(st.setAndWaitBlocking(ModelPrivacy.AUTO)) // an earlier, permissive choice is durable in both files
        main.failWrites = true // the main file's disk breaks: apply AND commit fail
        assertFalse("the failure is reported", st.setAndWaitBlocking(ModelPrivacy.OFFLINE_ONLY))
        assertEquals(ModelPrivacy.OFFLINE_ONLY, st.current())
        val next = ModelPrivacyStore(main.restart(), side.restart(), direct)
        assertEquals("the main file still says AUTO, the separate file says the last choice was Offline only", ModelPrivacy.OFFLINE_ONLY, next.current())
        assertTrue(next.wasReset.value)
    }

    @Test fun anAutoChoiceThatWasWrittenSuccessfullyClearsTheMarker() {
        val main = DiskPrefs(); val side = DiskPrefs()
        val st = ModelPrivacyStore(main, side, direct)
        st.setAndWaitBlocking(ModelPrivacy.OFFLINE_ONLY); st.setAndWaitBlocking(ModelPrivacy.AUTO)
        val next = ModelPrivacyStore(main.restart(), side.restart(), direct)
        assertEquals(ModelPrivacy.AUTO, next.current()); assertFalse(next.wasReset.value)
    }

    @Test fun anUnflushedApplyIsReallyLostOnAKillButTheCommitThatFollowsMakesItDurable() {
        val main = DiskPrefs(); val side = DiskPrefs() // flushApply false: apply() reaches memory only
        val st = ModelPrivacyStore(main, side, direct)
        st.setAndWaitBlocking(ModelPrivacy.AUTO)
        st.begin(ModelPrivacy.OFFLINE_ONLY) // killed right here: neither the marker nor the commit reached the disk
        val afterKill = ModelPrivacyStore(main.restart(), side.restart(), direct)
        assertEquals("documents the window: only the previous DURABLE choice survives a kill before the write", ModelPrivacy.AUTO, afterKill.current())
        // ... which is why the UI starts the durable write in the same tap (commitAsync) and why it is not tied to the screen:
        st.setAndWaitBlocking(ModelPrivacy.OFFLINE_ONLY)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(main.restart(), side.restart(), direct).current())
    }

    @Test fun aKillAfterTheMarkerWasFlushedReadsBackAsResetOfflineOnly() {
        val main = DiskPrefs(flushApply = true); val side = DiskPrefs(flushApply = true)
        val st = ModelPrivacyStore(main, side, direct)
        st.setAndWaitBlocking(ModelPrivacy.AUTO)
        st.begin(ModelPrivacy.OFFLINE_ONLY) // the apply()s were flushed, the durable commit never ran
        val next = ModelPrivacyStore(main.restart(), side.restart(), direct)
        assertEquals(ModelPrivacy.OFFLINE_ONLY, next.current()); assertTrue(next.wasReset.value)
    }

    @Test fun aNewerTapBetweenTheTwoCommitsOfAnOlderWriteStopsTheOlderOneBeforeItReachesTheMainFile() {
        val main = DiskPrefs(); val side = DiskPrefs()
        val st = ModelPrivacyStore(main, side, direct)
        st.setAndWaitBlocking(ModelPrivacy.OFFLINE_ONLY)
        var newer: ModelPrivacyStore.Pending? = null
        side.onCommit = { side.onCommit = null; newer = st.begin(ModelPrivacy.OFFLINE_ONLY) } // the double tap lands while the older write is between its commits
        var olderReported = false
        st.commitAsync(st.begin(ModelPrivacy.AUTO)) { olderReported = true }
        Thread.sleep(300)
        assertFalse("a superseded write reports nothing (it must not clear a failure)", olderReported)
        assertEquals("the older AUTO never reached the main file", ModelPrivacy.OFFLINE_ONLY.name, main.disk["mode"])
        val done = CountDownLatch(1)
        st.commitAsync(newer!!) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(main.restart(), side.restart(), direct).current())
    }

    @Test fun aSupersededWriteDoesNotClearAFailureReportedByTheNewerOne() {
        val main = DiskPrefs(); val side = DiskPrefs()
        val st = ModelPrivacyStore(main, side, direct)
        val older = st.begin(ModelPrivacy.AUTO); val newer = st.begin(ModelPrivacy.OFFLINE_ONLY)
        main.failWrites = true
        val results = java.util.concurrent.CopyOnWriteArrayList<Boolean>(); val latch = CountDownLatch(1)
        st.commitAsync(newer) { results += it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        st.commitAsync(older) { results += true } // runs after the newer one: skipped, so it must not report "true"
        Thread.sleep(300)
        assertEquals(listOf(false), results.toList())
    }

    private fun ModelPrivacyStore.setAndWaitBlocking(m: ModelPrivacy) = kotlinx.coroutines.runBlocking { setAndWait(m) }
}
