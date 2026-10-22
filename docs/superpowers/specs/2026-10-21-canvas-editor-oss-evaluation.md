# Canvas DAG editor: open-source library evaluation

Date: 2026-10-21. This is an evaluation only. No code changed. Slice 2 §4 and slice 3 §4.1 are the requirement sources.

## 1. Verdict
- **Recommendation: React Flow (`@xyflow/react` 12.12.0, MIT). Vendor its prebuilt UMD with React 18.3.1 UMD and `htm` (Apache-2.0) under `assets/canvas/vendor/`. Use no JSX, no npm and no bundler.**
- **Runner-up: Drawflow 0.0.60 (MIT).** It is vanilla JS, 45 KB (9 KB gzip), and supports touch and pinch. Choose it only if React is ruled out. It has no port types, no keyboard support and no ARIA, so `keys.js` and the a11y layer stay hand-built, and the last release was in 2024-09.
- Why React Flow wins:
  - It is the only candidate with built-in keyboard and a11y support: focusable nodes and edges, Enter/Space/Esc, auto-pan on focus, an `aria-live` region, `ariaLabelConfig`, and `domAttributes` for `aria-describedby`.
  - It provides `isValidConnection` and connection-state hooks, which give connect-time validation and allow/refuse feedback while dragging.
  - Pointer and touch support, pinch zoom, pan, and fixed node positions (`nodesDraggable=false`) work out of the box. Fixed positions fit Ruling S4, where server layout is the only layout.
  - Release cadence is monthly: 12.12.0 shipped on 2026-09-24.

## 2. Candidates (verified 2026-10-21 against GitHub/npm)
| Library | Licence | Last release | Vanilla, no build | Touch | Typed ports and validation | Keyboard/a11y | Verdict |
|---|---|---|---|---|---|---|---|
| **React Flow** `@xyflow/react` | MIT | 12.12.0, 2026-09-24 | UMD `dist/umd/index.js` (188 KB) needs globals `React`, `ReactDOM`, `jsxRuntime`; use `htm` instead of JSX | yes | `isValidConnection`, `onConnectStart/End`, custom handles | **best**: built in | **Pick** |
| Drawflow | MIT | 0.0.60, 2024-09-03 | yes, zero deps, 45 KB | yes, pinch | none; ports are `input_1`/`output_1`; validate in `connectionCreated`, then `removeSingleConnection` | none | Runner-up |
| LiteGraph.js | MIT | 0.7.18, 2024-01; repo last push 2024-08; ComfyUI fork npm deprecated | yes, one file | partial | **yes**, typed slots | **none**: Canvas2D draws everything, so there is no DOM to focus and no `aria-describedby` | Reject (a11y) |
| Rete.js v2 | MIT | 2.0.6, 2025-06-30 | no UMD; ESM/CJS only; needs about 5 packages plus a React/Vue/Angular/Svelte/Lit render plugin | via area plugin | socket compatibility | weak | Reject: more vendoring for less a11y |
| BaklavaJS | MIT | 2.8.1, 2025-11-02 | needs Vue 3 | partial | interface types | weak | Reject |
| Flume | MIT | stale | needs React and a build | weak | yes | weak | Reject |
| Node-RED editor | Apache-2.0 | 5.0.9, 2026-10-21 | cannot be separated from its runtime; jQuery-heavy | partial | no | partial | Reject: too heavy |
| jsPlumb Community | NOASSERTION in the repo | repo says "no longer receives updates" | yes | yes | no | no | Reject |
| `@xyflow/system` alone | MIT | 0.0.83 | ESM; pulls in d3-drag/zoom/selection | yes | helpers only | you build it | Reject: it is a toolkit, not an editor |

**Vendor payload (measured):**

| File | Size |
|---|---|
| `react.production.min.js` | 10.8 KB |
| `react-dom.production.min.js` | 131.8 KB |
| `@xyflow/react` UMD | 188.5 KB |
| `style.css` | 18.6 KB |
| `htm.umd.js` | 1.4 KB |
| **Total** | **about 351 KB raw, about 110 KB gzip** |

These files are served offline by NanoHTTPD. Download them once with `curl` from jsdelivr (there is no Node on the machine). Pin the versions and record SHA-256 hashes in `vendor/VERSIONS.txt`.

**`jsxRuntime` shim.** The React 18 UMD does not expose the `jsxRuntime` global that the React Flow UMD expects. A 6-line shim in `vendor/jsx-shim.js`, loaded before `index.js`, supplies it:

```js
const k = (t, p, key) => React.createElement(t, key === undefined ? p : Object.assign({}, p, { key }));
globalThis.jsxRuntime = { jsx: k, jsxs: k, Fragment: React.Fragment };
```

React 19 dropped UMD builds. Stay on 18.3.1, which satisfies the peer dependency `react >= 17`.

## 3. Keep vs replace
**Keep unchanged.** These are pure modules, still tested in GraalJS:
- `graph.js`: the pipeline model and the single draft;
- `validate.js`: the client port check, with refusal reasons;
- `format.js` and `diffview.js`;
- server `PipelineValidator`, `FlowLayouts`, `/api/validate`, `/api/node-types`, the API client in `app.js`, the runner/interpreter, and Freeze/Save/JSON.

The JSON format stays our pipeline model. React Flow never owns the data: it renders the draft.

**Keep, re-wired:**
- **`keys.js`** (pure: arrows by layer/column, `c` connect mode, Delete, `a` for the palette). Its output becomes calls on the adapter, not DOM moves. React Flow's own Tab/Enter focus remains, and its arrow-key node moves are inert because nodes are not draggable.

**Replace:**
- **`dag.js`**: SVG drawing, replaced by React Flow plus custom node and edge components.
- **`editor.js`**: the drag-connect, pan/zoom and selection plumbing.
- most of **`dom.js`** for the canvas. `h`/`btn` stay for the non-canvas views.

**New:**
- **`flowadapter.js`** (pure, with no React import). It provides:
  - `toFlow(pipe, layout, problems, nodeTypes)` → `{nodes, edges}`, with positions from `FlowLayouts` cells and badge and domain-colour data;
  - `canConnect(conn, pipe, nodeTypes)` → `{ok, reason}`, wrapping `validate.js` and also refusing cross-domain edges;
  - `applyChange(draft, change)` → a `graph.js` operation (add, connect, remove, `when`).
- **`floweditor.js`**: thin, the only file that touches React. It contains `htm` components for the node card (ports as typed handles, problem badge, `aria-describedby`), the palette grouped by `category`, and the inspector rendered from `ParamSchema`.

## 4. Integration risks
1. **GraalJS cannot run React meaningfully.** Mitigation: all logic goes in `flowadapter.js`, `graph.js`, `validate.js` and `keys.js`, which are tested headlessly. `floweditor.js` is kept declarative and thin, and is covered by a manual phone and laptop Chrome checklist. The parse check must **exclude `vendor/`**, because the UMD files are scripts, not modules. Without that exclusion the build fails.
2. **Keyboard model mismatch.** Slice 2 wants arrows by layer and column and a `c` connect mode. React Flow's default is Tab order plus arrow-to-move. Mitigation:
   - set `nodesDraggable=false` and `nodesFocusable=true`;
   - attach a single `onKeyDown` on the wrapper that delegates to `keys.js`;
   - focus a node with `setNodes(selected)` plus `fitView`/auto-pan.

   Verify that React Flow's built-in arrow handling does not fight ours. Fall back to `disableKeyboardA11y` with our own handler, keeping `nodesFocusable`.
3. **Refusal reasons while dragging.** `isValidConnection` returns only a boolean. Mitigation: call `canConnect` in `onConnectEnd`/connection state to show the inline reason, and use handle class names for the allowed and refused marks.
4. **Branch `when` menu.** React Flow has no built-in menu for this. Use a small popover on `onConnect` when the source branches, pre-selecting the first unused branch.
5. **UMD and shim brittleness.** A React Flow minor release could change its externals. Pin exact versions, keep SHA-256 hashes, and upgrade only deliberately.
6. **Phone performance.** 350 KB plus React on low-end Chrome. Pipelines are tens of nodes, which is fine. Measure the first render on the phone once.
7. **Licences.** React MIT, React Flow MIT (with an optional "Pro" subscription that we do not need), htm Apache-2.0. All are compatible with a private app. Keep the React Flow attribution, or set `proOptions.hideAttribution` only if allowed (it is allowed under MIT, though the authors ask for support).

## 5. Ordered swap tasks (slice 2 editor tasks; about 1 to 1.5 days total)
1. (0.5 h) Vendor the files with `curl` into `assets/canvas/vendor/`, add `jsx-shim.js` and `VERSIONS.txt` with hashes, and load them as classic `<script>` tags in `index.html` before the app module. Exclude `vendor/` from the GraalJS parse check.
2. (1 h) Write the `flowadapter.js` `toFlow` function plus GraalJS tests: positions from the layout, problem badges per node and edge, domain colours.
3. (1 h) Write `flowadapter.canConnect` on top of `validate.js` (port types, cross-domain refusal, reason text) plus tests, including the "accepts Photo or JSON, not Preview" string.
4. (1 h) Write `flowadapter.applyChange` mapping to `graph.js` operations (add with a `<short type>_<n>` id, connect with `when`, remove a node together with its edges) plus tests.
5. (2 h) Build `floweditor.js`:
   - a `ReactFlow` with `nodesDraggable=false`;
   - a custom node with typed handles, a badge and `aria-describedby`;
   - an edge style for problems;
   - `isValidConnection`, `onConnect`, `onNodesDelete`/`onEdgesDelete`;
   - the 250 ms debounced `/api/validate` call feeding back into `toFlow`.
6. (1 h) Add the palette grouped by `category` (button plus Enter to add, drag onto the pane) and the inspector rendered from `ParamSchema`, both writing to the single draft.
7. (1 h) Wire `keys.js` through the wrapper `onKeyDown`. Add tests for the `keys.js` decisions and adapter calls.
8. (0.5 h) Delete `dag.js` and the canvas parts of `editor.js`/`dom.js` and update `views.js`. Remove or port `dag.test.js`.
9. (1 h) Manual check on phone Chrome (360 px, touch connect, pinch) and laptop Chrome (keyboard-only pass, screen-reader spot check). Note the results in the handoff.

## Sources
- https://github.com/xyflow/xyflow (releases via the GitHub API); https://reactflow.dev/learn/advanced-use/accessibility
- https://cdn.jsdelivr.net/npm/@xyflow/react@12.12.0/dist/umd/index.js (UMD header and externals inspected)
- https://github.com/jerosoler/Drawflow (releases via the GitHub API); bundlephobia
- https://github.com/jagenjo/litegraph.js; npm `litegraph.js`, `@comfyorg/litegraph`
- https://github.com/retejs/rete; npm `rete`
- https://github.com/newcat/baklavajs; https://github.com/jsplumb/community-edition; https://github.com/node-red/node-red
