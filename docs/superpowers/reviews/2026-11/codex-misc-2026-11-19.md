No P1 found.

- **P2 — case variants bypass refusal:** [install-trip-build.sh:140](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:140) ignores lines without the exact `mCallState` casing. `mCallState=0\nMCALLSTATE=2\n` passes, as does an idle line followed by `mcallstatelists={0=2}`. This is pre-existing, but fails the requested case-variant protection. The four new cases pass; extra text, mixed lines, exact-case nonzero states, `mCallStateX=2`, and `mCallStateLists={0=2}` all refuse.

- **P2 — backup promise is too broad:** [PRIVACY.md:11](/Users/<user>/Desktop/rokid/proactive-context-agent/PRIVACY.md:11), repeated at lines 46 and 107, says pins are excluded from *every* backup and only settings export can carry them. Android backup/transfer exclusions are correct, but [install-trip-build.sh:368](/Users/<user>/Desktop/rokid/proactive-context-agent/tools/install-trip-build.sh:368) archives the entire app directory, including pins. Explicitly document this exception.

- **P2 — location-off protection is overstated:** [PRIVACY.md:111](/Users/<user>/Desktop/rokid/proactive-context-agent/PRIVACY.md:111) says location being off always treats the user as inside. [ZoneEvaluator.kt:133](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/core/model/src/main/kotlin/com/rokid/context/core/model/zone/ZoneEvaluator.kt:133) applies that immediately only at APPROACH/INSIDE; an OUTSIDE zone can remain outside until its fix exceeds 15 minutes. Qualify the sentence.

- **P2 — bridge identity description is wrong:** [PRIVACY.md:83](/Users/<user>/Desktop/rokid/proactive-context-agent/PRIVACY.md:83) describes trust in the first bridge key seen. [SignerTrust.kt:29](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/nexus-port/src/main/kotlin/io/github/pca/nexusport/SignerTrust.kt:29) instead checks PCA’s signer, accepted rotation lineage, or explicit pins. There is no first-use trust mechanism.

- **P3 — request description omits public identifiers:** [PRIVACY.md:29](/Users/<user>/Desktop/rokid/proactive-context-agent/PRIVACY.md:29) should include Wikipedia page IDs and Wikidata QIDs. Wikipedia geosearch and Overpass receive cell bounds; subsequent Wikipedia/Wikidata requests receive public IDs. No precise user fix or pin content appears in these request builders.

- **P3 — candidate-source claim is outdated:** [PRIVACY.md:121](/Users/<user>/Desktop/rokid/proactive-context-agent/PRIVACY.md:121) says candidates come from monument classes only. Current code also admits curated landmark QIDs and OSM-tagged places, including lakes and viewpoints. Describe those paths and the explicit person-class rejection.

- **P3 — retained seeded content needs clarification:** [PRIVACY.md:69](/Users/<user>/Desktop/rokid/proactive-context-agent/PRIVACY.md:69) should say Delete all retains seeded **place records**, alongside area definitions and pins. It clears recency and removes unseeded, unpinned fetched records.

The `d99d8ad2` refactor is behaviour-preserving: [HubGraph.kt:270](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/hub/HubGraph.kt:270) passes the preference access inside a lambda, and `gatedPlaceSide(false)` returns CENTRE without invoking it. Both overlay paths retain the same side conversion.

Confirmed: `places.db` is encrypted; all three Android backup sections exclude it and pins; suggestion master/delivery switches default OFF; Nexus is optional and defaults to PCA.

Validation: executed parser probes and checked backup XML. Android tests were not run in this read-only environment.

READY