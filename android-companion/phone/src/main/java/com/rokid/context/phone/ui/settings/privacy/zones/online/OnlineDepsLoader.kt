package com.rokid.context.phone.ui.settings.privacy.zones.online

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.rokid.context.phone.ui.settings.privacy.zones.tiles.OnlineMapDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** review2 #7: the street-map wiring while it is being built off Main ([loading]) and once it is ready ([deps]; null when the screen has no street map at all). */
class OnlineDeps(val deps: OnlineMapDeps?, val loading: Boolean)

/**
 * Builds the map wiring on IO (it reads the encrypted key vault, a cold Keystore can take long) and exposes loading / ready to Compose. A new [key] (a saved or cleared map key) builds it again; until then the screen shows what it
 * has without a street map. [make] null = no street map on this screen.
 */
@Composable
fun rememberOnlineDeps(make: (() -> OnlineMapDeps)?, key: Any? = null): OnlineDeps {
    if (make == null) return OnlineDeps(null, false)
    val state by produceState(OnlineDeps(null, true), make, key) {
        value = OnlineDeps(null, true)
        value = OnlineDeps(try { withContext(Dispatchers.IO) { make() } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }, false)
    }
    return state
}
