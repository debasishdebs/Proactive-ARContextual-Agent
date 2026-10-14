# Glasses: double-tap exit, minimised icon, overlay pop-ups — design

**Date:** 2026-10-14 · **Status:** draft for review (design agreed in chat 2026-10-14) · **Builds on:** v1 spec §1.7 (never pull our app over another app), §2 HUD, §3.5 gestures (double-tap = pause/resume), §5.3 confirm/undo; the background-capture fix (`devfix/bg-capture`: the keep-alive CaptureService keeps the camera while the HUD isn't in front).

## 1. Intent

What the user said, after a drive with Maps on the glasses:
- Double-tap on the timer screen should exit, but nothing visible happened (it toggled pause).
- They want a small icon instead of the big HUD, so other apps (Maps) stay usable and they still know capture is running.
- "Open on glass" pushed Maps into the background.
- Notifications and calls must still appear on screen while our full HUD is open; on the drive only voice feedback came through.

Agreed UX (the simpler variant the user chose):
1. Opening the app (Rokid launcher, or phone "Open on glass") always shows the **full timer HUD**, as today. There is no "icon mode" setting.
2. **Double-tap**, on any page (Glance / Today / Status), shows a 3 s countdown overlay: "Double-tap again to exit · 3".
   - A second double-tap inside the window **minimises** the app.
   - No second double-tap means that when the window ends, it **toggles pause/resume** (today's double-tap action, 3 s later).
3. **Minimised:** the activity moves to the back, and a small **circular icon** stays in a corner over whatever app is in front (Rokid home, Maps…). A **ring around it shrinks** as the countdown to the next photo runs down, and the icon shows a paused state when capture is paused. Capture keeps running.
4. **While minimised:**
   - Answers and nudges appear as a small **overlay pop-up** like a notification and auto-hide after a duration the user sets on the phone.
   - An action **Confirm** auto-opens our app, shows Yes/No, records the vote, then returns where the user was: re-minimised, so the previous app and the icon come back, or it stays on the full HUD if that was where the user was.
   - Glasses asks by tap need the app reopened. Phone asks work.
5. The full HUD keeps its existing size and position settings, plus a new icon-corner choice and a pop-up duration.

Exceptions kept:
- The transient overlays keep their own double-tap meaning, unchanged: Listening → cancel, Answer → dismiss, "Added · double-tap undo" → undo. The exit/pause rule applies only on pages.
- If the user remaps DOUBLE_TAP in Settings → Glasses → Gestures, the remapped action applies and the exit flow is off.

Deliberate change to spec §1.7: auto-opening for a Confirm is the one case where our app may come over another app. It's allowed because the user explicitly chose it, and the "display over other apps" permission exempts us from Android 12's background-activity-start block. Every other case, including capture fallback and "Open on glass" while minimised, still never pulls the HUD forward. While minimised, "Open on glass" shows the content as a pop-up instead.

Non-goals:
- tap input while minimised (v2, §7);
- an icon-only start mode;
- notification or call UI of our own.

## 2. Gesture state machine (`:core:hud`, pure Kotlin)

```kotlin
sealed interface ExitGate { data object Idle; data class Armed(val untilMs: Long) }
object DoubleTapExit {
    const val WINDOW_MS = 3_000L
    /** Returns the new gate and the action to run now (null = none yet). */
    fun onDoubleTap(gate: ExitGate, nowMs: Long): Pair<ExitGate, PageAction?>   // Idle→Armed(no action); Armed(in window)→Idle + MINIMISE
    fun onTick(gate: ExitGate, nowMs: Long): Pair<ExitGate, PageAction?>        // Armed(expired)→Idle + TOGGLE_PAUSE
}
enum class PageAction { MINIMISE, TOGGLE_PAUSE }
```

- The overlay shows a whole-second countdown (3, 2, 1) from `untilMs - now`.
- A tap or swipe while Armed cancels the gate, so no pause happens, and is then handled normally.
- The exit gate is never armed while a transient overlay is up.
- InputRouter routes DOUBLE_TAP to `DoubleTapExit` only when the mapped action is the default (pause/resume) and a page is showing.

## 3. Minimised icon (`:glasses`)

- `MinimisedIcon` is a `TYPE_APPLICATION_OVERLAY` window, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`: it never takes input, so the front app keeps the touchpad. It's added when minimised and removed when the HUD activity resumes.
- Size: 48 dp circle. Black areas stay see-through on the Rokid display, so it uses a bright ring on black.
- Countdown ring: arc = remaining / interval, driven by the same scheduler time the Glance countdown uses (`runtime.onCaptureScheduled` / next-capture time). Updated once a second. When paused it shows a pause glyph with no ring.
- Corner: `HudSettings.iconCorner` (new, additive; TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT, default TOP_RIGHT).
- Permission: needs `SYSTEM_ALERT_WINDOW`.
  - If it isn't granted, minimising falls back to plain `moveTaskToBack` with no icon, and one short toast: "Allow 'display over other apps' for the icon".
  - The phone's Glasses settings show the same hint with the grant instruction. The glasses' Settings → Apps page may not exist; the device check decides the instruction, and debug builds can be granted with `appops set com.rokid.context.glasses SYSTEM_ALERT_WINDOW allow`.
- The keep-alive CaptureService (bg-capture fix) owns the running state. The icon is shown only while it runs and the app is minimised.

## 4. Pop-ups while minimised (`:glasses`)

- When minimised, `HudPresenter` overlays Answer and Nudge (plus a Toast: link and voice notices) route to a `PopupOverlay` window instead of the activity.
  - The window is a separate overlay window, non-focusable, positioned top-center, at most 3 lines.
  - It auto-hides after `HudSettings.popupSeconds`: new and additive, default 6 s, range 3–20 s, set on the phone under Settings → Glasses → Pop-up time.
  - A newer pop-up replaces the old one.
- Listening never happens while minimised: an ask from the glasses needs the HUD, and phone asks show the answer as a pop-up.
- **Confirm while minimised:**
  - `ConfirmLauncher` brings `MainActivity` forward with `EXTRA_RETURN_TO = MINIMISED` and shows the existing Confirm overlay with its 15 s window.
  - When the vote is recorded or the window times out, if `RETURN_TO == MINIMISED`, the activity calls `moveTaskToBack(true)` and the icon returns. Android brings the previous task, e.g. Maps, back to front.
  - If the activity was already in front, nothing changes.
  - It needs the overlay permission, which exempts it from the background-activity-start block. Without the permission, the Confirm goes to the phone only (the existing behaviour) and the pop-up says "Confirm on your phone".

## 5. Notifications and calls over the full HUD

Investigate on the device first; this is a check that leads to a fix:
- Does our `MainActivity` use immersive/fullscreen flags, `FLAG_KEEP_SCREEN_ON`, a `showWhenLocked`/turn-screen-on, or a window type that suppresses Rokid's notification and call surfaces? Compare with a Rokid stock app.
- Change any flag of ours that suppresses them.
- If Rokid itself hides visual notifications for any full-screen app, report it plainly and leave it. That can't be fixed in our app.

## 6. Phone settings (`:phone`, Settings → Glasses)

- Existing size (`scale`) and position stay.
- New: **Icon corner** (4 choices), **Pop-up time** (3–20 s slider, default 6), and the overlay-permission hint.
- These are sent through the existing HudSettings sync. The fields are additive under PROTOCOL_VERSION 1: older glasses ignore them, and newer glasses default them.

## 7. v2 (not built now)

Glasses asks while minimised:
- **Rokid AI key:** the phone already receives `onAiKeyDown` / `onGlassAiAssistStart` when the glasses' AI key is pressed (seen in today's logs). An option would make that key start our ask, if the SDK lets us take it over without breaking "Hi Rokid".
- **Voice wake word on the glasses.**
- **Phone-side shortcut** (a notification action or Quick Settings tile) to start a glasses ask.

## 8. Testing

- **Unit (`:core:hud`):**
  - DoubleTapExit: a double-tap twice inside 3 s gives MINIMISE and no pause;
  - a single double-tap then expiry gives TOGGLE_PAUSE exactly once;
  - a tap or swipe while Armed cancels the gate with no pause;
  - remapped DOUBLE_TAP bypasses the gate;
  - overlays (Listening, Answer, undo toast) keep their own double-tap meaning.
- **Robolectric (`:glasses`):**
  - minimise calls `moveTaskToBack` and adds the icon window when the permission is granted;
  - without the permission: no window and the toast;
  - the icon ring fraction follows the next-capture time, and paused shows the glyph;
  - a pop-up auto-hides after `popupSeconds`;
  - a Confirm while minimised launches with RETURN_TO and re-minimises after the vote or the timeout;
  - a Confirm while in front doesn't re-minimise.
- **Protocol:** HudSettings with `iconCorner`/`popupSeconds` round-trips; old JSON without them decodes with the defaults.
- **Phone:** the new settings rows save and sync; the route smoke test passes.
- **Device:**
  - 4-tap minimises and the icon shows over Rokid home and over Maps;
  - the ring counts down and a capture still happens while minimised;
  - a phone ask gives a pop-up over Maps that auto-hides;
  - a Confirm opens our app, Yes then returns to Maps with the icon back;
  - 2-tap then wait toggles pause;
  - a notification or call shows over the full HUD (§5).

---

## Amendments (as built, 2026-10-14)

These entries are appended; the body above is unchanged.

**I1. §2: when the gate arms, and the fast 4-tap.**
- *Now:* the exit gate also arms under a plain toast, the pause splash or a nudge. It doesn't arm under overlays that have their own double-tap, or while recording. A Confirm, Listening or Answer that arrives mid-window cancels the gate with no pause.
- While the gate is armed, a second double-tap of the same kind passes the detector's 600 ms settle guard, so a fast 4-tap minimises instead of pausing.
- *Why:* to avoid an instant accidental pause; final review I1.
- *Commits:* `0235b1d`, `d74e54b`. *Date:* 2026-10-14.

**I2. §3: icon ring and manifest.**
- *Now:* the ring runs from `armed_at_ms` (CaptureScheduler) to the next capture, and stays full until the first arm. A ✓ photo-sent cue shows on the ring when a photo is buffered or delivered.
- The glasses manifest **declares** SYSTEM_ALERT_WINDOW: an appops grant alone had no effect on the device.
- *Commits:* `a9c2480`, `d53e76e`. *Date:* 2026-10-14.

**I3. §4: pop-ups and Confirm.**
- *Now:* while minimised, a nudge pops up unless verbosity is Silent. Confirm inputs are ignored for 600 ms after an auto-open, and the app re-minimises when the phone clears or replaces the Confirm. The icon and pop-ups are Android Views.
- The "Confirm on your phone" pop-up for a device without the overlay permission is **not built** (open minor).
- *Why:* the gesture tail must not vote; there is no lifecycle owner for Compose in an overlay window.
- *Commit:* `5f9c720`. *Date:* 2026-10-14.

**I4. §1 and §4: "Open on glass" while minimised.**
- *Now:* the phone decides. While its link state is `MINIMISED`, the phone never calls `appStart`; "Open on glasses" sends a 5 s pop-up "Running in the background · open it on your glasses". The glasses-side 5 s relaunch heuristic is superseded on the phone side.
- Data keeps flowing while minimised over the session-less CXRLink (v1 spec Amendment A2).
- *Why:* final review I2; minimised-link I2.
- *Commits:* `043eca5`, `9e02467`, merged `f82b82e`. *Date:* 2026-10-14. Device-verified 22:42–22:46 (frames, acks, settings, heartbeats). Script steps 5–7 are still pending.

**I5. §5: notifications and calls over the full HUD.** Not investigated yet. A call did show during the drive; notifications are unconfirmed (status and backlog §3).

**I6. §8: tests.** Task 3's tests were written right after the code (the coordinator allowed it once because of the user's drive). The §8 device list is not recorded as run.
