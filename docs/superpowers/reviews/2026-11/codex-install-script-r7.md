**One P1 remains at `16be8115`: false CLEAN after buffer wrap.**

[Lines 473–475](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:473) take the minimum timestamp across **all buffers**. Android [prunes buffers independently](https://android.googlesource.com/platform/system/logging/+/c85ab856b99936c4d4bd893e26ae2f1b06357b31/logd/SerializedLogBuffer.cpp). An old event in a quiet buffer therefore “proves” coverage even after `main` loses an early, caught SQLite failure. Mocked filter probe: old event + six healthy app-tail lines → coverage passes, zero errors; stable PID then permits CLEAN and glasses installation. This remains possible when `-G 16M` fails or the install/scan interval exceeds retention. Coverage must be established per relevant buffer.

Other checks:

- **Format correct:** epoch is a modifier; timestamp/PID/TID occupy fields 1/2/3. Multi-word tags leave those indices intact. [AOSP formatter](https://android.googlesource.com/platform/system/logging/+/refs/heads/main/liblog/logprint.cpp) prefixes every multiline message line. A genuinely unprefixed continuation is silently dropped at line 476; ordinary threadtime stack traces retain prefixes.
- **Margin:** `-2 s` is conservative, but integer truncation of `oldest` weakens it by almost one second. Fixed host/device skew is irrelevant because both timestamps use device time; device clock jumps remain unchecked.
- **Closed:** broadened SQLite signatures passed **60/60** mocked probes; ANR subject matching passed **4/4**. Package boundaries and glasses failure wording are corrected. Phone FAIL/UNVERIFIED exits 3 before glasses.

Read-only filter probes and `bash -n` passed; full temporary-file fixtures were not run. **No adb used.**

NOT READY