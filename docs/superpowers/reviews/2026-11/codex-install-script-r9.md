**R8’s P1 is closed at `6e71e6ff`. No P1 under your stated criteria.**

Read the unchanged commit, ran `bash -n`, and executed in-memory coverage, classifier and control-flow probes. **No adb.** Full file-backed fixtures were unavailable in the read-only sandbox.

- **Coverage:** consumed-based shortcut and `logcat -g` parsing are gone. Empty/separator-only buffers pass; non-empty buffers require a parseable minimum epoch, floored, `<= start−3`. Boundary `997.999` against start `1000` passes; `998.000`, a pruned healthy tail, and unparseable output reject coverage. No universal healthy-install parsing blocker found.
- **Failure/gating:** caught SQLite errors and unprefixed SQLite continuations match. Mocked FAIL and UNVERIFIED paths exit **3**, with only the phone install reached; CLEAN permits glasses.

Remaining **P2s**:

- **FIFO is conditional.** [SerializedLogBuffer](https://android.googlesource.com/platform/system/logging/+/c85ab856b99936c4d4bd893e26ae2f1b06357b31/logd/SerializedLogBuffer.cpp) capacity-prunes oldest chunks per log ID, without chatty prune rules. [ChattyLogBuffer](https://android.googlesource.com/platform/system/logging/+/64367595/logd/ChattyLogBuffer.cpp) can remove newer records of selected/noisy UIDs while older others survive. A quiet app UID is not normally the automatic worst-UID target, but explicit UID/PID rules or protection of older others can invalidate that assurance. Pruning is UID/PID-based, not error-priority protection. Qualify the “sound FIFO” comment.
- **Chatty summaries are not treated as unknown.** Probe: `chatty … identical 8 lines` produces no failure match. Duplicate collapsing ordinarily retains the initial original, so collapsing alone does not erase a retained SQLite signature; pruning/windowing can remove that original. Cheap mitigation: relevant app `identical`/`expire` summaries → UNVERIFIED. [AOSP implementation](https://android.googlesource.com/platform/system/logging/+/64367595/logd/ChattyLogBuffer.cpp).
- **External clears:** empty does not prove historical coverage after another actor clears the buffer; UID-specific clearing can also leave older unrelated records. This is a logcat evidence limit. Document the assumption; continuous capture begun before installation reduces exposure.
- **Clock changes:** rollback can place an actual SQLite error below the `start−2` scan cutoff; the probe confirmed exclusion. Cheap partial mitigation: compare device realtime elapsed with uptime elapsed and reject discontinuities.
- **Short retention:** normally, retained main history extending before `start−3` passes. A chatty phone retaining less history correctly returns UNVERIFIED; retention must cover the **whole install plus scan window**. Repeating “use `-G 16M`” after already attempting it is inadequate recovery advice. Recommend reducing log noise and performing manual section-3 verification; larger buffers or continuous capture may help. Glasses remain blocked.

READY