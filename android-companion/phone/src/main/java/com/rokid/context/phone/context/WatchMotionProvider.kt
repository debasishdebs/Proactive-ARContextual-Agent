package com.rokid.context.phone.context

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Exported entry for the LiveFit forwarder (spec §8): `ContentResolver.call(content://com.rokid.context.motionsource, "motion_state",
 * null, extras{json})`. Everything is decided by [WatchMotionIntake] (caller package, pinned signing certificate, source on,
 * size, seq, rate); with no intake published (hub not built, source off) the answer is `not_enabled`. Nothing else is served.
 */
class WatchMotionProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD) return null
        // Caller first (spec §8): a stranger learns nothing about the source's state and its extras are never unparcelled.
        val pkg = callingPackage
        if (pkg != WatchMotionIntake.LIVEFIT_PACKAGE) return reply(false, "bad_caller")
        return decide(pkg, extras?.getString("json")) { p, d -> hasCert(p, d) }
    }

    private fun hasCert(pkg: String, digest: ByteArray): Boolean {
        val pm = context?.packageManager ?: return false
        return try { pm.hasSigningCertificate(pkg, digest, PackageManager.CERT_INPUT_SHA256) } catch (e: Exception) { false }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        const val METHOD = "motion_state"
        const val AUTHORITY = "com.rokid.context.motionsource"

        private fun reply(ok: Boolean, reason: String?) = Bundle().apply { putBoolean("ok", ok); putString("reason", reason) }

        /** The whole decision, with the platform certificate check behind [hasCert] (package, digest bytes) so it can be faked. */
        internal fun decide(pkg: String?, json: String?, hasCert: (String, ByteArray) -> Boolean): Bundle {
            if (pkg != WatchMotionIntake.LIVEFIT_PACKAGE) return reply(false, "bad_caller")
            val intake = WatchMotionIntake.current ?: return reply(false, "not_enabled")
            val r = intake.accept(pkg, { sha -> hexToBytes(sha)?.let { hasCert(pkg, it) } ?: false }, json)
            return reply(r.ok, r.reason)
        }

        /** "AB:cd:…" or plain hex → bytes; null when not a whole number of hex pairs. */
        fun hexToBytes(s: String): ByteArray? {
            val h = s.filter { it != ':' && !it.isWhitespace() }
            if (h.isEmpty() || h.length % 2 != 0) return null
            return ByteArray(h.length / 2) { i ->
                val hi = Character.digit(h[2 * i], 16); val lo = Character.digit(h[2 * i + 1], 16)
                if (hi < 0 || lo < 0) return null
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
