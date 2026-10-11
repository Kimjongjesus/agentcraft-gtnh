/* Read-only dashboard: one same-origin GET at a time; no write endpoints. */
(() => {
  'use strict';
  const POLL_MS = 3000;
  const TIMEOUT_MS = 7000;
  const STALE_MS = 15000;
  const LIMIT = { agents: 20, boards: 12, tasks: 8, decisions: 12, fleet: 16, jobs: 12, usage: 12, alerts: 12, sources: 16 };
  const $ = (id) => document.getElementById(id);
  const object = (value) => value !== null && typeof value === 'object' && !Array.isArray(value);
  const rows = (value) => Array.isArray(value) ? value.filter(object) : [];
  const text = (value, fallback = 'Unknown') => typeof value === 'string' && value.trim() ? value : fallback;
  const number = (value) => typeof value === 'number' && Number.isFinite(value) ? value : null;
  const count = (value) => number(value) !== null && value >= 0 ? Math.floor(value).toLocaleString() : '—';
  const percent = (value) => number(value) !== null && value >= 0 && value <= 100 ? value : null;
  const timestamp = (value) => {
    if (typeof value === 'number' && Number.isFinite(value) && value > 0) return value < 1e11 ? value * 1000 : value;
    if (typeof value === 'string' && value.trim()) {
      const parsed = Date.parse(value);
      return Number.isFinite(parsed) ? parsed : null;
    }
    return null;
  };
  const date = (value) => {
    const ts = timestamp(value);
    return ts === null ? 'Unknown' : new Date(ts).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  };
  const node = (tag, className, value) => {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (value !== undefined) element.textContent = String(value);
    return element;
  };
  const put = (id, value) => { $(id).textContent = value; };
  const empty = (message) => node('p', 'empty', message);
  const badge = (label, tone = 'neutral') => node('span', `badge ${tone}`, label);
  const states = {
    up: ['Up', 'good'], degraded: ['Degraded', 'warning'], down: ['Down', 'error'],
    ok: ['OK', 'good'], failed: ['Failed', 'error'], running: ['Running', 'working'],
    editing: ['Editing', 'working'], reading: ['Reading', 'working'], testing: ['Testing', 'working'],
    thinking: ['Thinking', 'thinking'], waiting_user: ['Waiting on user', 'warning'],
    blocked: ['Blocked', 'error'], error: ['Error', 'error'], done: ['Done', 'good'],
    idle: ['Idle', 'neutral'], unknown: ['Unknown', 'neutral'], starting: ['Starting', 'neutral'],
    stale: ['Stale', 'warning'], warn: ['Warning', 'warning'], critical: ['Critical', 'error'],
    info: ['Info', 'neutral'], open: ['Open', 'warning'], resolved: ['Resolved', 'good'],
    active: ['Active', 'working'], planning: ['Planning', 'neutral']
  };
  const stateEntry = (value) => typeof value === 'string' && Object.prototype.hasOwnProperty.call(states, value) ? states[value] : states.unknown;
  const status = (value) => {
    const entry = stateEntry(value);
    return badge(entry[0], entry[1]);
  };
  const row = (title, marker) => {
    const item = node('li', 'data-row');
    const top = node('div', 'row-top');
    top.append(node('h3', '', title));
    if (marker) top.append(marker);
    item.append(top);
    return item;
  };
  const detail = (parent, value, className = 'row-copy') => {
    if (typeof value === 'string' && value.trim()) parent.append(node('p', className, value));
  };
  const collection = (key, data, renderer, message, limit = LIMIT[key]) => {
    const content = $(key + '-content');
    content.replaceChildren();
    put(key + '-count', `${count(data.length)} supplied`);
    const shown = data.slice(0, limit);
    if (!shown.length) content.append(empty(message));
    else {
      const list = node('ul', 'data-list');
      shown.forEach((item) => list.append(renderer(item)));
      content.append(list);
    }
    put(key + '-footnote', data.length > limit ? `Showing ${limit} of ${data.length} supplied rows. ${data.length - limit} more are not displayed.` : '');
  };
  let current = null;
  let lastReceived = null;
  let failed = false;
  let failureMessage = '';
  let paused = false;
  let inFlight = false;
  let timer = null;
  let sourcesById = new Map();

  function sourceNote(parent, sourceId) {
    const source = sourcesById.get(sourceId);
    if (!source) {
      parent.append(node('p', 'source-warning', 'Source freshness unknown.'));
    } else if (source.state !== 'ok') {
      const known = stateEntry(source.state)[0];
      parent.append(node('p', 'source-warning', `Source: ${known}. Values may be retained from an earlier collection.`));
    }
  }

  function renderAgents(data) {
    const content = $('agents-content');
    content.replaceChildren();
    put('agents-count', `${count(data.length)} supplied`);
    if (!data.length) content.append(empty('No agents supplied by the adapter.'));
    data.slice(0, LIMIT.agents).forEach((agent) => {
      const card = node('article', 'agent-card');
      const top = node('div', 'agent-top');
      const name = text(agent.name, text(agent.id, 'Unnamed agent'));
      const avatar = node('span', 'avatar', name.slice(0, 2).toUpperCase());
      avatar.setAttribute('aria-hidden', 'true');
      const identity = node('div', 'agent-identity');
      identity.append(node('h3', '', name), node('p', '', text(agent.title, text(agent.role, 'Role unknown'))));
      top.append(avatar, identity);
      const state = node('div', 'agent-status');
      state.append(agent.active === false ? badge('Off shift') : status(agent.state));
      if (agent.paused === true) state.append(document.createTextNode(' '), badge('Agent paused', 'warning'));
      card.append(top, state, node('p', 'activity', text(agent.activity, 'No activity reported.')));
      const meta = node('div', 'meta-line');
      meta.append(node('span', 'mono', text(agent.id, 'ID unknown')), node('span', '', `Station: ${text(agent.station)}`));
      if (agent.taskId) meta.append(node('span', 'mono', `Task ${text(agent.taskId)}`));
      card.append(meta);
      content.append(card);
    });
    put('agents-footnote', data.length > LIMIT.agents ? `Showing ${LIMIT.agents} of ${data.length} supplied agents.` : 'Agent states are observations, not controls.');
  }

  function stat(value, label) {
    const result = node('div', 'stat');
    result.append(node('strong', '', count(value)), node('span', '', label));
    return result;
  }

  function renderBoards(goals, tasks) {
    const content = $('boards-content');
    content.replaceChildren();
    put('boards-count', `${count(goals.length)} boards`);
    if (!goals.length) content.append(empty('No board summaries supplied. Task columns remain available below.'));
    goals.slice(0, LIMIT.boards).forEach((goal) => {
      const card = node('article', 'board-card');
      const top = node('div', 'board-title');
      top.append(node('h3', '', text(goal.text, text(goal.board, 'Board'))), status(goal.status));
      card.append(top);
      const counts = object(goal.counts) ? goal.counts : {};
      const windowed = number(goal.windowDays) !== null && goal.windowDays > 0;
      const progress = number(goal.progress);
      if (progress !== null && progress >= 0 && progress <= 1 && number(goal.total) !== null && goal.total > 0) {
        const bar = node('progress');
        bar.max = 1;
        bar.value = progress;
        bar.setAttribute('aria-label', `${text(goal.text, 'Board')} completion ${Math.round(progress * 100)}%`);
        card.append(bar, node('p', 'small muted', `${Math.round(progress * 100)}% complete${windowed ? ' · all-time done + open cards' : ' · supplied task window'}`));
      } else card.append(node('p', 'small muted', number(goal.total) === 0 ? 'No cards in this board summary.' : 'Completion unknown.'));
      const statistics = node('div', 'board-stats');
      statistics.append(stat(counts.done, windowed ? 'Done · all-time' : 'Done · supplied window'), stat(goal.total, windowed ? 'Total · all-time done + open' : 'Total · supplied window'), stat(goal.doneRecent, windowed ? `Done · recent ${goal.windowDays} days` : 'Done · supplied window'), stat(goal.openDecisions, 'Open decisions'));
      card.append(statistics, node('p', 'board-counts', `To do ${count(counts.todo)} · In progress ${count(counts.doing)} · Review ${count(counts.review)} · Blocked ${count(counts.blocked)}`));
      content.append(card);
    });
    if (goals.length > LIMIT.boards) content.append(node('p', 'footnote', `Showing ${LIMIT.boards} of ${goals.length} board summaries.`));
    const wall = $('tasks-content');
    wall.replaceChildren();
    // Protocol history retains cancelled tasks, but the wall must hide them.
    const visibleTasks = tasks.filter((task) => task.status !== 'cancelled');
    const columns = [['todo', 'To do'], ['doing', 'In progress'], ['review', 'Review'], ['blocked', 'Blocked'], ['done', 'Done · supplied window']];
    const normal = new Set(columns.map(([key]) => key));
    if (visibleTasks.some((task) => !normal.has(task.status))) columns.push(['other', 'Other / unknown']);
    wall.classList.toggle('has-other', columns.length > 5);
    columns.forEach(([key, label]) => {
      const mine = visibleTasks.filter((task) => key === 'other' ? !normal.has(task.status) : task.status === key);
      mine.sort((a, b) => (number(b.updatedAt) || 0) - (number(a.updatedAt) || 0));
      const column = node('div', `task-column ${key}`);
      const heading = node('div', 'column-heading');
      heading.append(node('h3', '', label), node('span', 'mono', count(mine.length)));
      column.append(heading);
      if (!mine.length) column.append(node('p', 'column-empty', 'No supplied cards.'));
      mine.slice(0, LIMIT.tasks).forEach((task) => {
        const card = node('article', 'task-card');
        card.append(node('span', 'mono', text(task.id, 'ID unknown')), node('h4', '', text(task.title, 'Untitled task')));
        detail(card, text(task.board, 'Board unknown'));
        detail(card, task.assignee ? `Assigned to ${text(task.assignee)}` : 'Unassigned');
        if (key === 'other') detail(card, `Status: ${text(task.status)}`);
        if (key === 'blocked') detail(card, task.blockedReason, 'blocked-reason');
        if (key === 'done') detail(card, task.summary);
        column.append(card);
      });
      if (mine.length > LIMIT.tasks) column.append(node('p', 'footnote', `Showing ${LIMIT.tasks} of ${mine.length}; ${mine.length - LIMIT.tasks} more not displayed.`));
      wall.append(column);
    });
    put('tasks-note', `${tasks.length} supplied task cards (adapter cap: 200). ${tasks.length - visibleTasks.length} cancelled cards hidden and excluded from board totals. Up to ${LIMIT.tasks} newest cards per column. Done cards are supplied-window only; all-time completion is shown separately above when available.`);
  }

  function renderOperations(ops) {
    sourcesById = new Map(rows(ops.sources).map((source) => [source.id, source]));
    const fleet = rows(ops.services);
    collection('fleet', fleet, (service) => {
      const item = row(text(service.name, 'Unnamed service'), status(service.state));
      detail(item, `${text(service.group, 'Group unknown')} · ${text(service.sourceId, 'Source unknown')}`);
      detail(item, service.detail);
      const metrics = node('div', 'readings');
      [['cpu', 'CPU'], ['mem', 'Memory'], ['disk', 'Disk']].forEach(([key, label]) => {
        const value = percent(service[key]);
        metrics.append(node('span', '', `${label} ${value === null ? 'unknown' : `${value}%`}`));
      });
      item.append(metrics);
      if (service.since) detail(item, `State since ${date(service.since)}`);
      sourceNote(item, service.sourceId);
      return item;
    }, 'No fleet services supplied. This does not imply healthy services.');
    collection('jobs', rows(ops.jobs), (job) => {
      const item = row(text(job.name, 'Unnamed job'), status(job.lastStatus));
      detail(item, `Schedule: ${text(job.schedule, 'Unknown')} · ${job.enabled === true ? 'Enabled' : job.enabled === false ? 'Disabled' : 'Enablement unknown'}`);
      detail(item, `Last run: ${date(job.lastRun)}\nNext run: ${date(job.nextRun)}`);
      if (number(job.durationMs) !== null) detail(item, `Last duration: ${(job.durationMs / 1000).toLocaleString()} seconds`);
      detail(item, job.detail);
      sourceNote(item, job.sourceId);
      return item;
    }, 'No scheduled jobs supplied.');
    const usage = rows(ops.usage);
    const usageContent = $('usage-content');
    usageContent.replaceChildren();
    put('usage-count', `${count(usage.length)} windows`);
    if (!usage.length) usageContent.append(empty('No provider usage supplied. Remaining capacity is unknown.'));
    usage.slice(0, LIMIT.usage).forEach((entry) => {
      const card = node('article', 'usage-card');
      card.append(node('h3', '', text(entry.provider, 'Provider unknown')), node('p', 'small muted', text(entry.window, 'Window unknown')));
      const remaining = percent(entry.remainingPct);
      const value = node('p', 'usage-value', remaining === null ? 'Unknown' : `${remaining}%`);
      value.append(node('span', '', 'remaining'));
      card.append(value);
      if (remaining !== null) {
        const meter = node('meter');
        meter.min = 0;
        meter.max = 100;
        meter.value = remaining;
        meter.setAttribute('aria-label', `${text(entry.provider, 'Provider')} ${text(entry.window, 'window')}: ${remaining}% remaining`);
        card.append(meter);
      }
      detail(card, `Resets: ${date(entry.resetsAt)}`);
      detail(card, entry.detail);
      sourceNote(card, entry.sourceId);
      usageContent.append(card);
    });
    put('usage-footnote', usage.length > LIMIT.usage ? `Showing ${LIMIT.usage} of ${usage.length} supplied usage windows.` : 'Each window is independent. Unknown capacity is not zero or unlimited.');
    const alerts = rows(ops.alerts).sort((a, b) => Number(a.state !== 'open') - Number(b.state !== 'open') || (number(b.ts) || 0) - (number(a.ts) || 0));
    collection('alerts', alerts, (alert) => {
      const markers = node('span');
      markers.append(status(alert.severity), document.createTextNode(' '), status(alert.state));
      const item = row(text(alert.title, 'Untitled alert'), markers);
      detail(item, `${text(alert.source, 'Source unknown')} · ${date(alert.ts)}`);
      detail(item, alert.detail);
      if (alert.state === 'resolved') detail(item, `Resolved: ${date(alert.resolvedAt)}`);
      sourceNote(item, alert.sourceId);
      return item;
    }, 'No alerts supplied. This is not confirmation that every source is healthy.');
    put('alerts-count', `${alerts.filter((alert) => alert.state === 'open').length} open / ${alerts.length} supplied`);
    collection('sources', rows(ops.sources), (source) => {
      const item = row(text(source.name, text(source.id, 'Unnamed source')), status(source.state));
      detail(item, `Last successful collection: ${date(source.lastOk)}`);
      detail(item, `Interval: ${number(source.interval) !== null ? `${source.interval}s` : 'Unknown'} · Rejected rows: ${count(source.rejected === undefined ? 0 : source.rejected)}`);
      detail(item, source.detail);
      const counts = object(source.counts) ? source.counts : {};
      detail(item, `Source counts: ${count(counts.services)} services · ${count(counts.jobs)} jobs · ${count(counts.usage)} usage · ${count(counts.alerts)} alerts`);
      detail(item, text(source.id, 'ID unknown'), 'mono muted');
      return item;
    }, 'No operations sources configured or supplied. Operations coverage is unknown.');
    const cap = object(ops.limits) ? ops.limits : {};
    const capText = ['services', 'jobs', 'usage', 'alerts'].map((key) => `${key} ${count(cap[key])}`).join(' · ');
    const existing = $('sources-footnote').textContent;
    put('sources-footnote', `${existing ? `${existing} ` : ''}Adapter snapshot limits: ${capText}. Source counts describe collection results; merged snapshots may be capped.`);
  }

  function render(payload) {
    const snap = payload.snapshot;
    renderAgents(rows(snap.agents));
    renderBoards(rows(snap.goals), rows(snap.tasks));
    const decisions = rows(snap.decisions).filter((entry) => entry.status === 'open');
    collection('decisions', decisions, (decision) => {
      const item = row(text(decision.question, 'Question unavailable'), badge(text(decision.kind, 'Decision'), 'warning'));
      detail(item, decision.context);
      detail(item, `Agent ${text(decision.agentId)} · Task ${text(decision.taskId)} · ${date(decision.createdAt)}`, 'meta-line');
      const options = Array.isArray(decision.options) ? decision.options.filter((option) => typeof option === 'string') : [];
      if (options.length) detail(item, `Reported choices (not controls): ${options.join(' / ')}`, 'choices');
      return item;
    }, 'No open decisions supplied. Answers are handled in Hermes.');
    put('decisions-count', `${decisions.length} open`);
    renderOperations(payload.ops);
  }

  function freshness() {
    const meta = current ? current.meta : {};
    const updated = timestamp(meta.updatedAt);
    const age = updated === null ? null : Math.max(0, Date.now() - updated);
    const transportAge = lastReceived === null ? null : Date.now() - lastReceived;
    // The adapter owns source-poll staleness (its interval is configurable).
    // This page separately detects a stalled HTTP transport.
    const stale = failed || meta.stale === true || (transportAge !== null && transportAge > STALE_MS);
    const unknown = current && (updated === null || typeof meta.stale !== 'boolean');
    const state = $('connection-state');
    let label = current ? 'Connected' : 'Connecting';
    let tone = 'working';
    if (unknown) { label = 'Freshness unknown'; tone = 'neutral'; }
    if (stale) { label = current ? 'Stale snapshot' : 'Unavailable'; tone = 'warning'; }
    if (paused) { label = stale ? 'Paused · stale' : 'Updates paused'; tone = 'neutral'; }
    state.textContent = label;
    state.className = `badge ${tone}`;
    put('poll-count', `Polls ${count(meta.polls)}`);
    put('freshness', current ? `Adapter updated: ${date(meta.updatedAt)}${age === null ? '' : ` · ${Math.floor(age / 1000)}s ago`}. Received: ${date(lastReceived)}.` : 'No snapshot received yet.');
    const notice = $('connection-notice');
    let message = 'Loading the read-only snapshot. No actions are sent to Hermes.';
    let noticeClass = 'notice';
    if (failed) {
      message = `${failureMessage} ${current ? 'Last received data is preserved; it may be out of date.' : 'No data is available yet.'} ${paused ? 'Resume updates or refresh to retry.' : 'Retrying automatically.'}`;
      noticeClass += ' error';
    } else if (paused) {
      message = 'Automatic updates are paused on this page only. Agents and scheduled jobs continue normally. Refresh now reads one snapshot.';
      noticeClass += ' warning';
    } else if (stale) {
      message = 'The adapter snapshot is stale. Shown states may be historical; do not treat them as current health.';
      noticeClass += ' warning';
    } else if (unknown) {
      message = 'Connected, but adapter freshness metadata is incomplete. Current health cannot be confirmed.';
      noticeClass += ' warning';
    } else if (current) {
      message = 'Read-only connection. Service states reflect their source reports; check source freshness below. Answer decisions in Hermes.';
    }
    // Avoid repeating live-region announcements for an unchanged connection state.
    if (notice.textContent !== message) notice.textContent = message;
    notice.className = noticeClass;
  }

  function validPayload(payload) {
    return object(payload) && object(payload.snapshot) && object(payload.ops) && object(payload.meta)
      && ['agents', 'tasks', 'decisions', 'goals'].every((key) => Array.isArray(payload.snapshot[key]))
      && ['services', 'jobs', 'usage', 'alerts', 'sources'].every((key) => Array.isArray(payload.ops[key]));
  }

  function schedule() {
    clearTimeout(timer);
    timer = null;
    if (!paused) timer = setTimeout(poll, POLL_MS);
  }

  async function poll() {
    if (inFlight) return;
    clearTimeout(timer);
    timer = null;
    inFlight = true;
    $('refresh').disabled = true;
    $('refresh').textContent = 'Refreshing…';
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), TIMEOUT_MS);
    try {
      const response = await fetch('/api/snapshot', { method: 'GET', cache: 'no-store', credentials: 'omit', mode: 'same-origin', redirect: 'error', signal: controller.signal, headers: { Accept: 'application/json' } });
      if (!response.ok) throw new Error('http');
      const payload = await response.json();
      if (!validPayload(payload)) throw new Error('shape');
      render(payload);
      current = payload;
      lastReceived = Date.now();
      failed = false;
      failureMessage = '';
    } catch (error) {
      failed = true;
      failureMessage = error.name === 'AbortError' ? 'Snapshot request timed out.' : error.message === 'shape' ? 'Snapshot response was incomplete.' : 'Could not read the snapshot.';
      if (!current) {
        ['agents', 'boards', 'tasks', 'decisions', 'fleet', 'jobs', 'usage', 'alerts', 'sources'].forEach((key) => $(key + '-content').replaceChildren(empty('Data unavailable. Waiting for a successful snapshot.')));
        ['agents', 'boards', 'decisions', 'fleet', 'jobs', 'usage', 'alerts', 'sources'].forEach((key) => put(key + '-count', 'Unavailable'));
      }
    } finally {
      clearTimeout(timeout);
      inFlight = false;
      $('refresh').disabled = false;
      $('refresh').textContent = 'Refresh now';
      freshness();
      schedule();
    }
  }

  $('refresh').addEventListener('click', poll);
  $('pause').addEventListener('click', () => {
    paused = !paused;
    $('pause').setAttribute('aria-pressed', String(paused));
    $('pause').textContent = paused ? 'Resume updates' : 'Pause updates';
    clearTimeout(timer);
    timer = null;
    freshness();
    if (!paused) poll();
  });
  setInterval(freshness, 1000);
  poll();
})();
