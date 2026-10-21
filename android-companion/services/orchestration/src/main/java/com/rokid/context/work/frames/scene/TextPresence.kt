package com.rokid.context.work.frames.scene

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How many words of printed text a photo carries; feeds the salience score. Recognised text is never kept or logged. */
fun interface TextPresence {
    /** The word count, or null when it couldn't run (no model, decode failure, timeout). */
    suspend fun words(jpeg: ByteArray): Int?
}

/**
 * ML Kit bundled Latin recogniser. A word is a whitespace-separated token with at least two letters.
 * [recognize] is the test seam: it returns the raw recognised text, which is counted and dropped here.
 */
class MlKitTextPresence(
    private val timeoutMs: Long = 2_000L,
    private val recognize: suspend (ByteArray) -> String? = ::recognizeWithMlKit,
) : TextPresence {
    override suspend fun words(jpeg: ByteArray): Int? {
        val text = try {
            withTimeoutOrNull(timeoutMs) { recognize(jpeg) }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        } ?: return null
        return countWords(text)
    }

    companion object {
        internal fun countWords(text: String): Int =
            text.split(Regex("\\s+")).count { token -> token.count { it.isLetter() } >= 2 }

        private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

        private suspend fun recognizeWithMlKit(jpeg: ByteArray): String? {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            try {
                return suspendCancellableCoroutine { cont ->
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                }
            } finally {
                bmp.recycle()
            }
        }
    }
}
