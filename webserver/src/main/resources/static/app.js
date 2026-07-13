/*
 * Tranto console — vanilla JS, zero dependencies.
 * Talks to the live REST API: deploys a flow, triggers an execution, and follows it over
 * Server-Sent Events, lighting up the topology and timeline as the engine reports state.
 */
'use strict';

const API = '/api/v1';
const $ = (id) => document.getElementById(id);
const short = (t) => (t || '').split('.').pop();

/* ---------- preloaded demo flows ---------- */
const EXAMPLES = [
    {
        icon: '👋', name: 'Hello Tranto', desc: 'log · sleep · return',
        yaml:
`id: hello
namespace: demo
inputs:
  - id: name
    type: STRING
    defaults: CTO
tasks:
  - id: greet
    type: io.tranto.plugin.core.log.Log
    message: "Hello {{ inputs.name }} — welcome to Tranto"
  - id: think
    type: io.tranto.plugin.core.flow.Sleep
    duration: PT0.7S
  - id: answer
    type: io.tranto.plugin.core.debug.Return
    format: "handled by {{ flow.namespace }}.{{ flow.id }}"
`
    },
    {
        icon: '🛠', name: 'Tools pipeline', desc: 'hash · json · regex · file',
        yaml:
`id: tools_pipeline
namespace: demo
tasks:
  - id: checksum
    type: io.tranto.plugin.tools.crypto.Hash
    from: "release-{{ execution.id }}"
  - id: encode
    type: io.tranto.plugin.tools.encoding.Base64Encode
    from: "hello world"
  - id: parse
    type: io.tranto.plugin.tools.json.Parse
    from: '{"user":{"name":"ada"},"tags":["a","b","c"]}'
  - id: extract_ids
    type: io.tranto.plugin.tools.text.RegexExtract
    from: "order-123, order-456, order-789"
    pattern: "order-(\\\\d+)"
  - id: write_report
    type: io.tranto.plugin.tools.file.Write
    path: "/tmp/tranto-ui/{{ execution.id }}.txt"
    content: "checksum={{ outputs.checksum.digest }} user={{ outputs.parse.value.user.name }}"
  - id: read_report
    type: io.tranto.plugin.tools.file.Read
    path: "{{ outputs.write_report.path }}"
  - id: stamp
    type: io.tranto.plugin.tools.datetime.Format
    offset: P1D
    format: "yyyy-MM-dd"
`
    },
    {
        icon: '⚡', name: 'Parallel fan-out', desc: 'concurrent branches',
        yaml:
`id: parallel_etl
namespace: demo
tasks:
  - id: extract
    type: io.tranto.plugin.core.log.Log
    message: "extracting source data"
  - id: transform
    type: io.tranto.plugin.core.flow.Parallel
    tasks:
      - id: branch_orders
        type: io.tranto.plugin.core.flow.Sleep
        duration: PT0.6S
      - id: branch_users
        type: io.tranto.plugin.core.flow.Sleep
        duration: PT0.9S
      - id: branch_events
        type: io.tranto.plugin.core.flow.Sleep
        duration: PT0.5S
  - id: load
    type: io.tranto.plugin.core.log.Log
    message: "loaded 3 branches into the warehouse"
`
    },
    {
        icon: '🛡', name: 'Resilient job', desc: 'tolerate failure · recover',
        yaml:
`id: resilient_job
namespace: demo
tasks:
  - id: risky_call
    type: io.tranto.plugin.core.execution.Fail
    message: "flaky upstream returned 503"
    allowFailure: true
  - id: fallback
    type: io.tranto.plugin.core.log.Log
    message: "primary failed — served from cache instead"
  - id: done
    type: io.tranto.plugin.core.debug.Return
    format: "recovered gracefully"
`
    }
];

/* ---------- state ---------- */
let activeExample = 0;
let followSource = null;
let elapsedTimer = null;
let startedAt = 0;
const nodeById = new Map();
const rowById = new Map();

/* ---------- boot ---------- */
function init() {
    renderLibrary();
    selectExample(0);
    wireEditor();
    wireToolbar();
    wireNav();
    refreshStatus();
    setInterval(refreshStatus, 3000);
    window.addEventListener('resize', drawConnectors);
}

/* ---------- flow library + editor ---------- */
function renderLibrary() {
    const lib = $('library');
    lib.innerHTML = '';
    EXAMPLES.forEach((ex, i) => {
        const el = document.createElement('button');
        el.className = 'flow-card' + (i === activeExample ? ' active' : '');
        el.innerHTML =
            `<span class="flow-ico">${ex.icon}</span>
             <span class="flow-meta"><b>${ex.name}</b><span>${ex.desc}</span></span>`;
        el.onclick = () => selectExample(i);
        lib.appendChild(el);
    });
}

function selectExample(i) {
    activeExample = i;
    $('yaml').value = EXAMPLES[i].yaml;
    renderLibrary();
    syncGutter();
}

function wireEditor() {
    const ta = $('yaml');
    ta.addEventListener('input', syncGutter);
    ta.addEventListener('scroll', () => { $('gutter').scrollTop = ta.scrollTop; });
    // soft-tab support
    ta.addEventListener('keydown', (e) => {
        if (e.key === 'Tab') {
            e.preventDefault();
            const s = ta.selectionStart, en = ta.selectionEnd;
            ta.value = ta.value.slice(0, s) + '  ' + ta.value.slice(en);
            ta.selectionStart = ta.selectionEnd = s + 2;
            syncGutter();
        }
    });
}

function syncGutter() {
    const lines = $('yaml').value.split('\n').length;
    let g = '';
    for (let i = 1; i <= lines; i++) g += i + '\n';
    $('gutter').textContent = g;
}

/* ---------- toolbar actions ---------- */
function wireToolbar() {
    $('btn-deploy').onclick = () => deploy().then(f => toast(`deployed ${f.namespace}.${f.id}`, 'ok')).catch(showErr);
    $('btn-run').onclick = runFlow;
}

function setBusy(b) {
    $('btn-run').disabled = b;
    $('btn-deploy').disabled = b;
}

async function deploy() {
    const res = await fetch(`${API}/flows`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/yaml' },
        body: $('yaml').value
    });
    if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
    return res.json();
}

async function runFlow() {
    setBusy(true);
    toast('deploying…');
    try {
        const flow = await deploy();
        toast('triggering…');
        const res = await fetch(`${API}/executions/${flow.namespace}/${flow.id}`, { method: 'POST' });
        if (!res.ok) throw new Error((await res.text()) || `HTTP ${res.status}`);
        const exec = await res.json();
        toast(`running ${flow.namespace}.${flow.id}`, 'ok');
        startExecutionView(flow, exec);
        follow(exec.id);
    } catch (e) {
        showErr(e);
    } finally {
        setBusy(false);
    }
}

/* ---------- execution view ---------- */
function startExecutionView(flow, exec) {
    switchView('editor');
    $('exec-empty').classList.add('hidden');
    $('exec-view').classList.remove('hidden');
    $('exec-flow').textContent = `${flow.namespace}.${flow.id}`;
    $('exec-id').textContent = '#' + exec.id.slice(0, 8);
    setBadge(exec.state || 'CREATED');

    nodeById.clear();
    rowById.clear();
    $('nodes').innerHTML = '';
    $('timeline').innerHTML = '';

    // Render the planned pipeline (grey) from the YAML, then let SSE light it up.
    scanTasks($('yaml').value).forEach(t => ensureNode(t.id, t.type));
    drawConnectors();

    startedAt = Date.now();
    clearInterval(elapsedTimer);
    elapsedTimer = setInterval(tickElapsed, 100);
}

function follow(id) {
    if (followSource) followSource.close();
    followSource = new EventSource(`${API}/executions/${id}/follow`);
    followSource.addEventListener('execution', (e) => onExecution(JSON.parse(e.data)));
    followSource.onerror = () => { /* stream closed on terminal; ignore */ };
}

function onExecution(exec) {
    setBadge(exec.state);
    (exec.taskRuns || []).forEach(tr => {
        ensureNode(tr.taskId, typeOf(tr.taskId)).setAttribute('data-s', tr.state);
        upsertRow(tr.taskId, tr.state);
    });
    drawConnectors();

    if (isTerminal(exec.state)) {
        clearInterval(elapsedTimer);
        tickElapsed();
        if (followSource) followSource.close();
        refreshStatus();
        if (!$('view-runs').classList.contains('hidden')) loadRuns();
    }
}

function ensureNode(id, type) {
    if (nodeById.has(id)) return nodeById.get(id);
    const el = document.createElement('div');
    el.className = 'node';
    el.setAttribute('data-s', 'CREATED');
    el.dataset.id = id;
    el.innerHTML =
        `<div class="nid"><span class="sdot"></span>${id}</div>
         <div class="ntype">${short(type) || 'task'}</div>`;
    $('nodes').appendChild(el);
    nodeById.set(id, el);
    return el;
}

function upsertRow(id, state) {
    let row = rowById.get(id);
    if (!row) {
        row = document.createElement('div');
        row.className = 'tl-row';
        row.innerHTML =
            `<span class="sdot"></span>
             <span><span class="tname">${id}</span> <span class="ttype">${short(typeOf(id))}</span></span>
             <span class="tstate"></span>`;
        $('timeline').appendChild(row);
        rowById.set(id, row);
    }
    row.querySelector('.sdot').style.background = stateColor(state);
    const st = row.querySelector('.tstate');
    st.textContent = state;
    st.style.color = stateColor(state);
}

/* map a taskId back to its declared type using the last-scanned plan */
let planIndex = {};
function typeOf(id) { return planIndex[id] || ''; }

function scanTasks(yaml) {
    const out = [];
    planIndex = {};
    const lines = yaml.split('\n');
    let inTasks = false;
    let pending = null;
    for (const line of lines) {
        const topKey = line.match(/^([a-zA-Z_]+):\s*$/);
        if (topKey) { inTasks = (topKey[1] === 'tasks'); continue; }
        if (!inTasks) continue;
        const idm = line.match(/^\s*-\s*id:\s*["']?([\w.-]+)["']?\s*$/);
        if (idm) { pending = { id: idm[1], type: '' }; out.push(pending); continue; }
        const tm = line.match(/^\s*type:\s*["']?([\w.]+)["']?\s*$/);
        if (tm && pending && !pending.type) { pending.type = tm[1]; planIndex[pending.id] = tm[1]; }
    }
    return out;
}

/* ---------- connectors (adaptive SVG over the flex-wrapped nodes) ---------- */
function drawConnectors() {
    const svg = $('connectors');
    const wrap = svg.parentElement;
    const nodes = [...$('nodes').children];
    if (!wrap || nodes.length < 2) { svg.innerHTML = ''; return; }
    const base = wrap.getBoundingClientRect();
    let paths = '';
    for (let i = 0; i < nodes.length - 1; i++) {
        const a = nodes[i].getBoundingClientRect();
        const b = nodes[i + 1].getBoundingClientRect();
        const sameRow = Math.abs(a.top - b.top) < 12;
        let x1, y1, x2, y2;
        if (sameRow) {
            x1 = a.right - base.left; y1 = a.top + a.height / 2 - base.top;
            x2 = b.left - base.left; y2 = b.top + b.height / 2 - base.top;
        } else {
            x1 = a.left + a.width / 2 - base.left; y1 = a.bottom - base.top;
            x2 = b.left + b.width / 2 - base.left; y2 = b.top - base.top;
        }
        const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
        const c = sameRow
            ? `M${x1},${y1} C${mx},${y1} ${mx},${y2} ${x2},${y2}`
            : `M${x1},${y1} C${x1},${my} ${x2},${my} ${x2},${y2}`;
        paths += `<path d="${c}" fill="none" stroke="url(#g)" stroke-width="2"/>`;
    }
    svg.innerHTML =
        `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0" stop-color="#34e0c4" stop-opacity=".55"/>
            <stop offset="1" stop-color="#7c8cf8" stop-opacity=".55"/>
         </linearGradient></defs>${paths}`;
}

/* ---------- status bar ---------- */
async function refreshStatus() {
    try {
        const [h, m] = await Promise.all([
            fetch(`${API}/health`).then(r => r.json()),
            fetch(`${API}/metrics`).then(r => r.json())
        ]);
        $('health-dot').className = 'dot up';
        $('health-state').textContent = h.status || 'UP';
        $('stat-flows').textContent = h.flows ?? 0;
        $('stat-execs').textContent = h.executions ?? 0;
        $('stat-success').textContent = m.SUCCESS ?? 0;
        $('stat-failed').textContent = m.FAILED ?? 0;
    } catch {
        $('health-dot').className = 'dot';
        $('health-state').textContent = 'down';
    }
}

/* ---------- runs view ---------- */
function wireNav() {
    $('nav-editor').onclick = () => switchView('editor');
    $('nav-runs').onclick = () => { switchView('runs'); loadRuns(); };
}

function switchView(which) {
    const editor = which === 'editor';
    $('view-editor').classList.toggle('hidden', !editor);
    $('view-runs').classList.toggle('hidden', editor);
    $('nav-editor').classList.toggle('active', editor);
    $('nav-runs').classList.toggle('active', !editor);
}

async function loadRuns() {
    const list = $('runs-list');
    try {
        const runs = await fetch(`${API}/executions`).then(r => r.json());
        if (!runs.length) { list.innerHTML = '<div class="empty" style="height:auto;color:var(--muted-2)">No executions yet.</div>'; return; }
        list.innerHTML = '';
        runs.slice().reverse().forEach(r => {
            const el = document.createElement('div');
            el.className = 'run-row';
            el.innerHTML =
                `<div><div class="rid">#${r.id.slice(0, 8)}</div><div class="rflow">${r.namespace}.${r.flowId}</div></div>
                 <span class="badge" data-s="${r.state}">${r.state}</span>`;
            el.onclick = () => replay(r.id);
            list.appendChild(el);
        });
    } catch (e) {
        list.innerHTML = `<div class="toast err">${e.message}</div>`;
    }
}

async function replay(id) {
    const exec = await fetch(`${API}/executions/${id}`).then(r => r.json());
    switchView('editor');
    $('exec-empty').classList.add('hidden');
    $('exec-view').classList.remove('hidden');
    $('exec-flow').textContent = `${exec.namespace}.${exec.flowId}`;
    $('exec-id').textContent = '#' + exec.id.slice(0, 8);
    setBadge(exec.state);
    clearInterval(elapsedTimer);
    $('exec-elapsed').textContent = '—';
    nodeById.clear(); rowById.clear();
    $('nodes').innerHTML = ''; $('timeline').innerHTML = '';
    (exec.taskRuns || []).forEach(tr => {
        planIndex[tr.taskId] = planIndex[tr.taskId] || '';
        ensureNode(tr.taskId, typeOf(tr.taskId)).setAttribute('data-s', tr.state);
        upsertRow(tr.taskId, tr.state);
    });
    drawConnectors();
}

/* ---------- small helpers ---------- */
function setBadge(state) {
    const b = $('exec-badge');
    b.textContent = state;
    b.setAttribute('data-s', state);
}
function tickElapsed() {
    $('exec-elapsed').textContent = ((Date.now() - startedAt) / 1000).toFixed(1) + 's';
}
function isTerminal(s) {
    return ['SUCCESS', 'WARNING', 'FAILED', 'KILLED', 'CANCELLED', 'SKIPPED'].includes(s);
}
function stateColor(s) {
    return ({
        RUNNING: '#35c9f0', SUCCESS: '#35d69a', FAILED: '#ff5d73',
        WARNING: '#ffb454', SKIPPED: '#6b7280'
    })[s] || '#47566b';
}
function toast(msg, cls) { const t = $('toast'); t.textContent = msg; t.className = 'toast ' + (cls || ''); }
function showErr(e) { toast(e.message || String(e), 'err'); }

document.addEventListener('DOMContentLoaded', init);
