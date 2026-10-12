package com.rokid.context.domain.summary

class CriticWorker(
    private val confidenceThreshold: Double = 0.70,
) {
    fun validate(summaryId: String, confidence: Double): CriticResult {
        val status = if (confidence < confidenceThreshold) SummaryStatus.NEEDS_REVIEW else SummaryStatus.TRUSTED
        return CriticResult(
            summaryId = summaryId,
            confidence = confidence,
            status = status,
        )
    }
}

enum class SummaryStatus {
    TRUSTED,
    NEEDS_REVIEW,
}

data class CriticResult(
    val summaryId: String,
    val confidence: Double,
    val status: SummaryStatus,
)
