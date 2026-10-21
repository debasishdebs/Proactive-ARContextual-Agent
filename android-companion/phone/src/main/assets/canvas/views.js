// The five views. Every string from the API is placed with textContent (through h()); there are no HTML sinks.
import { h, clear, btn } from './dom.js';
import { drawDag, kindOf } from './dag.js';

const when = (ms) => (ms ? new Date(ms).toLocaleString() : '–');
const pretty = (v) => JSON.stringify(v, null, 2);
const enc = encodeURIComponent;
const MAX_PHOTOS = 50;
const PAGE = 60;

function field(label, control) { return h('label', { class: 'field' }, h('span', null, label), control); }
function problemList(list) {
  if (!list || !list.length) return null;
  return h('ul', { class: 'problems' }, list.map((p) => h('li', null, String(p))));
}

// ---------------------------------------------------------------- pipelines

export async function pipelinesView(ctx, root) {
  const [list, runs] = await Promise.all([ctx.api('GET', '/api/pipelines'), ctx.api('GET', '/api/runs')]);
  const lastRun = (id) => runs.filter((r) => r.pipelineId === id).sort((a, b) => (b.startedAt || 0) - (a.startedAt || 0))[0];
  clear(root);
  root.appendChild(h('h2', null, 'Pipelines'));
  for (const p of list) {
    const lr = lastRun(p.id);
    root.appendChild(h('section', { class: 'card' },
      h('div', { class: 'row wrap' },
        h('strong', null, p.name),
        p.builtIn ? h('span', { class: 'tag' }, 'built-in') : null,
        p.live ? h('span', { class: 'tag live' }, 'LIVE v' + p.live.version) : null),
      h('p', { class: 'muted' }, 'Versions: ' + ((p.versions || []).map((v) => 'v' + v.version).join(', ') || 'none') +
        ' · Last run: ' + (lr ? lr.status + ' ' + when(lr.startedAt) : 'none')),
      h('div', { class: 'row wrap' },
        h('a', { class: 'btn primary', href: '#/p/' + enc(p.id) }, 'Open'),
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
  const typeOf = (t) => types.find((x) => x.type === t);
  const st = { data, pipe: parseJson(data.json), rev: data.draftRev, selected: null, dirty: false, checks: {} };
  const ro = !!data.builtIn;

  const dagBox = h('div', { class: 'dag-box' });
  const inspector = h('section', { class: 'card' });
  const status = h('span', { class: 'muted' });
  const markDirty = () => { st.dirty = true; status.textContent = 'Unsaved changes'; };

  const reload = async () => { await pipelineView(ctx, root, id); };

  async function save(jsonText) {
    try {
      const r = await ctx.api('PUT', '/api/pipelines/' + enc(id), { json: jsonText, draftRev: st.rev });
      st.rev = r.draftRev;
      await reload();
      if (r.problems && r.problems.length) ctx.showError({ message: 'Saved, with problems.', problems: r.problems });
      else ctx.showNotice('Draft saved.');
    } catch (e) {
      if (e.status === 409) ctx.showNotice('Changed elsewhere, reload');
      else ctx.showError(e);
    }
  }

  function drawDagNow() {
    clear(dagBox);
    if (!st.pipe) { dagBox.appendChild(h('p', { class: 'muted' }, 'The draft cannot be drawn. Fix it in Edit JSON.')); return; }
    dagBox.appendChild(drawDag(st.pipe, data.layout, { selected: st.selected, onSelect: (nid) => { st.selected = nid; drawDagNow(); drawInspector(); } }));
  }

  function textInput(value, onInput, extra) {
    const i = h('input', Object.assign({ type: 'text', value: value || '', disabled: ro }, extra || {}));
    i.addEventListener('input', () => onInput(i.value));
    return i;
  }
  function textArea(value, onInput, readOnly) {
    const t = h('textarea', { rows: 8, spellcheck: 'false', disabled: ro || readOnly });
    t.value = value || '';
    t.addEventListener('input', () => onInput(t.value));
    return t;
  }

  function promptEditor(node) {
    const live = (data.activePrompts || {})[node.id] || {};
    const box = h('div', { class: 'prompt-box' });
    const results = h('div', { class: 'check' });
    const draw = () => {
      clear(box);
      const isDraft = !!(node.params.prompt && node.params.prompt.source === 'draft');
      const d = node.params.prompt;
      const mode = (label, on) => btn(label, () => {
        if (on === isDraft) return;
        if (on) node.params.prompt = { source: 'draft', template: live.template || '', schema: live.schema == null ? null : live.schema };
        else node.params.prompt = { source: 'live' };
        markDirty();
        draw();
      }, 'btn' + (on === isDraft ? ' active' : ''));
      box.appendChild(h('div', { class: 'row' }, mode('Live', false), mode('Draft', true)));
      const tpl = isDraft ? d.template : live.template;
      const sch = isDraft ? d.schema : live.schema;
      box.appendChild(field('Template' + (isDraft ? '' : ' (live, read-only)'), textArea(tpl, (v) => { d.template = v; markDirty(); }, !isDraft)));
      box.appendChild(field('Schema' + (isDraft ? '' : ' (live, read-only)'), textArea(sch, (v) => { d.schema = v.trim() ? v : null; markDirty(); }, !isDraft)));
      box.appendChild(h('div', { class: 'row' }, btn('Check', async () => {
        clear(results);
        try {
          const r = await ctx.api('POST', '/api/check-prompt', { nodeType: node.type, template: String(tpl || ''), schema: sch ? String(sch) : null });
          results.appendChild(r.problems.length ? problemList(r.problems) : h('p', { class: 'ok' }, 'No problems.'));
        } catch (e) { ctx.showError(e); }
      }), live.version != null ? h('span', { class: 'muted' }, 'live prompt v' + live.version) : null));
      box.appendChild(results);
    };
    draw();
    return box;
  }

  function freePromptEditor(node) {
    const results = h('div', { class: 'check' });
    return h('div', { class: 'prompt-box' },
      field('Template', textArea(node.params.template, (v) => { node.params.template = v; markDirty(); })),
      field('Schema (JSON)', textArea(node.params.schema, (v) => { if (v.trim()) node.params.schema = v; else delete node.params.schema; markDirty(); })),
      h('div', { class: 'row' }, btn('Check', async () => {
        clear(results);
        try {
          const r = await ctx.api('POST', '/api/check-prompt', { nodeType: node.type, template: String(node.params.template || ''), schema: node.params.schema ? String(node.params.schema) : null });
          results.appendChild(r.problems.length ? problemList(r.problems) : h('p', { class: 'ok' }, 'No problems.'));
        } catch (e) { ctx.showError(e); }
      })), results);
  }

  function paramControl(node, t, p) {
    const params = node.params;
    switch (p.kind) {
      case 'prompt': return promptEditor(node);
      case 'int': {
        const i = h('input', { type: 'number', min: p.min, max: p.max, value: params[p.name] == null ? '' : params[p.name], disabled: ro });
        i.addEventListener('input', () => { const n = parseInt(i.value, 10); if (Number.isFinite(n)) params[p.name] = n; else delete params[p.name]; markDirty(); });
        return field(p.name + (p.min != null ? ' (' + p.min + '–' + p.max + ')' : ''), i);
      }
      case 'bool': {
        const c = h('input', { type: 'checkbox', checked: params[p.name] === true, disabled: ro });
        c.addEventListener('change', () => { params[p.name] = c.checked; markDirty(); });
        return field(p.name, c);
      }
      case 'labels': {
        const cur = Array.isArray(params[p.name]) ? params[p.name].join(', ') : '';
        return field(p.name + ' (comma separated)', textInput(cur, (v) => { params[p.name] = v.split(',').map((x) => x.trim()).filter(Boolean); markDirty(); }));
      }
      case 'schema': return null; // handled with the template
      case 'text': if (p.name === 'template') return null; // fallthrough to the shared prompt editor below
      // eslint-disable-next-line no-fallthrough
      default: return field(p.name, textInput(params[p.name] == null ? '' : String(params[p.name]), (v) => { if (v) params[p.name] = v; else delete params[p.name]; markDirty(); }));
    }
  }

  function drawInspector() {
    clear(inspector);
    const node = st.pipe && (st.pipe.nodes || []).find((n) => n.id === st.selected);
    if (!node) { inspector.appendChild(h('p', { class: 'muted' }, 'Select a node to inspect it.')); return; }
    if (!node.params || typeof node.params !== 'object') node.params = {};
    const t = typeOf(node.type);
    inspector.appendChild(h('h3', null, node.title || node.id));
    inspector.appendChild(h('p', { class: 'muted' }, node.type + (t ? ' · ' + t.label : '') + ' · id ' + node.id));
    if (ro) inspector.appendChild(h('p', { class: 'muted' }, 'Built-in pipelines are read-only. Duplicate it to edit.'));
    inspector.appendChild(field('Title', textInput(node.title, (v) => { node.title = v; markDirty(); })));
    if (t) for (const p of t.params) inspector.appendChild(paramControl(node, t, p));
    if (node.type === 'ai.prompt') inspector.appendChild(freePromptEditor(node));
  }

  // header and actions
  clear(root);
  root.appendChild(h('h2', null, data.name));
  root.appendChild(h('p', { class: 'muted' }, 'Draft revision ' + data.draftRev + (data.live ? ' · LIVE v' + data.live.version : '') + (data.liveShape ? ' · live shape' : '')));
  const probs = problemList(data.problems);
  if (probs) root.appendChild(h('section', { class: 'card warn' }, h('strong', null, 'Problems'), probs));
  root.appendChild(h('div', { class: 'row wrap' },
    ro ? null : btn('Save draft', () => { if (st.pipe) save(JSON.stringify(st.pipe, null, 2)); }, 'btn primary'),
    ro ? null : btn('Freeze', () => {
      const note = h('input', { type: 'text', maxlength: 80, placeholder: 'Note (80 characters at most)' });
      ctx.modal('Freeze draft', field('Note', note), [{ label: 'Freeze', cls: 'btn primary', run: async (close) => {
        try { const r = await ctx.api('POST', '/api/pipelines/' + enc(id) + '/freeze', { note: note.value.slice(0, 80) }); close(); await reload(); ctx.showNotice('Frozen as v' + r.version + '.'); } catch (e) { close(); ctx.showError(e); }
      } }]);
    }),
    h('a', { class: 'btn', href: '#/run/new?p=' + enc(id) }, 'New run (draft)'),
    status));
  root.appendChild(dagBox);
  root.appendChild(inspector);

  // Edit JSON
  const jsonArea = h('textarea', { rows: 16, spellcheck: 'false', class: 'mono', disabled: ro });
  jsonArea.value = st.pipe ? pretty(st.pipe) : String(data.json);
  root.appendChild(h('details', { class: 'card' }, h('summary', null, 'Edit JSON'), jsonArea,
    ro ? null : h('div', { class: 'row' }, btn('Save JSON', () => save(jsonArea.value), 'btn primary'))));

  // versions
  root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Versions'),
    (data.versions || []).length ? null : h('p', { class: 'muted' }, 'No frozen versions yet.'),
    (data.versions || []).map((v) => h('div', { class: 'row wrap version' },
      h('strong', null, 'v' + v.version), data.live && data.live.version === v.version ? h('span', { class: 'tag live' }, 'LIVE') : null,
      h('span', { class: 'muted' }, (v.note || '') + ' · ' + when(v.frozenAt)),
      h('a', { class: 'btn', href: '#/run/new?p=' + enc(id) + '&v=' + v.version }, 'Run'),
      btn('Publish', async () => {
        if (!window.confirm('Publish v' + v.version + ' as the live prompts?')) return;
        try {
          const r = await ctx.api('POST', '/api/pipelines/' + enc(id) + '/versions/' + v.version + '/publish');
          await reload();
          ctx.showNotice('Published: ' + (r.keys || []).join(', '));
        } catch (e) {
          if (e.code === 'publish_blocked') ctx.showError({ message: 'Publish blocked.', problems: e.problems });
          else ctx.showError(e);
        }
      })))));

  drawDagNow();
  drawInspector();
}

// ---------------------------------------------------------------- run new

export async function runNewView(ctx, root, pipelineId, version) {
  if (!pipelineId) { root.appendChild(h('p', null, 'Open a pipeline first.')); return; }
  const st = { frames: [], selected: new Set(), done: false, dwell: false, motion: false };
  clear(root);
  root.appendChild(h('h2', null, 'New run'));
  const counter = h('span', { class: 'muted' });
  const grid = h('div', { class: 'grid' });
  const more = h('div', { class: 'row' });
  const visible = () => st.frames.filter((f) => !st.motion || Number(f.motion) > 0 || f.motion === true);

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
      grid.appendChild(cell);
      ctx.loadImage(img, '/api/frames/' + enc(f.id) + '/thumb', true);
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
  const newest = h('input', { type: 'number', min: 1, max: MAX_PHOTOS, value: 10, 'aria-label': 'How many newest photos' });
  root.appendChild(h('section', { class: 'card' },
    h('p', { class: 'muted' }, 'Pipeline ' + pipelineId + (version ? ' v' + version : ' (draft)')),
    h('div', { class: 'row wrap' }, h('label', { class: 'inline' }, dwell, ' Dwell only'), h('label', { class: 'inline' }, motion, ' Motion only')),
    h('div', { class: 'row wrap' }, newest, btn('Select newest N', () => {
      const n = Math.max(1, Math.min(MAX_PHOTOS, parseInt(newest.value, 10) || 1));
      st.selected = new Set(visible().slice().sort((a, b) => b.ts - a.ts).slice(0, n).map((f) => f.id));
      drawGrid();
    }), btn('Clear', () => { st.selected = new Set(); drawGrid(); })),
    counter));
  root.appendChild(grid);
  root.appendChild(more);
  root.appendChild(h('div', { class: 'row sticky' }, btn('Estimate', async () => {
    const ids = visible().filter((f) => st.selected.has(f.id)).map((f) => f.id);
    if (!ids.length) { ctx.showNotice('Select at least one photo.'); return; }
    const body = { pipelineId, frameIds: ids };
    if (version) body.version = parseInt(version, 10);
    try {
      const e = await ctx.api('POST', '/api/runs/estimate', body);
      const line = e.photos + ' photos · about ' + e.nominal + ' model calls (at most ' + e.max + ')';
      const models = (e.models || []).map((m) => m.nodeId + ': ' + m.endpoint + ' / ' + m.model);
      ctx.modal('Start this run?', h('div', null, h('p', null, line), models.length ? h('ul', null, models.map((m) => h('li', null, m))) : null), [
        { label: 'Start', cls: 'btn primary', run: async (close) => {
          try { const r = await ctx.api('POST', '/api/runs', Object.assign({ confirmToken: e.confirmToken }, body)); close(); ctx.go('#/run/' + enc(r.runId)); } catch (err) { close(); ctx.showError(err); }
        } }]);
    } catch (err) { ctx.showError(err); }
  }, 'btn primary')));
  await load(true);
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

  async function draw(run, others) {
    const pipe = parseJson(run.pipelineJson);
    const order = topoOrder(pipe);
    const items = run.items || [];
    const scores = new Map((run.scores || []).map((s) => [scoreKey(s.frameId, s.nodeId), { score: s.score, note: s.note || '' }]));
    const frames = [];
    for (const i of items) if (!frames.includes(i.frameId)) frames.push(i.frameId);
    const running = String(run.status).toUpperCase() === 'RUNNING';
    clear(root);
    root.appendChild(h('h2', null, 'Run'));
    const pr = run.progress || { done: 0, total: 0 };
    root.appendChild(h('section', { class: 'card' },
      h('div', { class: 'row wrap' }, h('strong', null, String(run.status)), h('span', { class: 'muted' }, pr.done + ' / ' + pr.total + ' steps · ' + run.calls + ' calls · ' + run.failures + ' failures')),
      h('progress', { max: Math.max(1, pr.total), value: pr.done }),
      h('p', { class: 'muted' }, 'Pipeline ' + run.pipelineId + (run.version ? ' v' + run.version : ' (draft)') + ' · started ' + when(run.startedAt)),
      h('div', { class: 'row wrap' },
        running ? btn('Cancel', async () => { try { await ctx.api('POST', '/api/runs/' + enc(runId) + '/cancel'); } catch (e) { ctx.showError(e); } }, 'btn danger') : null,
        (() => {
          const sel = h('select', { 'aria-label': 'Compare with' }, h('option', { value: '' }, 'Compare with…'),
            others.filter((r) => r.runId !== runId).map((r) => h('option', { value: r.runId }, r.status + ' ' + when(r.startedAt))));
          sel.addEventListener('change', () => { if (sel.value) ctx.go('#/compare?a=' + enc(runId) + '&b=' + enc(sel.value)); });
          return sel;
        })())));
    for (const frameId of frames) {
      const img = h('img', { alt: 'photo', class: 'photo', width: 160, height: 160 });
      img.addEventListener('click', () => {
        const big = h('img', { alt: 'photo', class: 'big' });
        ctx.loadImage(big, '/api/frames/' + enc(frameId) + '/image', false);
        ctx.modal('Photo', big, []);
      });
      ctx.loadImage(img, '/api/frames/' + enc(frameId) + '/thumb', true);
      const meta = h('div', { class: 'meta' });
      frameDetails(frameId, meta);
      const cards = order.map((n) => {
        const it = items.find((x) => x.frameId === frameId && x.nodeId === n.id);
        if (!it) return h('article', { class: 'nodecard ' + kindOf(n.type) }, h('h4', null, n.title || n.id), h('p', { class: 'muted' }, 'pending'));
        return h('article', { class: 'nodecard ' + kindOf(n.type) },
          h('h4', null, n.title || n.id, ' ', h('span', { class: 'tag' }, String(it.status))),
          it.branch ? h('p', { class: 'muted' }, 'route: ' + it.branch) : null,
          renderOutput(it.output),
          it.error ? h('p', { class: 'err' }, 'error: ' + it.error) : null,
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
    } catch (e) { if (!stopped) ctx.showError(e); return; }
    if (stopped) return;
    const ae = document.activeElement;
    const editing = ae && root.contains(ae) && (ae.tagName === 'TEXTAREA' || ae.tagName === 'INPUT' || ae.tagName === 'SELECT');
    if (!editing || !root.firstChild) {
      let others = [];
      try { others = await ctx.api('GET', '/api/runs'); } catch (e) { others = []; }
      if (stopped) return;
      await draw(run, others);
    }
    if (String(run.status).toUpperCase() === 'RUNNING') timer = setTimeout(tick, 2000);
  }
  await tick();
}

// ---------------------------------------------------------------- compare

export async function compareView(ctx, root, a, b) {
  if (!a || !b) { root.appendChild(h('p', null, 'Pick two runs to compare.')); return; }
  const data = await ctx.api('GET', '/api/compare?a=' + enc(a) + '&b=' + enc(b));
  clear(root);
  root.appendChild(h('h2', null, 'Compare'));
  root.appendChild(h('p', { class: 'muted' }, h('a', { href: '#/run/' + enc(a) }, 'Run A'), ' · ', h('a', { href: '#/run/' + enc(b) }, 'Run B')));
  if (!(data.frames || []).length) root.appendChild(h('p', { class: 'muted' }, 'The runs have no photo in common.'));
  for (const f of data.frames || []) {
    const img = h('img', { alt: 'photo', class: 'photo', width: 160, height: 160 });
    ctx.loadImage(img, '/api/frames/' + enc(f.frameId) + '/thumb', true);
    root.appendChild(h('section', { class: 'card' }, img, (f.nodes || []).map((n) => {
      const left = h('div', { class: 'col' }, h('strong', null, 'A'));
      const right = h('div', { class: 'col' }, h('strong', null, 'B'));
      for (const l of n.diff || []) {
        if (l.op !== 'added') left.appendChild(h('div', { class: 'dl ' + (l.op === 'removed' ? 'del' : 'same') }, l.text));
        if (l.op !== 'removed') right.appendChild(h('div', { class: 'dl ' + (l.op === 'added' ? 'add' : 'same') }, l.text));
      }
      return h('div', { class: 'cmp' }, h('h4', null, n.nodeId), h('div', { class: 'cols' }, left, right));
    })));
  }
}
