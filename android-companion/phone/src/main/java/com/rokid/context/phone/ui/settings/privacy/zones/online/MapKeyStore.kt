package com.rokid.context.phone.ui.settings.privacy.zones.online

import com.rokid.context.ai.KeyVault

/**
 * The map-tile key, entered in Settings and kept on this phone only: in the same encrypted [KeyVault] file as the AI provider keys (excluded from backup and device transfer),
 * under its own ref (the orphan sweep only touches "key:" refs). A DEBUG build with no stored key falls back to [debugFallback] (the compiled dev key); a release build has none.
 * The key is never logged, shown after saving, exported or put in a message; every vault failure reads as "no key".
 */
class MapKeyStore(private val vault: KeyVault, private val debugFallback: String = "") {
    /** The key in use now: the stored one, else the debug fallback, else "". */
    fun key(): String = (stored() ?: debugFallback).trim()

    fun hasKey(): Boolean = key().isNotEmpty()

    fun hasStoredKey(): Boolean = stored() != null

    /** Trims and stores [raw]. False (nothing stored) when it is empty or not a plain tile key. */
    fun save(raw: String): Boolean {
        val k = raw.trim()
        if (!valid(k)) return false
        return try { vault.put(REF, k); true } catch (e: Exception) { false }
    }

    /** Forgets the stored key (a debug fallback key, if any, applies again). */
    fun clear(): Boolean = try { vault.remove(REF); true } catch (e: Exception) { false }

    private fun stored(): String? = try { vault.get(REF)?.trim()?.takeIf { it.isNotEmpty() } } catch (e: Exception) { null }

    companion object {
        const val REF = "map_tiles_key"
        private val PLAIN = Regex("[A-Za-z0-9_-]{1,80}")

        fun valid(k: String): Boolean = PLAIN.matches(k)

        /** The compiled dev key counts only in a debug build; a release build never uses one, whatever the field holds. */
        fun fallbackFor(debug: Boolean, compiled: String): String = if (debug) compiled.trim() else ""
    }
}

/** What the street-map card needs to let the owner enter, replace or clear the key. Nothing here ever returns the key itself. */
class MapKeyControl(
    val hasKey: Boolean,
    /** True when the key in use is the stored one (so "Clear" is offered); false for none or a debug-build fallback. */
    val hasStoredKey: Boolean,
    /** review2 #7: both suspend and run on IO (the encrypted vault is never written on Main). */
    val save: suspend (String) -> Boolean,
    val clear: suspend () -> Unit,
)

object MapKeyControls {
    /**
     * Builds the control from the store as it is NOW: [MapKeyStore.hasKey] and [MapKeyStore.hasStoredKey] read the encrypted vault, so the CALLER must be off Main (`OnlineMapGraph.deps` is, see `rememberOnlineDeps`). Save and Clear
     * hop to [io] themselves, so a click on Main never commits to the vault there.
     */
    fun read(store: MapKeyStore, io: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO): MapKeyControl = MapKeyControl(
        store.hasKey(), store.hasStoredKey(),
        { raw -> kotlinx.coroutines.withContext(io) { store.save(raw) } },
        { kotlinx.coroutines.withContext(io) { store.clear(); Unit } },
    )
}
