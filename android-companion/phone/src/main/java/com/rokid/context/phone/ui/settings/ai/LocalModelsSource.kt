package com.rokid.context.phone.ui.settings.ai

import com.rokid.context.ai.local.LocalModel
import com.rokid.context.data.models.LocalModelState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.SwapImpact
import com.rokid.context.phone.ui.list.ActionResult
import com.rokid.context.phone.ui.list.ItemAction
import com.rokid.context.phone.ui.list.ItemStatus
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.list.ListScreen
import com.rokid.context.phone.ui.list.ListSource
import com.rokid.context.phone.ui.list.NamedAction
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the page header shows besides the rows. */
data class LocalModelsHeader(
    val allowMetered: Boolean = false,
    val loadFailed: Boolean = false,
    /** A retired index is still inside its 7-day grace: Roll back is offered (spec §F.2). */
    val rollbackAvailable: Boolean = false,
    val impact: SwapImpact = SwapImpact(0, 0),
)

/** What the page needs from the embedding spaces for the swap confirmation and Roll back. */
interface SwapRollback {
    suspend fun available(): Boolean
    suspend fun impact(): SwapImpact
    suspend fun rollBack(): Boolean

    object None : SwapRollback {
        override suspend fun available() = false
        override suspend fun impact() = SwapImpact(0, 0)
        override suspend fun rollBack() = false
    }
}

class SpacesSwapRollback(private val spaces: EmbeddingSpaces, private val onRolledBack: () -> Unit) : SwapRollback {
    override suspend fun available() = spaces.rollbackTarget() != null
    override suspend fun impact() = spaces.swapImpact()
    override suspend fun rollBack(): Boolean {
        val target = spaces.rollbackTarget() ?: return false
        return spaces.rollback(target.id).also { if (it) onRolledBack() }
    }
}

/**
 * Settings -> AI -> On-device models (spec section 5): one row per catalog model with Download / Use for Embeddings /
 * Delete. Binding goes through the existing `ai.bindings` path and [onEmbedBindingChanged] (reindex, generation swap).
 */
class LocalModelsSource(
    private val catalog: List<LocalModel>,
    private val gateway: LocalModelGateway,
    private val ai: AiGraph,
    private val onEmbedBindingChanged: () -> Unit,
    private val mobileData: MobileDataSetting,
    val abiSupported: Boolean,
    private val loadFailures: LoadFailures = LoadFailures.None,
    private val swap: SwapRollback = SwapRollback.None,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ListSource {
    override val title = "On-device models"
    override val searchHint = "Search models"
    override val sortable = false
    override val statusLabels = mapOf(ItemStatus.Done to "Downloaded", ItemStatus.InProgress to "Downloading", ItemStatus.ActionNeeded to "Download")
    override val doneSection = "On this phone"
    override val actionSection = "Available"
    override val actionIcon: ImageVector = Icons.Rounded.Download
    override val changes: Flow<Unit> = merge(*catalog.map { m -> gateway.state(m) }.toTypedArray()).map { }

    private val headerState = MutableStateFlow(LocalModelsHeader())
    val header: StateFlow<LocalModelsHeader> = headerState.asStateFlow()

    private class Row(val model: LocalModel, val state: LocalModelState, val bound: Boolean, val failures: Int)
    @Volatile private var rows: Map<String, Row> = emptyMap()

    override suspend fun load(): List<ListItem> {
        val loaded = withContext(io) {
            val embed = ai.bindings.get(Role.EMBED)?.endpointId
            catalog.map { m -> Row(m, gateway.state(m).first(), embed == endpointIdFor(m), loadFailures.of(m.id)?.consecutive ?: 0) }
        }
        val metered = withContext(io) { mobileData.allowed() }
        val (rollback, impact) = withContext(io) { swap.available() to swap.impact() }
        rows = loaded.associateBy { it.model.id }
        headerState.value = LocalModelsHeader(metered, loaded.any { it.state == LocalModelState.Ready && it.failures > 0 }, rollback, impact)
        return loaded.map(::item)
    }

    private fun item(r: Row): ListItem {
        val (status, text) = when (val s = r.state) {
            LocalModelState.Absent -> if (abiSupported) ItemStatus.ActionNeeded to "Download" else ItemStatus.None to "Unavailable"
            is LocalModelState.Downloading -> ItemStatus.InProgress to (s.fraction?.let { "Downloading ${(it * 100).toInt()} %" } ?: "Downloading")
            LocalModelState.Ready -> ItemStatus.Done to when {
                r.failures >= RELOAD_AFTER -> REDOWNLOAD
                r.bound -> "In use"
                else -> "Downloaded"
            }
            is LocalModelState.Failed -> (if (abiSupported) ItemStatus.ActionNeeded else ItemStatus.None) to s.message
        }
        val m = r.model
        return ListItem(m.id, m.title, subtitle = "${size(m)} \u00b7 ${m.languages} \u00b7 ~${m.ramEstimateMb} MB RAM" + (m.note?.let { " \u00b7 $it" } ?: ""), icon = Icons.Rounded.Memory, status = status, trailingText = text)
    }

    override fun actionFor(item: ListItem): ItemAction? {
        val r = rows[item.id] ?: return null
        return when (r.state) {
            LocalModelState.Absent, is LocalModelState.Failed -> if (abiSupported) downloadAction(r.model, again = false) else null
            is LocalModelState.Downloading -> watchAction(r.model)
            LocalModelState.Ready -> when {
                r.failures >= RELOAD_AFTER -> if (abiSupported) downloadAction(r.model, again = true) else deleteAction(r)
                r.bound -> deleteAction(r)
                else -> useAction(r.model)
            }
        }
    }

    override fun moreActionsFor(item: ListItem): List<NamedAction> {
        val r = rows[item.id] ?: return emptyList()
        if (r.state != LocalModelState.Ready) return emptyList()
        val primaryIsDelete = r.bound && r.failures < RELOAD_AFTER || (r.failures >= RELOAD_AFTER && !abiSupported)
        return if (primaryIsDelete) emptyList() else listOf(NamedAction("Delete", deleteAction(r)))
    }

    /** Saved for later downloads, and applied to any download already queued or running (spec section 4). */
    fun setAllowMetered(allowed: Boolean) {
        headerState.value = headerState.value.copy(allowMetered = allowed)
        mobileData.set(allowed)
        rows.values.filter { it.state is LocalModelState.Downloading }.forEach { gateway.setAllowMetered(it.model, allowed) }
    }

    private fun downloadAction(m: LocalModel, again: Boolean): ItemAction {
        val cancelled = MutableStateFlow(false)
        val network = if (headerState.value.allowMetered) "" else " over Wi-Fi"
        return ItemAction(
            confirmTitle = if (again) "Download ${m.title} again?" else "Download ${m.title}?",
            confirmMessage = (if (again) "It couldn't load, so the file is replaced with a fresh copy. " else "") +
                "${size(m)} download$network. It uses ~${m.ramEstimateMb} MB of RAM while it runs.",
            confirmLabel = "Download",
            blocking = true,
            onCancel = { cancelled.value = true; gateway.cancel(m) },
        ) { progress ->
            withContext(io) {
                if (again) gateway.delete(m)
                gateway.enqueue(m, headerState.value.allowMetered)
            }
            await(m, cancelled, progress)
        }
    }

    /** Re-opens the progress of a download already running (a second tap never starts a second one). */
    private fun watchAction(m: LocalModel): ItemAction {
        val cancelled = MutableStateFlow(false)
        return ItemAction(blocking = true, onCancel = { cancelled.value = true; gateway.cancel(m) }) { progress -> await(m, cancelled, progress) }
    }

    private suspend fun await(m: LocalModel, cancelled: StateFlow<Boolean>, progress: (Float?) -> Unit): ActionResult {
        var started = false
        val (end, stopped) = combine(gateway.state(m), cancelled) { s, c -> s to c }.first { (s, c) ->
            if (s is LocalModelState.Downloading) { started = true; progress(s.fraction) }
            c || s == LocalModelState.Ready || s is LocalModelState.Failed || (started && s == LocalModelState.Absent)
        }
        return when {
            stopped -> ActionResult.Message("Download cancelled")
            end == LocalModelState.Ready -> ActionResult.Message("${m.title} downloaded")
            end is LocalModelState.Failed -> ActionResult.Failed(end.message)
            else -> ActionResult.Message("Download stopped")
        }
    }

    private fun useAction(m: LocalModel) = ItemAction(
        confirmTitle = "Use ${m.title} for Embeddings?",
        confirmMessage = "Search rebuilds its index with this model in the background, with no network. Search keeps working meanwhile." + impactText(headerState.value.impact),
        confirmLabel = "Use for Embeddings",
    ) { useForEmbeddings(m) }

    private fun deleteAction(r: Row) = ItemAction(
        confirmTitle = "Delete ${r.model.title}?",
        confirmMessage = (if (r.bound) "$KEYWORDS_ONLY. " else "") + "The ${size(r.model)} file is removed from this phone.",
        confirmLabel = "Delete",
    ) { delete(r.model) }

    /**
     * Creates or updates the endpoint `local_<id>` and binds EMBED to it. [onEmbedBindingChanged] runs once per real
     * change: a new binding, or a new model file for the bound endpoint (a new space). The same binding again is a no-op.
     */
    suspend fun useForEmbeddings(m: LocalModel): ActionResult {
        val outcome = withContext(io) {
            val id = endpointIdFor(m)
            val before = ai.endpoints.byId(id)
            val endpoint = Endpoint(id, "On-device \u00b7 ${m.title}", EndpointKind.LLAMACPP_LOCAL, gateway.fileFor(m).absolutePath, null,
                listOf(m.id), setOf(Capability.EMBEDDINGS), before?.createdAt ?: System.currentTimeMillis())
            if (before != endpoint && ai.bindings.saveEndpoint(endpoint, apiKey = null).isFailure) return@withContext UseOutcome.FAILED
            val current = ai.bindings.get(Role.EMBED)
            val sameBinding = current?.endpointId == id && current.model == m.id
            val newSpace = before == null || before.kind != endpoint.kind || before.baseUrl != endpoint.baseUrl || before.apiVersion != endpoint.apiVersion
            when {
                !sameBinding -> {
                    val keepFallback = current?.fallbackEndpointId?.takeIf { it != id }
                    val next = RoleBinding(Role.EMBED, id, m.id, current?.temperature, current?.maxTokens, keepFallback, current?.fallbackModel.takeIf { keepFallback != null })
                    if (ai.bindings.set(next).isFailure) UseOutcome.FAILED else UseOutcome.CHANGED
                }
                newSpace -> UseOutcome.CHANGED
                else -> UseOutcome.UNCHANGED
            }
        }
        return when (outcome) {
            UseOutcome.FAILED -> ActionResult.Failed("Couldn't set up ${m.title} for Embeddings")
            UseOutcome.UNCHANGED -> ActionResult.Message("${m.title} is already in use")
            UseOutcome.CHANGED -> { onEmbedBindingChanged(); ActionResult.Message("Using ${m.title} for Embeddings. Search is reindexing.") }
        }
    }

    /** Brings the retired index back (within 7 days of the swap); the page reloads through [changes]-driven refresh by the caller. */
    suspend fun rollBack(): ActionResult {
        val ok = withContext(io) { swap.rollBack() }
        if (ok) headerState.value = headerState.value.copy(rollbackAvailable = false)
        return if (ok) ActionResult.Message("Rolled back to the previous search index") else ActionResult.Failed("The previous search index is no longer available")
    }

    private enum class UseOutcome { CHANGED, UNCHANGED, FAILED }

    /** Unbinds EMBED first (search goes lexical), then deletes the endpoint, then the file (spec section 5). */
    suspend fun delete(m: LocalModel): ActionResult {
        val id = endpointIdFor(m)
        val unbound = withContext(io) {
            val b = ai.bindings.get(Role.EMBED)
            when {
                b?.endpointId == id -> { ai.bindings.clear(Role.EMBED); true }
                b?.fallbackEndpointId == id -> { ai.bindings.set(b.copy(fallbackEndpointId = null, fallbackModel = null)); false }
                else -> false
            }
        }
        if (unbound) onEmbedBindingChanged()
        val deleted = withContext(io) {
            if (ai.endpoints.byId(id) != null && ai.bindings.deleteEndpointIfUnused(id).isNotEmpty()) return@withContext false
            gateway.delete(m)
            true
        }
        return if (deleted) ActionResult.Message("${m.title} deleted") else ActionResult.Failed("${m.title} is still in use")
    }

    companion object {
        const val NOT_ARM64 = "On-device models need a 64-bit ARM phone"
        const val KEYWORDS_ONLY = "Search will use keywords only until you pick another embeddings model"
        const val LOAD_BANNER = "On-device model couldn't load. Search is using keywords only."
        const val REDOWNLOAD = "Couldn't load \u2014 re-download"
        /** Spec section 6: the row asks for a re-download after two consecutive load failures. */
        const val RELOAD_AFTER = 2

        /** `local_<id>`, with characters an endpoint id can't hold (EndpointRules: `[A-Za-z0-9_-]`, e.g. the `.` in `v1.5`) as `_`. */
        fun endpointIdFor(m: LocalModel) = "local_" + m.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        const val ROLL_BACK = "Roll back"
        const val RECOVERABLE = "Both can be recovered with Roll back for 7 days."

        /** The recall-loss counts before a swap (spec §F.2); empty when no deleted photo is affected. */
        fun impactText(i: SwapImpact): String = if (!i.any) "" else buildString {
            append("\n\n")
            if (i.reduced > 0) append("${i.reduced} photos will be searchable by text only\n")
            if (i.lost > 0) append("${i.lost} photos will no longer be findable at all\n")
            append(RECOVERABLE)
        }
        fun size(m: LocalModel) = "${(m.totalBytes + 500_000) / 1_000_000} MB"
    }
}

/** The `settings/local-models` destination body. */
@Composable
fun LocalModelsRoute(source: LocalModelsSource, onBack: () -> Unit) {
    val header by source.header.collectAsState()
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        ListScreen(source, onBack = onBack, onMessage = { scope.launch { snack.showSnackbar(it) } }, header = {
            SettingsCard {
                if (!source.abiSupported) NoticeText(LocalModelsSource.NOT_ARM64, error = true, tag = "local_models_abi")
                if (header.loadFailed) NoticeText(LocalModelsSource.LOAD_BANNER, error = true, tag = "local_models_load_failed")
                NoticeText(if (header.allowMetered) "Downloads can use mobile data" else "Downloads use Wi-Fi only", tag = "local_models_network")
                if (header.rollbackAvailable) SecondaryAction(LocalModelsSource.ROLL_BACK, { scope.launch { snack.showSnackbar(source.rollBack().let { (it as? ActionResult.Message)?.text ?: (it as ActionResult.Failed).message }) } }, tag = "local_models_roll_back")
                SwitchRow("Allow mobile data", "Models are large; mobile data may cost you", header.allowMetered, source::setAllowMetered, tag = "local_models_mobile_data")
            }
        })
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}
