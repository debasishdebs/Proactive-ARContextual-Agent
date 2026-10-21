import { h, s, clear } from '../../main/assets/canvas/dom.js';
import { installFakeDom, uninstall, SVG_NS } from './fake-dom.js';

function eq(a, b, msg) { if (a !== b) throw new Error((msg || 'expected') + ': ' + JSON.stringify(a) + ' !== ' + JSON.stringify(b)); }

export const tests = {
  hSetsTextNotHtml() {
    installFakeDom();
    try {
      const el = h('div', null, '<b>x</b>');
      eq(el.childNodes.length, 1, 'one child');
      eq(el.childNodes[0].nodeType, 3, 'a text node');
      eq(el.childNodes[0].data, '<b>x</b>', 'literal text');
      eq(el.children.length, 0, 'no element children');
      const t = h('p', { text: '<i>y</i>' });
      eq(t.textContent, '<i>y</i>', 'text attr is literal');
      eq(t.children.length, 0, 'text attr makes no elements');
      clear(el);
      eq(el.childNodes.length, 0, 'clear empties');
    } finally { uninstall(); }
  },
  attrsViaSetAttribute() {
    installFakeDom();
    try {
      let clicked = 0;
      const el = h('button', { class: 'btn', type: 'button', disabled: true, hidden: false, style: 'color:red', onclick: () => { clicked++; } }, 'go');
      eq(el.getAttribute('class'), 'btn');
      eq(el.getAttribute('type'), 'button');
      eq(el.getAttribute('disabled'), '', 'true becomes an empty attribute');
      eq(el.getAttribute('hidden'), null, 'false is skipped');
      eq(el.getAttribute('style'), null, 'inline style is never set');
      el.dispatch('click');
      eq(clicked, 1, 'on* function becomes a listener');
    } finally { uninstall(); }
  },
  svgNamespace() {
    installFakeDom();
    try {
      const g = s('g', { class: 'n' }, s('rect', { width: 5 }));
      eq(g.namespaceURI, SVG_NS);
      eq(g.children[0].namespaceURI, SVG_NS);
      eq(g.children[0].getAttribute('width'), '5');
      eq(h('div').namespaceURI, 'xhtml');
    } finally { uninstall(); }
  },
};
