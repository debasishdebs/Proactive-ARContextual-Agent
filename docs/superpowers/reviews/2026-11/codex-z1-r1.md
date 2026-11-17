Found four P1 issues:

- **Startup fails open.** [AskZoneFence.kt:53](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AskZoneFence.kt:53) treats an absent controller callback as clear; the new process’s fence also starts open. A queued Ask can therefore send stored text after process death, before HubGraph initializes protection—even with a persisted zone pause. The known deviation is a real hole. Initialize protection independently of the hub and include persisted records, failing closed until checked.

- **Model dispatch has a polling window.** [AnswerService.kt:383](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/AnswerService.kt:383) checks every 100 ms. Prompt/database reads and router work can suspend after the preceding check; dispatch or a validation retry can occur after zone entry but before cancellation. Ask’s `FrameLiveness` does not check zones. Fence each provider dispatch and register synchronous cancellation.

- **Delivery checks precede suspensions.** [AppAnswerer.kt:51](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/voice/AppAnswerer.kt:51) checks before `offNotice`/`answerText` suspend. Typed mirroring similarly checks before launching its database read. Entry during either read still permits HUD publication and Bluetooth speech; Hub’s answer overlay has no final zone filter. Recheck at delivery and fence pending delivery work.

- **Recovery backup can lose data.** [v17-upgrade-recovery.md:19](/Users/<user>/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/handoffs/v17-upgrade-recovery.md:19) archives a running database. Concurrent WAL checkpointing/writes can produce an inconsistent snapshot that passes the filename/size checks; rollback then deletes the current database. Force-stop before backup, validate the snapshot, and preserve the broken state **before** replacement.

Other checks: G1’s `NOT_ADMITTED`/`FAILED` paths produce no nearby card. Room testing and schema assets are confined to test configurations. Zone refusal mapping, `canvas_eval` guarding, and terminal queue handling are present; no unconditional outside-zone lockout found. The recovery doc correctly forbids uninstall/clear-data.

Static review only; tests were not run in the read-only workspace.

NOT READY