package com.rokid.context.domain.queue

enum class QueuePriority(private val order: Int) {
    PRIORITY_REPROCESS(0),
    EXPLICIT_WRITE(1),
    READ(2),
    SUMMARIZER(3),
    FRAME_INGEST(4),
    RETRY(5),
    ;

    companion object {
        fun arbitrate(vararg priorities: QueuePriority): List<QueuePriority> =
            priorities
                .distinct()
                .sortedBy { it.order }

        fun arbitrate(priorities: Iterable<QueuePriority>): List<QueuePriority> =
            priorities
                .distinct()
                .sortedBy { it.order }
    }
}
