#!/bin/bash
# Headless browser check of the canvas editor and views against the real CSP. Dev tool only: needs Node 18+ and Google
# Chrome (override with CHROME=...). No npm packages. Prints each page's result JSON; exit 1 on any error or CSP violation.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
PORT="${PORT:-8765}"
node "$HERE/serve.mjs" "$PORT" > /tmp/canvas-check-server.log 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null; wait $SERVER 2>/dev/null' EXIT
sleep 1
head -1 /tmp/canvas-check-server.log
rc=0
for page in editor app; do
  echo "== $page"
  "$CHROME" --headless=new --disable-gpu --no-sandbox --window-size=1000,1500 --virtual-time-budget=40000 --dump-dom "http://127.0.0.1:$PORT/$page.html" 2>/dev/null > "/tmp/canvas-check-$page.html"
  node -e '
    const s = require("fs").readFileSync(process.argv[1], "utf8");
    const m = /<pre id="out">([\s\S]*?)<\/pre>/.exec(s);
    if (!m) { console.log("no result: the page did not finish"); process.exit(1); }
    const out = JSON.parse(m[1].replace(/&quot;/g, "\"").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&"));
    console.log(JSON.stringify(out, null, 1));
    process.exit(out.errors.length ? 1 : 0);
  ' "/tmp/canvas-check-$page.html" || rc=1
done
exit $rc
