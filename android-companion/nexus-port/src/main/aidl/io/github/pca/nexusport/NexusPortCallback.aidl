package io.github.pca.nexusport;

/** Bridge -> PCA. Inbound events only: a tap on a notice action, the end of a speech, the hub accepting a history-forget request. Carries no text. */
oneway interface NexusPortCallback {
    void onAction(int slot, String actionId);
    void onSpeechDone();
    void onForgetAccepted();
}
