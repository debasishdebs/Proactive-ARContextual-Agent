package com.rokid.context.vision.local

/** Fixed knobs of the offline captioner (owner decision 2026-11-26, round 5; spike results offline-vlm-spike-2026-11-26.md). */
object CaptionConfig {
    /** Where the photo was taken: the kind of place, the main objects and the decor, so the text can be ranked against place descriptions (V2). */
    const val PROMPT = "Describe where this photo was taken: the kind of place (home, museum gallery, fort, show, restaurant), the main objects, and the decor, in two short sentences."

    /** The marker mtmd replaces with the image tokens; one per image, in front of the prompt text. */
    const val MEDIA_MARKER = "<__media__>"

    const val MAX_TOKENS = 48

    /** Longest side of the image handed to the model, in px (the glasses frames are about 480x640, so they are not scaled up). */
    const val MAX_SIDE_PX = 640

    /** llama.cpp threads. The spike's fastest setting on the S25 class; change here only. */
    const val THREADS = 6

    /** The model and the native context are released this long after the last caption, so the phone cools. */
    const val IDLE_RELEASE_MS = 5_000L

    /** Below this battery level (percent) and not charging, no caption runs. */
    const val MIN_BATTERY_PCT = 20

    /** `PowerManager.THERMAL_STATUS_SEVERE`; at or above it no caption runs. */
    const val THERMAL_SEVERE = 3
}
