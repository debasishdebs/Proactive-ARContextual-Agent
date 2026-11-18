package com.rokid.context.phone.hub

import com.rokid.context.orchestration.proactive.DeliveredCard
import com.rokid.context.orchestration.proactive.SuggestionSurfaces
import com.rokid.context.orchestration.proactive.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v3.1: the ONE suggestion card the PCA glasses HUD may be showing (never stored, text only in memory, gone after [com.rokid.context.core.model.PlaceCardInfo.SHOW_MS]). The hub maps it onto the SAME additive wire
 * overlay as Place Guide cards (`Overlay.Nudge` + `PlaceCardInfo`, id `sg_...`), so PROTOCOL_VERSION stays 1 and an older glasses build shows the plain nudge text.
 */
class SuggestionHudHolder(private val clock: () -> Long = System::currentTimeMillis) {
    data class Shown(val card: DeliveredCard, val atMs: Long)
    private val _card = MutableStateFlow<Shown?>(null)
    val card: StateFlow<Shown?> = _card.asStateFlow()
    private val lock = Any()

    fun show(c: DeliveredCard): Boolean { synchronized(lock) { _card.value = Shown(c, clock()) }; return true }
    /** Clears only the card with this id (a newer card stays). */
    fun clearIf(id: String): Boolean = synchronized(lock) { if (_card.value?.card?.id == id) { _card.value = null; true } else false }
    fun clear() { synchronized(lock) { _card.value = null } }
    fun shownAt(id: String): Long? = _card.value?.takeIf { it.card.id == id }?.atMs
}

/**
 * The production surfaces: the HUD holder above, and Nexus through [nexus] (the feeder; its sink runs its own gates again). The phone feed needs nothing here: it follows the store.
 * [withdrawNexus] takes ONE record off Nexus by ref (no-op when Nexus is not running).
 */
class PhoneSuggestionSurfaces(
    private val hud: SuggestionHudHolder,
    private val nexus: (DeliveredCard) -> Boolean,
    private val withdrawNexus: (ids: Collection<String>) -> Unit,
    private val withdrawAllNexus: () -> Unit,
) : SuggestionSurfaces {
    override fun showHud(card: DeliveredCard): Boolean = hud.show(card)
    override fun showNexus(card: DeliveredCard): Boolean = try { nexus(card) } catch (e: Exception) { false }
    override fun withdraw(ids: Collection<String>) { ids.forEach { hud.clearIf(it) }; try { withdrawNexus(ids) } catch (e: Exception) { /* the sink's own gates and TTL close it */ } }
    override fun withdrawAll() { hud.clear(); try { withdrawAllNexus() } catch (e: Exception) { } }
    override fun withdrawSurface(surface: Surface) {
        when (surface) { Surface.HUD -> hud.clear(); Surface.NEXUS -> try { withdrawAllNexus() } catch (e: Exception) { }; Surface.PHONE -> Unit }
    }
}

/** The one process-wide HUD holder (the hub reads it; the production surfaces write it). */
object SuggestionHud { val holder = SuggestionHudHolder() }
