package com.rokid.context.phone.hub.plan

import com.rokid.context.core.model.TraceChunk

/*
 * Seams for classes that belong to LATER slice 3a tasks. Each is a no-op here; the owning task replaces the no-op binding in HubGraph. Nothing in these is a design
 * for the later task: it is only the place where its class plugs in.
 */

/** TODO(Task 13, PhoneRtApplier): applies the phone half (RT_PHONE) of the desired plan on the phone; null = "use settings". */
fun interface PhoneRtApplierSeam { fun apply(planJson: String?) }

/** TODO(Task 18, TraceIntake): receives the glasses' `pc_trace_chunk` stream, stores complete traces and answers `pc_trace_ack`. */
fun interface TraceIntakeSeam { suspend fun onChunk(chunk: TraceChunk) }

object LaterSeams {
    val noPhoneRt = PhoneRtApplierSeam { }
    val noTraceIntake = TraceIntakeSeam { }
    // TODO(LA4, HubTraceRecorder): the hub's fusion evidence recorder (source HUB traces) is not built; nothing records yet.
}
