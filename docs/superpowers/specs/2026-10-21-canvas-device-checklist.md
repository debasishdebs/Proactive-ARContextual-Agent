# Experiment canvas: device checklist

For the user, on the real phone. Agents never use adb. Nothing here was run by an agent: the web UI JavaScript has never been
executed in a browser (no node or browser in the build environment), so steps 2 and 3 are its first real run.

## 0. Before installing

**INSTALL ORDER WARNING.** Once a v3 (canvas) build has opened the database, never install a v2 build on that phone (for
example a battery-only build, or main). Room cannot go back from v3 to v2: the app would crash on DB open ("migration from 3 to 2
required") until a v3 build is installed again. The data stays intact, but the app (and the glasses link) is down meanwhile.
Merge the battery branch and the canvas branch first, and install only the merged build. There is deliberately no destructive
downgrade fallback (it would wipe the memories). If a later branch adds a migration, it must be v4.

1. Back up the phone data first (the Room database is migrated in place): Settings -> Memory -> Export memories, and keep the file
   off the phone. This backup is the real safeguard.
2. Run `SqlCipherMigrationDeviceTest` on the device before installing the new build. It checks the encrypted v2 to v3 migration
   mechanics (six new `canvas_*` tables) on a synthetic keyed database (one memory) in the test app's own sandbox, not on a copy
   of your real data. It does not replace the backup in step 1. Do not install until it passes.
3. Install, open the app once, and confirm the existing memories, frames and prompts history are all still there.

## 1. Developer mode and the server

1. Turn Developer mode on. The Experiment canvas row appears under Settings -> AI.
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
3. A wrong code is refused with the tries left; the 5th wrong attempt in a row locks pairing (429, about 30 s). Wait, then pair with a fresh code.
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

## 6. Python helper (laptop)

Needs the laptop helper installed and a Wi-Fi LAN with the phone. Never paste the pairing code, a token or a job id into a report.

1. In the app, Accept pairing, enter the code on the laptop, and turn the Python grant on. The browser run page shows "Laptop helper: paired", the grant and "The helper is ready".
2. In the browser build a pipeline with a Python step (photos, a prompt, then py.fn), pick 3 photos, Estimate, Start. The run page shows "Waiting for your tap on the phone" with a countdown that follows the phone's clock. There is no Confirm button in the browser, and nothing on the laptop has started.
3. On the phone tap Confirm on the card: the laptop runs it and the run page shows the Python results as plain text (put `<b>x</b>` in the output: it must stay literal).
4. Start another run and tap Decline: the run page says the run was not confirmed (declined or expired) and that nothing was sent. Start one more and wait for the two minutes: the same text, and the card leaves the phone.
5. Start a run, do not tap, and Forget one of its photos in the app: the run ends CANCELLED with none of its rows kept. A Forget of a photo that is not in the run shows "Stopped: a photo was forgotten" (STOPPED_FORGET); the card is gone and a late Confirm is impossible.
6. Start a run, tap Confirm, then turn the Python grant off while the laptop works: the job is voided within seconds and the laptop deletes its copy. Repeat with Disconnect and with leaving the LAN (Wi-Fi off).
7. With the grant on and no tap, watch the laptop's helper log for 2 minutes: it is polling and leasing nothing, and it never receives the source.
8. Restart the app: the helper needs a new pairing (its token did not survive) and the grant is off.

## 7. Report back

Note any step that failed, the console errors, and for crashes the logcat of the `CanvasService` process (never paste the pairing
code or token, and never share unfiltered logcat from the glasses side).
