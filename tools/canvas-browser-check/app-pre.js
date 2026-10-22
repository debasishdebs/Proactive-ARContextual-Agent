sessionStorage.setItem('pcToken', 'tok');
const PNG = Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='), (c) => c.charCodeAt(0));
window.__reqs = [];
const lay = { layers: 5, columns: 1, cells: { photos: { layer: 0, column: 0 }, extract: { layer: 1, column: 0 }, trust: { layer: 2, column: 0 }, critic: { layer: 3, column: 0 }, write: { layer: 4, column: 0 } } };
window.__lay = lay;
window.fetch = async (path, init) => {
  const auth = init && init.headers && init.headers.Authorization;
  window.__reqs.push((init && init.method || 'GET') + ' ' + path + (auth ? ' [auth]' : ' [NO AUTH]'));
  const j = (x, status) => new Response(JSON.stringify(x), { status: status || 200, headers: { 'Content-Type': 'application/json' } });
  if (path === '/api/node-types') return j(await (await import('./fixtures.js')).nodeTypes);
  if (path === '/api/pipelines') return j([{ id: 'p', name: 'Mine', builtIn: false, versions: [], live: null }]);
  if (path === '/api/runs') return j([{ runId: 'r1', pipelineId: 'p', status: 'DONE', startedAt: 1 }, { runId: 'r2', pipelineId: 'p', status: 'DONE', startedAt: 2 }]);
  if (path === '/api/pipelines/p') { const { livePipe } = await import('./fixtures.js'); return j({ id: 'p', name: 'Mine', json: JSON.stringify(livePipe()), draftRev: 3, builtIn: false, problems: [], problemsDetailed: [], layout: lay, versions: [], live: null, liveShape: false, activePrompts: {} }); }
  if (path === '/api/pipelines/b') { const { livePipe } = await import('./fixtures.js'); return j({ id: 'b', name: 'Builtin', json: JSON.stringify(livePipe()), draftRev: 1, builtIn: true, problems: [], problemsDetailed: [], layout: lay, versions: [], live: null, liveShape: true, activePrompts: {} }); }
  if (path === '/api/runs/r1') { const { livePipe } = await import('./fixtures.js'); return j({ runId: 'r1', pipelineId: 'p', status: 'DONE', pipelineJson: JSON.stringify(livePipe()), items: [{ frameId: 'f1', nodeId: 'extract', status: 'OK', output: { caption: 'a black cat', facts: ['cat'] } }, { frameId: 'f2', nodeId: 'extract', status: 'OK', output: { caption: 'forgotten photo' } }], scores: [], progress: { done: 2, total: 2 }, calls: 2, failures: 0, startedAt: 1 }); }
  if (path.startsWith('/api/frames/f') && path.endsWith('/thumb')) return path.includes('f2') ? j({ error: 'not_found' }, 404) : new Response(new Blob([PNG], { type: 'image/png' }), { status: 200 });
  if (path.startsWith('/api/frames/f') && path.endsWith('/image')) return path.includes('f2') ? j({ error: 'not_found' }, 404) : new Response(new Blob([PNG], { type: 'image/png' }), { status: 200 });
  if (path.startsWith('/api/frames?')) return j([{ id: 'f1', ts: 1000 }, { id: 'f2', ts: 2000 }]);
  if (path.startsWith('/api/frames/')) return j({ ts: 1, motion: 0, dwell: 0, blur: 0, state: 'ok', live: [] });
  if (path.startsWith('/api/compare')) return j({ frames: [{ frameId: 'f1', nodes: [{ nodeId: 'extract', diff: [{ op: 'same', text: 'common' }, { op: 'added', text: 'only in B' }] }] }, { frameId: 'f2', nodes: [] }] });
  if (path === '/api/validate') { const b = JSON.parse(init.body); return j({ rev: b.rev, layout: lay, problems: [] }); }
  if (path.startsWith('/api/pipelines/p') && init.method === 'PUT') return j({ draftRev: 9, problems: [] });
  return j({}, 404);
};
