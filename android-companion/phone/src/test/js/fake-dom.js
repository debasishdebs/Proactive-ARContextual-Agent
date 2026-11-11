// A minimal fake DOM for the headless canvas tests. Just enough of the surface the bundle uses; no layout, no HTML parsing.
export class FakeNode {
  constructor() { this.parentNode = null; this.childNodes = []; }
  get firstChild() { return this.childNodes[0] || null; }
  appendChild(c) {
    if (c.parentNode) c.parentNode.removeChild(c);
    c.parentNode = this;
    this.childNodes.push(c);
    return c;
  }
  removeChild(c) {
    const i = this.childNodes.indexOf(c);
    if (i < 0) throw new Error('removeChild: not a child');
    this.childNodes.splice(i, 1);
    c.parentNode = null;
    return c;
  }
  get children() { return this.childNodes.filter((c) => c.nodeType === 1); }
  get textContent() { return this.childNodes.map((c) => c.textContent).join(''); }
  set textContent(v) {
    for (const c of this.childNodes) c.parentNode = null;
    this.childNodes = [];
    const t = String(v);
    if (t !== '') this.appendChild(new FakeText(t));
  }
}

export class FakeText extends FakeNode {
  constructor(data) { super(); this.nodeType = 3; this.data = String(data); }
  get textContent() { return this.data; }
  set textContent(v) { this.data = String(v); }
}

export class FakeElement extends FakeNode {
  constructor(tagName, namespaceURI) {
    super();
    this.nodeType = 1;
    this.tagName = tagName;
    this.namespaceURI = namespaceURI || 'xhtml';
    this.attributes = new Map();
    this.listeners = new Map();
    const self = this;
    this.classList = {
      list() { return (self.attributes.get('class') || '').split(/\s+/).filter(Boolean); },
      contains(n) { return this.list().includes(n); },
      add(n) { if (!this.contains(n)) self.setAttribute('class', this.list().concat(n).join(' ')); },
      remove(n) { self.setAttribute('class', this.list().filter((x) => x !== n).join(' ')); },
      toggle(n, force) {
        const want = force === undefined ? !this.contains(n) : !!force;
        if (want) this.add(n); else this.remove(n);
        return want;
      },
    };
  }
  setAttribute(k, v) { this.attributes.set(String(k), String(v)); }
  getAttribute(k) { return this.attributes.has(k) ? this.attributes.get(k) : null; }
  hasAttribute(k) { return this.attributes.has(k); }
  removeAttribute(k) { this.attributes.delete(k); }
  addEventListener(type, fn) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(fn);
  }
  removeEventListener(type, fn) {
    const l = this.listeners.get(type) || [];
    const i = l.indexOf(fn);
    if (i >= 0) l.splice(i, 1);
  }
  /** Fires [type] on this element; returns the event passed to the listeners. */
  dispatch(type, init) {
    const ev = Object.assign({ type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } }, init || {});
    for (const fn of (this.listeners.get(type) || []).slice()) fn(ev);
    return ev;
  }
}

/** Depth-first first element matching [pred], or null. */
export function find(el, pred) {
  if (el.nodeType !== 1) return null;
  if (pred(el)) return el;
  for (const c of el.children) { const r = find(c, pred); if (r) return r; }
  return null;
}

export function findAll(el, pred, out) {
  const acc = out || [];
  if (el.nodeType !== 1) return acc;
  if (pred(el)) acc.push(el);
  for (const c of el.children) findAll(c, pred, acc);
  return acc;
}

const SVG = 'http:' + '//www.w3.org/2000/svg';
const saved = {};

/** Installs globalThis.document (and Node) with [ids] as pre-made elements for getElementById. */
export function installFakeDom(ids) {
  saved.document = globalThis.document;
  saved.Node = globalThis.Node;
  const byId = new Map();
  for (const id of ids || []) { const e = new FakeElement('div'); e.setAttribute('id', id); byId.set(id, e); }
  globalThis.Node = FakeNode;
  globalThis.document = {
    createElement: (t) => new FakeElement(t),
    createElementNS: (ns, t) => new FakeElement(t, ns),
    createTextNode: (d) => new FakeText(d),
    getElementById: (id) => byId.get(id) || null,
    body: new FakeElement('body'),
    hidden: false,
    listeners: {},
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
  };
  return globalThis.document;
}

export function uninstall() {
  globalThis.document = saved.document;
  globalThis.Node = saved.Node;
}

export const SVG_NS = SVG;
