// Validate everything the Hermes adapter sends against the UPSTREAM protocol schema
// (foreman/src/protocol.ts, zod). Run from foreman/ so its node_modules resolve:
//
//   cd foreman && node --import tsx ../hermes-adapter/scripts/validate-upstream.ts --port 17878 --seconds 10
//
// Connects without an Origin header (like the mod), sends hello, then validates the snapshot and
// every incremental message for N seconds. Exit code 1 on any schema violation.
import WebSocket from 'ws';
import { ServerMessage } from '../../foreman/src/protocol.js';

const args = process.argv.slice(2);
const flag = (name: string, dflt: string) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt;
};
const port = Number(flag('port', '7878'));
const seconds = Number(flag('seconds', '10'));

let total = 0;
let bad = 0;
const byType = new Map<string, number>();
const ws = new WebSocket(`ws://127.0.0.1:${port}`);
ws.on('open', () => {
  ws.send(JSON.stringify({ v: 1, type: 'hello', modVersion: 'validate-upstream', protocol: 1, client: 'cli', id: 'h1' }));
  // a mutating intent must be refused with ack ok=false
  ws.send(JSON.stringify({ v: 1, type: 'goal.submit', text: 'should be refused', id: 'g1' }));
});
ws.on('message', (data) => {
  total++;
  const raw = JSON.parse(data.toString());
  byType.set(raw.type, (byType.get(raw.type) ?? 0) + 1);
  const r = ServerMessage.safeParse(raw);
  if (!r.success) {
    bad++;
    console.log(`INVALID ${raw.type}: ${r.error.issues.slice(0, 5).map((i) => `${i.path.join('.')}: ${i.message}`).join('; ')}`);
  }
  if (raw.type === 'snapshot') {
    console.log(`snapshot: ${raw.agents.length} agents, ${raw.tasks.length} tasks, ${raw.decisions.length} decisions, ${raw.feed.length} feed, ${raw.logs.length} log tails`);
  }
  if (raw.type === 'ack' && raw.re === 'g1') console.log(`goal.submit -> ack ok=${raw.ok} error=${JSON.stringify(raw.error)}`);
});
ws.on('error', (e) => {
  console.error(`connection error: ${e.message}`);
  process.exit(2);
});
setTimeout(() => {
  console.log(`messages: ${total} (${[...byType].map(([k, v]) => `${k}=${v}`).join(', ')}), schema violations: ${bad}`);
  ws.close();
  process.exit(bad ? 1 : 0);
}, seconds * 1000);
