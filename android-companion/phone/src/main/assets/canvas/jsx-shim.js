const k = (t, p, key) => React.createElement(t, key === undefined ? p : Object.assign({}, p, { key }));
globalThis.jsxRuntime = { jsx: k, jsxs: k, Fragment: React.Fragment };
