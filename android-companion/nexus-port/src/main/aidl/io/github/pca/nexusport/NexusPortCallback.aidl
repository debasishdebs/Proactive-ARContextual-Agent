package io.github.pca.nexusport;

/** Bridge -> PCA. Inbound events only: a tap on a notice action, the end of a speech, the hub accepting a history-forget request. Carries no text, except the one assist-button transcript. */
oneway interface NexusPortCallback {
    void onAction(int slot, String actionId);
    void onSpeechDone();
    void onForgetAccepted();
    // ---- appended in 2.0 T5 (assist-button ask; additive). The transcript is text the user said: the receiver must never log it. ----
    /** A Nexus speech session opened (true) or ended (false). */
    void onListening(boolean active);
    /** The final transcript of the one utterance. At most once per session. */
    void onAskTranscript(String text);
    /** The session ended without a transcript: an AskFail code (1 DENIED_BUSY, 2 NO_SPEECH, 3 OTHER). */
    void onAskFailed(int reason);
    // ---- appended in the v2 r2 fix (additive) ----
    /** The hub accepted the history wipe requested with [generation] (see NexusPort.forgetHistoryAt). An acknowledgement settles the debt only up to that generation. */
    void onForgetAcceptedAt(long generation);
}
