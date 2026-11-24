package com.rokid.context.phone.place

import android.content.Context
import com.rokid.context.phone.ui.home.LastPlaceCard
import com.rokid.context.phone.ui.home.LastPlaceCards
import com.rokid.context.phone.ui.home.PlaceCardHomeSource
import com.rokid.context.work.frames.place.PlaceGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.ZoneId

/**
 * Home's "Last place card" (B2): polls the newest row of the phone-only card history every few seconds while Home is collected. It never creates `places.db` (a phone that never used Place Guide has no file: nothing is shown),
 * and a read failure shows nothing. Reads happen on IO.
 */
class AndroidPlaceCardHomeSource(
    private val app: Context,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val everyMs: Long = 4_000,
) : PlaceCardHomeSource {
    override val last: Flow<LastPlaceCard?> = flow {
        while (true) {
            emit(runCatching { PlaceGraph.existingHistory(app)?.recent(1)?.firstOrNull()?.let { LastPlaceCards.of(it, now(), zone()) } }.getOrNull())
            delay(everyMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
}
