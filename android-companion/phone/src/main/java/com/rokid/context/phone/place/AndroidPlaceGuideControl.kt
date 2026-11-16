package com.rokid.context.phone.place

import android.content.Context
import com.rokid.context.memory.place.GeoBox
import com.rokid.context.phone.ui.settings.place.PlaceGuideControl
import java.util.Locale

/** The production [PlaceGuideControl]: the switch in `place_guide` prefs and the process-wide pack. [changed] re-checks the hub's foreground location type after the switch moves. */
class AndroidPlaceGuideControl(private val app: Context, private val changed: (Context) -> Unit) : PlaceGuideControl {
    private val settings = com.rokid.context.work.frames.place.PlaceGuideSettings.of(app)
    override var enabled: Boolean
        get() = settings.enabled
        set(v) { settings.enabled = v; changed(app) }

    override suspend fun seed(box: GeoBox): Result<String> = runCatching {
        val id = String.format(Locale.ROOT, "seed:%.3f,%.3f,%.3f,%.3f", box.south, box.west, box.north, box.east)
        val r = com.rokid.context.work.frames.place.PlaceGraph.pack(app).seedArea(id, box)
        if (r.complete) "Seeded ${r.cells} cells, ${r.places} places in the pack." else "Seeded ${r.cells - r.failed} of ${r.cells} cells; ${r.failed} failed. Try again when online."
    }

    override suspend fun summary(): String {
        val (n, bytes) = com.rokid.context.work.frames.place.PlaceGraph.pack(app).size()
        return "$n places in the pack (${bytes / 1024} KB)"
    }
}
