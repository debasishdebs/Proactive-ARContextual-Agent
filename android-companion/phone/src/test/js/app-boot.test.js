import { parseBootHash } from '../../main/assets/canvas/boot.js';

function eq(a, b, msg) { if (a !== b) throw new Error((msg || 'expected') + ': ' + JSON.stringify(a) + ' !== ' + JSON.stringify(b)); }

export const tests = {
  tokenFragment() {
    const r = parseBootHash('#t=abc%2Bdef');
    eq(r.token, 'abc+def');
    eq(r.pair, undefined);
    eq(r.route, '/');
  },
  pairFragment() {
    const r = parseBootHash('#pair=12%2034-56x789');
    eq(r.pair, '123456', 'digits only, at most 6');
    eq(r.token, undefined);
  },
  malformedFragmentIsPairing() {
    const r = parseBootHash('#t=%E0%A4%A');
    eq(r.token, undefined, 'no token from a malformed escape');
    eq(r.pair, '', 'falls back to pairing with no prefill');
    const p = parseBootHash('#pair=%zz');
    eq(p.pair, '');
    for (const bad of [undefined, null, 42, '']) parseBootHash(bad);
  },
  routeParsed() {
    const r = parseBootHash('#/p/abc?x=1');
    eq(r.route, '/p/abc?x=1');
    eq(r.token, undefined);
    eq(r.pair, undefined);
    eq(parseBootHash('').route, '/');
    eq(parseBootHash('#').route, '/');
  },
};
