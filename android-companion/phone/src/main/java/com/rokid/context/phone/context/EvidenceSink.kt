package com.rokid.context.phone.context

import com.rokid.context.core.model.context.Evidence

/** Where a source delivers its facts. Safe to call from any thread: the hub only enqueues. */
fun interface EvidenceSink { fun accept(e: Evidence) }
