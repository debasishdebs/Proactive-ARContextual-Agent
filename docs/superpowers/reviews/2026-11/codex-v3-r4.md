- **P1 remains — stale hints can become fresh calm evidence.** [GlassesHintAge.kt:20](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/context/GlassesHintAge.kt:20) treats the minimum observed offset as a trusted baseline. A stream consistently delayed five minutes returns `null, 0, 0…`, admitting over-two-minute hints as fresh. With another fresh calm source and sufficient coverage, this permits NOT_DRIVING. Require a bounded clock baseline or keep age unknown.

- **Other findings closed by static inspection:** monotonic tracker windows; version validation and write under the tracker lock; two different fresh sources, at least one moving. `store.add` is synchronous—no suspending-store deadlock found.

- **Confirmed:** protocol remains v1; old phones ignore the additive field; missing `sessionStartMs` never supplies glasses calm evidence. Restart begins UNKNOWN and blocks. Insufficient sources can keep shadow recording paused; that usability limitation is P2.

Static review plus arithmetic reproduction; Android tests not run.

NOT READY