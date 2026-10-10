/* Synthetic-only browser logic regressions. Run: node --test tests/test_dashboard_js.js
 * No dependencies, network, adapter source reads, or production test-only exports.
 */
'use strict';
const assert = require('node:assert/strict');
const test = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../hermes_adapter/dashboard_static/dashboard.js'), 'utf8');
const NOW = 1700000000000;

class Element {
  constructor(tag = 'div') {
    this.tagName = tag;
    this.children = [];
    this.className = '';
    this.attributes = {};
    this.listeners = {};
    this.disabled = false;
    this._text = '';
    this.classList = {
      toggle: (name, on) => {
        const names = new Set(this.className.split(/\s+/).filter(Boolean));
        if (on) names.add(name); else names.delete(name);
        this.className = [...names].join(' ');
      }
    };
  }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map((child) => child.textContent).join(''); }
  set innerHTML(_) { throw new Error('Dynamic HTML must never be interpreted'); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this._text = ''; this.children = [...children]; }
  setAttribute(name, value) { this.attributes[name] = String(value); }
  addEventListener(name, callback) { this.listeners[name] = callback; }
}
const descendants = (element, predicate) => element.children.flatMap((child) =>
  [...(predicate(child) ? [child] : []), ...descendants(child, predicate)]);
const tagged = (element, tag) => descendants(element, (child) => child.tagName === tag);
const classed = (element, name) => descendants(element, (child) => child.className.split(/\s+/).includes(name));
function fixture() {
  // Field names match Mapper.build(), OpsHub.snapshot() and DashboardServer metadata.
  return {
    snapshot: { agents: [], goals: [], tasks: [], decisions: [] },
    ops: { services: [], jobs: [], usage: [], alerts: [], sources: [],
      limits: { services: 256, jobs: 128, usage: 32, alerts: 100, sources: 16 } },
    meta: { updatedAt: NOW, generatedAt: NOW, polls: 1, stale: false, readOnly: true }
  };
}
async function harness(payload) {
  const elements = new Map();
  const ids = ['refresh', 'pause', 'connection-state', 'connection-notice', 'poll-count', 'freshness', 'tasks-note'];
  for (const key of ['agents', 'boards', 'tasks', 'decisions', 'fleet', 'jobs', 'usage', 'alerts', 'sources']) {
    ids.push(`${key}-content`);
    if (key !== 'tasks') ids.push(`${key}-count`);
    if (!['boards', 'tasks'].includes(key)) ids.push(`${key}-footnote`);
  }
  ids.forEach((id) => elements.set(id, new Element()));
  const get = (id) => {
    assert.ok(elements.has(id), `Unexpected DOM id: ${id}`);
    return elements.get(id);
  };
  let next = payload;
  let now = NOW;
  const requests = [];
  const timeouts = new Map();
  let timerId = 0;
  let refreshFreshness;
  class Clock extends Date { static now() { return now; } }
  const context = vm.createContext({
    document: { getElementById: get, createElement: (tag) => new Element(tag),
      createTextNode: (value) => { const el = new Element('#text'); el.textContent = value; return el; } },
    Date: Clock, AbortController,
    fetch: async (url, options) => {
      requests.push({ url, options });
      if (next instanceof Error) throw next;
      return { ok: true, json: async () => next };
    },
    setTimeout: (callback, delay) => { const id = ++timerId; timeouts.set(id, { callback, delay }); return id; },
    clearTimeout: (id) => timeouts.delete(id),
    setInterval: (callback) => { refreshFreshness = callback; return 1; }
  });
  vm.runInContext(script, context, { filename: 'dashboard.js' });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(get('refresh').disabled, false, 'Initial poll must finish');
  return {
    get, requests, timeouts,
    refresh: async (value) => { next = value; await get('refresh').listeners.click(); },
    pause: async () => { get('pause').listeners.click(); await new Promise((resolve) => setImmediate(resolve)); },
    advance: (ms) => { now += ms; refreshFreshness(); }
  };
}

test('board all-time counts stay separate from window cards, including zero and no-window boards', async () => {
  const p = fixture();
  p.snapshot.goals = [
    { text: 'Board synthetic', board: 'synthetic', status: 'active', progress: 0.8, total: 125,
      counts: { done: 100, todo: 10, doing: 5, review: 5, blocked: 5 }, doneRecent: 2, windowDays: 3, openDecisions: 4 },
    { text: 'Board all-supplied', status: 'done', progress: 1, total: 1,
      counts: { done: 1 }, doneRecent: 1, windowDays: 0, openDecisions: 0 },
    { text: 'Board empty', status: 'planning', progress: 0, total: 0,
      counts: { done: 0 }, doneRecent: 0, windowDays: 3, openDecisions: 0 }
  ];
  p.snapshot.tasks = [{ id: 'd1', status: 'done', title: 'Window card', updatedAt: NOW }];
  const h = await harness(p);
  const cards = classed(h.get('boards-content'), 'board-card');
  assert.match(cards[0].textContent, /80% complete · all-time done \+ open cards/);
  assert.deepEqual(classed(cards[0], 'stat').map((el) => el.textContent),
    ['100Done · all-time', '125Total · all-time done + open', '2Done · recent 3 days', '4Open decisions']);
  assert.match(cards[1].textContent, /100% complete · supplied task window/);
  assert.equal(tagged(cards[1], 'progress')[0].value, 1);
  assert.match(cards[2].textContent, /No cards in this board summary/);
  assert.equal(tagged(cards[2], 'progress').length, 0);
  assert.equal(classed(h.get('tasks-content'), 'task-card').length, 1);
});

test('cancelled history is hidden, unknown status is isolated, and only newest eight cards are shown', async () => {
  const p = fixture();
  p.snapshot.tasks = [
    { id: 'cancel', title: 'Cancelled canary', status: 'cancelled', updatedAt: NOW + 99 },
    ...Array.from({ length: 10 }, (_, i) => ({ id: `t${i}`, title: `Task ${i}`, status: 'todo', updatedAt: NOW + i })),
    { id: 'future', title: 'Future status', status: 'future' },
    { id: 'blocked', title: 'Blocked task', status: 'blocked', blockedReason: 'Synthetic blocker' },
    { id: 'done', title: 'Done task', status: 'done', summary: 'Synthetic summary' }
  ];
  const h = await harness(p);
  const wall = h.get('tasks-content');
  assert.doesNotMatch(wall.textContent, /Cancelled canary|cancel/);
  assert.match(wall.textContent, /Other \/ unknown/);
  const todo = classed(wall, 'todo')[0];
  assert.equal(tagged(todo, 'span')[0].textContent, '10');
  assert.deepEqual(classed(todo, 'task-card').map((el) => tagged(el, 'span')[0].textContent),
    ['t9', 't8', 't7', 't6', 't5', 't4', 't3', 't2']);
  assert.match(todo.textContent, /Showing 8 of 10; 2 more/);
  assert.match(wall.textContent, /Synthetic blocker/);
  assert.match(wall.textContent, /Synthetic summary/);
  assert.match(h.get('tasks-note').textContent, /14 supplied task cards.*1 cancelled cards hidden/);
  await h.refresh({ ...p, snapshot: { ...p.snapshot, tasks: [p.snapshot.tasks[0]] } });
  assert.equal(wall.children.length, 5);
  assert.doesNotMatch(wall.className, /has-other/);
});

test('operations use source.counts, optional rejection zero, limits, and per-source freshness', async () => {
  const p = fixture();
  p.ops.sources = [
    { id: 'fresh', name: 'Fresh source', state: 'ok', interval: 60, lastOk: NOW,
      counts: { services: 1, jobs: 2, usage: 3, alerts: 4 } },
    { id: 'old', name: 'Old source', state: 'stale', interval: 120, lastOk: NOW - 600000,
      rejected: 7, detail: 'Synthetic collection failure', counts: { services: 1, jobs: 0, usage: 0, alerts: 0 } }
  ];
  p.ops.services = [
    { id: 'fresh/service', name: 'Healthy', state: 'up', sourceId: 'fresh', cpu: 0, mem: 100, disk: 50.5, since: NOW },
    { id: 'old/service', name: 'Retained', state: 'unknown', sourceId: 'old' },
    { id: 'missing/service', name: 'Uncovered', state: 'down', sourceId: 'missing' }
  ];
  const h = await harness(p);
  const sources = classed(h.get('sources-content'), 'data-row');
  assert.match(sources[0].textContent, /Interval: 60s · Rejected rows: 0/);
  assert.match(sources[0].textContent, /Source counts: 1 services · 2 jobs · 3 usage · 4 alerts/);
  assert.match(sources[1].textContent, /Rejected rows: 7/);
  assert.match(sources[1].textContent, /Synthetic collection failure/);
  assert.match(h.get('sources-footnote').textContent, /services 256 · jobs 128 · usage 32 · alerts 100/);
  const services = classed(h.get('fleet-content'), 'data-row');
  assert.equal(classed(services[0], 'source-warning').length, 0);
  assert.match(services[0].textContent, /CPU 0%Memory 100%Disk 50.5%/);
  assert.match(services[1].textContent, /Source: Stale.*retained/);
  assert.match(services[1].textContent, /CPU unknownMemory unknownDisk unknown/);
  assert.match(services[2].textContent, /Source freshness unknown/);
  assert.equal(h.get('connection-state').textContent, 'Connected', 'Fresh adapter does not imply fresh sources');
});

test('enum values must be strings, not coercible arrays or booleans', async () => {
  const p = fixture();
  const malformed = [['ok'], true, { state: 'ok' }, 'toString', '__proto__', null];
  p.ops.sources = malformed.map((state, i) => ({ id: `s${i}`, name: `Source ${i}`, state }));
  p.ops.services = malformed.map((state, i) => ({ name: `Service ${i}`, state: ['up'], sourceId: `s${i}` }));
  p.snapshot.agents = malformed.map((state, i) => ({ name: `Agent ${i}`, state, active: true, paused: false }));
  const h = await harness(p);
  assert.deepEqual(classed(h.get('sources-content'), 'badge').map((el) => el.textContent), malformed.map(() => 'Unknown'));
  assert.deepEqual(classed(h.get('fleet-content'), 'badge').map((el) => el.textContent), malformed.map(() => 'Unknown'));
  assert.deepEqual(classed(h.get('agents-content'), 'badge').map((el) => el.textContent), malformed.map(() => 'Unknown'));
  classed(h.get('fleet-content'), 'source-warning').forEach((el) => assert.match(el.textContent, /Source: Unknown/));
});

test('usage distinguishes zero from omitted capacity; job and alert timestamps use wire keys', async () => {
  const p = fixture();
  p.ops.sources = [{ id: 's', state: 'ok' }];
  p.ops.usage = [
    { provider: 'Synthetic provider', window: 'Short', remainingPct: 0, resetsAt: NOW, sourceId: 's' },
    { provider: 'Synthetic provider', window: 'Long', sourceId: 's' },
    { provider: 'Synthetic provider', window: 'Invalid', remainingPct: true, resetsAt: 'invalid', sourceId: 's' }
  ];
  p.ops.jobs = [{ name: 'Synthetic job', lastStatus: 'failed', enabled: false, schedule: 'Hourly',
    lastRun: NOW, nextRun: NOW + 60000, durationMs: 2500, sourceId: 's' }];
  p.ops.alerts = [
    { title: 'Resolved alert', state: 'resolved', severity: 'info', ts: NOW + 1000, resolvedAt: NOW + 2000, sourceId: 's' },
    { title: 'Open alert', state: 'open', severity: 'critical', ts: NOW, sourceId: 's' }
  ];
  const h = await harness(p);
  const usage = classed(h.get('usage-content'), 'usage-card');
  assert.equal(classed(usage[0], 'usage-value')[0].textContent, '0%remaining');
  assert.equal(tagged(usage[0], 'meter')[0].value, 0);
  assert.equal(classed(usage[1], 'usage-value')[0].textContent, 'Unknownremaining');
  assert.equal(tagged(usage[1], 'meter').length, 0);
  assert.match(usage[2].textContent, /Resets: Unknown/);
  assert.equal(tagged(usage[2], 'meter').length, 0);
  const date = (ms) => new Date(ms).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const jobs = h.get('jobs-content').textContent;
  assert.ok(jobs.includes(`Last run: ${date(NOW)}\nNext run: ${date(NOW + 60000)}`));
  assert.match(jobs, /Failed.*Schedule: Hourly · Disabled.*Last duration: 2.5 seconds/s);
  const alerts = classed(h.get('alerts-content'), 'data-row');
  assert.match(alerts[0].textContent, /Open alertCritical.*Open/);
  assert.ok(alerts[1].textContent.includes(`Resolved: ${date(NOW + 2000)}`));
  assert.equal(h.get('alerts-count').textContent, '1 open / 2 supplied');
});

test('agent state, active and paused stay independent; only open decisions and text options render', async () => {
  const p = fixture();
  p.snapshot.agents = [
    { id: 'a1', name: '<script>synthetic</script>', title: 'Synthetic lead', state: 'waiting_user',
      active: true, paused: true, station: 'user', activity: 'Needs input', taskId: 't1' },
    { id: 'a2', state: 'running', active: false, paused: false }
  ];
  p.snapshot.decisions = [
    { question: 'Open question', status: 'open', kind: 'permission', options: ['Allow', 3, null, 'Deny'],
      agentId: 'a1', taskId: 't1', createdAt: NOW },
    { question: 'Answered canary', status: 'answered' },
    { question: 'Cancelled canary', status: 'cancelled' }
  ];
  const h = await harness(p);
  const agents = classed(h.get('agents-content'), 'agent-card');
  assert.match(agents[0].textContent, /<script>synthetic<\/script>/);
  assert.match(agents[0].textContent, /Waiting on user.*Agent paused/);
  assert.match(agents[0].textContent, /Station: user.*Task t1/);
  assert.match(agents[1].textContent, /Off shift/);
  assert.doesNotMatch(agents[1].textContent, /Running/);
  assert.equal(h.get('decisions-count').textContent, '1 open');
  assert.match(h.get('decisions-content').textContent, /Reported choices \(not controls\): Allow \/ Deny/);
  assert.doesNotMatch(h.get('decisions-content').textContent, /Answered canary|Cancelled canary/);
  assert.equal(tagged(h.get('decisions-content'), 'button').length, 0);
});

test('adapter staleness is authoritative for configurable poll intervals; transport is checked independently', async () => {
  const p = fixture();
  const h = await harness(p);
  h.advance(20000);
  assert.equal(h.get('connection-state').textContent, 'Stale snapshot', 'No recent HTTP receipt');
  await h.refresh(p);
  assert.equal(h.get('connection-state').textContent, 'Connected', 'A 20-second-old source poll can be fresh at a longer configured interval');
  assert.match(h.get('freshness').textContent, /20s ago/);
  await h.refresh({ ...p, meta: { ...p.meta, stale: true } });
  assert.equal(h.get('connection-state').textContent, 'Stale snapshot', 'Backend stale signal is never suppressed by a fresh HTTP receipt');
  await h.refresh({ ...p, meta: { ...p.meta, stale: 'false' } });
  assert.equal(h.get('connection-state').textContent, 'Freshness unknown');
});

test('malformed refresh retains last good data; refresh is same-origin GET and pauses only polling', async () => {
  const p = fixture();
  p.snapshot.agents = [{ name: 'Preserved synthetic agent', state: 'idle', active: true }];
  const h = await harness(p);
  assert.equal(h.requests.length, 1);
  assert.equal(h.requests[0].url, '/api/snapshot');
  assert.equal(h.requests[0].options.method, 'GET');
  assert.equal(h.requests[0].options.credentials, 'omit');
  assert.equal(h.requests[0].options.mode, 'same-origin');
  assert.equal(h.requests[0].options.redirect, 'error');
  assert.equal([...h.timeouts.values()][0].delay, 3000);
  await h.refresh({ ...p, ops: {} });
  assert.equal(h.get('connection-state').textContent, 'Stale snapshot');
  assert.match(h.get('connection-notice').textContent, /Snapshot response was incomplete.*Last received data is preserved/);
  assert.match(h.get('agents-content').textContent, /Preserved synthetic agent/);
  await h.refresh(p);
  assert.equal(h.get('connection-state').textContent, 'Connected');
  await h.pause();
  assert.equal(h.timeouts.size, 0);
  assert.equal(h.get('pause').attributes['aria-pressed'], 'true');
  assert.equal(h.get('connection-state').textContent, 'Updates paused');
  h.advance(16000);
  assert.equal(h.get('connection-state').textContent, 'Paused · stale');
  await h.refresh({ ...p, meta: { ...p.meta, updatedAt: NOW + 16000 } });
  assert.equal(h.timeouts.size, 0, 'Manual refresh must not resume polling');
  assert.equal(h.get('connection-state').textContent, 'Updates paused');
});
