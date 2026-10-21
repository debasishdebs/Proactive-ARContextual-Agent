package com.rokid.context.glasses.capture.diag

import com.rokid.context.core.model.Overlay

/** Low-priority nudges are not shown while the screen is off (they would only light it for nothing); everything else is. */
object PopupGate {
    fun show(o: Overlay?, interactive: Boolean, nudgesAllowed: Boolean = true): Boolean = o !is Overlay.Nudge || (interactive && nudgesAllowed)
}
