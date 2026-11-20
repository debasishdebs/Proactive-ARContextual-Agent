package com.rokid.context.connectors.sources

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.memory.MemoryGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** The data sources' process-wide graph (R-64). */
interface SourcesGraphApi {
    val provider: ContextSourceProvider

    /** Settings › Data sources. Reading it the first time reads prefs: do that off Main. */
    val settings: SourceSettingsStore get() = throw UnsupportedOperationException()

    /** Sources that are on but miss a permission now. */
    fun needsPermission(): StateFlow<Set<SourceKind>> = throw UnsupportedOperationException()

    /** A rough place for a new frame, only when LOCATION is on and granted; else null. Never throws. */
    suspend fun placeLabel(nowMs: Long): String? = null
}

/** Real sources: calendar, contacts, notes, location. Nothing is built or read until first use. */
class SourcesRuntime(
    private val newSettings: () -> SourceSettingsStore,
    private val sources: List<ContextSource>,
    private val granted: (String) -> Boolean,
) : SourcesGraphApi {
    private val store by lazy(newSettings)
    private val enabled by lazy { EnabledSources(sources, store, granted, Dispatchers.IO) }

    override val settings: SourceSettingsStore get() = store
    override val provider: ContextSourceProvider = ContextSourceProvider { q, now -> withContext(Dispatchers.IO) { enabled.lines(q, now) } }
    override fun needsPermission(): StateFlow<Set<SourceKind>> = enabled.needsPermission()

    override suspend fun placeLabel(nowMs: Long): String? = withContext(Dispatchers.IO) {
        val location = sources.firstOrNull { it.kind == SourceKind.LOCATION } as? LocationSource ?: return@withContext null
        if (SourceKind.LOCATION !in store.enabled.value || !location.permissions.all(granted)) return@withContext null
        location.placeLabel(nowMs)
    }
}

object SourcesGraph {
    @Volatile private var instance: SourcesGraphApi? = null

    fun get(context: Context): SourcesGraphApi = instance ?: synchronized(this) { instance ?: build(context.applicationContext).also { instance = it } }

    fun resetForTests(api: SourcesGraphApi?) { synchronized(this) { instance = api } }

    private fun build(app: Context): SourcesGraphApi {
        val memory by lazy { MemoryGraph.get(app) }
        val cr = app.contentResolver
        val calendar = CalendarSource.resolver(cr)
        val contacts = ContactsSource.resolver(cr)
        return SourcesRuntime(
            newSettings = { SourceSettingsStore(app.getSharedPreferences(SourceSettingsStore.PREFS, Context.MODE_PRIVATE)) },
            sources = listOf(
                CalendarSource(ZoneId.systemDefault()) { from, to -> calendar(from, to) },
                NotesSource({ memory.shopping.open(10).map { ShoppingLineRow(it.name, it.qty, it.unit) } }, { memory.notes.recent(5).map { it.text } }),
                ContactsSource { tokens -> contacts(tokens) },
                LocationSource(
                    LocationSource.lastKnown(app),
                    // the Geocoder is a remote call: never under Offline only, and only when the data saver allows a small remote call
                    LocationSource.gated(LocationSource.geocoder(app), { com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() }, { com.rokid.context.ai.datasaver.DataSaverGraph.get(app) }),
                    Dispatchers.IO,
                ),
            ),
            granted = { p -> ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED },
        )
    }
}
