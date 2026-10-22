// Throwaway dev server for the headless browser check. Serves the canvas assets, the test fixtures and this folder's
// harness pages, and sends the REAL Content-Security-Policy read from CanvasRouter.kt, so a violation is real.
// Usage: node serve.mjs <port>      (stop it with Ctrl-C or kill; run.sh does that for you)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const assets = path.join(repo, 'android-companion/phone/src/main/assets/canvas');
const fixtures = path.join(repo, 'android-companion/phone/src/test/js/fixtures.js');
const router = fs.readFileSync(path.join(repo, 'android-companion/phone/src/main/java/com/rokid/context/phone/canvas/server/CanvasRouter.kt'), 'utf8');
const csp = /"Content-Security-Policy" to "([^"]+)"/.exec(router)?.[1];
if (!csp) { console.error('could not read the CSP from CanvasRouter.kt'); process.exit(2); }
const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8' };

http.createServer((req, res) => {
  const name = path.basename(new URL(req.url, 'http://x').pathname);
  const ext = path.extname(name);
  let file = null;
  if (name === 'fixtures.js') file = fixtures;
  else if (types[ext] && /^[a-z0-9_.-]+$/.test(name)) file = [path.join(here, name), path.join(assets, name)].find((f) => fs.existsSync(f));
  if (!file) { res.writeHead(404); res.end('not found'); return; }
  res.writeHead(200, { 'Content-Type': types[ext], 'Content-Security-Policy': csp, 'Cache-Control': 'no-store' });
  res.end(fs.readFileSync(file));
}).listen(Number(process.argv[2] || 8765), '127.0.0.1', () => console.log('CSP: ' + csp));
