// Pure port checks and problem grouping for the canvas editor. No DOM, no React.

const LABELS = { PHOTOS: 'Photo', EXTRACTION: 'Facts', JSON: 'JSON', PREVIEW: 'Preview' };

export const portLabel = (port) => LABELS[port] || String(port);

/** Node type lookup over the /api/node-types array (or a {type: nodeType} map). */
export function typeOf(nodeTypes, type) {
  if (Array.isArray(nodeTypes)) return nodeTypes.find((t) => t.type === type) || null;
  return (nodeTypes && nodeTypes[type]) || null;
}

const nodeOf = (pipe, id) => ((pipe && pipe.nodes) || []).find((n) => n.id === id) || null;
const titleOf = (node, nt) => String((node && node.title) || (nt && nt.label) || (node && node.id) || '');

function list(items) {
  if (items.length <= 1) return items.join('');
  return items.slice(0, -1).join(', ') + ' or ' + items[items.length - 1];
}

/** null when from → to is allowed by port types, else a short fixed refusal text. */
export function portCheck(pipe, from, to, nodeTypes) {
  const a = nodeOf(pipe, from), b = nodeOf(pipe, to);
  if (!a || !b) return 'No such node';
  const ta = typeOf(nodeTypes, a.type), tb = typeOf(nodeTypes, b.type);
  if (!ta || !tb) return 'Unknown node type';
  const accepts = tb.inputs || [];
  if (accepts.length === 0) return titleOf(b, tb) + ' takes no input';
  if (!accepts.includes(ta.output)) {
    return titleOf(b, tb) + ' accepts ' + list(accepts.map(portLabel)) + ', not ' + portLabel(ta.output);
  }
  for (const e of (pipe.edges || [])) {
    if (e.to !== to || e.from === from) continue;
    const src = nodeOf(pipe, e.from);
    const ts = src && typeOf(nodeTypes, src.type);
    if (ts && ts.output !== ta.output) return titleOf(b, tb) + ' already receives ' + portLabel(ts.output);
  }
  return null;
}

/** The edge key used for ids and problem grouping: "from>to>when" (when empty when absent). */
export const edgeKey = (e) => e.from + '>' + e.to + '>' + (e.when == null ? '' : e.when);

/** problems: [{text, nodeId?, edge?:{from,to,when?}}] (or plain strings) → {nodeId|edgeKey: [text]}; unattributed under "". */
export function problemsByNode(problems) {
  const out = {};
  const add = (k, t) => { (out[k] || (out[k] = [])).push(t); };
  for (const p of problems || []) {
    if (typeof p === 'string') { add('', p); continue; }
    if (!p) continue;
    const text = String(p.text);
    let placed = false;
    if (p.edge) { add(edgeKey(p.edge), text); placed = true; }
    if (p.nodeId) { add(p.nodeId, text); placed = true; }
    if (!placed) add('', text);
  }
  return out;
}
