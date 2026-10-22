# Review: slice 3a Tasks 14 (3f0aa729) and 15 (bfbb9856), base 138ebb27

Reviewer: Opus, read-only. Inputs: plan (preamble, Round 3, S85/S85a, Global Constraints, Tasks 14, 15, 31, 32, 38), briefs, reports, review diff, Live Fit @ 4529735, design-options §4.4.
Method: I ported the parser into a Python replica (scratchpad only) to run about 75 adversarial utterances and timing probes. No Gradle, no adb, no edits to the worktree except this file.

## Verdicts

| Task | Spec compliant | Quality approved |
|---|---|---|
| 14 | No (2 Important) | No (needs fixes) |
| 15 | No (1 Important) | No (needs fixes) |

## Task 14: Spec compliance

- ✅ `MarkPhrases` is the single source: MARK, RETRO, UNDO, INTENTS(word, canvasLabel, intentLabel: String) and QUESTION_WORDS match the S85 table word for word (MarkPhrases.kt:15-24, 250-264).
- ✅ The S85a mapping is right: reading/writing → COMPUTER_WORK, travelling → VEHICLE_PASSENGER, riding → OTHER. Every name is in the real enum (`core/model/.../ContextTypes.kt:18-19`: SITTING_IDLE, WORKOUT, COMPUTER_WORK, DRIVING, VEHICLE_PASSENGER, FLIGHT_CABIN, WALKING, OTHER). This is tested against `IntentLabel.entries` (MarkCommandParserTest.kt:539-549).
- ✅ The question-word veto has the 24 listed words and only the can you / could you exceptions (MarkCommandParser.kt:156-159). "forget" appears nowhere and never matches.
- ✅ All 14 named tests are present. EnglishClausesTest, VoiceCommandGroupTest and LanguagePackTest are present.
- ❌ **Important (plan conflict, needs a human decision): "note that" and "remember that" are already live action triggers.**
  - Where: `services/orchestration/.../ActionTriggers.kt:10-12` (NOTE_ADD, shipped 2026-10-13; schema text in `DefaultSchemas.kt:28`).
  - What happens now: "note that the meeting is at five" → `Mark(null)`. So do "remember that my car is on level 3", "remember that guy from the party?" and "save this for later".
  - Why it matters: in Task 31 only unmatched clauses reach Ask. The note text sits in the same clause, so it is swallowed. A shipped feature (user notes) regresses silently, which is the same kind of collision as "forget".
  - Fix (for the user to choose):
    - (a) for MARK, leftover non-intent words in the command's own clause (no separator) → return null and pass through to Ask. A later clause ("mark that, baking") stays `Mark(null)` per the ruling. This changes the `unknownWordPlainMark` case "mark that was great".
    - (b) or drop "note that" and "remember that" from MARK.
- ❌ **Important: the `parse` API swallows every later clause, so Task 31 cannot forward the unmatched clauses.**
  - Where: MarkCommandParser.kt:139-143. `remainder = rest + clauses.drop(i + 1).flatten()`.
  - What happens now:
    - "mark that and what's this building" → `Mark(null)`, and the question is consumed.
    - "mark that, then forget it" → `Mark(null)`, and the Forget/Ask clause is lost.
    - "what's this building, mark that" → `Mark(null)`, and nothing reports that clause 1 was unmatched.
  - Why it matters: the spec golden (design-options §4.4 rule 4 and :291, "mixed") requires mark + Ask "what's this building". Task 31's `onlyUnmatchedClauseReachesAsk` needs to know which clauses the parser consumed. D9 says to take `ParsedUtterance` / `LanguagePack.parseUtterance`, and neither was ported (compare LanguageRegistry.kt:3-9 with Live Fit `CompositeCommands.kt:5-25`).
  - Fix: return `ParsedUtterance(command, notUnderstood: List<String>)`. A later clause joins the intent remainder only if it is intent-shaped (fillers, intent words, negations). Otherwise it is returned as unmatched. Add the "mixed" golden.
- Minor: provenance headers do not match the spec format.
  - The spec wants `// Adapted from Live Fit <path>@<commit> (Apache-2.0, same owner)` (design-options §4.4; slice3 spec :771).
  - The files have KDoc prose with no path or commit. VoiceCommandGroup.kt:3 and LanguageRegistry.kt:3 also omit the licence.
  - The report's claims are accurate: Live Fit has an Apache-2.0 LICENSE, 245/245 commits are by Debasish Kanhar, and the source files have no headers.
- Minor: EnglishClauses is not verbatim, as D9 asked. It adds the separator `|\s*[.!?;]+\s+` (EnglishClauses.kt:8 against Live Fit CompositeCommands.kt:10). The change is reasonable ("Mark that! I'm driving."), but neither the report nor the KDoc says so.
- Minor: only 2 of the 6 round-1 negatives are pinned ("Mark called me", "what did Mark say"). These pass in the replica but have no tests: "am I driving too fast", "what bus goes to Andheri", "remind me about the meeting", "is this a plane".

## Task 14: Quality

Strengths:
- The tables are cleanly data-driven, and the tests loop over every phrase and intent.
- Clause matching is anchored with `take(n) == phrase`, so "Mark called me", "mark my words", "remove marker" and "cancel market" are all null.
- Negation and conflict handling works.
- Retro and Undo pass through on extra words ("I missed that bus" → null).
- The code is pure: no Android imports and no I/O (checked all five main files).
- The registry is immutable: `val` maps and `listOf`/`setOf`. Kotlin `Regex` is thread-safe, so there is no thread-safety issue.
- No non-ASCII characters (grep -P on all new .kt), no `}` in regexes.

Adversarial results from the replica (same logic):
- Correct:
  - "did I mark that" → null.
  - "can you mark that" → Mark.
  - "can you not mark that" → null.
  - "I'll remember this later" → null.
  - "I need to remember this" → null.
  - "MARK THAT" → Mark.
  - "Mark that! I'm driving." → driving.
  - "mark that... I'm driving" → driving.
  - curly apostrophe → driving.
  - "i'm the one who said mark that" → null.
  - "the doctor said this is important" → null.
- False positives: see the Important finding above.
  - "mark that down as a question", "this is important to me", "that was important wasn't it", "remember this is a secret", "save that file please", "mark this email as read" → all `Mark(null)`.
- False negatives (Minor):
  - "hey Rokid mark that", "um mark that", "okay so remember this" and "yaar mark that" → null. `MarkPhrases.FILLERS` holds only the 4 S85 words.
  - "so, mark that" works only because EnglishClauses' larger filler set drops the "so" clause. The behaviour depends on whether the recogniser emits a comma.
  - "rokid", "um", "uh" and "so" could be added as leading fillers. This is within S85's intent but changes the table, so it is the user's call.
- Minor: "I missed that, I'm driving" → `Retro` and drops the intent silently (`MarkCommand.Retro` has no intent field). "undo mark, what did I say" → null, so both the undo and the question go to Ask as one utterance.
- Minor (performance): EnglishClauses' separator `\s*,\s*|...` backtracks quadratically on long whitespace runs. The replica took 5.2 s at 16k spaces and 0.3 s at 4k. Normal text is linear (16k chars in 14 ms). Live Fit has the same issue. Transcripts are short, but there is no length cap: add `if (utterance.length > 500) return null` (or truncate) at the parse entry. Also hoist `byLength` and the `split(' ')` lists to vals (MarkCommandParser.kt:180, 184). They are re-sorted and re-split for every call and token.
- Minor: `VoiceCommandGroup.blockedMessage` ("'X' is turned off in Settings", VoiceCommandGroup.kt:348) refers to a setting that no CORE task builds (Task 32, the voice toggle, is LATER LA12).
- Minor: the commit trailers are `Claude Sonnet 5.5`, while Global Constraints name `Claude Opus 5.5`. Sonnet is the honest value, so align the plan or accept it.
- ⚠️ Cannot verify from this diff: the undo semantics ("this listen's mark, or the newest voice/tap mark within 2 min") and the retro window live in Task 31. Check them there.

## Task 15: Spec compliance

- ✅ `MarkPhrasesDoc.render()` and `renderCompact()` read only `MarkPhrases` and `VoiceCommandGroup`.
- ✅ The markers are correct.
- ✅ The 5 named tests are present, and the fixed privacy and unsupported sentences exist (voice-marks.md:846-856).
- ✅ The README section is 8 lines with the link.
- ✅ The intent table appears in the doc.
- ✅ "Recognition runs on the phone's on-device recogniser only" is true. I checked: every recogniser is `createOnDeviceSpeechRecognizer` or `EXTRA_PREFER_OFFLINE` (AndroidOnDeviceStt.kt:61,90; SpeechPacks.kt:33-34; SpeechIntents.kt:22).
- ❌ **Important (part plan-mandated): the doc promises things that do not exist in 3a-CORE.**
  - voice-marks.md:858-860, "Turning it off": "open Settings and switch off Voice marks. Each command group ... can also be switched off on its own. Tap and notification marks keep working."
    - No voice-mark setting exists. The toggle is Task 32 (LATER LA12).
    - No per-group Settings UI is planned in any CORE task.
    - Notification marks are LATER.
  - voice-marks.md:699, "Or use the phone notification, the tile or the home button": these are LATER LA12. The brief required the line, but the Round 3 cut deferred the feature.
  - README.md:40, "on by default", is trivially true but implies a switch exists.
  - Fix: say what is true now. For example: "Voice marks are always on in this build; a switch to turn them off is coming. Voice marks only happen after you press push-to-talk." Drop or mark as "coming later" the notification, tile and home entry and the per-group toggles. Remove "notification" from "Tap and notification marks keep working".
- Minor: voice-marks.md:849 says "what did you mark" does nothing. That is wrong: it is not a mark, and it goes to Ask as a normal question (Task 38 step 10 expects "a normal answer"). Reword: "...is not a mark; it is answered as a normal question."
- Minor: voice-marks.md:705 says "A mark is never lost because the app did not know your word". The README's "unknown words save a plain mark" is true for MARK only. Retro and Undo with extra words ("I missed that bus") are deliberately not marks, so say so.
- Minor: voice-marks.md:731, "Same as a mark ... recorded as a voice mark", does not tell the user how it differs from "mark that", because both are voice marks. Either state the difference or drop the claim.

## Task 15: Quality

Strengths:
- The generator and comparator work in both directions:
  - every table phrase must appear in backticks;
  - every backticked token in the block must be in the table.
- The regeneration switch is cheap, and the doc block is plainly derived from code.
- Root discovery works: android-companion has no README.md or docs/, so the walk-up stops at the repo root.

Issues:
- Minor (UP-TO-DATE pitfall): the docs and README are not declared as inputs of `testDebugUnitTest`, and neither is `VOICE_MARKS_REGEN` (services/voice/build.gradle.kts has no `inputs.*`).
  - A table change recompiles and reruns the test, so the main drift direction is caught.
  - A hand edit inside a generated block, with no source change, leaves the test UP-TO-DATE and goes undetected locally. This repo has no CI (.github/workflows is absent).
  - A regen run after a green run with no code change is also skipped.
  - Fix: in services/voice/build.gradle.kts, `tasks.withType<Test>().configureEach { inputs.file(rootProject.file("../docs/voice-marks.md")); inputs.file(rootProject.file("../README.md")); inputs.property("voiceMarksRegen", System.getenv("VOICE_MARKS_REGEN") ?: "") }`. Alternatively, add `--rerun` to the regen command in MarkPhrasesDoc.kt:279 and voice-marks.md:866.
- Minor: the regen command in MarkPhrasesDoc.kt:279 and voice-marks.md:866 uses `./gradlew` directly, not the throttled `gw.sh` that Global Constraints require for agents.
- Minor: `maybeRegen` (VoiceMarksDocTest.kt:635-640) does not check that the markers exist before calling `substring`. If a marker is missing it throws `StringIndexOutOfBounds` instead of the clear "markers missing" message. Call `block()` first.
- Minor: `assertTrue("forget" !in inDoc)` (VoiceMarksDocTest.kt:669) cannot fail as written. `inDoc` holds only backticked tokens, which are already constrained to table phrases, and the table has no "forget".

## Findings, one line each

1. Important, T14: "note that…" and "remember that…" collide with the shipped NOTE_ADD triggers (ActionTriggers.kt:10-12). Content-bearing utterances become `Mark(null)` and are swallowed (MarkCommandParser.kt:168). This conflicts with the plan, and the user must choose between end-of-clause-only matching and dropping the phrases.
2. Important, T14: `parse` folds every later clause into the intent remainder (MarkCommandParser.kt:142). Questions and Forget clauses after a mark are lost, and `ParsedUtterance` / `parseUtterance` were not ported. Task 31's "only unmatched clauses go to Ask" cannot be built on this API, and the "mixed" golden is missing.
3. Important, T15: "Turning it off" (voice-marks.md:858-860) describes a Voice marks setting and per-group toggles that do not exist, and notification/tile/home marks (voice-marks.md:699) are LATER LA12. Reword to match the current build (partly plan-mandated).
4. Minor, T14: the provenance headers lack the spec's `<path>@<commit>` format, and two files omit the licence.
5. Minor, T14: the EnglishClauses separator was changed (sentence punctuation) although D9 said verbatim. The change is undocumented.
6. Minor, T14: no length cap. The separator regex is O(n²) on long whitespace runs (5.2 s at 16k spaces in the replica). `byLength` and the split lists are recomputed per token.
7. Minor, T14: the leading-filler set is only the 4 S85 words. "hey Rokid mark that" and "um mark that" are false negatives, and the result depends on recogniser commas.
8. Minor, T14: Retro drops a trailing intent silently, and "undo mark, <question>" drops both the undo and the question split.
9. Minor, T14: 4 of the 6 round-1 negative goldens are not pinned as tests.
10. Minor, T14: `blockedMessage` refers to a Settings toggle that does not exist in CORE.
11. Minor, T14/T15: the commit trailers say Sonnet 5.5, while the plan says Opus 5.5.
12. Minor, T15: "what did you mark does nothing" is wrong; it goes to Ask.
13. Minor, T15: "a mark is never lost" and "same as a mark" are imprecise for Retro and Undo.
14. Minor, T15: the docs and README are not Gradle test inputs, and neither is the env var. Hand edits drift undetected while the test is UP-TO-DATE.
15. Minor, T15: the regen command bypasses gw.sh, `maybeRegen` does not guard against missing markers, and the "forget" doc assertion cannot fail.
