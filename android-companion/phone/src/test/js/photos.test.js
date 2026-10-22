import { photoThumb, openPhoto, failText, GONE, FAILED } from '../../main/assets/canvas/photos.js';
import { h } from '../../main/assets/canvas/dom.js';
import { installFakeDom, uninstall, find, findAll } from './fake-dom.js';

function eq(a, b, msg) { const x = JSON.stringify(a), y = JSON.stringify(b); if (x !== y) throw new Error((msg || 'expected') + ': ' + x + ' !== ' + y); }
function ok(v, msg) { if (!v) throw new Error(msg || 'expected truthy'); }

/** ctx stub: records loadImage calls (and their lazy flag), runs onFail(status) when told to, records modals. */
function fakeCtx(failStatus) {
  const loads = [], modals = [];
  return {
    loads, modals,
    loadImage(img, path, lazy, onFail) { loads.push({ img, path, lazy }); if (failStatus && onFail) onFail(failStatus); },
    modal(title, content, actions, cls) { modals.push({ title, content, actions, cls }); return () => {}; },
  };
}
const withDom = (fn) => { installFakeDom(); try { fn(); } finally { uninstall(); } };

export const tests = {
  failTextByStatus() {
    eq(failText(404), GONE); eq(failText(410), GONE);
    eq(failText(500), FAILED); eq(failText(undefined), FAILED);
    eq(GONE, 'Photo no longer available');
  },
  thumbRequestsTheThumbRouteLazily() {
    withDom(() => {
      const ctx = fakeCtx();
      const box = photoThumb(ctx, 'f/1');
      eq(ctx.loads.length, 1);
      eq(ctx.loads[0].path, '/api/frames/f%2F1/thumb'); eq(ctx.loads[0].lazy, true);
      ok(!ctx.loads[0].path.includes('token'), 'no token in the URL: the bearer header does it');
      ok(find(box, (e) => e.tagName === 'button'), 'a button wraps the image');
    });
  },
  missingPhotoShowsTheMessageAndNoButton() {
    withDom(() => {
      const box = photoThumb(fakeCtx(404), 'x');
      ok(box.textContent.includes('Photo no longer available'));
      eq(find(box, (e) => e.tagName === 'button'), null, 'nothing to open');
    });
  },
  otherFailureSaysCouldNotLoad() {
    withDom(() => {
      const box = photoThumb(fakeCtx(500), 'x');
      ok(box.textContent.includes(FAILED));
    });
  },
  clickOpensFullSizeNextToTheValue() {
    withDom(() => {
      const ctx = fakeCtx();
      const box = photoThumb(ctx, 'f1', { value: () => h('p', { class: 'model-value' }, 'caption: a cat') });
      find(box, (e) => e.tagName === 'button').dispatch('click');
      eq(ctx.modals.length, 1);
      eq(ctx.modals[0].cls, 'fullshot');
      const c = ctx.modals[0].content;
      eq(ctx.loads[1].path, '/api/frames/f1/image'); eq(ctx.loads[1].lazy, false);
      ok(find(c, (e) => e.getAttribute('class') === 'model-value'), 'the returned value is beside the photo');
      ok(find(c, (e) => e.tagName === 'img'));
    });
  },
  fullSizeMissingShowsTheMessageInTheModal() {
    withDom(() => {
      const ctx = fakeCtx(404);
      openPhoto(ctx, 'gone', () => h('p', null, 'value'));
      const c = ctx.modals[0].content;
      ok(c.textContent.includes('Photo no longer available'));
      ok(c.textContent.includes('value'), 'the value stays visible');
      eq(findAll(c, (e) => e.tagName === 'img').length, 0);
    });
  },
  noValueStillOpens() {
    withDom(() => {
      const ctx = fakeCtx();
      openPhoto(ctx, 'f2');
      eq(ctx.modals.length, 1);
    });
  },
};
