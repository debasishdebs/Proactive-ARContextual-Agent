At `7ccc25d4`, both original P1s are closed: marker-parser failures refuse; failed log retrieval cannot pass verification. Exit 3 remains nonzero, so a caller checking `!= 0` treats it as failure.

**One remaining P1 — serial aliases bypass watch isolation.** [Serial checks](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:214) and [install routing](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:329) compare strings only. A phone’s USB serial and Wi-Fi endpoint can be supplied as distinct phone/watch targets. With matching signatures and an acceptable versionCode, `watch-debug.apk` can replace the phone app because they share the applicationId. Isolated mocks confirmed both guards accept this routing. Resolve physical device identities and reject duplicates or uncertain identity before installing.

No other P1 found in the requested guards. The docs’ universal “exit 2” claim is inaccurate, but exit 3 does not create success for a zero/nonzero caller.

`bash -n` and isolated mock checks passed. No adb ran.

NOT READY