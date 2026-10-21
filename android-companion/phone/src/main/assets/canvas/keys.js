// Pure keyboard decisions over layout cells ({id: {layer, column}}). Returns intents; never touches the DOM.

export const idle = { mode: 'idle' };

const ARROWS = ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'];

/** The nearest node in the arrow's direction by layer and column; the current id when there is none. */
export function move(cells, currentId, key) {
  const cur = cells && cells[currentId];
  if (!cur || !ARROWS.includes(key)) return currentId;
  let best = null, bestKey = null;
  for (const id of Object.keys(cells).sort()) {
    const c = cells[id];
    if (id === currentId) continue;
    let k = null;
    if (key === 'ArrowDown' && c.layer > cur.layer) k = [c.layer - cur.layer, Math.abs(c.column - cur.column), c.column];
    else if (key === 'ArrowUp' && c.layer < cur.layer) k = [cur.layer - c.layer, Math.abs(c.column - cur.column), c.column];
    else if (key === 'ArrowRight' && c.layer === cur.layer && c.column > cur.column) k = [0, c.column - cur.column, c.column];
    else if (key === 'ArrowLeft' && c.layer === cur.layer && c.column < cur.column) k = [0, cur.column - c.column, c.column];
    if (!k) continue;
    if (!bestKey || k[0] < bestKey[0] || (k[0] === bestKey[0] && (k[1] < bestKey[1] || (k[1] === bestKey[1] && k[2] < bestKey[2])))) { best = id; bestKey = k; }
  }
  return best === null ? currentId : best;
}

/**
 * state: {mode:'idle'} | {mode:'connecting', from, target}; ctx: {cells, current (focused node id|null)}.
 * → {state, action?}; action is {action:'focus'|'target'|'connect'|'cancel'|'palette'|'remove', ...}.
 */
export function reduce(state, key, ctx) {
  const st = state || idle;
  const cells = (ctx && ctx.cells) || {};
  const current = ctx ? ctx.current : null;
  if (st.mode === 'connecting') {
    if (key === 'Escape') return { state: idle, action: { action: 'cancel' } };
    if (key === 'Enter') {
      if (st.target && st.target !== st.from) return { state: idle, action: { action: 'connect', from: st.from, to: st.target } };
      return { state: st };
    }
    if (ARROWS.includes(key)) {
      const target = move(cells, st.target || st.from, key);
      if (target === st.target) return { state: st };
      return { state: { mode: 'connecting', from: st.from, target }, action: { action: 'target', id: target } };
    }
    return { state: st };
  }
  if (key === 'c') return current ? { state: { mode: 'connecting', from: current, target: current } } : { state: st };
  if (key === 'a') return { state: st, action: { action: 'palette' } };
  if (key === 'Delete' || key === 'Backspace') return current ? { state: st, action: { action: 'remove', id: current } } : { state: st };
  if (ARROWS.includes(key) && current) {
    const id = move(cells, current, key);
    return id === current ? { state: st } : { state: st, action: { action: 'focus', id } };
  }
  return { state: st };
}
