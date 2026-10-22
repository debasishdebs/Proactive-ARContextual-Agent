import { mountEditor } from './floweditor.js';
import { nodeTypes, livePipe } from './fixtures.js';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const out = {}; const errors = [];
window.addEventListener('securitypolicyviolation', (e) => errors.push('CSP violation: ' + e.violatedDirective + ' ' + (e.blockedURI || '')));
window.addEventListener('error', (e) => errors.push('error: ' + e.message));
const origErr = console.error; console.error = (...a) => { errors.push('console.error: ' + a.map(String).join(' ').slice(0, 300)); };
const calls = [];
function layoutOf(p) {
  const depth = {}; const cells = {}; let changed = true;
  for (const n of p.nodes) depth[n.id] = 0;
  for (let i = 0; i < 10 && changed; i++) { changed = false; for (const e of p.edges) if (depth[e.to] < depth[e.from] + 1) { depth[e.to] = depth[e.from] + 1; changed = true; } }
  const colAt = {}; let layers = 0;
  for (const n of p.nodes) { const l = depth[n.id]; colAt[l] = (colAt[l] || 0); cells[n.id] = { layer: l, column: colAt[l]++ }; layers = Math.max(layers, l + 1); }
  return { layers, columns: 2, cells };
}
const api = async (m, path, body) => {
  calls.push(m + ' ' + path);
  if (path === '/api/validate') { const p = JSON.parse(body.json); return { rev: body.rev, layout: layoutOf(p), problems: [] }; }
  if (m === 'PUT') return { draftRev: 9, problems: [] };
  if (path.endsWith('/freeze')) return { version: 2 };
  if (path === '/api/check-prompt') return { problems: ['template needs {{caption}}'] };
  return {};
};
const pipe = livePipe();
const handle = mountEditor(document.getElementById('app'), {
  api, nodeTypes, readOnly: false, onError: (e) => errors.push('onError ' + e.message), onDraft: () => {},
  pipeline: { id: 'p', pipe, draftRev: 3, layout: layoutOf(pipe), problems: [{ text: 'sample problem', nodeId: 'trust' }], activePrompts: { extract: { template: 'LIVE T', schema: '{"a":1}', version: 1 } } },
});
const q = (s) => document.querySelector(s); const qa = (s) => Array.from(document.querySelectorAll(s));
const nodeEl = (id) => qa('.react-flow__node').find((n) => n.getAttribute('data-id') === id);
const key = (el, k, init) => { const ev = new KeyboardEvent('keydown', Object.assign({ key: k, bubbles: true, cancelable: true }, init || {})); el.dispatchEvent(ev); return ev; };
await sleep(800);
out.nodes = qa('.react-flow__node').map((n) => n.getAttribute('data-id'));
out.edges = qa('.react-flow__edge').length;
out.problemsList = qa('.card.warn li').map((l) => l.textContent);
out.badge = qa('.node-card .badge').map((b) => b.textContent);
out.paletteGroups = qa('.palette-group legend').map((l) => l.textContent);
out.refAttrib = !!q('.react-flow__attribution');
// select via click
nodeEl('extract').click(); await sleep(150);
out.inspectorTitle = q('.inspector h3') && q('.inspector h3').textContent;
out.inspectorFields = qa('.inspector .field > span').map((s) => s.textContent);
// palette add after the selected node
const critBtn = qa('.palette button').find((b) => b.textContent === 'Critic'); critBtn.click(); await sleep(150);
out.afterAdd = qa('.react-flow__node').map((n) => n.getAttribute('data-id'));
out.inspectorAfterAdd = q('.inspector h3') && q('.inspector h3').textContent;
await sleep(500);
// Delete on a focused node: once
const added = out.afterAdd.find((i) => i.startsWith('critic_'));
nodeEl(added).focus(); const ev = key(nodeEl(added), 'Delete'); await sleep(300);
out.afterDelete = qa('.react-flow__node').map((n) => n.getAttribute('data-id')); out.deleteDefaultPrevented = ev.defaultPrevented; out.draftNodes = handle.getDraft().nodes.length;
// Backspace typed in the title input is ignored
nodeEl('trust').click(); await sleep(150);
const title = q('.inspector input[type=text]'); title.focus(); key(title, 'Backspace'); key(title, 'Delete'); key(title, 'a'); await sleep(200);
out.afterInputKeys = handle.getDraft().nodes.length;
// type into title through React's value setter
const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
setter.call(title, 'My trust'); title.dispatchEvent(new Event('input', { bubbles: true })); await sleep(150);
out.titleNow = handle.getDraft().nodes.find((n) => n.id === 'trust').title; out.dirty = handle.isDirty();
// keyboard connect mode: extract -> ... c, arrow, enter
nodeEl('extract').focus(); key(nodeEl('extract'), 'c'); await sleep(100);
out.statusC = q('.refusal').textContent;
key(nodeEl('extract'), 'ArrowDown'); await sleep(100); out.statusTarget = q('.refusal').textContent;
key(nodeEl('extract'), 'Enter'); await sleep(150); out.statusEnter = q('.refusal').textContent;
key(nodeEl('trust'), 'Escape');
// arrow focus
nodeEl('photos').focus(); key(nodeEl('photos'), 'ArrowDown'); await sleep(100); out.focusAfterArrow = document.activeElement && document.activeElement.getAttribute && document.activeElement.getAttribute('data-id');
// a focuses palette
key(nodeEl('photos'), 'a'); await sleep(100); out.afterA = document.activeElement && document.activeElement.textContent;
// prompt draft
nodeEl('extract').click(); await sleep(150);
const sel = qa('.inspector select')[0]; const selSetter = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set;
selSetter.call(sel, 'draft'); sel.dispatchEvent(new Event('change', { bubbles: true })); await sleep(200);
out.draftPrompt = JSON.stringify(handle.getDraft().nodes.find((n) => n.id === 'extract').params.prompt);
out.textareas = qa('.inspector textarea').map((t) => t.value + '|' + t.readOnly);
const checkBtn = qa('.inspector button').find((b) => b.textContent === 'Check prompt'); if (checkBtn) { checkBtn.click(); await sleep(200); out.check = qa('.inspector .problems li').map((l) => l.textContent); }
// remove button
nodeEl('critic').click(); await sleep(150);
qa('.inspector button').find((b) => b.textContent === 'Remove node').click(); await sleep(200);
out.afterRemoveBtn = handle.getDraft().nodes.length;
// save and freeze
out.save = await handle.save(); out.dirtyAfterSave = handle.isDirty();
out.freeze = await handle.freeze('note');
out.calls = calls.filter((c) => !c.includes('validate')).concat(calls.filter((c) => c.includes('validate')).length + ' validate calls');
out.errors = errors;
document.getElementById('out').textContent = JSON.stringify(out, null, 1);
document.title = 'DONE';
