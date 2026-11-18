Confirmed by static inspection: `85986a44` closes the r4 P1. No new P1 found from this change.

- `GlassesHintAge` and `CalmSource.GLASSES` are deleted. ContextHub’s receipt stamp cannot admit glasses calm evidence; DrivingFeed only accepts glasses VEHICLE hints.
- Two distinct fresh phone-side sources, one moving, ten-minute vehicle exclusion and heartbeat coverage remain enforced. Monotonic timing and atomic version-check/write remain intact.
- Old-glasses wire/callback coverage survives. Glasses plus zero/one phone source stays UNKNOWN; UNKNOWN drops.

Android tests were not run in this read-only environment.

READY