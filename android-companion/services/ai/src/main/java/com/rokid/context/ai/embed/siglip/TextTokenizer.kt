package com.rokid.context.ai.embed.siglip

/**
 * Result of tokenising one string for a text tower: [ids] and [attentionMask] always have the same length
 * (the tokenizer's fixed max length for fixed-length models such as SigLIP 2). `attentionMask[i] == 1L` for real
 * tokens (including the final EOS) and `0L` for right padding.
 */
class EncodedText(val ids: LongArray, val attentionMask: LongArray) {
    init { require(ids.size == attentionMask.size) { "ids/mask length mismatch" } }
    override fun equals(other: Any?) =
        other is EncodedText && ids.contentEquals(other.ids) && attentionMask.contentEquals(other.attentionMask)
    override fun hashCode() = 31 * ids.contentHashCode() + attentionMask.contentHashCode()
    override fun toString() = "EncodedText(ids=${ids.size})"
}

/** A text -> token ids function. SigLIP 2 (SentencePiece BPE) implements it; Nomic's WordPiece could later. */
interface TextTokenizer {
    /** Fixed output length of [encode]. */
    val maxLength: Int
    fun encode(text: String): EncodedText
}
