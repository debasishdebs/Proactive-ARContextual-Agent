import { portCheck, problemsByNode, portLabel, edgeKey } from '../../main/assets/canvas/validate.js';
import { nodeTypes, livePipe, eq, ok } from './fixtures.js';

const PROTO = ['constructor', '__proto__', 'hasOwnProperty', 'toString'];

export const tests = {
  protoKeyIdsDontCrash() {
    for (const id of PROTO) {
      const m = problemsByNode([{ text: 'x:' + id, nodeId: id }, { text: 'e', edge: { from: id, to: 'b' } }]);
      eq(Object.prototype.hasOwnProperty.call(m, id) ? m[id] : null, ['x:' + id], id);
      eq(m[id + '>b>'], ['e']);
    }
    const pipe = { nodes: [{ id: 'constructor', type: 'ai.prompt', title: '', params: {} }, { id: 'photos', type: 'input.photos', title: 'Photo', params: {} }], edges: [] };
    eq(portCheck(pipe, 'constructor', 'photos', nodeTypes), 'Photo takes no input');
    eq(portCheck(pipe, 'photos', 'constructor', [].concat(nodeTypes)), null);
    eq(portLabel('constructor'), 'constructor');
    const asMap = {}; for (const t of nodeTypes) asMap[t.type] = t;
    eq(portCheck({ nodes: [{ id: 'a', type: 'constructor', title: '', params: {} }, { id: 'b', type: 'ai.prompt', title: 'P', params: {} }], edges: [] }, 'a', 'b', asMap), 'Unknown node type');
  },
  portCheckAcceptsListedInputs() {
    const p = livePipe();
    eq(portCheck(p, 'photos', 'extract', nodeTypes), null);
    eq(portCheck(p, 'extract', 'critic', nodeTypes), null);
    eq(portCheck(p, 'extract', 'write', nodeTypes), null);
  },
  portCheckMixedIncoming() {
    const p = livePipe();
    p.nodes.push({ id: 'prompt_1', type: 'ai.prompt', title: 'Prompt', params: {} });
    p.edges.push({ from: 'extract', to: 'prompt_1' });            // EXTRACTION incoming already
    eq(portCheck(p, 'photos', 'prompt_1', nodeTypes), 'Prompt already receives Facts');
    eq(portCheck(p, 'extract', 'prompt_1', nodeTypes), null, 'same type is fine');
  },
  problemsByNodeAndEdge() {
    const m = problemsByNode([
      { text: 'node a: bad id', nodeId: 'a' },
      { text: 'node a: second', nodeId: 'a' },
      { text: 'edge x->y: when required (pass, fail)', edge: { from: 'x', to: 'y' } },
      { text: 'edge x->y: unknown when "z"', edge: { from: 'x', to: 'y', when: 'z' } },
      { text: 'too many nodes (max 40)' },
    ]);
    eq(m.a, ['node a: bad id', 'node a: second']);
    eq(m['x>y>'], ['edge x->y: when required (pass, fail)']);
    eq(m['x>y>z'], ['edge x->y: unknown when "z"']);
    eq(m[''], ['too many nodes (max 40)'], 'unattributed problems sit under the empty key');
    eq(edgeKey({ from: 'x', to: 'y', when: 'z' }), 'x>y>z');
    eq(problemsByNode(null), {});
  },
  refusalTextExact() {
    eq(portCheck(livePipe(), 'write', 'extract', nodeTypes), 'Facts from frames accepts Photo or JSON, not Preview');
    eq(portCheck(livePipe(), 'extract', 'photos', nodeTypes), 'Photo takes no input');
    eq(portLabel('PHOTOS'), 'Photo'); eq(portLabel('EXTRACTION'), 'Facts'); eq(portLabel('JSON'), 'JSON'); eq(portLabel('PREVIEW'), 'Preview');
    ok(portCheck(livePipe(), 'trust', 'prompt', nodeTypes), 'unknown node refused');
  },
};
