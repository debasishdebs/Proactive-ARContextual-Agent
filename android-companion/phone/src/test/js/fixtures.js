// Shared fixtures for the editor-stream JS tests (not a test file: it exports no `tests`).
// Node types mirror GET /api/node-types (Task 9 adds short, palette, category).
// the family each type serves in GET /api/node-types (NodeFamiliesTest checks the registry): Source, Aggregate, Process, Condition, Outcome
export const FAMILY = {
  'input.photos': 'Source', 'imu.head_motion': 'Source', 'imu.settle': 'Source', 'hub.context': 'Source', 'signal.in': 'Source',
  'ai.vision_extract': 'Process', 'ai.prompt': 'Process', 'ai.critic': 'Process',
  'gate.local_critic': 'Condition', 'route.switch': 'Condition', 'gate.threshold': 'Condition', 'gate.novelty': 'Condition', 'gate.rate_cap': 'Condition', 'expr.guard': 'Condition',
  'memory.write': 'Outcome', 'capture.image': 'Outcome', 'policy.capture_rate': 'Outcome', 'signal.out': 'Outcome',
};
const t = (type, label, short, inputs, output, extra) => Object.assign({
  type, label, short, inputs, output, branches: [], branchesFromCases: false, params: [], palette: true, category: 'AI', family: FAMILY[type],
}, extra || {});

// what /api/node-types serves for gate.local_critic (CanvasBackendImplTest checks the real JSON has this shape and the 0.7 default)
export const localCriticParams = [
  { name: 'aggregate', kind: 'enum', label: 'Combine the fact confidences by', options: ['mean', 'min', 'max'], default: 'mean' },
  { name: 'operator', kind: 'enum', label: 'Trusted when confidence is', options: ['>=', '>'], default: '>=' },
  { name: 'cutoff', kind: 'number', label: 'Cutoff', min: 0, max: 1, step: 0.05, default: 0.7 },
];
export const localCriticInner = {
  nodes: [
    { id: 'facts', label: 'Facts in (none: trusted)', kind: 'source' }, { id: 'aggregate', label: 'Combine confidences', kind: 'op' },
    { id: 'cutoff', label: 'Cutoff', kind: 'const' }, { id: 'compare', label: 'Compare', kind: 'op' },
    { id: 'trusted', label: 'trusted', kind: 'branch' }, { id: 'unsure', label: 'unsure', kind: 'branch' },
  ],
  wires: [
    { from: 'facts', to: 'aggregate', port: '' }, { from: 'aggregate', to: 'compare', port: 'value' }, { from: 'cutoff', to: 'compare', port: 'cutoff' },
    { from: 'compare', to: 'trusted', port: 'yes' }, { from: 'compare', to: 'unsure', port: 'no' },
  ],
  exposed: [{ param: 'aggregate', node: 'aggregate' }, { param: 'operator', node: 'compare' }, { param: 'cutoff', node: 'cutoff' }],
};

// what /api/node-types serves for input.photos (CanvasBackendImplTest checks the real JSON); every default is today's behaviour
export const photoParams = [
  { name: 'source', kind: 'enum', label: 'Source', options: ['glasses', 'all'], default: 'glasses' },
  { name: 'sinceHours', kind: 'int', label: 'Only photos from the last hours (0 = any time)', min: 0, max: 720, step: 1, default: 0 },
  { name: 'markedOnly', kind: 'bool', label: 'Only marked moments', default: false },
  { name: 'limit', kind: 'int', label: 'At most this many photos (newest first)', min: 1, max: 50, step: 1, default: 50 },
];

export const nodeTypes = [
  t('input.photos', 'Photo', 'photos', [], 'PHOTOS', { palette: false, category: 'Inputs', params: photoParams }),
  t('ai.vision_extract', 'Facts from frames', 'extract', ['PHOTOS', 'JSON'], 'EXTRACTION', {
    params: [{ name: 'batch', kind: 'int', min: 1, max: 3 }, { name: 'prompt', kind: 'prompt' }],
  }),
  t('gate.local_critic', 'Local trust check', 'trust', ['EXTRACTION'], 'EXTRACTION', { branches: ['trusted', 'unsure'], category: 'Checks', params: localCriticParams, inner: localCriticInner }),
  t('ai.critic', 'Critic', 'critic', ['EXTRACTION'], 'EXTRACTION', { branches: ['pass', 'fail'], params: [{ name: 'prompt', kind: 'prompt' }] }),
  t('ai.prompt', 'Prompt', 'prompt', ['PHOTOS', 'JSON', 'EXTRACTION'], 'JSON', {
    params: [{ name: 'role', kind: 'role' }, { name: 'template', kind: 'text' }, { name: 'schema', kind: 'schema' }, { name: 'images', kind: 'bool' }],
  }),
  t('route.switch', 'Switch', 'route', ['JSON'], 'JSON', { branchesFromCases: true, params: [{ name: 'field', kind: 'text', default: 'activity' }, { name: 'cases', kind: 'labels' }], category: 'Routing' }),
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

// ---- schema-2 (capture half) node types, as GET /api/node-types serves them (version, domain, v2 ParamSchema descriptors)
const GAP = 'timer photo gap (settled/quiet/walk/still cap)';
const gapCol = (name, l) => ({ name, label: 'timer photo gap (' + l + ')', kind: 'int', min: 5, max: 300, blankable: false, allowZero: true, group: GAP });
const v2 = (type, label, short, inputs, output, domain, category, extra) => Object.assign({
  type, label, short, inputs, output, branches: [], branchesFromCases: false, params: [], palette: true, category, family: FAMILY[type], version: 1, sinceSchema: 2, domain,
  domainFromParams: false, executor: 'BUILTIN', root: false, liveable: true, cost: { modelCalls: 0, rtOps: 0, cpuClass: 0 },
}, extra || {});
const num = (name, min, max, def, step) => ({ name, kind: 'number', min, max, step, default: def, sweep: true });
const sig = (domain) => [{ name: 'name', kind: 'enum', options: ['ctx', 'intent'], default: 'ctx', sweep: false }, { name: 'domain', kind: 'enum', options: ['RT_GLASSES', 'RT_PHONE'], default: domain, sweep: false }];

export const captureTypes = [
  v2('imu.head_motion', 'Head movement', 'head', [], 'MOTION_EVENT', 'RT_GLASSES', 'Sensors', {
    root: true, params: [num('onsetRadS', 0.4, 1, 0.6, 0.05), { name: 'bridgeMs', kind: 'int', min: 100, max: 500, step: 25, default: 250, sweep: true }],
  }),
  v2('gate.threshold', 'Threshold', 'thresh', ['MOTION_EVENT'], 'MOTION_EVENT', 'RT_GLASSES', 'Thresholds/Filters', {
    params: [{ name: 'feature', kind: 'enum', options: ['gyroPeak', 'boutMs', 'boutRad'], default: 'gyroPeak', sweep: false },
      { name: 'op', kind: 'enum', options: ['>', '>='], default: '>', sweep: false }, num('value', 0, 5000, 0.6, undefined)],
  }),
  v2('hub.context', 'Motion from context hub', 'hub', [], 'CONTEXT', 'RT_PHONE', 'Signals/Hub', { root: true }),
  v2('signal.out', 'Send signal', 'sigout', ['CONTEXT'], 'SIGNAL', 'RT_PHONE', 'Signals/Hub', { domainFromParams: true, executor: 'SIGNAL', params: sig('RT_PHONE') }),
  v2('signal.in', 'Receive signal', 'sigin', [], 'CONTEXT', 'RT_GLASSES', 'Signals/Hub', { domainFromParams: true, executor: 'SIGNAL', params: sig('RT_GLASSES') }),
  v2('policy.capture_rate', 'Capture frequency by activity', 'rate', ['MOTION_EVENT', 'TRIGGER', 'CONTEXT'], 'TRIGGER', 'RT_GLASSES', 'Policy/Frequency', {
    executor: 'TABLE', params: [{
      name: 'rows', kind: 'table', label: 'Capture frequency by activity', sweep: false,
      table: {
        rows: ['REST', 'VEHICLE_PASSENGER', 'VEHICLE_DRIVING', 'FLIGHT'],
        cols: [{ name: 'capPerMin', label: 'cap per minute', kind: 'int', min: 1, max: 4, blankable: true, allowZero: false },
          gapCol('settledGapS', 'settled'), gapCol('quietGapS', 'quiet'), gapCol('walkGapS', 'walk'), gapCol('stillCapGapS', 'still cap'),
          { name: 'forceSettle', label: 'force settle', kind: 'bool', blankable: false, allowZero: false }, { name: 'nudges', label: 'nudges', kind: 'bool', blankable: false, allowZero: false }],
      },
    }],
  }),
  v2('capture.image', 'Capture image', 'capture', ['TRIGGER'], 'PHOTOS', 'RT_GLASSES', 'Actions/Capture', {
    params: [{ name: 'hamming', kind: 'int', min: 6, max: 16, step: 1, default: 10, sweep: true }, { name: 'ringCompare', kind: 'enum', options: ['last', 'all'], default: 'all', sweep: true }],
  }),
];
/** expr.guard as /api/node-types serves it (X2): one structured `graph` param, MOTION_EVENT in and out, never liveable. */
export const exprGuardType = v2('expr.guard', 'Expression guard', 'guard', ['MOTION_EVENT'], 'MOTION_EVENT', 'RT_GLASSES', 'Thresholds/Filters', {
  liveable: false, params: [{ name: 'graph', kind: 'graph', label: 'Expression graph', sweep: false }],
});
export const allTypes = nodeTypes.concat(captureTypes, [exprGuardType]);

/** head -> threshold -> rate(+hub) -> capture -> (photos binding) input.photos; one pipeline over RT_GLASSES, RT_PHONE and ASYNC. */
export function capturePipe() {
  return {
    schema: 2, id: 'cap', name: 'Capture',
    nodes: [
      { id: 'head_1', type: 'imu.head_motion', title: 'Head movement', params: {} },
      { id: 'thresh_1', type: 'gate.threshold', title: 'Threshold', params: { feature: 'gyroPeak', op: '>', value: 0.6 } },
      { id: 'hub_1', type: 'hub.context', title: 'Motion from context hub', params: {} },
      { id: 'sigout_1', type: 'signal.out', title: 'Send signal', params: { name: 'ctx', domain: 'RT_PHONE' } },
      { id: 'sigin_1', type: 'signal.in', title: 'Receive signal', params: { name: 'ctx', domain: 'RT_GLASSES' } },
      { id: 'rate_1', type: 'policy.capture_rate', title: 'Capture frequency by activity', params: {} },
      { id: 'capture_1', type: 'capture.image', title: 'Capture image', params: {} },
      { id: 'photos', type: 'input.photos', title: 'Photo', params: {} },
    ],
    edges: [
      { from: 'head_1', to: 'thresh_1' }, { from: 'thresh_1', to: 'rate_1' }, { from: 'hub_1', to: 'sigout_1' },
      { from: 'sigin_1', to: 'rate_1' }, { from: 'rate_1', to: 'capture_1' }, { from: 'capture_1', to: 'photos' },
    ],
  };
}
