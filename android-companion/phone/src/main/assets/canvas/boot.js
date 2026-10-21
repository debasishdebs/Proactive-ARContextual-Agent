// Pure boot-fragment parsing (no document/window), so it can be tested headless. It never throws.

/**
 * Reads the location hash handed to the page.
 * `#t=<token>` carries a session token, `#pair=<code>` prefills the pairing code, anything else is a route.
 * A malformed fragment (bad percent-escape, empty token) falls back to pairing with no prefill.
 * Returns { token?, pair?, route }.
 */
export function parseBootHash(hash) {
  const h = typeof hash === 'string' ? hash : '';
  try {
    if (h.startsWith('#t=')) {
      const token = decodeURIComponent(h.slice(3));
      return token ? { token, route: '/' } : { pair: '', route: '/' };
    }
    if (h.startsWith('#pair=')) {
      return { pair: decodeURIComponent(h.slice(6)).replace(/[^0-9]/g, '').slice(0, 6), route: '/' };
    }
  } catch (e) {
    return { pair: '', route: '/' };
  }
  return { route: h.replace(/^#/, '') || '/' };
}
