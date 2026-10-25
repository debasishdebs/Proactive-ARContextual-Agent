// Bootstrap: token handling, API client, hash router, shared UI services (banner, modal, images).
import { h, clear, btn } from './dom.js';
import { parseBootHash } from './boot.js';
import { pipelinesView, pipelineView, runNewView, runView, compareView } from './views.js';
import { diffView } from './versions.js';
import { liveView } from './liveview.js';
import { asksView, askView } from './asks.js';
import { replayView } from './replayview.js';
import { sweepView } from './paretoview.js';
import { mergedView } from './mergedview.js';
import { parseIds } from './mergedflow.js';

const TOKEN_KEY = 'pcToken';
let token = null;
try { token = sessionStorage.getItem(TOKEN_KEY); } catch (e) { token = null; }

function setToken(t) {
  token = t || null;
  try { if (token) sessionStorage.setItem(TOKEN_KEY, token); else sessionStorage.removeItem(TOKEN_KEY); } catch (e) { /* storage may be blocked; the token stays in memory */ }
}

export class ApiError extends Error {
  constructor(status, code, message, problems) {
    super(message);
    this.status = status;
    this.code = code;
    this.problems = problems || [];
  }
}

/** A request that outlived its view or session (the user left, signed out or the browser re-paired). Never shown, never paints anything. */
export class StaleError extends Error { constructor() { super('stale'); this.stale = true; } }

const root = document.getElementById('app');
const nav = document.getElementById('nav');
const banner = document.getElementById('banner');

// ---- banner ----
function clearBanner() { clear(banner); banner.hidden = true; }

function showError(err) {
  if (err && err.stale) return;
  clear(banner);
  const known = err instanceof ApiError || (err && Array.isArray(err.problems));
  const msg = known && typeof err.message === 'string' ? err.message : 'Something went wrong.';
  banner.appendChild(h('strong', null, msg));
  const problems = err && err.problems ? err.problems : [];
  if (problems.length) banner.appendChild(h('ul', null, problems.map((p) => h('li', null, String(p)))));
  banner.appendChild(btn('Dismiss', clearBanner, 'btn small'));
  banner.hidden = false;
}

function showNotice(text) {
  clear(banner);
  banner.appendChild(h('span', null, text));
  banner.appendChild(btn('Dismiss', clearBanner, 'btn small'));
  banner.hidden = false;
}

// ---- API ----
let controllers = new Set(); // in-flight requests of the current view: aborted when it is left

async function request(method, path, body) {
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  const init = { method, headers };
  let c = null;
  if (typeof AbortController !== 'undefined') {
    c = new AbortController();
    controllers.add(c);
    init.signal = c.signal;
  }
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
  const done = () => { if (c) controllers.delete(c); };
  const p = fetch(path, init);
  p.then(done, done);
  return p;
}

async function readError(res) {
  let j = {};
  try { j = await res.json(); } catch (e) { j = {}; }
  return new ApiError(res.status, j.error || 'error', j.message || 'Request failed (' + res.status + ').', Array.isArray(j.problems) ? j.problems.map(String) : []);
}

export async function api(method, path, body) {
  const gen = generation; // every continuation below is bound to the view it started in
  let res;
  try { res = await request(method, path, body); } catch (e) {
    if (gen !== generation) throw new StaleError();
    throw new ApiError(0, 'network', 'The phone could not be reached.');
  }
  if (gen !== generation) throw new StaleError();
  if (res.status === 401) {
    setToken(null);
    showPairing('');
    throw new ApiError(401, 'unauthorized', 'Signed out. Pair again.');
  }
  if (!res.ok) { const err = await readError(res); if (gen !== generation) throw new StaleError(); throw err; }
  const j = await res.json();
  if (gen !== generation) throw new StaleError();
  return j;
}

// ---- cleanup per view ----
let leaveFns = [];
let imageCache = new Map();
let imageStatus = new Map(); // path -> HTTP status of a failed image load (404: the photo was forgotten)
let generation = 0;
let guardFn = null; // () => confirm text | null: asked before the view is left (unsaved editor changes)
let lastHash = '';
let restoring = false;

function onLeave(fn) { leaveFns.push(fn); }

function leave() {
  generation++;
  for (const c of controllers) { try { c.abort(); } catch (e) { /* ignore */ } }
  controllers = new Set();
  guardFn = null;
  for (const fn of leaveFns.splice(0)) { try { fn(); } catch (e) { /* ignore */ } }
  for (const p of imageCache.values()) p.then((u) => { if (u) URL.revokeObjectURL(u); }, () => {});
  imageCache = new Map();
  imageStatus = new Map();
}

/** A blob: URL for an image route, fetched with the bearer token. Cached until the view changes. */
function imageUrl(path) {
  if (imageCache.has(path)) return imageCache.get(path);
  const gen = generation;
  const p = request('GET', path).then(async (res) => {
    if (!res.ok) { imageStatus.set(path, res.status); return null; }
    const url = URL.createObjectURL(await res.blob());
    if (gen !== generation) { URL.revokeObjectURL(url); return null; }
    return url;
  }).catch(() => null);
  imageCache.set(path, p);
  return p;
}

/** Loads [path] into the <img> [img]; lazy (when visible) if [lazy]. onFail(status) runs when the image can't be shown. */
function loadImage(img, path, lazy, onFail) {
  const go = () => imageUrl(path).then((u) => {
    if (u) { img.setAttribute('src', u); return; }
    img.setAttribute('alt', 'photo unavailable');
    if (onFail) onFail(imageStatus.get(path));
  });
  if (!lazy || typeof IntersectionObserver === 'undefined') { go(); return; }
  const obs = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting)) { obs.disconnect(); go(); }
  }, { rootMargin: '200px' });
  obs.observe(img);
  onLeave(() => obs.disconnect());
}

// ---- modal ----
function modal(title, content, actions, cls) {
  const overlay = h('div', { class: 'overlay' });
  const close = () => { if (overlay.parentNode) overlay.parentNode.removeChild(overlay); };
  const box = h('div', { class: 'modal' + (cls ? ' ' + cls : ''), role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('h2', null, title), content,
    h('div', { class: 'row end' }, (actions || []).map((a) => btn(a.label, () => a.run(close), a.cls || 'btn')), btn('Close', close, 'btn')));
  overlay.appendChild(box);
  document.body.appendChild(overlay);
  onLeave(close);
  return close;
}

/** A view with unsaved work registers fn: it returns the confirm text, or null when there is nothing to lose. */
function guard(fn) { guardFn = fn; }

export const ctx = { api, ApiError, showError, showNotice, clearBanner, imageUrl, loadImage, modal, onLeave, go, guard, reload: () => route(true) };

function go(hash) { location.hash = hash; }

// ---- pairing ----
function showPairing(prefill) {
  leave();
  clear(nav);
  clear(root);
  const input = h('input', { type: 'text', inputmode: 'numeric', autocomplete: 'one-time-code', maxlength: 6, value: prefill || '', 'aria-label': 'Pairing code' });
  const submit = async () => {
    clearBanner();
    try {
      const res = await request('POST', '/api/pair', { code: input.value.trim() });
      if (!res.ok) throw await readError(res);
      const j = await res.json();
      setToken(j.token);
      route();
    } catch (e) { showError(e instanceof ApiError ? e : new ApiError(0, 'network', 'The phone could not be reached.')); }
  };
  input.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') submit(); });
  root.appendChild(h('section', { class: 'card' },
    h('h2', null, 'Pair this browser'),
    h('p', null, 'Enter the 6-digit code shown on the canvas screen of the phone app.'),
    input, h('div', { class: 'row' }, btn('Pair', submit, 'btn primary'))));
}

// ---- router ----
function parseHash() {
  const raw = location.hash.replace(/^#/, '') || '/';
  const q = raw.indexOf('?');
  const path = q < 0 ? raw : raw.slice(0, q);
  const query = new URLSearchParams(q < 0 ? '' : raw.slice(q + 1));
  return { parts: path.split('/').filter(Boolean), query };
}

function renderNav() {
  clear(nav);
  nav.appendChild(h('a', { href: '#/' }, 'Pipelines'));
  nav.appendChild(h('a', { href: '#/asks' }, 'Asks'));
  nav.appendChild(h('a', { href: '#/replay' }, 'Replay'));
  nav.appendChild(h('a', { href: '#/sweep' }, 'Sweep'));
  // "Live" shows only while an interpreter is published; the nav does not wait for the answer
  const slot = h('span', null);
  nav.appendChild(slot);
  const gen = generation;
  api('GET', '/api/publish').then((p) => { if (gen === generation && p && p.interpreter) slot.appendChild(h('a', { href: '#/live' }, 'Live')); }, () => {});
  nav.appendChild(btn('Sign out', async () => {
    const text = guardFn ? guardFn() : null;
    if (text && !window.confirm(text)) return;
    try { await request('POST', '/api/logout'); } catch (e) { /* ignore */ }
    setToken(null);
    showPairing('');
  }, 'btn small'));
}

async function route(force) {
  if (restoring) { restoring = false; return; } // the hash we just put back after a declined leave
  if (!token) { showPairing(''); return; }
  if (force !== true && guardFn) {
    const text = guardFn();
    if (text && !window.confirm(text)) { restoring = true; location.hash = lastHash; return; }
  }
  guardFn = null;
  lastHash = location.hash;
  leave();
  clearBanner();
  renderNav();
  clear(root);
  const { parts, query } = parseHash();
  try {
    if (parts.length === 0) await pipelinesView(ctx, root);
    else if (parts[0] === 'p' && parts[1] && parts[2] === 'diff') await diffView(ctx, root, parts[1], query.get('a') || '', query.get('b') || '');
    else if (parts[0] === 'p' && parts[1]) await pipelineView(ctx, root, parts[1]);
    else if (parts[0] === 'run' && parts[1] === 'new') await runNewView(ctx, root, query.get('p') || '', query.get('v') || '');
    else if (parts[0] === 'run' && parts[1]) await runView(ctx, root, parts[1], query.get('after'));
    else if (parts[0] === 'live') await liveView(ctx, root);
    else if (parts[0] === 'replay') await replayView(ctx, root);
    else if (parts[0] === 'sweep') await sweepView(ctx, root);
    else if (parts[0] === 'asks') await asksView(ctx, root);
    else if (parts[0] === 'ask' && parts[1]) await askView(ctx, root, parts[1]);
    else if (parts[0] === 'merged') await mergedView(ctx, root, parseIds(query.get('ids')));
    else if (parts[0] === 'compare') await compareView(ctx, root, query.get('a') || '', query.get('b') || '');
    else root.appendChild(h('p', null, 'Not found.'));
  } catch (e) {
    if (!(e instanceof ApiError && e.status === 401) && !(e && e.stale)) showError(e);
  }
}

function boot() {
  lastHash = location.hash;
  const b = parseBootHash(location.hash);
  if (b.token !== undefined) {
    setToken(b.token);
    history.replaceState(null, '', '/#/');
  } else if (b.pair !== undefined) {
    history.replaceState(null, '', '/#/');
    if (!token) { showPairing(b.pair); window.addEventListener('hashchange', () => route()); return; }
  }
  window.addEventListener('hashchange', () => route());
  route();
}

boot();
