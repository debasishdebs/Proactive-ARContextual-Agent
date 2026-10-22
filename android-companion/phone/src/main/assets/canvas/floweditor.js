// The DAG editor screen on vendored React Flow: the only file that touches React, and it is declarative on purpose:
// every decision (what a key does, what a change means, what is dirty, what is refused) lives in editorlogic.js and
// editorflow.js, which GraalJS tests. React, ReactDOM, ReactFlow and htm are read inside mountEditor, never at import,
// so this module (and app.js, which imports it) loads without them. Text from the API is only ever a React text child.
import * as L from './editorlogic.js';
import { canConnect } from './flowadapter.js';
import { validateDraft, saveDraft, freezeDraft } from './editorflow.js';

/**
 * opts: { api, pipeline: {id, pipe, draftRev, layout, problems, activePrompts}, nodeTypes, readOnly, onDraft({pipe, dirty}), onError(e) }
 * → { getDraft, isDirty, savedRev, save, freeze(note), unmount }
 */
export function mountEditor(el, opts) {
  const g = globalThis;
  const React = g.React, ReactDOM = g.ReactDOM, RF = g.ReactFlow;
  const html = g.htm.bind(React.createElement);
  const { api, nodeTypes } = opts;
  const p = opts.pipeline;
  const store = L.createStore(L.open(p.pipe, p.draftRev, p.layout, p.problems, { readOnly: opts.readOnly }));
  const env = { activePrompts: p.activePrompts || {} };
  const act = (fn) => store.update(fn);
  const fail = (e) => { if (opts.onError) opts.onError(e); };

  // ---- one node on the canvas
  const NodeCard = ({ id, data }) => {
    const probs = data.problems || [];
    const cls = ['node-card', data.pending ? 'pending' : '', probs.length ? 'has-problem' : '', data.role ? 'role-' + data.role : ''].filter(Boolean).join(' ');
    return html`<div className=${cls} aria-describedby=${probs.length ? 'prob-' + id : undefined}>
      <${RF.Handle} type="target" position=${RF.Position.Top} />
      <div className="nt">${data.title}</div>
      <div className="nk">${data.typeLabel}</div>
      ${probs.length ? html`<span className="badge" aria-hidden="true">${probs.length}</span>` : null}
      ${probs.length ? html`<ul id=${'prob-' + id} className="sr-only">${probs.map((t, i) => html`<li key=${i}>${t}</li>`)}</ul>` : null}
      <${RF.Handle} type="source" position=${RF.Position.Bottom} />
    </div>`;
  };
  const NODE_TYPES = { card: NodeCard };

  // ---- one inspector control: local text while typing, the draft only ever gets coerced values
  const Control = ({ spec, disabled, onRaw, texts }) => {
    const [text, setText] = React.useState(spec.value);
    const [err, setErr] = React.useState('');
    const typed = (raw) => { setText(raw); if (texts) texts.current[spec.key] = raw; setErr(onRaw(raw) || ''); };
    let input;
    if (spec.control === 'checkbox') {
      input = html`<input type="checkbox" checked=${spec.value === true} disabled=${disabled} onChange=${(e) => setErr(onRaw(e.target.checked) || '')} />`;
    } else if (spec.control === 'select') {
      input = html`<select value=${spec.value} disabled=${disabled} onChange=${(e) => setErr(onRaw(e.target.value) || '')}>
        ${spec.value === '' ? html`<option value="" disabled>Choose…</option>` : null}
        ${spec.options.map((o) => html`<option key=${o.value} value=${o.value}>${o.label}</option>`)}
      </select>`;
    } else if (spec.control === 'textarea') {
      input = html`<textarea rows="6" spellCheck="false" value=${text} readOnly=${!!spec.readOnly} disabled=${disabled} onChange=${(e) => typed(e.target.value)} />`;
    } else {
      input = html`<input type=${spec.control === 'number' ? 'number' : 'text'} min=${spec.min} max=${spec.max} value=${text} disabled=${disabled} onChange=${(e) => typed(e.target.value)} />`;
    }
    const hint = spec.control === 'number' && spec.min !== undefined ? ' (' + spec.min + '–' + spec.max + ')' : spec.control === 'labels' ? ' (comma separated)' : '';
    return html`<label className="field"><span>${spec.label + hint}</span>${input}${err ? html`<span className="err" role="alert">${err}</span>` : null}</label>`;
  };

  const Inspector = ({ ed }) => {
    const m = L.inspectorFor(ed, nodeTypes, env);
    const texts = React.useRef({});
    const [check, setCheck] = React.useState(null);
    const id = m ? m.id : null;
    React.useEffect(() => { texts.current = {}; setCheck(null); }, [id]);
    if (!m) return html`<section className="card inspector"><p className="muted">Select a node to inspect it.</p></section>`;
    const payload = L.checkPayload(ed, m.id, nodeTypes, env, texts.current);
    const runCheck = async () => {
      const body = L.checkPayload(store.get(), m.id, nodeTypes, env, texts.current);
      try { const r = await api('POST', '/api/check-prompt', body); setCheck(r.problems || []); } catch (e) { fail(e); }
    };
    const change = (f, part) => (raw) => {
      if (f.kind === 'prompt' && !part) { delete texts.current['prompt.template']; delete texts.current['prompt.schema']; }
      const r = L.inputChange(store.get(), m.id, f.name, part, raw, nodeTypes, env);
      store.set(r.ed);
      return r.error;
    };
    const targets = L.connectOptions(ed, m.id, nodeTypes);
    return html`<section className="card inspector" aria-label="Node inspector">
      <h3>${m.title || m.id}</h3>
      <p className="muted">${m.type} · ${m.typeLabel} · id ${m.id}</p>
      ${m.readOnly ? html`<p className="muted">${L.READ_ONLY_TEXT}</p>` : null}
      <${Control} key=${m.id + ':title'} spec=${{ label: 'Title', control: 'text', value: m.title }} disabled=${m.readOnly}
        onRaw=${(raw) => { const r = L.titleChange(store.get(), m.id, raw, nodeTypes); store.set(r.ed); return r.error; }} />
      ${m.fields.map((f) => html`<div key=${m.id + ':' + f.name}>
        <${Control} spec=${Object.assign({ key: f.name }, f)} disabled=${m.readOnly} texts=${texts} onRaw=${change(f, null)} />
        ${(f.parts || []).map((part) => html`<${Control} key=${m.id + ':' + f.name + ':' + part.part + ':' + f.value}
          spec=${Object.assign({ key: f.name + '.' + part.part }, part)} disabled=${m.readOnly} texts=${texts} onRaw=${change(f, part.part)} />`)}
      </div>`)}
      ${m.readOnly ? null : html`<label className="field"><span>Connect to</span>
        <select value="" aria-label="Connect this node to" disabled=${targets.length === 0}
          onChange=${(e) => { const t = e.target.value; if (t) act((cur) => L.connectTo(cur, { source: m.id, target: t }, nodeTypes)); }}>
          <option value="">${targets.length ? 'Choose a node…' : 'No valid target'}</option>
          ${targets.map((t) => html`<option key=${t.id} value=${t.id}>${t.title}</option>`)}
        </select></label>`}
      <div className="row wrap">
        ${payload ? html`<button type="button" className="btn" onClick=${runCheck}>Check prompt</button>` : null}
        <button type="button" className="btn danger" disabled=${!m.removable}
          onClick=${() => act((cur) => L.removeItems(cur, { nodeIds: [m.id], edgeIds: [] }, nodeTypes))}>Remove node</button>
      </div>
      ${check ? (check.length ? html`<ul className="problems">${check.map((t, i) => html`<li key=${i}>${String(t)}</li>`)}</ul>` : html`<p className="ok">No problems.</p>`) : null}
    </section>`;
  };

  const Problems = ({ ed, focusNode }) => {
    const items = L.problemItems(ed);
    if (!items.length) return html`<section className="card"><h3>Problems</h3><p className="ok">No problems found.</p></section>`;
    return html`<section className="card warn" aria-label="Problems"><h3>Problems</h3>
      <ul className="problems">${items.map((it, i) => html`<li key=${i}>${it.nodeId
        ? html`<button type="button" className="linkbtn" onClick=${() => { act((cur) => L.selectNode(cur, it.nodeId)); focusNode(it.nodeId); }}>${it.text}</button>`
        : it.text}</li>`)}</ul></section>`;
  };

  const Picker = ({ ed }) => {
    const c = ed.choose;
    if (!c) return null;
    const title = (id) => { const n = ed.ds.pipe.nodes.find((x) => x.id === id); return n ? n.title || n.id : id; };
    return html`<section className="card picker" role="group" aria-label="Which branch?">
      <p>Which branch of ${title(c.from)} goes to ${title(c.to)}?</p>
      ${c.branches.map((b) => html`<label key=${b} className="inline"><input type="radio" name="branch" autoFocus=${b === c.pick} checked=${b === c.pick}
        onChange=${() => act((cur) => L.pickBranch(cur, b))} /> ${b}</label>`)}
      <div className="row">
        <button type="button" className="btn primary" onClick=${() => act((cur) => L.confirmChoose(cur, nodeTypes))}>Connect</button>
        <button type="button" className="btn" onClick=${() => act(L.cancelChoose)}>Cancel</button>
      </div>
    </section>`;
  };

  const Editor = () => {
    const ed = React.useSyncExternalStore(store.subscribe, store.get);
    const rf = RF.useReactFlow();
    const pane = React.useRef(null);
    const palette = React.useRef(null);
    const pointer = React.useRef('');
    const [measured, setMeasured] = React.useState(Object.create(null));
    const last = React.useRef(null);
    const flow = L.stabilize(last.current, L.flowFor(ed, nodeTypes), measured);
    last.current = flow;

    // validate 250 ms after the last change; a reply for an older revision is dropped by the draft state
    React.useEffect(() => {
      if (ed.ds.rev === 0 && ed.ds.layout) return undefined;
      const t = setTimeout(() => { validateDraft(store, api); }, 250);
      return () => clearTimeout(t);
    }, [ed.ds.rev]);

    const focusNode = (id) => setTimeout(() => {
      const el = pane.current && Array.from(pane.current.querySelectorAll('.react-flow__node')).find((n) => n.getAttribute('data-id') === id);
      if (el) el.focus();
    }, 0);
    const where = (ev) => rf.screenToFlowPosition({ x: ev.clientX, y: ev.clientY });

    const onKeyDown = (ev) => {
      const t = ev.target;
      const nodeEl = t && t.closest ? t.closest('.react-flow__node') : null;
      const d = L.keyDecision(store.get(), {
        key: ev.key, tag: t && t.tagName, editable: !!(t && t.isContentEditable), mod: ev.ctrlKey || ev.metaKey || ev.altKey,
        nodeId: nodeEl ? nodeEl.getAttribute('data-id') : null,
      }, nodeTypes);
      store.set(d.ed);
      if (!d.handled) return;
      ev.preventDefault();
      if (d.stop) ev.stopPropagation();
      if (d.effect && d.effect.kind === 'focus') focusNode(d.effect.id);
      if (d.effect && d.effect.kind === 'palette' && palette.current) { const b = palette.current.querySelector('button'); if (b) b.focus(); }
    };

    const groups = L.paletteGroups(ed, nodeTypes);
    const ro = L.isReadOnly(ed);
    return html`<div className="editor">
      <section className="card palette" ref=${palette} aria-label="Add a step">
        ${groups.length ? groups.map((gr) => html`<fieldset key=${gr.category} className="palette-group"><legend>${gr.category}</legend>
          ${gr.items.map((it) => html`<button type="button" key=${it.type} className=${'btn' + (ed.armed === it.type ? ' active' : '')} draggable="true"
            aria-pressed=${ed.armed === it.type}
            onDragStart=${(ev) => { ev.dataTransfer.setData('text/plain', it.type); ev.dataTransfer.effectAllowed = 'copy'; }}
            onPointerDown=${(ev) => { pointer.current = ev.pointerType; }}
            onClick=${() => { const touch = pointer.current === 'touch'; pointer.current = ''; act((cur) => (touch ? L.arm(cur, it.type, nodeTypes) : L.addFromPalette(cur, it.type, nodeTypes))); }}>${it.label}</button>`)}
        </fieldset>`) : html`<p className="muted">${ed.readOnly ? L.READ_ONLY_TEXT : 'Saving. Editing is paused.'}</p>`}
      </section>
      <div className="flow-pane" ref=${pane} onKeyDown=${onKeyDown}
        onDragOver=${(ev) => { ev.preventDefault(); ev.dataTransfer.dropEffect = 'copy'; }}
        onDrop=${(ev) => { ev.preventDefault(); const type = ev.dataTransfer.getData('text/plain'); if (type) { const at = where(ev); act((cur) => L.dropOnPane(cur, type, at, L.flowFor(cur, nodeTypes).nodes, nodeTypes)); } }}>
        <${RF.ReactFlow} nodes=${flow.nodes} edges=${flow.edges} nodeTypes=${NODE_TYPES} colorMode="system"
          nodesDraggable=${false} nodesFocusable=${true} edgesFocusable=${true} nodesConnectable=${!ro} fitView=${true} zoomOnPinch=${true} panOnDrag=${true}
          deleteKeyCode=${['Delete', 'Backspace']} minZoom=${0.3}
          isValidConnection=${(c) => canConnect(c, store.get().ds.pipe, nodeTypes).ok}
          onNodesChange=${(ch) => { setMeasured((m) => L.recordMeasures(m, ch)); act((cur) => L.applySelection(cur, 'node', ch)); }}
          onEdgesChange=${(ch) => act((cur) => L.applySelection(cur, 'edge', ch))}
          onConnect=${(c) => act((cur) => L.connectTo(cur, c, nodeTypes))}
          onConnectEnd=${(ev, cs) => act((cur) => { const why = L.refusalFromConnectionState(cs, cur.ds.pipe, nodeTypes); return why ? L.say(cur, why) : cur; })}
          onBeforeDelete=${async (items) => L.deletable(items)}
          onNodesDelete=${(ns) => act((cur) => L.removeItems(cur, { nodeIds: ns.map((n) => n.id), edgeIds: [] }, nodeTypes))}
          onEdgesDelete=${(es) => act((cur) => L.removeItems(cur, { nodeIds: [], edgeIds: es.map((x) => x.id) }, nodeTypes))}
          onPaneClick=${(ev) => { const at = where(ev); act((cur) => L.paneClick(cur, at, L.flowFor(cur, nodeTypes).nodes, nodeTypes)); }} />
      </div>
      <p className="refusal" role="status" aria-live="polite">${ed.status}</p>
      <${Picker} ed=${ed} />
      <${Inspector} ed=${ed} />
      <${Problems} ed=${ed} focusNode=${focusNode} />
    </div>`;
  };

  const root = ReactDOM.createRoot(el);
  root.render(html`<${RF.ReactFlowProvider}><${Editor} /><//>`);
  const off = store.subscribe(() => { if (opts.onDraft) opts.onDraft({ pipe: store.get().ds.pipe, dirty: L.isDirty(store.get()) }); });
  return {
    getDraft: () => store.get().ds.pipe,
    isDirty: () => L.isDirty(store.get()),
    leaveText: (what) => L.leaveText(store.get(), what),
    savedRev: () => store.get().ds.savedRev,
    save: () => saveDraft(store, api, p.id),
    freeze: (note) => freezeDraft(store, api, p.id, note),
    unmount() { off(); root.unmount(); },
  };
}
