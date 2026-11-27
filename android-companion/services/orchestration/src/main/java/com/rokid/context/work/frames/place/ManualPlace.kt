package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace

/** Why the Developer "Add place manually" form was not saved (nothing here carries a name, a note or a coordinate). */
enum class ManualFault { NAME, COORDINATES, RADIUS, NOTE, KIND, DUPLICATE }

sealed interface ManualResult {
    data class Saved(val place: UserPlace) : ManualResult
    data class Invalid(val fault: ManualFault) : ManualResult
    data class Refused(val why: PinRefusal) : ManualResult
}

/**
 * Developer "Add place manually" (Phase 1, no photo): a REAL pin at typed coordinates, through the one pin save path ([PlacePinning.createAt]: same validation, same zone refusal with the point itself and the controller's state,
 * same pin list, same lock, same mirror to places.db, same source `manual`). It creates no other data. The values are user data: nothing in this file logs them.
 */
object ManualPlace {
    /** The kinds the form offers: the pin form's list (a person is never one). */
    val KINDS: List<String> = PlacePinning.KINDS
    /** Two pins of the same name (case-insensitive) and kind closer than this are one place. */
    const val DUPLICATE_M = 25.0

    fun defaultRadius(kind: String): Int = PlaceKinds.defaultRadiusM(kind).coerceIn(UserPlace.MIN_RADIUS_M, PlaceKinds.MAX_RADIUS_M)

    /**
     * Reads a pasted "lat, lon" (a maps app: `12.9716, 77.5946`, `12.9716 77.5946`, `12.9716;77.5946`, `(12.9716, 77.5946)`, `12.9716° N, 77.5946° E`, `12.97N 77.59W`). Null = not two numbers, or outside -90..90 / -180..180.
     */
    fun parseCoordinates(text: String): Pair<Double, Double>? {
        val t = text.trim().trim('(', ')', '[', ']').replace('°', ' ').replace('−', '-')
        if (t.isEmpty() || t.length > 80) return null
        val parts = t.split(Regex("\\s*[,;]\\s*|\\s+")).filter { it.isNotEmpty() }.let { raw ->
            // "12.9 N" style: a lone hemisphere letter belongs to the number before it
            val out = ArrayList<String>()
            for (p in raw) if (p.length == 1 && p[0].uppercaseChar() in "NSEW" && out.isNotEmpty() && out.last().last().isDigit()) out[out.lastIndex] = out.last() + p else out += p
            out
        }
        if (parts.size != 2) return null
        val lat = number(parts[0], "NS") ?: return null
        val lon = number(parts[1], "EW") ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private fun number(s: String, hemis: String): Double? {
        var x = s; var sign = 1.0
        val last = x.last().uppercaseChar()
        if (last in "NSEW") { if (last !in hemis) return null; if (last == 'S' || last == 'W') sign = -1.0; x = x.dropLast(1) }
        if (!Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)").matches(x)) return null
        if (sign < 0 && x.startsWith("-")) return null
        return x.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it * sign }
    }

    /** The radius text as a whole number of metres in the allowed range, or null. A blank text is the kind's default. */
    fun parseRadius(text: String, kind: String): Int? {
        if (text.isBlank()) return defaultRadius(kind)
        return text.trim().toIntOrNull()?.takeIf { it in UserPlace.MIN_RADIUS_M..PlaceKinds.MAX_RADIUS_M }
    }

    /** Validates the typed values; the first fault, or null. Pure. */
    fun fault(name: String, note: String, kind: String, coordinates: String, radius: String): ManualFault? = when {
        UserPlace.cleanName(name).isEmpty() || name.trim().length > UserPlace.NAME_MAX -> ManualFault.NAME
        kind !in KINDS -> ManualFault.KIND
        parseCoordinates(coordinates) == null -> ManualFault.COORDINATES
        parseRadius(radius, kind) == null -> ManualFault.RADIUS
        UserPlace.cleanNote(note).length != note.replace(Regex("\\s+"), " ").trim().length -> ManualFault.NOTE
        else -> null
    }

    /** Saves the place as a normal pin. Everything else (zone, validity, list cap, mirror) is the pin path's. */
    suspend fun create(pinning: PlacePinning, name: String, note: String, kind: String, coordinates: String, radius: String): ManualResult {
        fault(name, note, kind, coordinates, radius)?.let { return ManualResult.Invalid(it) }
        val (lat, lon) = parseCoordinates(coordinates)!!
        val form = PinForm(name, kind, parseRadius(radius, kind)!!, null, note, null)
        val clean = UserPlace.cleanName(name)
        if (pinning.pins().any { it.kind == kind && it.name.equals(clean, ignoreCase = true) && Geo.distanceM(lat, lon, it.lat, it.lon) < DUPLICATE_M }) return ManualResult.Invalid(ManualFault.DUPLICATE)
        return when (val r = pinning.createAt(lat, lon, form)) {
            is PinResult.Saved -> ManualResult.Saved(r.place)
            is PinResult.Refused -> ManualResult.Refused(r.why)
        }
    }

    /** What the form says for a fault (no value is repeated). */
    fun faultText(f: ManualFault): String = when (f) {
        ManualFault.NAME -> "Enter a name (up to ${UserPlace.NAME_MAX} characters)."
        ManualFault.COORDINATES -> "Enter the coordinates as \"latitude, longitude\" (for example 12.9716, 77.5946): latitude -90 to 90, longitude -180 to 180."
        ManualFault.RADIUS -> "The radius must be a whole number from ${UserPlace.MIN_RADIUS_M} to ${PlaceKinds.MAX_RADIUS_M} m."
        ManualFault.NOTE -> "The note can be up to ${UserPlace.NOTE_MAX} characters."
        ManualFault.KIND -> "Choose a kind."
        ManualFault.DUPLICATE -> "That place is already pinned."
    }
}
