package com.rokid.context.glasses.log

import com.rokid.context.core.model.Hello
import com.rokid.context.glasses.capture.trace.TraceStateReporter

/**
 * LGC6-min producer: fills the additive `Hello.diagFlagState` and `Hello.traceBlocker` from the REAL state. Both are fixed-vocabulary words, never free text.
 *
 * [trace] is null while the recorder has not been built yet (nothing has needed it): the producer then never constructs it (the caller is the link thread) and reports the
 * flag for an epoch that is not loaded ([DiagRecord.stateFor] with false: `armed_no_epoch` when the phone asked, else `unprovisioned`) and no blocker. Memory reads only.
 */
fun Hello.withDiagFields(diag: DiagRecord, trace: TraceStateReporter?): Hello = copy(
    diagFlagState = (if (trace != null) diag.state() else diag.stateFor(false)).wire,
    traceBlocker = trace?.blocker()?.takeIf { it in TraceStateReporter.VOCABULARY },
)
