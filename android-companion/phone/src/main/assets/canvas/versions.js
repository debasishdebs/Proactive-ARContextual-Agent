// Version diff, the publish dialog (three modes), and the publish card with rollback and history. Server text goes through h() only.
import { h, clear, btn } from './dom.js';
import { rowsFor } from './diffview.js';
import { MODES, callRangeText, rollbackProblems, historyRows, when } from './format.js';
import { rollbackPayload } from './runlogic.js';
import { planStatusCard } from './planpublish.js';

const enc = encodeURIComponent;
const side = (v) => (v === 'draft' ? 'the draft' : 'v' + v);

export async function diffView(ctx, root, id, a, b) {
  clear(root);
  root.appendChild(h('h2', null, 'Compare versions'));
  root.appendChild(h('p', null, h('a', { href: '#/p/' + enc(id) }, 'Back to the pipeline')));
  if (!a || !b) { root.appendChild(h('p', { class: 'muted' }, 'Pick two versions to compare.')); return; }
  const rows = rowsFor(await ctx.api('GET', '/api/pipelines/' + enc(id) + '/diff?a=' + enc(a) + '&b=' + enc(b)));
  root.appendChild(h('p', { class: 'muted' }, side(a) + ' → ' + side(b)));
  if (rows.empty) root.appendChild(h('p', { class: 'muted' }, 'No differences.'));
  if (rows.renamed) root.appendChild(h('p', null, 'The pipeline was renamed.'));
  const list = (title, items) => (items.length ? h('section', { class: 'card' }, h('h3', null, title), items.map((r) => h('div', { class: 'dl ' + r.cls }, r.text))) : null);
  root.appendChild(list('Nodes', rows.nodes));
  root.appendChild(list('Edges', rows.edges));
  for (const p of rows.prompts) root.appendChild(h('section', { class: 'card' }, h('h3', null, 'Prompt ' + p.title), p.lines.map((l) => h('div', { class: 'dl ' + l.cls }, l.text))));
}

/**
 * The publish dialog of one frozen version. [data] is GET /api/pipelines/{id}; the server's gates answer the publish itself.
 * [mayLeave]() asks before the reload that would drop unsaved editor changes.
 */
export function publishDialog(ctx, id, version, data, mayLeave) {
  // the version's own shape when the server sent it; otherwise the draft's numbers would be another pipeline's, so none are shown
  const row = (data.versions || []).find((v) => v.version === version) || {};
  const it = row.interpretable || null;
  const ms = MODES.filter((m) => (m.mode === 'flow') === !!row.flow); // a flow version has its own mode only
  const radios = ms.map((m) => {
    const r = h('input', { type: 'radio', name: 'mode', value: m.mode });
    r.checked = m.mode === (row.flow ? 'flow' : row.liveShape === false ? 'shadow' : 'prompts');
    return r;
  });
  const range = it ? callRangeText(it.callRange) : '';
  const body = h('div', null,
    ms.map((m, i) => h('label', { class: 'field' }, h('span', null, radios[i], ' ', h('strong', null, m.label)), h('span', { class: 'muted' }, m.text))),
    row.liveShape === false && !row.flow ? h('p', { class: 'muted' }, 'This version is not the live shape, so Prompts only may be refused.') : null,
    row.flow ? null : !it ? h('p', { class: 'muted' }, 'The cost estimate describes your current draft; this version may differ. See Diff with draft.')
      : it.problems.length ? h('div', null, h('p', { class: 'muted' }, 'This version cannot be an interpreter yet:'), h('ul', { class: 'problems' }, it.problems.map((p) => h('li', null, p))))
      : (range ? h('p', null, range + '.') : null),
    it ? (it.warnings || []).map((w) => h('p', { class: 'muted' }, w)) : null,
    h('p', { class: 'muted' }, 'The server checks the version you publish and says what blocks it.'));
  ctx.modal('Publish v' + version, body, [{ label: 'Publish', cls: 'btn primary', run: async (close) => {
    const mode = ms[radios.findIndex((r) => r.checked)].mode;
    if (mode === 'live' && !window.confirm('Live replaces the extraction step for every batch. Publish v' + version + ' as Live?')) return;
    if (mayLeave && !mayLeave()) return;
    close();
    try {
      const r = await ctx.api('POST', '/api/pipelines/' + enc(id) + '/versions/' + version + '/publish', { mode });
      await ctx.reload();
      ctx.showNotice(r.kind === 'flow' ? 'Published as a live flow.' : r.kind === 'interpreter' ? 'Published as ' + r.mode + '.' : 'Published: ' + (r.keys || []).join(', '));
    } catch (e) {
      if (e.code === 'publish_blocked') ctx.showError({ message: 'Publish blocked.', problems: e.problems });
      else ctx.showError(e);
    }
  } }]);
}

/** The newest publish with Roll back (naming it to the server), and the history of every publish. */
export async function publishCard(ctx, host) {
  const [pub, hist] = await Promise.all([ctx.api('GET', '/api/publish'), ctx.api('GET', '/api/publish/history')]);
  const head = historyRows(pub.stack || []).slice(0, 1)[0];
  const payload = rollbackPayload(pub);
  const rows = historyRows(hist);
  host.appendChild(h('section', { class: 'card' }, h('h3', null, 'Publishing'),
    head ? h('p', null, 'Newest: ' + head.title + (pub.interpreter ? ' · interpreted ' + pub.interpreter.interpreted + ' · fallbacks ' + pub.interpreter.fallbacks : '')) : h('p', { class: 'muted' }, 'Nothing is published.'),
    payload ? btn(head.groupId ? 'Undo latest (whole group)' : 'Roll back', async () => {
      if (!window.confirm((head.groupId ? 'Undo the whole group, async half first then the capture plan: ' : 'Roll back ') + head.title + '?')) return;
      try {
        await ctx.api('POST', '/api/publish/rollback', payload);
        await ctx.reload();
        ctx.showNotice('Rolled back.');
      } catch (e) {
        if (e.code === 'rollback_blocked') ctx.showError({ message: 'Roll back refused.', problems: rollbackProblems(e.problems) });
        else ctx.showError(e);
      }
    }, 'btn danger') : null,
    rows.length ? h('details', null, h('summary', null, 'History (' + rows.length + ')'),
      rows.map((r) => h('p', { class: 'muted' }, r.title + ' · ' + when(r.publishedAt) + (r.rolledBack ? ' · Rolled back: ' + (r.reason || 'by hand') : '')))) : null));
  await planStatusCard(ctx, host);
}
