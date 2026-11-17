package io.github.pca.nexusport;

/**
 * Bridge -> PCA phone app, SYNCHRONOUS (T6). The bridge calls it from a worker thread and waits at most 12 s (Nexus gives a skill 15 s). [opId] is one of the four skill ids
 * (status, pause_capture, resume_capture, mark_moment). Returns "ok:<state>" (state in capturing, paused, zone_paused, stopped) or "fail:<code>" (a lowercase domain code such as pause_zone, paused,
 * not_worn, disabled, unavailable, failed). Never returns memory content: skills are action-only.
 */
interface NexusSkillHost {
    String invoke(String opId);
    /**
     * Appended in the P1-2 fix (additive). SYNCHRONOUS: the bridge asks BEFORE it opens a Nexus speech session for an assist-button ask. True only while the ask switches are on and the phone's gates (mode, worn, zone
     * fences, pause) are open; false otherwise. A bridge that cannot get an answer must not open the session.
     */
    boolean authorizeListen();
}
