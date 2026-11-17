const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const out = {}; const errors = [];
window.addEventListener('securitypolicyviolation', (e) => errors.push('CSP violation: ' + e.violatedDirective + ' ' + (e.blockedURI || '')));
window.addEventListener('error', (e) => errors.push('error: ' + e.message));
const oe = console.error; console.error = (...a) => { errors.push('console.error: ' + a.map(String).join(' ').slice(0, 300)); };
const q = (s) => document.querySelector(s); const qa = (s) => Array.from(document.querySelectorAll(s));
let confirms = []; let confirmAnswer = false; window.confirm = (m) => { confirms.push(m); return confirmAnswer; };
await sleep(1000);
location.hash = '#/p/p'; await sleep(1200);
out.pipelineNodes = qa('.react-flow__node').length; out.pipelineButtons = qa('main .row button, main .row a').map((b) => b.textContent);
// edit then leave
qa('.palette button').find((b) => b.textContent === 'Critic').click(); await sleep(300);
out.status = q('[role=status]').textContent;
out.dirtyText = qa('span.muted').map((s) => s.textContent).filter(Boolean);
location.hash = '#/'; await sleep(600);
out.confirmAsked = confirms.slice(); out.hashAfterDecline = location.hash; out.stillEditor = qa('.react-flow__node').length;
confirmAnswer = true; location.hash = '#/'; await sleep(600);
out.hashAfterAccept = location.hash; out.h2 = q('h2') && q('h2').textContent;
// built-in
location.hash = '#/p/b'; await sleep(1200);
out.builtinButtons = qa('main .row button, main .row a').map((b) => b.textContent); out.builtinPalette = qa('.palette button').length;
location.hash = '#/'; await sleep(400);
// run view with thumbnails
location.hash = '#/run/r1'; await sleep(1500);
out.thumbs = qa('.photo-thumb').length; out.imgSrc = qa('.photo-thumb img').map((i) => (i.getAttribute('src') || '').slice(0, 5));
out.gone = qa('.photo-thumb').map((t) => t.textContent);
const btn = q('.photo-thumb button'); btn.click(); await sleep(800);
out.modalClass = q('.modal') && q('.modal').getAttribute('class');
out.modalImg = q('.modal img') && (q('.modal img').getAttribute('src') || '').slice(0, 5);
out.modalValue = q('.shotvalue') && q('.shotvalue').textContent;
q('.overlay .row button').click(); await sleep(100);
// compare
location.hash = '#/compare?a=r1&b=r2'; await sleep(1500);
out.cmpThumbs = qa('.photo-thumb').length; out.cmpGone = qa('.photo-thumb').map((t) => t.textContent);
q('.photo-thumb button').click(); await sleep(800);
out.cmpModal = q('.shotvalue') && q('.shotvalue').textContent;
q('.overlay .row button').click();
// run new enlarge
location.hash = '#/run/new?p=p'; await sleep(1500);
out.enlarge = qa('.thumbwrap button').map((b) => b.textContent).filter((t) => t === 'Enlarge').length;
out.reqs = window.__reqs.filter((r) => r.includes('/api/frames/') && (r.includes('thumb') || r.includes('image')));
out.noAuth = window.__reqs.filter((r) => r.includes('NO AUTH')).length;
out.queryToken = window.__reqs.filter((r) => /token/.test(r.split(' ')[1] || '')).length;
try {
// signals page: list, edit, try on recent data (one POST, no PUT), back
location.hash = '#/signals'; await sleep(900);
out.signalsList = q('main') ? qa('main h2, main strong').map((e) => e.textContent) : [];
qa('button').find((b) => b.textContent === 'Edit').click(); await sleep(500);
const win = q('select[aria-label=Window]'); win.value = '1800'; win.dispatchEvent(new Event('change'));
qa('button').find((b) => b.textContent === 'Run').click(); await sleep(600);
out.signalsTry = qa('.expr-try-out li').map((l) => l.textContent); out.signalsStrip = qa('svg.expr-strip path').length; out.signalsTryBody = window.__bodies.filter((b) => b.path === '/api/signals/try').map((b) => b.seconds);
out.signalsWrites = window.__reqs.filter((r) => /^(PUT|DELETE) \/api\/signals/.test(r)).length;
qa('button').find((b) => b.textContent === 'Back').click(); await sleep(500);
out.signalsListAgain = qa('main strong').map((e) => e.textContent);
} catch (e) { out.signalsErr = String(e && e.stack || e).slice(0, 300); }
out.errors = errors;
document.getElementById('out').textContent = JSON.stringify(out, null, 1); document.title = 'DONE';
