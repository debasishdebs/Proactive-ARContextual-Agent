// The inspector's field model: node-type param descriptors → form fields, and raw input → coerced values. Pure.
// No JSON Schema library: a schema is only checked to be valid JSON here; the server is the authority.

const ID = /^[a-z][a-z0-9_]{0,31}$/;

const label = (name) => {
  const t = String(name).replace(/_/g, ' ');
  return t.charAt(0).toUpperCase() + t.slice(1);
};

const options = (list) => (Array.isArray(list) ? list : []).map((o) => (typeof o === 'string' ? { value: o, label: o } : { value: String(o.value), label: String(o.label == null ? o.value : o.label) }));

/**
 * descriptors from /api/node-types params: {name, kind: int|bool|text|prompt|role|schema|labels, min?, max?, default?}
 * → [{name, kind, control: number|checkbox|text|textarea|select|labels, label, min?, max?, options?, value, current}]
 * value is the current param, or its default; ctx = {roles, prompts} supplies the select options.
 */
export function fields(descriptors, params, ctx) {
  const ps = params || {};
  return (descriptors || []).map((d) => {
    const cur = ps[d.name];
    const f = { name: d.name, kind: d.kind, label: label(d.name), current: cur };
    switch (d.kind) {
      case 'int':
        f.control = 'number';
        if (d.min !== undefined) f.min = d.min;
        if (d.max !== undefined) f.max = d.max;
        f.value = cur !== undefined ? cur : d.default !== undefined ? d.default : d.min !== undefined ? d.min : 0;
        break;
      case 'bool':
        f.control = 'checkbox';
        f.value = cur !== undefined ? cur === true : d.default === true;
        break;
      case 'prompt':
        f.control = 'select';
        f.options = options(ctx && ctx.prompts);
        f.value = cur && typeof cur === 'object' && cur.source ? String(cur.source) : 'live';
        break;
      case 'role':
        f.control = 'select';
        f.options = options(ctx && ctx.roles);
        f.value = cur !== undefined ? String(cur) : '';
        break;
      case 'schema':
        f.control = 'textarea';
        f.value = cur === undefined ? '' : typeof cur === 'string' ? cur : JSON.stringify(cur);
        break;
      case 'labels':
        f.control = 'labels';
        f.value = Array.isArray(cur) ? cur.join(', ') : '';
        break;
      default: // text
        f.control = d.name === 'template' ? 'textarea' : 'text';
        f.value = cur === undefined ? '' : String(cur);
    }
    return f;
  });
}

const rangeText = (f) => (f.min !== undefined && f.max !== undefined ? 'Must be between ' + f.min + ' and ' + f.max
  : f.min !== undefined ? 'Must be at least ' + f.min : 'Must be at most ' + f.max);

/** raw input → {value} | {error}. */
export function coerce(f, raw) {
  switch (f.kind) {
    case 'int': {
      const s = typeof raw === 'number' ? String(raw) : String(raw == null ? '' : raw).trim();
      if (!/^-?\d+$/.test(s)) return { error: 'Must be a whole number' };
      const n = Number(s);
      if ((f.min !== undefined && n < f.min) || (f.max !== undefined && n > f.max)) return { error: rangeText(f) };
      return { value: n };
    }
    case 'bool':
      return { value: raw === true || raw === 'true' };
    case 'prompt': {
      const src = String(raw);
      if (!f.options.some((o) => o.value === src)) return { error: 'Pick one of the listed options' };
      const base = f.current && typeof f.current === 'object' ? Object.assign({}, f.current) : {};
      base.source = src;
      if (src === 'draft' && typeof base.template !== 'string') base.template = '';
      if (src === 'live') { delete base.template; delete base.schema; }
      return { value: base };
    }
    case 'role': {
      const v = String(raw);
      if (!f.options.some((o) => o.value === v)) return { error: 'Pick one of the listed options' };
      return { value: v };
    }
    case 'schema': {
      const s = String(raw == null ? '' : raw);
      try { JSON.parse(s); } catch (e) { return { error: 'Must be valid JSON' }; }
      return { value: s };
    }
    case 'labels': {
      const items = String(raw == null ? '' : raw).split(',').map((x) => x.trim()).filter((x) => x.length > 0);
      if (items.length === 0) return { error: 'Add at least one label' };
      if (items.length > 12) return { error: 'At most 12 labels' };
      if (new Set(items).size !== items.length) return { error: 'Labels must be unique' };
      if (items.some((x) => !ID.test(x) || x === 'other')) return { error: 'Labels use a-z, 0-9 and _, and "other" is reserved' };
      return { value: items };
    }
    default:
      return { value: String(raw == null ? '' : raw) };
  }
}
