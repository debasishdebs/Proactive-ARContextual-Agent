package com.rokid.context.ai.embed.siglip

import com.rokid.context.memory.ImageTextEmbedder
import java.io.File

/**
 * The SigLIP 2 B/16-224 image-text space (milestone Y of plan 2026-11-20-siglip2-integration-spike): the vision tower (milestone V, [SigLipImageEmbedder]) and the text
 * tower (milestone X, [SigLipTextEmbedder]) behind the memory layer's [ImageTextEmbedder]. A photo and a text land in the SAME 768-wide space, both L2-normalised, so their cosine
 * means "this text describes that photo". STANDALONE: NOT registered in LocalModels, the catalog, EmbeddingSpec, PlaceSamplers or any flag. Nothing instantiates it in production.
 *
 * Memory note: the towers load INDEPENDENTLY and lazily (a tower's ORT session is created by its first use). Resident cost once loaded, from the file sizes: vision about 95 MB
 * of weights, text about 283 MB of weights plus the tokenizer vocabulary (about 9 MB), so both towers together are roughly 380 MB (on a device expect more for ORT arenas; UNVERIFIED,
 * needs a device load test). So use them one at a time where possible: the shadow pattern is "open text -> embed all new anchor texts -> [releaseText]" and "open vision -> embed
 * the dwell frame -> [releaseVision]". [releaseText] drops the text session AND the tokenizer vocabulary; [releaseVision] drops the vision session; both are safe to call at any
 * time (the next use reloads). [close] releases both.
 *
 * Batching: one item per ORT run, because the int8 exports quantise activations per tensor and a vector depends on its batchmates (see the two towers' docs).
 * [embedTexts] therefore returns exactly the vectors [embedText] would, one run each.
 *
 * Space identity: [spaceId] is `siglip2-b16-224@ba1f3b0|<visionVariant>+<textVariant>|pooler|bilinear224+lower.eos.pad64|l2|d=768` (see [fingerprint]). Scores from two different ids are
 * never compared. It must NOT go in `embedding_space` / `EmbeddingSpec` (it would become `EmbeddingDao.current()` and break Ask).
 *
 * Shadow-code fit: PlaceShadow takes `suspend () -> ImageTextEmbedder?` and PinReferenceVectors takes an [ImageTextEmbedder], so this class plugs in directly; the existing adapter
 * `ImageSpaceTargetImageText` only wraps the Nomic `ImageSpaceTarget`. No adapter is needed (and none was added); a wiring step supplies `{ SigLipImageTextEmbedder(...) }` as the embedder lambda.
 *
 * Catalog fields a later wiring step needs (do NOT add them yet): three files of one model entry, or two ids (vision, text) plus the tokenizer.
 *   id siglip2-base-p16-224-vision | url [SigLipImageEmbedder.MODEL_URL] | sha256 [SigLipImageEmbedder.MODEL_SHA256] | size 94,553,333 B | licence apache-2.0 (upstream google/siglip2-base-patch16-224;
 *     the onnx-community conversion repo declares NO licence field: ruling plus a NOTICE crediting Google needed) | role PHOTO_SPACE (never bindable as the EMBED text embedder)
 *   id siglip2-base-p16-224-text   | url [SigLipTextEmbedder.MODEL_URL]  | sha256 [SigLipTextEmbedder.MODEL_SHA256]  | size 283,438,275 B | licence as above | role PHOTO_SPACE
 *   tokenizer.model (4,241,003 B) | url https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/ba1f3b0843f24bc5417d38e19c37b287d719b2f4/tokenizer.model | sha256 to be recorded by a human | licence as above
 *   Phone download total is about 382 MB (vision only 94.6 MB is a valid first step: text then reports "no score").
 */
class SigLipImageTextEmbedder(
    private val vision: SigLipImageEmbedder,
    private val text: SigLipTextEmbedder,
) : ImageTextEmbedder, AutoCloseable {
    override val dim: Int get() = DIM
    override val spaceId: String get() = fingerprint(vision.variant, text.variant, text.spaceId)

    override suspend fun embedImage(bytes: ByteArray): FloatArray = vision.embedImage(bytes)

    override suspend fun embedText(text: String): FloatArray = this.text.embedText(text)

    override suspend fun embedTexts(texts: List<String>): List<FloatArray> = text.embedTexts(texts)

    /** Several photos (one ORT run each); a convenience beside the interface for pin-reference and backfill callers. */
    suspend fun embedImages(images: List<ByteArray>): List<FloatArray> = vision.embedImages(images)

    /** Free the vision session; the next [embedImage] reloads it. The text tower is untouched. */
    fun releaseVision() = vision.close()

    /** Free the text session and the tokenizer vocabulary; the next [embedText] reloads them. The vision tower is untouched. */
    fun releaseText() = text.close()

    override fun close() { releaseVision(); releaseText() }

    companion object {
        const val DIM = 768

        /** From the vision variant, the text variant and the text half's own fingerprint (which carries the tokenisation settings, e.g. pad64). */
        internal fun fingerprint(visionVariant: String, textVariant: String, textFingerprint: String): String {
            val pad = Regex("pad\\d+").find(textFingerprint)?.value ?: "pad?"
            return "siglip2-b16-224@ba1f3b0|$visionVariant+$textVariant|pooler|bilinear224+lower.eos.$pad|l2|d=$DIM"
        }

        /** Convenience for a later wiring step: both towers from files, each loading lazily. */
        fun fromFiles(visionOnnx: File, textOnnx: File, tokenizerModel: File): SigLipImageTextEmbedder =
            SigLipImageTextEmbedder(SigLipImageEmbedder(visionOnnx), SigLipTextEmbedder(textOnnx, SigLipTokenizer(tokenizerModel)))
    }
}
