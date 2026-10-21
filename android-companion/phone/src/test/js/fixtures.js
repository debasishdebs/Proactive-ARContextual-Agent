// Shared fixtures for the editor-stream JS tests (not a test file: it exports no `tests`).
// Node types mirror GET /api/node-types (Task 9 adds short, palette, category).
const t = (type, label, short, inputs, output, extra) => Object.assign({
  type, label, short, inputs, output, branches: [], branchesFromCases: false, params: [], palette: true, category: 'AI',
}, extra || {});

export const nodeTypes = [
  t('input.photos', 'Photo', 'photos', [], 'PHOTOS', { palette: false, category: 'Inputs' }),
  t('ai.vision_extract', 'Facts from frames', 'extract', ['PHOTOS', 'JSON'], 'EXTRACTION', {
    params: [{ name: 'batch', kind: 'int', min: 1, max: 3 }, { name: 'prompt', kind: 'prompt' }],
  }),
  t('gate.local_critic', 'Local trust check', 'trust', ['EXTRACTION'], 'EXTRACTION', { branches: ['trusted', 'unsure'], category: 'Checks' }),
  t('ai.critic', 'Critic', 'critic', ['EXTRACTION'], 'EXTRACTION', { branches: ['pass', 'fail'], params: [{ name: 'prompt', kind: 'prompt' }] }),
  t('ai.prompt', 'Prompt', 'prompt', ['PHOTOS', 'JSON', 'EXTRACTION'], 'JSON', {
    params: [{ name: 'role', kind: 'role' }, { name: 'template', kind: 'text' }, { name: 'schema', kind: 'schema' }, { name: 'images', kind: 'bool' }],
  }),
  t('route.switch', 'Switch', 'route', ['JSON'], 'JSON', { branchesFromCases: true, params: [{ name: 'cases', kind: 'labels' }], category: 'Routing' }),
  t('memory.write', 'Save memory (preview)', 'write', ['EXTRACTION'], 'PREVIEW', { category: 'Memory' }),
];

/** photos -> extract -> trust -(trusted)-> write ; trust -(unsure)-> critic -(pass|fail)-> write */
export function livePipe() {
  return {
    schema: 1, id: 'live-extract', name: 'Live',
    nodes: [
      { id: 'photos', type: 'input.photos', title: 'Photo', params: {} },
      { id: 'extract', type: 'ai.vision_extract', title: 'Facts from frames', params: { batch: 3, prompt: { source: 'live' } } },
      { id: 'trust', type: 'gate.local_critic', title: 'Confidence check', params: {} },
      { id: 'critic', type: 'ai.critic', title: 'Critic', params: { prompt: { source: 'live' } } },
      { id: 'write', type: 'memory.write', title: 'Save memory', params: {} },
    ],
    edges: [
      { from: 'photos', to: 'extract' },
      { from: 'extract', to: 'trust' },
      { from: 'trust', to: 'write', when: 'trusted' },
      { from: 'trust', to: 'critic', when: 'unsure' },
      { from: 'critic', to: 'write', when: 'pass' },
      { from: 'critic', to: 'write', when: 'fail' },
    ],
  };
}

export const liveCells = {
  photos: { layer: 0, column: 0 }, extract: { layer: 1, column: 0 }, trust: { layer: 2, column: 0 },
  critic: { layer: 3, column: 0 }, write: { layer: 4, column: 0 },
};

export function eq(a, b, msg) {
  const x = JSON.stringify(a), y = JSON.stringify(b);
  if (x !== y) throw new Error((msg || 'expected') + ': ' + x + ' !== ' + y);
}
export function ok(v, msg) { if (!v) throw new Error(msg || 'expected truthy'); }
