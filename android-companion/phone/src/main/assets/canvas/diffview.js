// Pure: turns the server's PipelineDiff JSON into row models (classes add / del / chg / same). No DOM.
const edgeText = (e) => e.from + ' → ' + e.to + (e.when ? ' (when ' + e.when + ')' : '');
const LINE = { added: 'add', removed: 'del', same: 'same' };

export function rowsFor(d) {
  const diff = d || {};
  const nodes = (diff.nodes || []).map((n) => ({
    cls: n.kind === 'added' ? 'add' : n.kind === 'removed' ? 'del' : 'chg',
    text: (n.kind === 'added' ? 'Added node ' : n.kind === 'removed' ? 'Removed node ' : 'Changed node ') + n.nodeId + (n.kind === 'changed' && (n.params || []).length ? ': ' + n.params.join(', ') : ''),
  }));
  const edges = (diff.edgesAdded || []).map((e) => ({ cls: 'add', text: edgeText(e) })).concat((diff.edgesRemoved || []).map((e) => ({ cls: 'del', text: edgeText(e) })));
  const prompts = (diff.prompts || []).map((p) => ({ title: p.nodeId + ' · ' + p.part, lines: (p.lines || []).map((l) => ({ cls: LINE[l.op] || 'same', text: l.text })) }));
  return { renamed: !!diff.renamed, nodes, edges, prompts, empty: !diff.renamed && !nodes.length && !edges.length && !prompts.length };
}
