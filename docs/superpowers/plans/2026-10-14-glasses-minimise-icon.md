# Glasses Minimise + Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. This is a lean plan, per the user's standing preference: interfaces, behaviour and named tests. The spec has the details.

**Goal:** a 3 s double-tap exit gate (otherwise pause), a minimised countdown icon overlay, pop-ups while minimised, a Confirm that auto-opens and re-minimises, and phone settings for icon corner and pop-up time.

**Spec:** `docs/superpowers/specs/2026-10-14-glasses-minimise-icon-design.md` (approved 2026-10-14).

**Architecture:** a pure-Kotlin gate in `:core:hud`; additive HudSettings fields in `:core:model`; overlay windows (`TYPE_APPLICATION_OVERLAY`, not focusable, not touchable) in `:glasses`; two settings rows in `:phone`.

## Global Constraints
- PROTOCOL_VERSION 1, additive only. `iconCorner: IconCorner = TOP_RIGHT` (TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT) and `popupSeconds: Int = 6` (clamped 3..20) go on `HudSettings`. Old JSON must decode with the defaults.
- Overlays never take input. Without SYSTEM_ALERT_WINDOW: minimise is plain `moveTaskToBack`, plus one toast; no icon or pop-ups; a Confirm goes to the phone, with the pop-up text "Confirm on your phone" shown only if the permission is granted (i.e. never in this case).
- Spec §1.7 exception: only a Confirm may bring the HUD forward. Capture fallback and "Open on glass" while minimised never do; "Open on glass" shows a pop-up instead.
- Keep the keep-alive CaptureService (devfix/bg-capture) behaviour intact. The icon shows only while it runs and the app is minimised.
- Gradle `--no-daemon`, never `--stop`. Never print secrets or read .env. Never push. Commit messages end `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No unescaped `}` in a Regex. Grep the diff for accidental non-ASCII. **NO adb or device use: the user is wearing the devices.**

## Review Focus
1. A pause must never toggle twice. A gate armed, then a minimise, then a return must not fire TOGGLE_PAUSE late. Covered by `minimiseClearsGateNoLatePause`.
2. A Confirm auto-open while the wearer is mid-swipe in another app must not steal the vote: the vote needs the Confirm visible (existing rule). Covered by `confirmAutoOpenNeedsVisibleConfirmBeforeVote`.
3. Re-minimise after a vote must not happen when the user reopened the app themselves during the Confirm. Covered by `userOpenDuringConfirmKeepsHudInFront`.
4. Overlay windows must not leak across process or activity recreation. Exactly one icon window. Covered by `iconWindowIsSingleAcrossRecreate`.
5. Pop-up flood: rapid answers or nudges replace the pop-up, never stack. Covered by `newerPopupReplacesOlder`.

## Task 1: DoubleTapExit gate + InputRouter wiring (`:core:hud`, `:glasses` input)
- Interfaces: as in spec §2 (`ExitGate`, `DoubleTapExit.onDoubleTap/onTick`, `PageAction`). The countdown overlay text is "Double-tap again to exit · N".
- Tests:
  - `twoDoubleTapsInWindowMinimiseNoPause`
  - `singleDoubleTapThenExpiryTogglesPauseOnce`
  - `tapOrSwipeWhileArmedCancelsNoPause`
  - `remappedDoubleTapBypassesGate`
  - `overlaysKeepTheirDoubleTap` (Listening cancel, Answer dismiss, undo)
  - `minimiseClearsGateNoLatePause`

## Task 2: Protocol fields + phone settings (`:core:model`, `:phone`)
- `HudSettings.iconCorner`, `HudSettings.popupSeconds` (additive).
- Settings → Glasses: an "Icon corner" ChoiceRow (4 options) and a "Pop-up time" slider (3–20 s). Add an overlay-permission hint line: "For the minimised icon, allow 'display over other apps' for Proactive Context on the glasses."
- Tests:
  - `oldJsonDecodesWithIconAndPopupDefaults`
  - `iconAndPopupRoundTrip`
  - `popupSecondsClamped`
  - `glassesSettingsSavesIconCornerAndPopupTime`
  - smoke route test passes.

## Task 3: Minimised icon overlay (`:glasses`)
- `MinimisedIcon(context, windowManager)`: `show(corner)`, `update(fractionRemaining, paused)`, `hide()`. A 48 dp circle with a ring arc. Fed by the scheduler's next-capture time and interval, ticking once per second.
- MainActivity on MINIMISE: `moveTaskToBack(true)`, then `show` if `Settings.canDrawOverlays` (otherwise the toast). On resume: `hide`.
- Tests:
  - `minimiseMovesTaskBackAndShowsIconWhenPermitted`
  - `noPermissionNoWindowShowsToast`
  - `ringFractionFollowsNextCapture`
  - `pausedShowsGlyphNoRing`
  - `iconWindowIsSingleAcrossRecreate`

## Task 4: Pop-ups + Confirm auto-open (`:glasses`)
- `PopupOverlay`: `show(text, seconds)` (top-center, max 3 lines, auto-hide; replaces any current one). When minimised, HudPresenter routes Answer, Nudge and Toast here.
- `ConfirmLauncher`: when minimised and a Confirm arrives, start MainActivity with `EXTRA_RETURN_TO=MINIMISED`. After the vote or timeout, `moveTaskToBack(true)` and show the icon again. If the user brought the app forward themselves, clear RETURN_TO.
- Tests:
  - `answerWhileMinimisedShowsPopupThatAutoHides`
  - `newerPopupReplacesOlder`
  - `confirmWhileMinimisedOpensAndReminimisesAfterVote`
  - `confirmTimeoutAlsoReminimises`
  - `confirmWhileInFrontDoesNotReminimise`
  - `userOpenDuringConfirmKeepsHudInFront`
  - `confirmAutoOpenNeedsVisibleConfirmBeforeVote`
  - `openOnGlassWhileMinimisedShowsPopupNotHud`

## Gate
`./gradlew --no-daemon test :phone:assembleDebug :glasses:assembleDebug`: BUILD SUCCESSFUL with 0 failures. Then a final review, then merge into feat.

## Device check (controller, later)
Spec §8 device list, plus §5 (notifications and calls over the full HUD). Granting the overlay permission on a debug build: `appops set com.rokid.context.glasses SYSTEM_ALERT_WINDOW allow`.
