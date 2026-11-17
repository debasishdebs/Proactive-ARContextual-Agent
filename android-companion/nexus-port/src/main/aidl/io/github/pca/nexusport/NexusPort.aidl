package io.github.pca.nexusport;

import io.github.pca.nexusport.NexusPortCallback;
import io.github.pca.nexusport.NexusSkillHost;

/**
 * PCA phone app -> Nexus bridge. Plain primitives only (no Parcelable, so the wire cannot grow a field by accident).
 * ONLY NexusHudSink calls this (NexusChokePointTest). Every method returns a NexusResult code and never throws for a domain failure.
 * [slot] is a Slot code: 1 notice, 2 pin, 3 activity, 4 surface, 5 speech.
 */
interface NexusPort {
    /** The wire version of this interface (NexusPortVersion.CURRENT). */
    int apiVersion();
    /** Registers the one callback (actions, speech end, history-forget accepted). Null clears it. */
    void setCallback(NexusPortCallback callback);
    /** Feature bits the user switched on in PCA (NexusFeatures). 0 = everything off: the bridge hides all and refuses speech. Default in the bridge: 0. */
    int setFeatures(int mask);
    /** Shows a notice (slot 1) or a pin (slot 2). actionIds/actionLabels are parallel arrays (notice only). */
    int show(int slot, String title, String text, long ttlMs, boolean wake, in String[] actionIds, in String[] actionLabels);
    /** Hides what [slot] shows. Idempotent. */
    int hide(int slot);
    /** Speaks [text] on the glasses (needs the TTS feature bit). One voice: a new line preempts. */
    int speak(String text);
    /** Stops the speech now. Idempotent. */
    int stopSpeech();
    /** Asks the hub to forget every notice this bridge showed (history). The callback says when it is accepted. */
    int forgetHistory();
    // ---- appended in 2.0 T5/T6 (additive: the transaction ids above are unchanged) ----
    /** Stops the Nexus speech-to-text session of an assist-button ask now (a trip). Idempotent. */
    int stopListening();
    /** Registers the phone's skill host (T6), or null to clear it. The bridge answers skill calls ONLY through it; with none set every skill call fails with the domain code `unavailable`. */
    int setSkillHost(NexusSkillHost host);
}
