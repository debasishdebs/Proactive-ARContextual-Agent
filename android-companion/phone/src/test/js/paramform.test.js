import { fields, coerce, promptParts, coercePromptPart } from '../../main/assets/canvas/paramform.js';
import { nodeTypes, eq, ok } from './fixtures.js';

const desc = (type) => nodeTypes.find((t) => t.type === type).params;
const ctx = { roles: ['VISION', 'TEXT'], prompts: ['live', 'draft'] };
const field = (type, name, params) => fields(desc(type), params || {}, ctx).find((f) => f.name === name);

export const tests = {
  protoKeyNamesDontCrash() {
    const d = [{ name: 'constructor', kind: 'int', min: 1, max: 3 }, { name: '__proto__', kind: 'text' }, { name: 'toString', kind: 'bool' }];
    const f = fields(d, {}, ctx);
    eq(f.map((x) => x.value), [1, '', false]);
    eq(fields(d, JSON.parse('{"constructor":2,"__proto__":"hi","toString":true}'), ctx).map((x) => x.value), [2, 'hi', true]);
  },
  intFieldBoundsFromDescriptor() {
    const f = field('ai.vision_extract', 'batch', { batch: 2 });
    eq(f.control, 'number'); eq(f.min, 1); eq(f.max, 3); eq(f.value, 2); eq(f.label, 'Batch');
    const p = field('ai.vision_extract', 'prompt', { prompt: { source: 'live' } });
    eq(p.control, 'select'); eq(p.value, 'live');
    eq(p.options, [{ value: 'live', label: 'live' }, { value: 'draft', label: 'draft' }]);
    const r = field('ai.prompt', 'role', { role: 'TEXT' });
    eq(r.control, 'select'); eq(r.options.map((o) => o.value), ['VISION', 'TEXT']); eq(r.value, 'TEXT');
    eq(field('ai.prompt', 'template').control, 'textarea');
    eq(field('ai.prompt', 'schema').control, 'textarea');
    eq(field('ai.prompt', 'images').control, 'checkbox');
    eq(field('route.switch', 'cases', { cases: ['a', 'b'] }).control, 'labels');
    eq(field('route.switch', 'cases', { cases: ['a', 'b'] }).value, 'a, b');
  },
  coerceIntOutOfRange() {
    const f = field('ai.vision_extract', 'batch');
    eq(coerce(f, '0'), { error: 'Must be between 1 and 3' });
    eq(coerce(f, '4'), { error: 'Must be between 1 and 3' });
    eq(coerce(f, '3'), { value: 3 });
    eq(coerce(f, 1), { value: 1 });
  },
  coerceNonInteger() {
    const f = field('ai.vision_extract', 'batch');
    eq(coerce(f, '1.5'), { error: 'Must be a whole number' });
    eq(coerce(f, ''), { error: 'Must be a whole number' });
    eq(coerce(f, 'abc'), { error: 'Must be a whole number' });
    eq(coerce(f, ' 2 '), { value: 2 });
  },
  boolCheckbox() {
    const f = field('ai.prompt', 'images', { images: true });
    eq(f.value, true);
    eq(coerce(f, true), { value: true });
    eq(coerce(f, false), { value: false });
    eq(coerce(f, 'true'), { value: true });
    eq(coerce(f, 'false'), { value: false });
  },
  schemaMustBeJson() {
    const f = field('ai.prompt', 'schema');
    eq(coerce(f, '{"type":"object"}'), { value: '{"type":"object"}' });
    eq(coerce(f, '{nope'), { error: 'Must be valid JSON' });
    const clear = coerce(f, '');
    eq('value' in clear && clear.value === undefined && !clear.error, true, 'an empty schema clears the param');
    eq(coerce(f, '   ').error, undefined);
    for (const bad of ['[]', '1', 'null', '"s"', 'true']) eq(coerce(f, bad), { error: 'Must be a JSON object' }, bad);
    eq(field('ai.prompt', 'schema', { schema: { type: 'object' } }).value, '{"type":"object"}', 'an object schema is shown as JSON text');
  },
  labelsSplitTrimUnique() {
    const f = field('route.switch', 'cases');
    eq(coerce(f, ' a, b ,,c '), { value: ['a', 'b', 'c'] });
    eq(coerce(f, 'a, b, a'), { error: 'Labels must be unique' });
    ok(coerce(f, '').error, 'at least one label');
    ok(coerce(f, 'other').error, 'other is reserved');
    ok(coerce(f, 'Has Space').error, 'ids only');
    ok(coerce(f, 'a,b,c,d,e,f,g,h,i,j,k,l,m').error, 'at most 12');
  },
  selectCoerce() {
    const p = field('ai.vision_extract', 'prompt', { prompt: { source: 'live' } });
    eq(coerce(p, 'draft'), { value: { source: 'draft', template: '' } });
    eq(coerce(p, 'live'), { value: { source: 'live' } });
    ok(coerce(p, 'zzz').error);
    const r = field('ai.prompt', 'role');
    eq(coerce(r, 'VISION'), { value: 'VISION' });
    ok(coerce(r, 'EMBED').error);
  },
  defaultsWhenParamMissing() {
    const f = fields(desc('ai.vision_extract'), {}, ctx);
    eq(f.find((x) => x.name === 'batch').value, 1, 'int: min');
    eq(f.find((x) => x.name === 'prompt').value, 'live');
    const g = fields(desc('ai.prompt'), {}, ctx);
    eq(g.find((x) => x.name === 'images').value, false);
    eq(g.find((x) => x.name === 'template').value, '');
    eq(g.find((x) => x.name === 'role').value, '');
    eq(fields(desc('route.switch'), {}, ctx).find((x) => x.name === 'cases').value, '');
    eq(fields(undefined, {}, ctx), []);
  },
  switchFieldDefaultsToActivity() {
    const f = fields(desc('route.switch'), {}, ctx).find((x) => x.name === 'field');
    eq([f.control, f.value], ['text', 'activity'], 'a text param shows its descriptor default when absent');
    eq(fields(desc('route.switch'), { field: 'mood' }, ctx).find((x) => x.name === 'field').value, 'mood');
    eq(coerce(f, 'mood'), { value: 'mood' });
  },
  promptPartsLiveAreReadOnly() {
    const f = field('ai.vision_extract', 'prompt', { prompt: { source: 'live' } });
    eq(promptParts(f, { template: 'T', schema: '{"a":1}' }).map((p) => [p.part, p.value, p.readOnly, p.control]), [['template', 'T', true, 'textarea'], ['schema', '{"a":1}', true, 'textarea']]);
    eq(promptParts(f, null), [], 'no live prompt known: nothing to show');
    eq(promptParts(f, { template: 'T', schema: { a: 1 } })[1].value, '{"a":1}', 'an object schema is shown as JSON text');
  },
  promptPartsDraftAreEditable() {
    const f = field('ai.vision_extract', 'prompt', { prompt: { source: 'draft', template: 'mine', schema: '{}' } });
    eq(promptParts(f, { template: 'T' }).map((p) => [p.part, p.value, p.readOnly]), [['template', 'mine', false], ['schema', '{}', false]]);
    const bare = field('ai.vision_extract', 'prompt', { prompt: { source: 'draft' } });
    eq(promptParts(bare, null).map((p) => p.value), ['', '']);
  },
  coercePromptPartRules() {
    const f = field('ai.vision_extract', 'prompt', { prompt: { source: 'draft', template: 'a', schema: '{}' } });
    eq(coercePromptPart(f, 'template', 'new'), { value: { source: 'draft', template: 'new', schema: '{}' } });
    eq(coercePromptPart(f, 'schema', ''), { value: { source: 'draft', template: 'a' } });
    eq(coercePromptPart(f, 'schema', '{"x":1}'), { value: { source: 'draft', template: 'a', schema: '{"x":1}' } });
    eq(coercePromptPart(f, 'schema', '{nope'), { error: 'Must be valid JSON' });
    eq(coercePromptPart(f, 'schema', '[1]'), { error: 'Must be a JSON object' });
    eq(coercePromptPart(f, 'other', 'x'), { error: 'Unknown prompt field' });
    const live = field('ai.vision_extract', 'prompt', { prompt: { source: 'live' } });
    eq(coercePromptPart(live, 'template', 'x'), { error: 'Switch to Draft to edit the prompt' });
    eq(f.current.template, 'a', 'the field is not mutated');
  },
};
