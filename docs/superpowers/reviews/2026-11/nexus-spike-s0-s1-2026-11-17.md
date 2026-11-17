# Nexus spike S0 and S1 results (2026-11-17, no glasses)

Spec: `docs/superpowers/specs/2026-11-17-nexus-spike-and-v2-plan.md` section 2. Rulings in force: `decisions/2026-11-17-codex-proxy-rulings-2.md` section B (one at a time with Rokid-Coder, PCA stays the default HUD, no content skills, no Nexus STT/TTS, GitHub-only distribution). Everything ran on the host and on the emulator only. No real device was addressed. Nothing was pushed, and nothing was added to the PCA tree besides this note and the plan section.

## Verdict

- S0 done. The plan's source is **two releases stale**: the clone (`15cd98ad`, 24 Oct) is 680 commits behind upstream, and the latest release is now **v2.6.0 (14 Nov, SDK `sdk-v0.27.0`)**, not 2.0.4. The four spec lines the plan relies on have not moved in a way that breaks the design.
- S1 done on the emulator. The SDK resolves offline and a headless probe APK builds. The phone hub starts on an emulator with no Hi Rokid and no glasses. Discovery, Pending, approval and the link-down results were all observed. The per-operation Skills consent screen was not reached, because it needs an approved *caller* plugin.
- Plan assumption check: the "does the phone hub start without Hi Rokid or a CXR link" unknown is answered **yes** (v2.6.0 debug build).

## S0: clone versus upstream

Method: `git fetch --tags origin` only in `~/Desktop/rokid/rokid-resources/nexus/Rokid-Nexus` (HEAD, branches and working tree untouched; `git status` clean). Newer trees were read with `git show <tag>:path` and one detached `git worktree add` into the scratch dir (`~/Desktop/rokid/scratch/nexus-sdk-v0.27.0`).

| Item | Clone `15cd98ad` (24 Oct) | v2.0.4 (7 Nov) | Latest v2.6.0 (14 Nov) |
|---|---|---|---|
| Release tags after the clone | none | v2.0.0 (31 Oct) to v2.0.4 | v2.2.0 to v2.6.0, SDK 0.24.0 to 0.27.0 |
| SDK pin in PLUGIN_SDK.md | `sdk-v0.23.0` | `sdk-v0.24.0` | `sdk-v0.27.0` |
| PLUGIN_SDK.md / BUSSPEC.md length | 1951 / 3296 lines | 2587 / 4232 | 2843 / 4554 |
| Commits since the clone | 0 | 526 | 680 |

Spec lines the plan cites, one by one:

1. **BUSSPEC L8-12, one CXR-L session, the phone hub owns it.** Text is **unchanged** in v2.0.4 and v2.6.0 ("Exactly one CXR-L session may exist on the phone: the phone hub owns it. No client app ever links CXR-L/CXR-M directly."). The S2 coexistence gate is still the deciding unknown. Line numbers in the plan (L8-12, L48-67, L1611-1630) are valid only for the old clone; BUSSPEC grew by about 1,260 lines.
2. **Plugin manifest and permissions.** Still one exported service with action `com.anezium.rokidbus.action.PLUGIN`, `API_VERSION` 3, `CAPABILITIES`, `RECEIVE_PREFIXES`; installing grants nothing. Changes: (a) new optional `LAUNCHABLE` metadata (a plugin whose glasses UI is only launcher pages declares `false`); (b) the sample manifest now declares `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` and the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` permissions (SDK 0.24.0 keeps the service alive for page handoffs; missing declarations are non-fatal); (c) Plugin access gained per-plugin switches ("Assist button opens Nexus", "Show its activity", "Pin its activity", "Float over the glasses", floating-activities mode) and the old quick-action switches were removed. The approval model itself is unchanged. `docs/PLUGINS.md` headless rule is unchanged (the headless rule was only edited for the quiet-notification wording).
3. **Skills v1.** The wire contract, limits (32 operations, 64 KiB catalog, 16 KiB results, 15 s) and the shared parser `shared/.../skills/*` are **byte-identical** between the clone and `sdk-v0.27.0`. One addition: calls from a provider's own paths are refused with bus error `SKILL_LEASE_DENIED` unless a display session or lease exists. Two catalog rules were not in the plan and bit the probe: `data` must be **non-empty** and drawn from a fixed vocabulary (`place_names`, `schedules`, `itinerary`, `media_metadata`, `playback_state`, `status`, `text`), and output strings need `maxLength` or an `enum`. The plan's 4-op PCA catalog should declare `data: ["status"]` (T6 acceptance should test this).
4. **HUD kinds.** Notice (1024 chars or 16 lines, TTL 2-45 s, unchanged in `NoticeSurfaceContract`), pin and activity are unchanged and still supported. **New and relevant:** (a) **Pages** (launcher pages, `rokidbus.plugin.pages`, `PageBody` templates, page actions) became the recommended way to show browsable content; (b) **card immersions are deprecated** ("do not add NexusCard menus or confirmations"), and surfaces are now only for an engaged step; (c) `PLUGIN_SDK.md` now says "choose the object by what the wearer is doing" with a fifth choice (page). The plan's notice, pin and status-pin mapping still holds; a future Activity or list surface should be a **page**, not a surface. Nothing in the 2.0 scope (notices, pin, TTS) is affected.
5. **Not verifiable offline:** the APK digests in the plan (`nexus-phone-2.0.4` `a4474abc...`, signer CN=Anezium). That needs a release download, which was out of scope. Any 2.0 pin should now be re-picked against v2.6.0 (or later) and its published digests.

Other upstream changes worth knowing: 2.2.0 launcher bundle (pages, templates, composer tools, image pages), 2.5.0 phone-assisted Wi-Fi recovery (needs a Wi-Fi permission), 2.6.0 row sections. Rokid-Coder's vendored copy is a separate fork and is not affected.

## S1: probe plugin on the emulator

Scratch (outside PCA, never committed): probe sources `~/Desktop/rokid/scratch/nexus-probe/`, SDK worktree `~/Desktop/rokid/scratch/nexus-sdk-v0.27.0` (detached at `sdk-v0.27.0`), a copy of `CxrGlobal` in the scratch dir, and build logs.

### Build (offline)

- The SDK artifact `com.github.Anezium.Rokid-Nexus:bus-client` is **not** in `~/.m2` or the Gradle cache, and there is no local AAR anywhere under `~/Desktop/rokid`. It resolved anyway by building from the upstream worktree: the probe is a module of that worktree (`project(":nexus-probe").projectDir = file("../nexus-probe")`, dependency `project(":bus-client")`), so the SDK is compiled from source. Gradle 9.5.1 and AGP 9.2.0 were already cached, so `./gradlew --no-daemon --offline -PskipCxrGlobal=true :nexus-probe:assembleDebug` succeeded (about 2 min cold, 8-55 s warm). One at a time, after checking for other Gradle builds.
- Probe: package `io.github.pca.nexusprobe`, plugin id `pcaprobe`, one service, `CAPABILITIES=surfaces,stt,tts,assistant`, `LAUNCHABLE=false`, a 4-op catalog (`status`, `pause_capture`, `resume_capture`, `mark_moment`, canned answers, no data). `aapt2 dump badging` shows no launchable activity. It logs only fixed strings (tag `PCAPROBE`), no transcripts.
- The phone hub could also be built offline: `:phone-hub:assembleDebug` from the same worktree (3 min 40 s; needs the `CxrGlobal` sibling directory, copied into scratch). The hub used here is therefore a **debug-signed build of v2.6.0 source (versionCode 20600)**, not the signed 2.0.4 or 2.6.0 release APK.

### Observed on `emulator-5554` (AVD MyEmulator, API 34, arm64)

| Step | Result |
|---|---|
| Install probe, hub not installed | Installs. `cmd package query-services -a ...PLUGIN` lists `ProbeService`. No launcher entry (`resolve-activity` finds none). Push gives `showNotice=NOT_REGISTERED`, `showPin=NOT_REGISTERED`, `supportsSkills=false`; no crash. |
| Install hub, start it | Hub needs `BLUETOOTH_CONNECT` (`pm grant`) before START HUB works. It then runs with "No bonded glasses device found; SPP loop waiting". No Hi Rokid package is installed (`pm list packages` for `sprite`/`aiapp` is empty), so no CXR-L bind exists. **The hub does start without Hi Rokid or glasses.** |
| Discovery to Pending | The probe's bind registers: `registrationState=1`. Settings, Plugin access, shows "PCA probe", badge "UNVERIFIED INSTALLED PLUGIN", state **Pending**, four capability rows (replace the glasses assistant, speech to text, show on your glasses, text to speech), buttons "Allow on glasses" and "Deny". Hub log: `plugin registered package=io.github.pca.nexusprobe plugin=pcaprobe status=...`. |
| Approve | After "Allow on glasses": state **Approved**, hub log `status=approved`, probe gets `registrationState=0`. New rows appear (Assist button opens Nexus, Show its activity, Pin its activity, Float over the glasses, Floating activities). |
| Skills catalog | First build: hub shows "This plugin's skills are turned off: Nexus cannot read their declaration" (invalid catalog: empty `data` array and open string). After fixing to `data:["status"]` and an enum, a reinstall shows the SKILLS section as valid, with text "An assistant you allow here can ask PCA probe for these operations ... No assistant may use skills yet. Allow 'Use skills from other plugins' on it first." So the catalog is accepted, but the per-operation consent screen needs an approved caller (the Assistant plugin) and was **not reached**. |
| Link down (approved, no glasses) | `linkState=0`; `supportsSkills=true`; `showNotice=CAPABILITY_NOT_AVAILABLE`, `showPin=CAPABILITY_NOT_AVAILABLE`. Gives up quietly, as T3 requires. (The plan guessed `DENIED_NO_LINK`; for surfaces the code is `CAPABILITY_NOT_AVAILABLE`, and `NOT_REGISTERED` before registration.) |
| Hub stopped (`am force-stop` of the hub) | A plugin push cold-starts the hub: `BusHubService created enabled=true` about 1.4 s later. The first push inside that window returns `NOT_REGISTERED`; the plugin is re-registered as approved with no new prompt. Approval survives a hub restart and a probe reinstall (`install -r`). |
| Coexistence with PCA | PCA phone (`com.rokid.context` 1.0.0) and PCA glasses app were already on the AVD. Both PCA and the hub plus probe ran together with no crash and no `FATAL` in logcat. This proves only that the apps install and run side by side on one device. PCA opens its CXR-L session only with glasses, so it says nothing about S2. |

Cleanup: the probe and the hub were uninstalled, `wm size reset` was run, and the emulator was stopped with `adb -s emulator-5554 emu kill`. PCA on the AVD was never touched or reinstalled. No emulator or Gradle process was left running; no real device was addressed.

## Implications for the plan

- T1 (`:nexus-bridge`) can build against the SDK without JitPack by sourcing it from an upstream worktree, but the real build should pin a published tag (currently `sdk-v0.27.0`); the "weekly diff check" risk is real (24 Oct to 14 Nov is six SDK versions).
- T6: add the `data` rule and an enum for the state field; the validator unit test should reuse `SkillCatalogParser` from `shared` rather than a hand-written subset.
- T3 and T8: map `CAPABILITY_NOT_AVAILABLE` and `NOT_REGISTERED` to "give up quietly" and a screen string; allow for a cold-start window of about 1.5 s after the hub restarts.
- Longer term, a PCA "list surface" should be a launcher page, not an immersion.
- Option A and Option B are still undecided: S2 is the only thing that settles them.

## Still needs hardware or other work

- **S2** coexistence: PCA's CXR-L CUSTOM_APP session beside the Nexus hub (both start orders), gestures, popup layering, assist-button intercept, capture while a Nexus surface is open.
- **S3** Nexus STT accuracy and latency on the 10-utterance set, TTS loudness, 60-minute battery and heat. Both are OFF by ruling until the user agrees.
- How anything actually **renders** (notice, pin) on the glasses; the emulator showed only the registration and result codes.
- The per-operation Skills consent screen and a real skill invocation (needs the Assistant plugin built or installed as the caller).
- The Bus inspector journal (Developer mode), not exercised.
- Release APK digest and signer check for the version we would actually install (v2.6.0 or later), plus the hub's accessibility onboarding.
- S1b (can a third-party glasses app be a glasses-side bus client) is a desk check and was not part of this run.
