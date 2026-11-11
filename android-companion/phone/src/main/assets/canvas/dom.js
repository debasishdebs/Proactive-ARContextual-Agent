// DOM helpers. Everything is built with createElement, textContent and setAttribute: no HTML strings, ever.
// Data from the API (and model output) is untrusted, so there is no path from a string to markup here.
const SVG_NS = 'http:' + '//www.w3.org/2000/svg';
export const enc = encodeURIComponent; // a path segment or query value built from data

function fill(el, attrs, children) {
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    const lk = k.toLowerCase();
    if (lk === 'style') continue; // the CSP forbids inline style; use classes
    if (k === 'class') el.setAttribute('class', String(v));
    else if (k === 'text') el.textContent = String(v);
    else if (lk.startsWith('on')) {
      if (typeof v === 'function') el.addEventListener(lk.slice(2), v);
    } else if (k === 'value') el.value = String(v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, String(v));
  }
  add(el, children);
  return el;
}

export function add(el, children) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue;
    if (Array.isArray(c)) add(el, c);
    else if (c instanceof Node) el.appendChild(c);
    else el.appendChild(document.createTextNode(String(c)));
  }
}

export function h(tag, attrs, ...children) {
  return fill(document.createElement(tag), attrs, children);
}

export function s(tag, attrs, ...children) {
  return fill(document.createElementNS(SVG_NS, tag), attrs, children);
}

export function clear(el) {
  while (el.firstChild) el.removeChild(el.firstChild);
  return el;
}

export function btn(label, onclick, cls) {
  return h('button', { type: 'button', class: cls || 'btn', onclick }, label);
}
