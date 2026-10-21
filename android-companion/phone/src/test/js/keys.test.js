import { move, reduce, idle } from '../../main/assets/canvas/keys.js';
import { liveCells, eq } from './fixtures.js';

// a0 a1 on layer 0 (cols 0,1); b0 on layer 1 (col 0); c0, c2 on layer 2
const cells = {
  a0: { layer: 0, column: 0 }, a1: { layer: 0, column: 1 },
  b0: { layer: 1, column: 0 },
  c0: { layer: 2, column: 0 }, c2: { layer: 2, column: 2 },
};

export const tests = {
  protoKeyIdsDontCrash() {
    const c = JSON.parse('{"constructor":{"layer":0,"column":0},"__proto__":{"layer":1,"column":0},"x":{"layer":2,"column":0}}');
    eq(move(c, 'constructor', 'ArrowDown'), '__proto__');
    eq(move(c, '__proto__', 'ArrowDown'), 'x');
    eq(move(cells, 'toString', 'ArrowDown'), 'toString', 'an id that is not a cell stays');
    eq(move(cells, 'constructor', 'ArrowDown'), 'constructor');
    eq(reduce(idle, 'ArrowDown', { cells, current: 'hasOwnProperty' }).action, undefined);
  },
  arrowMovesByLayerAndColumn() {
    eq(move(cells, 'a0', 'ArrowDown'), 'b0');
    eq(move(cells, 'a1', 'ArrowDown'), 'b0', 'nearest column in the next layer');
    eq(move(cells, 'b0', 'ArrowDown'), 'c0');
    eq(move(cells, 'c2', 'ArrowUp'), 'b0');
    eq(move(cells, 'a0', 'ArrowRight'), 'a1');
    eq(move(cells, 'c0', 'ArrowRight'), 'c2');
    eq(move(cells, 'c2', 'ArrowLeft'), 'c0');
  },
  staysAtEdge() {
    eq(move(cells, 'a0', 'ArrowUp'), 'a0');
    eq(move(cells, 'a0', 'ArrowLeft'), 'a0');
    eq(move(cells, 'c2', 'ArrowRight'), 'c2');
    eq(move(cells, 'c0', 'ArrowDown'), 'c0');
    eq(move(cells, 'zzz', 'ArrowDown'), 'zzz', 'unknown id stays');
    eq(move(cells, 'a0', 'x'), 'a0', 'other keys stay');
  },
  connectModeFlow() {
    let r = reduce(idle, 'c', { cells, current: 'a0' });
    eq(r.state, { mode: 'connecting', from: 'a0', target: 'a0' });
    r = reduce(r.state, 'ArrowDown', { cells, current: 'a0' });
    eq(r.state.target, 'b0'); eq(r.action, { action: 'target', id: 'b0' });
    r = reduce(r.state, 'ArrowDown', { cells, current: 'a0' });
    eq(r.state.target, 'c0');
    r = reduce(r.state, 'Enter', { cells, current: 'a0' });
    eq(r.action, { action: 'connect', from: 'a0', to: 'c0' });
    eq(r.state, idle);
  },
  escCancels() {
    let r = reduce(idle, 'c', { cells, current: 'a0' });
    r = reduce(r.state, 'Escape', { cells, current: 'a0' });
    eq(r.state, idle); eq(r.action, { action: 'cancel' });
    r = reduce(idle, 'c', { cells, current: 'a0' });
    r = reduce(r.state, 'Enter', { cells, current: 'a0' });
    eq(r.state.mode, 'connecting', 'Enter on the source itself does not connect');
    eq(r.action, undefined);
    eq(reduce(idle, 'Escape', { cells, current: 'a0' }).action, undefined);
  },
  aFocusesPalette() {
    eq(reduce(idle, 'a', { cells, current: 'a0' }).action, { action: 'palette' });
    eq(reduce(idle, 'a', { cells, current: null }).action, { action: 'palette' });
  },
  deleteEmitsRemove() {
    eq(reduce(idle, 'Delete', { cells, current: 'b0' }).action, { action: 'remove', id: 'b0' });
    eq(reduce(idle, 'Backspace', { cells, current: 'b0' }).action, { action: 'remove', id: 'b0' });
    eq(reduce(idle, 'Delete', { cells, current: null }).action, undefined);
    eq(reduce(idle, 'ArrowDown', { cells, current: 'a0' }).action, { action: 'focus', id: 'b0' });
    eq(reduce(idle, 'ArrowUp', { cells, current: 'a0' }).action, undefined, 'no move, no action');
    eq(liveCells.write.layer, 4);
  },
};
