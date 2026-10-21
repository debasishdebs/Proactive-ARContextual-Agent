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
        val pkg = callingPackage
        val r = WatchMotionIntake.current?.accept(pkg, { sha -> certMatches(pkg, sha) }, extras?.getString("json"))
            ?: WatchMotionIntake.Result(false, "not_enabled")
        return Bundle().apply { putBoolean("ok", r.ok); putString("reason", r.reason) }
    }

    private fun certMatches(pkg: String?, sha: String): Boolean {
        val pm = context?.packageManager ?: return false
        val digest = hexToBytes(sha) ?: return false
        return try { pm.hasSigningCertificate(pkg ?: return false, digest, PackageManager.CERT_INPUT_SHA256) } catch (e: Exception) { false }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        const val METHOD = "motion_state"
        const val AUTHORITY = "com.rokid.context.motionsource"

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
