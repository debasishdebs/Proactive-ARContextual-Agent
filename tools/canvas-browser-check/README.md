# canvas-browser-check

Dev-only headless-browser check of the canvas web UI (editor and views), run against the real vendored React Flow, the real
`floweditor.js`/`views.js`/`app.js`, a fake `fetch`, and the **real Content-Security-Policy**: `serve.mjs` reads the header
string from `CanvasRouter.kt` and sends it on every response, and both pages record `securitypolicyviolation` events into
their `errors` list.

Not part of the app build or the Gradle tests. No npm dependencies. Needs Node 18+ and Google Chrome (set `CHROME=` to another
binary). Optional.

    tools/canvas-browser-check/run.sh

It starts a throwaway server on 127.0.0.1:8765 (`PORT=` to change), loads `editor.html` (mounts the editor over fixtures and
drives palette, delete, keyboard, inspector, save and freeze) and `app.html` (boots the real `app.js`: leave guard, built-in
view, run and compare thumbnails with the bearer header, 404 photo), prints each result as JSON, stops the server, and exits 1
if any page reports an error or a CSP violation. Edit `editor.js` / `app-drive.js` to extend the sessions.
The pages are served from this folder; `fixtures.js` is the unit-test fixture file. Chrome's results are not a substitute for the
phone WebView checklist (Task 14).
