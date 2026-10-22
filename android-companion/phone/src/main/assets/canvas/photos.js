// A tested image as a thumbnail that opens full screen next to the model's returned value (spec 0z).
// Photos come from the authenticated canvas routes through ctx.loadImage (bearer header -> blob URL, revoked on leave);
// the credential never appears in a photo URL. A forgotten photo answers 404 and shows a message instead of a picture.
import { h, clear } from './dom.js';

export const GONE = 'Photo no longer available';
export const FAILED = 'Photo could not be loaded';
const enc = encodeURIComponent;

export const failText = (status) => (status === 404 || status === 410 ? GONE : FAILED);

/** Full size, with the model's returned value beside it. value: () => Node | Node[] (built fresh for the dialog). */
export function openPhoto(ctx, frameId, value) {
  const img = h('img', { alt: 'photo', class: 'big' });
  const slot = h('div', { class: 'shot' }, img);
  const side = h('div', { class: 'shotvalue' }, value ? value() : null);
  ctx.modal('Photo', h('div', { class: 'fullshot' }, slot, side), [], 'fullshot');
  ctx.loadImage(img, '/api/frames/' + enc(frameId) + '/image', false, (status) => {
    clear(slot);
    slot.appendChild(h('p', { class: 'muted gone', role: 'status' }, failText(status)));
  });
}

/** A thumbnail button for a frame (opts: {value, alt, lazy}). */
export function photoThumb(ctx, frameId, opts) {
  const o = opts || {};
  const img = h('img', { alt: o.alt || 'photo', class: 'photo', width: 160, height: 160 });
  const open = h('button', { type: 'button', class: 'photo-btn', 'aria-label': 'Show this photo full screen' }, img);
  open.addEventListener('click', () => openPhoto(ctx, frameId, o.value));
  const box = h('div', { class: 'photo-thumb' }, open);
  ctx.loadImage(img, '/api/frames/' + enc(frameId) + '/thumb', o.lazy !== false, (status) => {
    clear(box);
    box.appendChild(h('p', { class: 'muted gone', role: 'status' }, failText(status)));
  });
  return box;
}
