package com.rokid.context.phone.ui.settings.privacy.zones.online

import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.KeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MapKeyStoreTest {
    @Test fun noKeyAnywhere_isNoKey_andNoProvider() {
        val s = MapKeyStore(InMemoryKeyVault())
        assertFalse(s.hasKey()); assertEquals("", s.key())
        assertNull(OnlineProvider.select(s.key(), debug = false)) // no network host is reachable without a key
    }

    @Test fun aSavedKeyIsTrimmedStoredAndRead_andClearForgetsIt() {
        val v = InMemoryKeyVault(); val s = MapKeyStore(v)
        assertTrue(s.save("  abc_DEF-123 "))
        assertEquals("abc_DEF-123", s.key()); assertTrue(s.hasStoredKey())
        assertEquals("abc_DEF-123", v.get(MapKeyStore.REF))
        assertEquals("OnlineProvider(MapTiler)", OnlineProvider.select(s.key(), debug = false).toString())
        assertTrue(s.clear())
        assertFalse(s.hasKey()); assertNull(v.get(MapKeyStore.REF))
    }

    @Test fun anImplausibleKeyIsNotStored() {
        val v = InMemoryKeyVault(); val s = MapKeyStore(v)
        listOf("", "   ", "a b", "x?key=1", "k\nk", "a".repeat(81), "https://x/y").forEach { assertFalse(it, s.save(it)) }
        assertNull(v.get(MapKeyStore.REF))
    }

    @Test fun theDebugFallbackAppliesOnlyWithoutAStoredKey_andOnlyInDebug() {
        val s = MapKeyStore(InMemoryKeyVault(), MapKeyStore.fallbackFor(debug = true, compiled = "devkey"))
        assertEquals("devkey", s.key()); assertFalse(s.hasStoredKey())
        s.save("typedkey"); assertEquals("typedkey", s.key())
        s.clear(); assertEquals("devkey", s.key())
        assertEquals("", MapKeyStore.fallbackFor(debug = false, compiled = "devkey"))
        assertFalse(MapKeyStore(InMemoryKeyVault(), MapKeyStore.fallbackFor(false, "devkey")).hasKey())
    }

    @Test fun aBrokenVaultReadsAsNoKey_andNeverThrows() {
        val broken = object : KeyVault {
            override fun get(ref: String): String? = throw IllegalStateException("keystore")
            override fun put(ref: String, key: String) = throw IllegalStateException("keystore")
            override fun remove(ref: String) = throw IllegalStateException("keystore")
        }
        val s = MapKeyStore(broken)
        assertFalse(s.hasKey()); assertFalse(s.save("abc")); assertFalse(s.clear())
    }

    /** The key's only readers: the store reads BuildConfig.MAP_TILES_KEY, the graph reads the store. Nothing else (dump API, diagnostics, logs) may name the field or the vault ref. */
    @Test fun onlyTheStoreAndTheGraphTouchTheKey() {
        val files = File("..").let { root -> listOf("phone", "core", "services").flatMap { m ->
            File(root, m).walkTopDown().onEnter { it.name != "build" && it.name != "test" && it.name != "androidTest" }
                .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/src/main/") }.toList() } }
        assertTrue(files.size > 100)
        val allowed = setOf("MapKeyStore.kt", "OnlineMapGraph.kt", "MapKeyField.kt")
        val offenders = files.filter { f -> val t = f.readText(); (t.contains("MAP_TILES_KEY") || t.contains("map_tiles_key") || t.contains("MapKeyStore")) && f.name !in allowed }.map { it.name }
        assertEquals("only the store and the graph may use the map key: $offenders", emptyList<String>(), offenders)
        val logged = files.filter { f -> f.name in allowed && Regex("""PcLog\.\w+\([^)]*(key|Key)""").containsMatchIn(f.readText()) }.map { it.name }
        assertEquals("the key must never reach a log line: $logged", emptyList<String>(), logged)
    }
}
