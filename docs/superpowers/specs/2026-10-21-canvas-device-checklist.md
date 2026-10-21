# Experiment canvas: device checklist

For the user, on the real phone. Agents never use adb. Nothing here was run by an agent: the web UI JavaScript has never been
executed in a browser (no node or browser in the build environment), so steps 2 and 3 are its first real run.

## 0. Before installing

1. Back up the phone data first (the Room database is migrated in place).
2. Run `SqlCipherMigrationDeviceTest` on the device before installing the new build. It checks the encrypted v2 to v3 migration
   (six new `canvas_*` tables) on a copy of the real encrypted DB. Do not install until it passes.
3. Install, open the app once, and confirm the existing memories, frames and prompts history are all still there.

## 1. Developer mode and the server

1. Turn Developer mode on. The canvas row appears on the home screen.
2. Open the editor. The pipelines list shows Live extraction and Intent, then extract.
3. Watch for a crash named "did not start foreground" (or a ForegroundServiceStartNotAllowed message) when the server starts, in the
   background, from a notification, and with notifications denied. The service must handle a refused `startForeground` by stopping
   itself and showing the reason, not by crashing.
4. Developer mode off: the server stops and the notification goes. Reload the browser page: it must fail to connect.
5. Idle 30 minutes: the server and the notification stop on their own (not while a run is active).

## 2. WebView / browser smoke test of every page

Open each page, once in the in-app WebView and once in a laptop browser. Check each loads with no blank panel, the browser
console is free of CSP errors (the policy is `default-src 'self'`, no inline scripts or styles), and nothing is clipped.

- Pipelines list: built-ins marked read-only, Duplicate works, Delete is refused for built-ins.
- Editor: node DAG draws, selecting a node shows its params, the Check button reports problems, Save conflicts (409) show a reload prompt.
- Frame picker: thumbnails load (they are fetched with the token and shown as blob URLs), the 5 most recent can be selected.
- Estimate: shows photos, models, token counts, and a refusal message when over limits.
- Run view: progress updates by polling, Cancel works, per-photo outputs and live results show side by side.
- Compare: two runs diff per photo and node.
- Scores: thumbs up, down and a note persist after reload.
- Versions: Freeze, Publish, Rollback buttons and the "active publish" banner.
- Model text containing `<b>x</b>` or `<img src=x onerror=alert(1)>` must show as literal text (set via textContent only).

## 3. Pairing from a laptop on the LAN

1. In the app turn LAN on. The URL and a 6 digit code show.
2. On a laptop on the same Wi-Fi open the URL (`http://<phone ip>:8765`, or 8766 to 8770 if busy). Pair with the code.
3. A wrong code is refused with the tries left; the 6th wrong attempt in a row locks pairing (429, about 30 s). Wait, then pair with a fresh code.
4. A code older than 5 minutes is refused. Opening the URL with a different Host (for example the phone name) is refused.
5. LAN off: the laptop can no longer connect.

## 4. Runs

1. Pick 5 recent photos, Estimate, then Run. Outputs and live results appear side by side.
2. Bulk run: select a few photos (5 to 10), run, and watch the phone stay responsive. It must not run while the phone is hot.
3. Edit the extract prompt as a Draft, run, compare with the first run.
4. Forget one of those photos in the app: it disappears from the run detail and its scores.
5. Confirm the live memories, frames and keyframe state did not change because of any run.

## 5. Freeze, publish, rollback

1. Freeze the edited pipeline (v1), then Publish v1. Prompts history shows an entry named "Canvas: ...".
2. Trigger or wait for the next live keyframe: it uses the new prompt.
3. Roll back. The previous prompt is active again and history shows it.
4. A second rollback is refused ("rollback_blocked"). Edit the live prompt by hand after a publish, then roll back: it must be refused too.

## 6. Report back

Note any step that failed, the console errors, and for crashes the logcat of the `CanvasService` process (never paste the pairing
code or token, and never share unfiltered logcat from the glasses side).
