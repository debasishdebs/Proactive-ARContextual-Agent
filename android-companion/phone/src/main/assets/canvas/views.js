// The five views. Every string from the API is placed with textContent (through h()); there are no HTML sinks.
import { h, clear, btn } from './dom.js';
import { mountEditor } from './floweditor.js';
import { photoThumb, openPhoto } from './photos.js';
import { publishDialog, publishCard } from './versions.js';
import { planDialog } from './planpublish.js';
import { mergedHref, withCapture } from './mergedflow.js';
import { when, usageLine, branchText, compareSummary, tokenEstimateText } from './format.js';
import { statusLabel, errorText, helperCard, loadHelperStatus, hasPython } from './helperview.js';
import { isActive, partnerOf, abPair, shouldAutoOpen, retryDelay, abOptions, parseRef, refKey, shouldRedraw, pollAction, isMoving } from './runlogic.js';

const pretty = (v) => JSON.stringify(v, null, 2);
const enc = encodeURIComponent;
const MAX_PHOTOS = 50;
const PAGE = 60;

function field(label, control) { return h('label', { class: 'field' }, h('span', null, label), control); }
/** The colour class of a node card in a run (input, ai, output, step). */
function kindOf(type) {
  const t = String(type || '');
  if (t.startsWith('input.')) return 'input';
  if (t === 'memory.write') return 'output';
  if (t.startsWith('ai.')) return 'ai';
  return 'step';
}

// ---------------------------------------------------------------- pipelines

export async function pipelinesView(ctx, root) {
  const [list, runs] = await Promise.all([ctx.api('GET', '/api/pipelines'), ctx.api('GET', '/api/runs')]);
  const lastRun = (id) => runs.filter((r) => r.pipelineId === id).sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0))[0];
  clear(root);
  root.appendChild(h('h2', null, 'Pipelines'));
  await publishCard(ctx, root);
  // merged view: tick pipelines, then open them together read-only (#/merged?ids=a,b)
  const picked = new Set();
  const mergedBtn = btn('View merged flow', () => { if (picked.size) ctx.go(mergedHref(Array.from(picked))); }, 'btn primary');
  const syncMerged = () => { mergedBtn.disabled = picked.size === 0; };
  syncMerged();
  root.appendChild(h('div', { class: 'row wrap' }, mergedBtn, h('span', { class: 'muted' }, 'Tick two or more pipelines to see them together.')));
  for (const p of list) {
    const lr = lastRun(p.id);
    root.appendChild(h('section', { class: 'card' },
      h('div', { class: 'row wrap' },
        h('input', { type: 'checkbox', 'aria-label': 'Select ' + p.name, onchange: (ev) => { if (ev.target.checked) picked.add(p.id); else picked.delete(p.id); syncMerged(); } }),
        h('strong', null, p.name),
        p.builtIn ? h('span', { class: 'tag' }, 'built-in') : null,
        p.live ? h('span', { class: 'tag live' }, 'LIVE v' + p.live.version) : null),
      h('p', { class: 'muted' }, 'Versions: ' + ((p.versions || []).map((v) => 'v' + v.version).join(', ') || 'none') +
        ' · Last run: ' + (lr ? statusLabel(lr.status) + ' ' + when(lr.startedAt) : 'none')),
      h('div', { class: 'row wrap' },
        h('a', { class: 'btn primary', href: '#/p/' + enc(p.id) }, 'Open'),
        p.id === 'default-capture' ? null : btn('Show with capture', () => ctx.go(mergedHref(withCapture(p.id)))),
        btn('Duplicate', async () => {
          const name = window.prompt('Name for the copy', p.name + ' copy');
          if (!name || !name.trim()) return;
          try { const r = await ctx.api('POST', '/api/pipelines', { from: p.id, name: name.trim() }); ctx.go('#/p/' + enc(r.id)); } catch (e) { ctx.showError(e); }
        }),
        p.builtIn ? null : btn('Delete', async () => {
          if (!window.confirm('Delete "' + p.name + '"?')) return;
          try { await ctx.api('DELETE', '/api/pipelines/' + enc(p.id)); await pipelinesView(ctx, root); } catch (e) { ctx.showError(e); }
        }, 'btn danger'))));
  }
}

// ---------------------------------------------------------------- pipeline

function parseJson(text) { try { return JSON.parse(text); } catch (e) { return null; } }

export async function pipelineView(ctx, root, id) {
  const [types, data] = await Promise.all([ctx.api('GET', '/api/node-types'), ctx.api('GET', '/api/pipelines/' + enc(id))]);
  const ro = !!data.builtIn;
  const pipe = parseJson(data.json);
  const status = h('span', { class: 'muted', role: 'status' });
  const editorBox = h('div', { class: 'editor-box' });
  let editor = null;
  const showDirty = () => { status.textContent = editor && editor.isDirty() ? 'Unsaved changes' : ''; };
  let refreshJson = () => {};
  const revLine = h('p', { class: 'muted' });
  const showRev = (rev) => { revLine.textContent = 'Draft revision ' + rev + (data.live ? ' · LIVE v' + data.live.version : '') + (data.liveShape ? ' · live shape' : ''); };
  showRev(data.draftRev);
  let busy = false;
  const busyButtons = [];
  /** Save draft and Freeze run one at a time: a second tap would send the same draftRev and get a false conflict. */
  const exclusive = (fn) => async () => {
    if (busy) return;
    busy = true; busyButtons.forEach((b) => { b.disabled = true; });
    try { await fn(); } finally { busy = false; busyButtons.forEach((b) => { b.disabled = false; }); }
  };

  async function saveJson(jsonText) {
    try {
      const r = await ctx.api('PUT', '/api/pipelines/' + enc(id), { json: jsonText, draftRev: editor ? editor.savedRev() : data.draftRev });
      await ctx.reload();
      if (r.problems && r.problems.length) ctx.showError({ message: 'Saved, with problems.', problems: r.problems });
      else ctx.showNotice('Draft saved.');
    } catch (e) {
      if (e.status === 409) ctx.showNotice('Changed elsewhere, reload');
      else ctx.showError(e);
    }
  }

  async function saveDraft() {
    if (!editor) return;
    const r = await editor.save();
    showDirty();
    if (r.ok) showRev(editor.savedRev());
    if (r.ok) {
      if (r.problems.length) ctx.showError({ message: 'Saved, with problems.', problems: r.problems });
      else ctx.showNotice('Draft saved.');
    } else if (r.conflict) ctx.showNotice('Changed elsewhere, reload');
    else ctx.showError(r.error);
  }

  clear(root);
  root.appendChild(h('h2', null, data.name));
  root.appendChild(revLine);
  if (id !== 'default-capture') root.appendChild(h('p', null, h('a', { class: 'btn', href: mergedHref(withCapture(id)) }, 'Show with capture')));
  const saveBtn = ro ? null : btn('Save draft', exclusive(saveDraft), 'btn primary');
  const freezeBtn = ro ? null : btn('Freeze', () => {
    const note = h('input', { type: 'text', maxlength: 80, placeholder: 'Note (80 characters at most)' });
    ctx.modal('Freeze draft', field('Note', note), [{ label: 'Freeze', cls: 'btn primary', run: async (close) => {
      close();
      if (!editor) return;
      await exclusive(async () => {
        const r = await editor.freeze(note.value.slice(0, 80));
        if (r.ok) { await ctx.reload(); ctx.showNotice('Frozen as v' + r.version + '.'); } else if (r.conflict) ctx.showNotice(r.message); else ctx.showError(r.error);
      })();
    } }]);
  });
  if (saveBtn) busyButtons.push(saveBtn, freezeBtn);
  root.appendChild(h('div', { class: 'row wrap' },
    ro ? btn('Duplicate to edit', async () => {
      const name = window.prompt('Name for the copy', data.name + ' copy');
      if (!name || !name.trim()) return;
      try { const r = await ctx.api('POST', '/api/pipelines', { from: id, name: name.trim() }); ctx.go('#/p/' + enc(r.id)); } catch (e) { ctx.showError(e); }
    }, 'btn primary') : null,
    saveBtn, freezeBtn,
    h('a', { class: 'btn', href: '#/run/new?p=' + enc(id) }, 'New run (draft)'),
    status));
  root.appendChild(editorBox);

  if (!pipe) {
    editorBox.appendChild(h('p', { class: 'muted' }, 'The draft cannot be edited here. Fix it in Edit JSON.'));
  } else {
    editor = mountEditor(editorBox, {
      api: ctx.api, nodeTypes: types, readOnly: ro, onError: ctx.showError, onDraft: () => { showDirty(); refreshJson(); },
      pipeline: { id, pipe, draftRev: data.draftRev, layout: data.layout, problems: data.problemsDetailed || [], activePrompts: data.activePrompts || {} },
    });
    const warn = (ev) => { if (editor.isDirty()) { ev.preventDefault(); ev.returnValue = ''; } };
    window.addEventListener('beforeunload', warn);
    ctx.onLeave(() => { window.removeEventListener('beforeunload', warn); editor.unmount(); });
    ctx.guard(() => (editor.isDirty() ? 'You have unsaved changes to this pipeline. Leave without saving?' : null));
  }

  // Edit JSON: shows the editor's current draft when opened
  const jsonArea = h('textarea', { rows: 16, spellcheck: 'false', class: 'mono', disabled: ro });
  jsonArea.value = pipe ? pretty(pipe) : String(data.json);
  const jsonBox = h('details', { class: 'card' }, h('summary', null, 'Edit JSON'), jsonArea,
    ro ? null : h('div', { class: 'row' }, btn('Save JSON', () => saveJson(jsonArea.value), 'btn primary')));
  // the panel follows the canvas while it is open, so Save JSON never overwrites edits it has not shown
  refreshJson = () => { if (jsonBox.open && editor) jsonArea.value = pretty(editor.getDraft()); };
  jsonBox.addEventListener('toggle', refreshJson);
  root.appendChild(jsonBox);

  // versions
  root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Versions'),
    (data.versions || []).length ? null : h('p', { class: 'muted' }, 'No frozen versions yet.'),
    (data.versions || []).map((v) => h('div', { class: 'row wrap version' },
      h('strong', null, 'v' + v.version), data.live && data.live.version === v.version ? h('span', { class: 'tag live' }, 'LIVE') : null,
      h('span', { class: 'muted' }, (v.note || '') + ' · ' + when(v.frozenAt)),
      h('a', { class: 'btn', href: '#/run/new?p=' + enc(id) + '&v=' + v.version }, 'Run'),
      prevOf(data.versions, v.version) ? h('a', { class: 'btn', href: '#/p/' + enc(id) + '/diff?a=' + prevOf(data.versions, v.version) + '&b=' + v.version }, 'Diff with previous') : null,
      h('a', { class: 'btn', href: '#/p/' + enc(id) + '/diff?a=' + v.version + '&b=draft' }, 'Diff with draft'),
      btn('Publish plan', () => planDialog(ctx, id, v.version)),
      btn('Publish', () => publishDialog(ctx, id, v.version, data, () => {
        // the publish re-renders this page: it must not silently drop unsaved canvas edits
        const lose = editor && editor.leaveText('Publishing reloads this page.');
        return !(lose && !window.confirm(lose));
      }))))));
}

/** The highest version below [v], or 0. */
function prevOf(versions, v) { return Math.max(0, ...(versions || []).map((x) => x.version).filter((n) => n < v)); }

// ---------------------------------------------------------------- run new

export async function runNewView(ctx, root, pipelineId, version) {
  if (!pipelineId) { root.appendChild(h('p', null, 'Open a pipeline first.')); return; }
  // a pipeline that starts at `input.moments` is run over Moments (the photos of one second), not over single photos
  try {
    const p0 = await ctx.api('GET', '/api/pipelines/' + enc(pipelineId));
    if (hasInput(parseJson(p0 && p0.json), 'input.scenes')) { await scenesRunView(ctx, root, pipelineId, version, parseJson(p0.json)); return; }
    if (hasMomentsInput(parseJson(p0 && p0.json))) { await momentsRunView(ctx, root, pipelineId, version, parseJson(p0.json)); return; }
  } catch (e) { if (e && e.stale) return; /* fall back to the photo page */ }
  const st = { frames: [], selected: new Set(), done: false, dwell: false, motion: false, day: '' };
  let pipelines = [];
  try { pipelines = await ctx.api('GET', '/api/pipelines'); } catch (e) { if (e && e.stale) return; pipelines = []; } // only the A/B choices need it; a view that was left stops here
  clear(root);
  root.appendChild(h('h2', null, 'New run'));
  const counter = h('span', { class: 'muted' });
  const helperSlot = h('div', null);
  const grid = h('div', { class: 'grid' });
  const more = h('div', { class: 'row' });
  const visible = () => st.frames.filter((f) => !st.motion || isMoving(f.motion));

  const upd = () => { counter.textContent = st.selected.size + ' of ' + MAX_PHOTOS + ' selected'; };
  const drawGrid = () => {
    clear(grid);
    for (const f of visible()) {
      const img = h('img', { alt: 'photo', width: 160, height: 160 });
      const cell = h('button', { type: 'button', class: 'thumb' + (st.selected.has(f.id) ? ' sel' : ''), 'aria-pressed': st.selected.has(f.id) ? 'true' : 'false' },
        img, h('span', { class: 'cap' }, new Date(f.ts).toLocaleTimeString() + (f.dwell ? ' · dwell' : '')));
      cell.addEventListener('click', () => {
        if (st.selected.has(f.id)) st.selected.delete(f.id);
        else if (st.selected.size < MAX_PHOTOS) st.selected.add(f.id);
        else { ctx.showNotice('At most ' + MAX_PHOTOS + ' photos per run.'); return; }
        cell.classList.toggle('sel', st.selected.has(f.id));
        cell.setAttribute('aria-pressed', st.selected.has(f.id) ? 'true' : 'false');
        upd();
      });
      grid.appendChild(h('div', { class: 'thumbwrap' }, cell,
        btn('Enlarge', () => openPhoto(ctx, f.id), 'btn small')));
      ctx.loadImage(img, '/api/frames/' + enc(f.id) + '/thumb', true, () => cell.setAttribute('title', 'Photo no longer available'));
    }
    upd();
  };
  const drawMore = () => {
    clear(more);
    if (!st.done) more.appendChild(btn('Load older', () => load(false)));
  };
  async function load(first) {
    const before = !first && st.frames.length ? Math.min(...st.frames.map((f) => f.ts)) : null;
    const q = new URLSearchParams({ limit: String(PAGE) });
    if (before !== null) q.set('before', String(before));
    if (st.dwell) q.set('dwell', 'true');
    if (st.day) q.set('day', st.day);
    try {
      const got = await ctx.api('GET', '/api/frames?' + q.toString());
      const seen = new Set(st.frames.map((f) => f.id));
      for (const f of got) if (!seen.has(f.id)) st.frames.push(f);
      st.done = got.length < PAGE;
      drawGrid();
      drawMore();
    } catch (e) { ctx.showError(e); }
  }

  const dwell = h('input', { type: 'checkbox' });
  dwell.addEventListener('change', () => { st.dwell = dwell.checked; st.frames = []; st.done = false; load(true); });
  const motion = h('input', { type: 'checkbox' });
  motion.addEventListener('change', () => { st.motion = motion.checked; drawGrid(); });
  const day = h('input', { type: 'date', 'aria-label': 'Day' });
  day.addEventListener('change', () => { st.day = day.value; st.frames = []; st.selected = new Set(); st.done = false; load(true); });
  const baseRef = Object.assign({ pipelineId }, version ? { version: parseInt(version, 10) } : {});
  const abSel = h('select', { 'aria-label': 'A/B against…' }, h('option', { value: '' }, 'A/B against…'),
    abOptions(pipelines, refKey(baseRef)).map((o) => h('option', { value: o.value }, o.label)));
  const refName = (r) => { const p = pipelines.find((x) => x.id === r.pipelineId); return (p ? p.name : r.pipelineId) + (r.version ? ' v' + r.version : ' (draft)'); };
  const newest = h('input', { type: 'number', min: 1, max: MAX_PHOTOS, value: 10, 'aria-label': 'How many newest photos' });
  root.appendChild(h('section', { class: 'card' },
    h('p', { class: 'muted' }, 'Pipeline ' + pipelineId + (version ? ' v' + version : ' (draft)')),
    h('div', { class: 'row wrap' }, h('label', { class: 'inline' }, dwell, ' Dwell only'), h('label', { class: 'inline' }, motion, ' Motion only'), h('label', { class: 'inline' }, 'Day ', day), abSel),
    h('div', { class: 'row wrap' }, newest, btn('Select newest N', () => {
      const n = Math.max(1, Math.min(MAX_PHOTOS, parseInt(newest.value, 10) || 1));
      st.selected = new Set(visible().slice().sort((a, b) => b.ts - a.ts).slice(0, n).map((f) => f.id));
      drawGrid();
    }), btn('Clear', () => { st.selected = new Set(); drawGrid(); })),
    counter));
  root.appendChild(helperSlot);
  root.appendChild(grid);
  root.appendChild(more);
  root.appendChild(h('div', { class: 'row sticky' }, btn('Estimate', async () => {
    const ids = visible().filter((f) => st.selected.has(f.id)).map((f) => f.id);
    if (!ids.length) { ctx.showNotice('Select at least one photo.'); return; }
    const other = parseRef(abSel.value);
    if (other) { await startAb(ids, other); return; }
    const body = { pipelineId, frameIds: ids };
    if (version) body.version = parseInt(version, 10);
    try {
      const e = await ctx.api('POST', '/api/runs/estimate', body);
      confirmStart(e, [], async () => { const r = await ctx.api('POST', '/api/runs', Object.assign({ confirmToken: e.confirmToken }, body)); ctx.go('#/run/' + enc(r.runId)); });
    } catch (err) { ctx.showError(err); }
  }, 'btn primary')));

  /** One confirm for a run or an A/B pair: photos, calls, the model labels and (when the server has one) the token estimate. */
  function confirmStart(e, head, start) {
    const line = e.arms && e.arms.length ? e.arms.map((a, i) => 'Arm ' + 'AB'[i] + ': ' + a.photos + ' photos · about ' + a.nominal + ' model calls (at most ' + a.max + ')').join(' | ') : e.photos + ' photos · about ' + e.nominal + ' model calls (at most ' + e.max + ')';
    const models = (e.models || []).map((m) => m.nodeId + ': ' + m.endpoint + ' / ' + m.model);
    ctx.modal(head.length ? 'Start this A/B pair?' : 'Start this run?', h('div', null, head.map((t) => h('p', null, t)), h('p', null, line), h('p', { class: 'muted' }, tokenEstimateText(e.tokens)),
      models.length ? h('ul', null, models.map((m) => h('li', null, m))) : null), [
      { label: 'Start', cls: 'btn primary', run: async (close) => { try { await start(); close(); } catch (err) { close(); ctx.showError(err); } } }]);
  }

  async function startAb(ids, other) {
    const body = { a: baseRef, b: other, frameIds: ids };
    try {
      const e = await ctx.api('POST', '/api/runs/estimate-ab', body);
      confirmStart(e, ['A: ' + refName(baseRef), 'B: ' + refName(other)], async () => {
        const r = await ctx.api('POST', '/api/runs/ab', Object.assign({ confirmToken: e.confirmToken }, body));
        ctx.go('#/run/' + enc(r.runA));
      });
    } catch (err) { ctx.showError(err); }
  }
  // a Python pipeline needs the laptop helper and one tap on the phone per run: show where that stands (status only; the page has no way to tap for you)
  try {
    const p = await ctx.api('GET', '/api/pipelines/' + enc(pipelineId));
    if (hasPython(parseJson(p && p.json))) {
      helperSlot.appendChild(helperCard(await loadHelperStatus(ctx)));
      helperSlot.appendChild(h('p', { class: 'muted' }, 'Each Python step waits for a tap on the phone after you start the run.'));
    }
  } catch (e) { if (e && e.stale) return; /* the card is a convenience */ }
  await load(true);
}

/** True when the graph's input is `input.moments`. */
const hasInput = (pipe, type) => !!pipe && Array.isArray(pipe.nodes) && pipe.nodes.some((n) => n && n.type === type);
const hasMomentsInput = (pipe) => hasInput(pipe, 'input.moments');

/** Scenes run page: pick Scenes (a time window of stored Scenes), estimate, confirm, start. A Prompt step receives only each Scene's visible text, never a photo. */
async function scenesRunView(ctx, root, pipelineId, version, pipe) {
  const st = { scenes: [], selected: new Set() };
  clear(root);
  root.appendChild(h('h2', null, 'New run over Scenes'));
  const counter = h('span', { class: 'muted' });
  const list = h('div', { class: 'moments' });
  const helperSlot = h('div', null);
  const upd = () => { counter.textContent = st.selected.size + ' of ' + MAX_PHOTOS + ' Scenes selected'; };
  const draw = () => {
    clear(list);
    if (!st.scenes.length) list.appendChild(h('p', { class: 'muted' }, 'No Scenes in this period.'));
    for (const sc of st.scenes) {
      const box = h('input', { type: 'checkbox', 'aria-label': 'Scene ' + sc.id });
      box.checked = st.selected.has(sc.id);
      box.addEventListener('change', () => {
        if (!box.checked) st.selected.delete(sc.id);
        else if (st.selected.size < MAX_PHOTOS) st.selected.add(sc.id);
        else { box.checked = false; ctx.showNotice('At most ' + MAX_PHOTOS + ' Scenes per run.'); return; }
        upd();
      });
      list.appendChild(h('label', { class: 'inline' }, box, ' ' + new Date(sc.startMs).toLocaleString() + ' · ' + sc.momentIds.length + (sc.momentIds.length === 1 ? ' moment' : ' moments') + (sc.activity ? ' · ' + sc.activity : '')));
    }
    upd();
  };
  async function load(day) {
    const from = day ? new Date(day + 'T00:00:00').getTime() : Date.now() - 86400000;
    const to = day ? from + 86400000 : Date.now();
    try { st.scenes = await ctx.api('GET', '/api/scenes?from=' + from + '&to=' + to + '&limit=200'); st.selected = new Set(); draw(); } catch (e) { ctx.showError(e); }
  }
  const day = h('input', { type: 'date', 'aria-label': 'Day' });
  day.addEventListener('change', () => load(day.value));
  root.appendChild(h('section', { class: 'card' }, h('p', { class: 'muted' }, 'Pipeline ' + pipelineId + (version ? ' v' + version : ' (draft)') + ' · last 24 hours unless you pick a day'),
    h('div', { class: 'row wrap' }, h('label', { class: 'inline' }, 'Day ', day), btn('Clear', () => { st.selected = new Set(); draw(); })), counter));
  root.appendChild(helperSlot);
  root.appendChild(list);
  root.appendChild(h('div', { class: 'row sticky' }, btn('Estimate', async () => {
    const ids = st.scenes.filter((sc) => st.selected.has(sc.id)).map((sc) => sc.id);
    if (!ids.length) { ctx.showNotice('Select at least one Scene.'); return; }
    const body = { pipelineId, sceneIds: ids };
    if (version) body.version = parseInt(version, 10);
    try {
      const e = await ctx.api('POST', '/api/runs/estimate', body);
      ctx.modal('Start this run?', h('div', null, h('p', null, e.photos + ' Scenes · ' + e.photoCount + ' photos'),
        h('p', { class: 'muted' }, 'Your steps receive each Scene\'s visible summary and caption only (a hidden or stale one is left out), never a photo. If a Scene changes before you start, nothing runs.')), [
        { label: 'Start', cls: 'btn primary', run: async (close) => {
          try { const r = await ctx.api('POST', '/api/runs', Object.assign({ confirmToken: e.confirmToken }, body)); close(); ctx.go('#/run/' + enc(r.runId)); } catch (err) { close(); ctx.showError(err); }
        } }]);
    } catch (err) { ctx.showError(err); }
  }, 'btn primary')));
  if (hasPython(pipe)) {
    try { helperSlot.appendChild(helperCard(await loadHelperStatus(ctx))); } catch (e) { if (e && e.stale) return; }
  }
  await load('');
}

/**
 * Moment-lite run page: pick Moments (each is the photos of one second, with a photo count) of a day, estimate, confirm, start. It reuses the photo page's day filter
 * and limits; the browser still cannot confirm anything (the Python step waits for the native tap on the phone).
 */
async function momentsRunView(ctx, root, pipelineId, version, pipe) {
  const st = { moments: [], selected: new Set(), day: '' };
  clear(root);
  root.appendChild(h('h2', null, 'New run over Moments'));
  const counter = h('span', { class: 'muted' });
  const list = h('div', { class: 'moments' });
  const helperSlot = h('div', null);
  const upd = () => { counter.textContent = st.selected.size + ' of ' + MAX_PHOTOS + ' Moments selected · ' + st.moments.filter((m) => st.selected.has(m.id)).reduce((n, m) => n + m.photos, 0) + ' photos'; };
  const draw = () => {
    clear(list);
    if (!st.moments.length) list.appendChild(h('p', { class: 'muted' }, 'No Moments in this period.'));
    for (const m of st.moments) {
      const box = h('input', { type: 'checkbox', 'aria-label': 'Moment ' + m.id });
      box.checked = st.selected.has(m.id);
      box.addEventListener('change', () => {
        if (!box.checked) st.selected.delete(m.id);
        else if (st.selected.size < MAX_PHOTOS) st.selected.add(m.id);
        else { box.checked = false; ctx.showNotice('At most ' + MAX_PHOTOS + ' Moments per run.'); return; }
        upd();
      });
      list.appendChild(h('label', { class: 'inline' }, box, ' ' + new Date(m.startMs).toLocaleTimeString() + ' · ' + m.photos + (m.photos === 1 ? ' photo' : ' photos') +
        (m.memories ? ' · ' + m.memories + ' memories' : '') + (m.marks ? ' · ' + m.marks + ' marks' : '')));
    }
    upd();
  };
  async function load() {
    const q = new URLSearchParams({ limit: '200' });
    if (st.day) q.set('day', st.day);
    try { st.moments = await ctx.api('GET', '/api/moments?' + q.toString()); st.selected = new Set(); draw(); } catch (e) { ctx.showError(e); }
  }
  const day = h('input', { type: 'date', 'aria-label': 'Day' });
  day.addEventListener('change', () => { st.day = day.value; load(); });
  const newest = h('input', { type: 'number', min: 1, max: MAX_PHOTOS, value: 10, 'aria-label': 'How many newest Moments' });
  root.appendChild(h('section', { class: 'card' },
    h('p', { class: 'muted' }, 'Pipeline ' + pipelineId + (version ? ' v' + version : ' (draft)')),
    h('div', { class: 'row wrap' }, h('label', { class: 'inline' }, 'Day ', day)),
    h('div', { class: 'row wrap' }, newest, btn('Select newest N', () => {
      const n = Math.max(1, Math.min(MAX_PHOTOS, parseInt(newest.value, 10) || 1));
      st.selected = new Set(st.moments.slice().sort((a, b) => b.startMs - a.startMs).slice(0, n).map((m) => m.id));
      draw();
    }), btn('Clear', () => { st.selected = new Set(); draw(); })),
    counter));
  root.appendChild(helperSlot);
  root.appendChild(list);
  root.appendChild(h('div', { class: 'row sticky' }, btn('Estimate', async () => {
    const ids = st.moments.filter((m) => st.selected.has(m.id)).map((m) => m.id);
    if (!ids.length) { ctx.showNotice('Select at least one Moment.'); return; }
    const body = { pipelineId, momentIds: ids };
    if (version) body.version = parseInt(version, 10);
    try {
      const e = await ctx.api('POST', '/api/runs/estimate', body);
      ctx.modal('Start this run?', h('div', null, h('p', null, e.photos + ' Moments · ' + e.photoCount + ' photos'),
        h('p', { class: 'muted' }, 'The Python step sends only each Moment\'s time and photo times to your laptop helper, after you tap Confirm on the phone. If a Moment changes before you start, nothing runs.')), [
        { label: 'Start', cls: 'btn primary', run: async (close) => {
          try { const r = await ctx.api('POST', '/api/runs', Object.assign({ confirmToken: e.confirmToken }, body)); close(); ctx.go('#/run/' + enc(r.runId)); } catch (err) { close(); ctx.showError(err); }
        } }]);
    } catch (err) { ctx.showError(err); }
  }, 'btn primary')));
  if (hasPython(pipe)) {
    try {
      helperSlot.appendChild(helperCard(await loadHelperStatus(ctx)));
      helperSlot.appendChild(h('p', { class: 'muted' }, 'Each Python step waits for a tap on the phone after you start the run.'));
    } catch (e) { if (e && e.stale) return; }
  }
  await load();
}

// ---------------------------------------------------------------- run

function topoOrder(pipe) {
  const nodes = (pipe && pipe.nodes) || [];
  const indeg = new Map(nodes.map((n) => [n.id, 0]));
  for (const e of (pipe && pipe.edges) || []) if (indeg.has(e.to)) indeg.set(e.to, indeg.get(e.to) + 1);
  const out = [];
  const queue = nodes.filter((n) => indeg.get(n.id) === 0);
  while (queue.length) {
    const n = queue.shift();
    out.push(n);
    for (const e of pipe.edges) {
      if (e.from !== n.id || !indeg.has(e.to)) continue;
      indeg.set(e.to, indeg.get(e.to) - 1);
      if (indeg.get(e.to) === 0) queue.push(nodes.find((x) => x.id === e.to));
    }
  }
  for (const n of nodes) if (!out.includes(n)) out.push(n);
  return out;
}

/** What each node returned for one photo: shown beside the full-size photo, so relevancy can be judged by eye. */
function returnedValues(order, items, frameId) {
  return order.map((n) => {
    const it = items.find((x) => x.frameId === frameId && x.nodeId === n.id);
    if (!it) return null;
    return h('article', { class: 'nodecard ' + kindOf(n.type) }, h('h4', null, n.title || n.id),
      renderOutput(it.output), it.error ? h('p', { class: 'err' }, 'error: ' + errorText(it.error)) : null);
  });
}

function renderOutput(o) {
  if (o === null || o === undefined) return h('p', { class: 'muted' }, '(no output)');
  const box = h('div', { class: 'out' });
  if (typeof o === 'object') {
    if (typeof o.caption === 'string') box.appendChild(h('p', { class: 'caption' }, o.caption));
    if (Array.isArray(o.facts)) {
      box.appendChild(h('ul', { class: 'facts' }, o.facts.map((f) => h('li', null, typeof f === 'string' ? f : (f && typeof f.text === 'string' ? f.text : JSON.stringify(f))))));
    }
  }
  box.appendChild(h('details', null, h('summary', null, 'JSON'), h('pre', null, typeof o === 'string' ? o : pretty(o))));
  return box;
}

export async function runView(ctx, root, runId) {
  const frameInfo = new Map();
  let timer = null;
  let stopped = false;
  let drawnStatus = null;
  let sawActive = false;
  let attempt = 0;
  let paused = false;
  const hidden = () => typeof document !== 'undefined' && document.hidden === true;
  const onVisible = () => { if (paused && !hidden() && !stopped) { paused = false; return tick(); } };
  if (document.addEventListener) { document.addEventListener('visibilitychange', onVisible); ctx.onLeave(() => document.removeEventListener('visibilitychange', onVisible)); }
  ctx.onLeave(() => { stopped = true; if (timer) clearTimeout(timer); });
  const scoreKey = (f, n) => f + '|' + n;

  function frameDetails(frameId, box) {
    if (!frameInfo.has(frameId)) frameInfo.set(frameId, ctx.api('GET', '/api/frames/' + enc(frameId)).catch(() => null));
    frameInfo.get(frameId).then((f) => {
      if (!f) return;
      box.appendChild(h('p', { class: 'muted' }, when(f.ts) + ' · motion ' + (f.motion == null ? '–' : f.motion) + ' · dwell ' + (f.dwell == null ? '–' : f.dwell) + ' · blur ' + (f.blur == null ? '–' : f.blur) + ' · ' + (f.state || '')));
      const live = f.live || [];
      box.appendChild(h('div', { class: 'live' }, h('strong', null, 'Live result'),
        live.length ? h('ul', null, live.map((m) => h('li', null, (m.type ? m.type + ': ' : '') + m.text))) : h('p', { class: 'muted' }, 'none')));
    });
  }

  function scoreControls(run, frameId, nodeId, scores) {
    const key = scoreKey(frameId, nodeId);
    const cur = scores.get(key) || { score: 0, note: '' };
    const send = async () => {
      try { await ctx.api('PUT', '/api/runs/' + enc(runId) + '/scores', { frameId, nodeId, score: cur.score, note: cur.note }); } catch (e) { ctx.showError(e); }
    };
    const up = btn('👍', null, 'btn score');
    const down = btn('👎', null, 'btn score');
    const paint = () => { up.classList.toggle('active', cur.score === 1); down.classList.toggle('active', cur.score === -1); };
    up.addEventListener('click', () => { cur.score = cur.score === 1 ? 0 : 1; scores.set(key, cur); paint(); send(); });
    down.addEventListener('click', () => { cur.score = cur.score === -1 ? 0 : -1; scores.set(key, cur); paint(); send(); });
    const note = h('textarea', { rows: 2, maxlength: 280, placeholder: 'Note', 'aria-label': 'Note' });
    note.value = cur.note || '';
    note.addEventListener('blur', () => { if (note.value !== (cur.note || '')) { cur.note = note.value; scores.set(key, cur); send(); } });
    paint();
    return h('div', { class: 'scorebar' }, h('div', { class: 'row' }, up, down), note);
  }

  async function draw(run, others, helper) {
    const pipe = parseJson(run.pipelineJson);
    const order = topoOrder(pipe);
    const items = run.items || [];
    const scores = new Map((run.scores || []).map((s) => [scoreKey(s.frameId, s.nodeId), { score: s.score, note: s.note || '' }]));
    const frames = [];
    for (const i of items) if (!frames.includes(i.frameId)) frames.push(i.frameId);
    const running = isActive(run.status);
    const partner = partnerOf(others, run);
    clear(root);
    root.appendChild(h('h2', null, 'Run'));
    const pr = run.progress || { done: 0, total: 0 };
    root.appendChild(h('section', { class: 'card' },
      h('div', { class: 'row wrap' }, h('strong', { class: String(run.status) === 'STOPPED_FORGET' ? 'forgotten' : null }, statusLabel(run.status)), h('span', { class: 'muted' }, pr.done + ' / ' + pr.total + ' steps · ' + run.calls + ' calls · ' + run.failures + ' failures')),
      h('progress', { max: Math.max(1, pr.total), value: pr.done }),
      h('p', { class: 'muted' }, 'Pipeline ' + run.pipelineId + (run.version ? ' v' + run.version : ' (draft)') + ' · started ' + when(run.startedAt)),
      h('p', null, usageLine(run.usage)),
      branchText(run.branches).map((t) => h('p', { class: 'muted' }, t)),
      partner ? h('p', null, 'A/B partner: ', h('a', { href: '#/run/' + enc(partner.runId) }, 'run ' + partner.runId), ' (' + statusLabel(partner.status) + ')') : null,
      h('div', { class: 'row wrap' },
        (running || (partner && isActive(partner.status))) ? btn(run.abGroup ? 'Cancel A/B pair' : 'Cancel', async () => { try { await ctx.api('POST', '/api/runs/' + enc(runId) + '/cancel'); } catch (e) { ctx.showError(e); } }, 'btn danger') : null,
        (() => {
          const sel = h('select', { 'aria-label': 'Compare with' }, h('option', { value: '' }, 'Compare with…'),
            others.filter((r) => r.runId !== runId).map((r) => h('option', { value: r.runId }, r.status + ' ' + when(r.startedAt))));
          sel.addEventListener('change', () => { if (sel.value) ctx.go('#/compare?a=' + enc(runId) + '&b=' + enc(sel.value)); });
          return sel;
        })())));
    if (helper !== undefined) root.appendChild(helperCard(helper));
    for (const frameId of frames) {
      const isMoment = frameId.startsWith('moment:') || frameId.startsWith('scene:'); // a Moment or Scene run's item: no single photo to show
      const img = isMoment ? h('strong', null, (frameId.startsWith('scene:') ? 'Scene ' + frameId.slice(6) : 'Moment ' + frameId.slice(7))) : photoThumb(ctx, frameId, { value: () => returnedValues(order, items, frameId) });
      const meta = h('div', { class: 'meta' });
      if (!isMoment) frameDetails(frameId, meta);
      const cards = order.map((n) => {
        const it = items.find((x) => x.frameId === frameId && x.nodeId === n.id);
        if (!it) return h('article', { class: 'nodecard ' + kindOf(n.type) }, h('h4', null, n.title || n.id), h('p', { class: 'muted' }, 'pending'));
        return h('article', { class: 'nodecard ' + kindOf(n.type) },
          h('h4', null, n.title || n.id, ' ', h('span', { class: 'tag' }, String(it.status))),
          it.branch ? h('p', { class: 'muted' }, 'route: ' + it.branch) : null,
          renderOutput(it.output),
          it.error ? h('p', { class: 'err' }, 'error: ' + errorText(it.error)) : null,
          h('p', { class: 'muted' }, (it.latencyMs != null ? it.latencyMs + ' ms' : '') + ' · tokens ' + (it.promptTokens || 0) + '/' + (it.completionTokens || 0) + (it.model ? ' · ' + it.model : '') + (it.promptVersion != null ? ' · prompt v' + it.promptVersion : '')),
          scoreControls(run, frameId, n.id, scores));
      });
      root.appendChild(h('section', { class: 'card photo-card' }, h('div', { class: 'photo-head' }, img, meta), cards));
    }
  }

  async function tick() {
    if (stopped) return;
    let run;
    try {
      run = await ctx.api('GET', '/api/runs/' + enc(runId));
      attempt = 0;
    } catch (e) {
      const wait = stopped ? null : retryDelay(attempt, e);
      if (wait !== null) { attempt++; timer = setTimeout(() => tick(), wait); return; }
      if (!stopped) ctx.showError(e);
      return;
    }
    if (stopped) return;
    let others = [];
    try { others = await ctx.api('GET', '/api/runs'); } catch (e) { others = []; }
    if (stopped) return;
    // a run with a Python step that is still going: say what the laptop is waiting for (the tap is on the phone); undefined = no card
    let helper;
    if (isActive(run.status) && hasPython(parseJson(run.pipelineJson))) { helper = await loadHelperStatus(ctx); if (stopped) return; }
    const ae = document.activeElement;
    const editing = ae && root.contains(ae) && (ae.tagName === 'TEXTAREA' || ae.tagName === 'INPUT' || ae.tagName === 'SELECT');
    if ((!editing || !root.firstChild) && shouldRedraw(drawnStatus, run)) {
      await draw(run, others, helper);
      drawnStatus = String(run.status);
    }
    const partner = partnerOf(others, run);
    const partnerActive = !!partner && isActive(partner.status);
    if (isActive(run.status) || partnerActive) sawActive = true;
    const pair = shouldAutoOpen(sawActive, run, partner) ? abPair(others, run.abGroup) : null;
    if (pair) { ctx.go('#/compare?a=' + enc(pair.a) + '&b=' + enc(pair.b)); return; }
    // Poll until the status on screen is terminal (and an A/B partner is), whether or not a field has focus.
    const act = pollAction(hidden(), isActive(run.status) || partnerActive || drawnStatus !== String(run.status));
    if (act === 'pause') paused = true;
    else if (act === 'wait') timer = setTimeout(tick, 2000);
  }
  await tick();
}

// ---------------------------------------------------------------- compare

export async function compareView(ctx, root, a, b) {
  if (!a || !b) { root.appendChild(h('p', null, 'Pick two runs to compare.')); return; }
  const [data, ra, rb] = await Promise.all([ctx.api('GET', '/api/compare?a=' + enc(a) + '&b=' + enc(b)),
    ctx.api('GET', '/api/runs/' + enc(a)).catch(() => null), ctx.api('GET', '/api/runs/' + enc(b)).catch(() => null)]);
  clear(root);
  root.appendChild(h('h2', null, 'Compare'));
  root.appendChild(h('p', { class: 'muted' }, h('a', { href: '#/run/' + enc(a) }, 'Run A'), ' · ', h('a', { href: '#/run/' + enc(b) }, 'Run B')));
  const sum = compareSummary(data);
  root.appendChild(h('section', { class: 'card' },
    ra ? h('p', null, 'A: ' + usageLine(ra.usage)) : null, rb ? h('p', null, 'B: ' + usageLine(rb.usage)) : null,
    sum.switches.length ? h('h3', null, 'Branches') : null,
    sum.switches.map((r) => h('p', null, r.id + ' · A: ' + (r.a || 'none') + ' · B: ' + (r.b || 'none') + ' · agreement ' + r.agreement)),
    sum.scores.length ? h('h3', null, 'Score sums') : null,
    sum.scores.map((r) => h('p', null, r.id + ' · A ' + r.a + ' · B ' + r.b))));
  if (!(data.frames || []).length) root.appendChild(h('p', { class: 'muted' }, 'The runs have no photo in common.'));
  for (const f of data.frames || []) {
    const blocks = () => (f.nodes || []).map((n) => {
      const left = h('div', { class: 'col' }, h('strong', null, 'A'));
      const right = h('div', { class: 'col' }, h('strong', null, 'B'));
      for (const l of n.diff || []) {
        if (l.op !== 'added') left.appendChild(h('div', { class: 'dl ' + (l.op === 'removed' ? 'del' : 'same') }, l.text));
        if (l.op !== 'removed') right.appendChild(h('div', { class: 'dl ' + (l.op === 'added' ? 'add' : 'same') }, l.text));
      }
      return h('div', { class: 'cmp' }, h('h4', null, n.nodeId), h('div', { class: 'cols' }, left, right));
    });
    root.appendChild(h('section', { class: 'card' }, photoThumb(ctx, f.frameId, { value: blocks }), blocks()));
  }
}
