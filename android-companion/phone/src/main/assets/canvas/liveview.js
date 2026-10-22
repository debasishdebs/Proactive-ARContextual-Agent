// The Live view: the active interpreter, its label counts and the recent frames with the traces it wrote.
import { h, clear, btn } from './dom.js';
import { photoThumb } from './photos.js';
import { when } from './format.js';

const enc = encodeURIComponent;

export async function liveView(ctx, root) {
  const d = await ctx.api('GET', '/api/live');
  clear(root);
  root.appendChild(h('h2', null, 'Live'));
  if (!d.active) { root.appendChild(h('p', { class: 'muted' }, 'No interpreter is published.')); return; }
  const labels = Object.keys(d.labels || {}).map((k) => k + ' ' + d.labels[k]).join(', ');
  root.appendChild(h('section', { class: 'card' },
    h('div', { class: 'row wrap' }, h('strong', null, d.pipelineId + ' v' + d.version), h('span', { class: 'tag live' }, String(d.mode))),
    h('p', null, 'interpreted ' + d.interpreted + ' · fallbacks ' + d.fallbacks),
    h('p', { class: 'muted' }, labels ? 'Labels: ' + labels : 'No labels yet.'),
    btn('Refresh', () => ctx.reload())));
  for (const f of d.frames || []) {
    const meta = h('div', { class: 'meta' },
      h('p', null, when(f.at) + ' · label: ' + (f.label || 'none') + ' · batch: ' + (f.batch || 'none')),
      (f.nodes || []).map((n) => h('details', null, h('summary', null, n.nodeId + ' · ' + n.status + (n.branch ? ' · ' + n.branch : '')),
        n.output ? h('pre', null, String(n.output)) : null, n.error ? h('p', { class: 'err' }, 'error: ' + n.error) : null)));
    ctx.api('GET', '/api/frames/' + enc(f.frameId)).then((fr) => {
      const mem = (fr && fr.live) || [];
      meta.appendChild(h('div', { class: 'live' }, h('strong', null, 'Live memories'),
        mem.length ? h('ul', null, mem.map((m) => h('li', null, (m.type ? m.type + ': ' : '') + m.text))) : h('p', { class: 'muted' }, 'none')));
    }, () => {});
    root.appendChild(h('section', { class: 'card photo-card' }, h('div', { class: 'photo-head' }, photoThumb(ctx, f.frameId), meta)));
  }
}
