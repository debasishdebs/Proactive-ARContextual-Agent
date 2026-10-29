// Devices: the four mic and display switches (GET /api/devices, POST /api/devices/capability). Text and checkboxes only; a failed switch shows the API's message and the list is re-read.
import { h, clear } from './dom.js';

export const HUD_NOTE = 'The glasses display is the HUD overlay itself; switched off, answers and nudges skip it and fall back to another surface.';
const ORDER = ['phone.mic', 'phone.display', 'glasses.mic', 'glasses.display'];

/** [{key, device, capability, enabled, label, note}] in the fixed order; unknown pairs are dropped. */
export function deviceRows(list) {
  const rows = (Array.isArray(list) ? list : []).filter((c) => ORDER.includes(c.device + '.' + c.capability)).map((c) => ({
    key: c.device + '.' + c.capability, device: c.device, capability: c.capability, enabled: c.enabled === true,
    label: (c.device === 'phone' ? 'Phone ' : 'Glasses ') + (c.capability === 'mic' ? 'mic' : 'display'),
    note: c.constraint === 'hud' ? HUD_NOTE : '',
  }));
  return rows.sort((a, b) => ORDER.indexOf(a.key) - ORDER.indexOf(b.key));
}

export async function devicesView(ctx, root) {
  const draw = async () => {
    const d = await ctx.api('GET', '/api/devices');
    clear(root);
    root.appendChild(h('h2', null, 'Devices'));
    for (const r of deviceRows(d.capabilities)) {
      const change = async (e) => {
        try { await ctx.api('POST', '/api/devices/capability', { device: r.device, capability: r.capability, enabled: e.target.checked }); } catch (err) { if (err && err.stale) return; ctx.showError(err); }
        try { await draw(); } catch (err) { if (!(err && err.stale)) ctx.showError(err); }
      };
      root.appendChild(h('section', { class: 'card device-row' },
        h('label', null, h('input', { type: 'checkbox', 'aria-label': r.label, checked: r.enabled ? true : null, onchange: change }), ' ' + r.label),
        r.note ? h('p', { class: 'muted' }, r.note) : null));
    }
    root.appendChild(h('p', { class: 'muted' }, 'A switched-off mic ignores that device\'s voice; typing still works. All on by default.'));
  };
  await draw();
}
