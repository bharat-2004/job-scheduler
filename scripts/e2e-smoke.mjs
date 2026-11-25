// End-to-end smoke test against the running Compose stack (through Nginx).
// Usage: node scripts/e2e-smoke.mjs [baseUrl]      (default http://localhost:8080)
// Needs Node 18+ (built-in fetch). Exits non-zero on the first failed check.

const BASE = (process.argv[2] ?? 'http://localhost:8080') + '/api/v1';
let passed = 0;

function check(condition, message, extra) {
  if (!condition) {
    console.error(`FAIL  ${message}`, extra ?? '');
    process.exit(1);
  }
  passed++;
  console.log(`ok    ${message}`);
}

async function call(method, path, { token, body } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : null; } catch { json = text; }
  return { status: res.status, body: json, servedBy: res.headers.get('x-served-by') };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const created = []; // [token, jobId] pairs, deleted at the end so their crons stop firing
const TERMINAL = ['SUCCESS', 'FAILED', 'TIMED_OUT'];

async function waitForRun(token, runId, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const r = await call('GET', `/runs/${runId}`, { token });
    if (TERMINAL.includes(r.body?.status)) return r.body;
    await sleep(1000);
  }
  throw new Error(`run ${runId} did not finish in ${timeoutMs} ms`);
}

async function newUser() {
  const email = `e2e-${Date.now()}-${Math.floor(Math.random() * 1e6)}@example.com`;
  const reg = await call('POST', '/auth/register', { body: { email, password: 'correct-horse-battery' } });
  check(reg.status === 201, `register ${email}`, reg.body);
  const login = await call('POST', '/auth/login', { body: { email, password: 'correct-horse-battery' } });
  check(login.status === 200 && login.body.accessToken, 'login returns a JWT');
  return login.body.accessToken;
}

async function createJob(token, name, script, { cron = '*/5 * * * *', timeoutSeconds = 60 } = {}) {
  const r = await call('POST', '/jobs', {
    token,
    body: { name, script, timeoutSeconds, schedule: { type: 'CRON', cron, timezone: 'Asia/Kolkata' } },
  });
  check(r.status === 201, `create job '${name}'`, r.body);
  created.push([token, r.body.id]);
  return r.body;
}

async function runNow(token, jobId) {
  const r = await call('POST', `/jobs/${jobId}/runs`, { token });
  // Usually QUEUED, but a free worker can start it within milliseconds of the publish.
  check(r.status === 201 && ['QUEUED', 'RUNNING'].includes(r.body.status), 'manual trigger returns 201', r.body);
  return r.body;
}

// ---------------------------------------------------------------------------------------------

const alice = await newUser();
const bob = await newUser();

// Auth and validation
check((await call('GET', '/jobs')).status === 401, 'no token -> 401');
check((await call('POST', '/auth/login', { body: { email: 'nobody@example.com', password: 'x' } })).status === 401,
  'wrong credentials -> 401');
const badCron = await call('POST', '/jobs', {
  token: alice, body: { name: 'bad', script: 'print(1)', timeoutSeconds: 10, schedule: { type: 'CRON', cron: 'every day' } },
});
check(badCron.status === 400 && /cron/.test(badCron.body.detail), 'invalid cron -> 400 ProblemDetail', badCron.body);
const longTimeout = await call('POST', '/jobs', {
  token: alice, body: { name: 'slow', script: 'print(1)', timeoutSeconds: 600, schedule: { type: 'CRON', cron: '*/5 * * * *' } },
});
check(longTimeout.status === 400 && /interval/.test(longTimeout.body.detail), 'timeout > interval -> 400', longTimeout.body);
const soon = await call('POST', '/jobs', {
  token: alice, body: { name: 'soon', script: 'print(1)', timeoutSeconds: 5,
    schedule: { type: 'ONE_TIME', runAt: new Date(Date.now() + 20_000).toISOString() } },
});
check(soon.status === 400, 'one-time run less than 1 minute ahead -> 400', soon.body);

// Happy path: manual run, logs, script versioning
const hello = await createJob(alice, 'hello', 'import sys, platform\nprint("hello from", platform.python_version())\nprint("to stderr", file=sys.stderr)\n');
check(hello.nextRunTime && hello.scriptVersion === 1, 'job has nextRunTime and script v1');
check((await call('POST', '/jobs', { token: alice, body: { name: 'hello', script: 'x', timeoutSeconds: 5,
  schedule: { type: 'CRON', cron: '*/5 * * * *' } } })).status === 409, 'duplicate job name -> 409');

const run1 = await runNow(alice, hello.id);
const done1 = await waitForRun(alice, run1.id);
check(done1.status === 'SUCCESS' && done1.exitCode === 0, 'manual run SUCCESS with exit code 0', done1);
check(done1.dispatchedAt && done1.startedAt && done1.workerId, 'run records dispatch, start and worker', done1);
const logs1 = await call('GET', `/runs/${run1.id}/logs`, { token: alice });
check(logs1.status === 200 && /hello from 3\.12/.test(logs1.body) && /to stderr/.test(logs1.body),
  'logs contain stdout and stderr', logs1.body);

// Ownership: Bob sees 404, not 403
check((await call('GET', `/jobs/${hello.id}`, { token: bob })).status === 404, "another user's job -> 404");
check((await call('GET', `/runs/${run1.id}`, { token: bob })).status === 404, "another user's run -> 404");
check((await call('POST', `/jobs/${hello.id}/runs`, { token: bob })).status === 404, "trigger another user's job -> 404");

// 409 while running
const sleeper = await createJob(alice, 'sleeper', 'import time\nprint("start", flush=True)\ntime.sleep(8)\nprint("end")\n');
const run2 = await runNow(alice, sleeper.id);
const again = await call('POST', `/jobs/${sleeper.id}/runs`, { token: alice });
check(again.status === 409, 'second manual trigger while one is queued/running -> 409', again.body);
check((await waitForRun(alice, run2.id)).status === 'SUCCESS', 'sleeper run SUCCESS');

// Failure modes
const failing = await createJob(alice, 'failing', 'raise SystemExit(3)\n');
const f = await waitForRun(alice, (await runNow(alice, failing.id)).id);
check(f.status === 'FAILED' && f.failureReason === 'SCRIPT_ERROR' && f.exitCode === 3, 'non-zero exit -> FAILED SCRIPT_ERROR', f);

const slow = await createJob(alice, 'slow', 'import time\nprint("working", flush=True)\ntime.sleep(30)\n', { timeoutSeconds: 4 });
const s = await waitForRun(alice, (await runNow(alice, slow.id)).id);
check(s.status === 'TIMED_OUT' && s.hasLogs, 'over timeout -> killed, TIMED_OUT, partial logs kept', s);

const hog = await createJob(alice, 'hog', 'x = bytearray(600 * 1024 * 1024)\nprint(len(x))\n');
const h = await waitForRun(alice, (await runNow(alice, hog.id)).id);
check(h.status === 'FAILED' && /memory/.test(h.failureDetail ?? ''), 'over memory cap -> FAILED, OOM noted', h);

// Sandbox
const sandbox = await createJob(alice, 'sandbox', [
  'import os, socket',
  'print("uid", os.getuid())',
  'try:',
  '    open("/etc/evil", "w"); print("rootfs WRITABLE")',
  'except OSError as e: print("rootfs read-only:", e.errno)',
  'for host in ["postgres", "rabbitmq", "storage"]:',
  '    try:',
  '        socket.create_connection((host, 5432 if host == "postgres" else 5672 if host == "rabbitmq" else 9000), timeout=3); print(host, "REACHABLE")',
  '    except OSError as e: print(host, "unreachable")',
].join('\n') + '\n');
await waitForRun(alice, (await runNow(alice, sandbox.id)).id).then(async (r) => {
  const out = (await call('GET', `/runs/${r.id}/logs`, { token: alice })).body;
  check(/uid 65534/.test(out), 'script runs as non-root uid 65534', out);
  check(/rootfs read-only/.test(out), 'root filesystem is read-only', out);
  check(!/REACHABLE/.test(out), 'internal services unreachable from the sandbox', out);
});

// Update creates a new script version; enable/disable; delete
const put = await call('PUT', `/jobs/${hello.id}`, { token: alice, body: {
  name: 'hello', script: 'print("v2")\n', timeoutSeconds: 30, schedule: { type: 'CRON', cron: '*/10 * * * *', timezone: 'UTC' } } });
check(put.status === 200 && put.body.scriptVersion === 2, 'PUT stores script v2', put.body);
const v2 = await waitForRun(alice, (await runNow(alice, hello.id)).id);
check(v2.scriptVersion === 2 && /v2/.test((await call('GET', `/runs/${v2.id}/logs`, { token: alice })).body), 'next run uses v2');
check(done1.scriptVersion === 1, 'earlier run still records v1');

const off = await call('PATCH', `/jobs/${hello.id}`, { token: alice, body: { enabled: false } });
check(off.status === 200 && off.body.enabled === false && off.body.nextRunTime === null, 'disable clears nextRunTime');
const on = await call('PATCH', `/jobs/${hello.id}`, { token: alice, body: { enabled: true } });
check(on.body.enabled && Date.parse(on.body.nextRunTime) >= Date.now() + 59_000, 'enable puts next run >= 1 minute ahead');

const history = await call('GET', `/jobs/${hello.id}/runs`, { token: alice });
check(history.status === 200 && history.body.totalElements >= 2, 'run history is listed', history.body);

check((await call('DELETE', `/jobs/${failing.id}`, { token: alice })).status === 204, 'delete -> 204');
check((await call('GET', `/jobs/${failing.id}`, { token: alice })).status === 404, 'deleted job -> 404');

// Load balancing
const served = new Set();
for (let i = 0; i < 6; i++) served.add((await call('GET', '/jobs', { token: alice })).servedBy);
check(served.size === 2, `Nginx spreads requests over both API instances (${[...served].join(', ')})`);

// Scheduled execution: an every-minute job must run on its own
const tick = await createJob(alice, 'every-minute', 'print("tick")\n', { cron: '* * * * *', timeoutSeconds: 30 });
console.log(`...   waiting for the scheduler to run 'every-minute' (first run ${tick.nextRunTime})`);
const waitUntil = Date.parse(tick.nextRunTime) + 60_000;
let scheduled;
while (Date.now() < waitUntil) {
  await sleep(3000);
  const page = (await call('GET', `/jobs/${tick.id}/runs`, { token: alice })).body;
  scheduled = page.content.find((r) => r.triggerType === 'SCHEDULED' && TERMINAL.includes(r.status));
  if (scheduled) break;
}
check(scheduled?.status === 'SUCCESS', 'scheduled run executed by the scheduler', scheduled);
const lateness = (Date.parse(scheduled.startedAt) - Date.parse(scheduled.scheduledTime)) / 1000;
check(scheduled.scheduledTime === tick.nextRunTime, 'run created for exactly the computed time');
console.log(`      scheduled ${scheduled.scheduledTime}, started ${scheduled.startedAt} (${lateness.toFixed(2)} s after)`);
check(lateness >= 0 && lateness < 2, 'started on time (created a minute ahead, released by delayed delivery)');

for (const [token, id] of created) await call('DELETE', `/jobs/${id}`, { token });
console.log(`\nAll ${passed} checks passed (test jobs deleted).`);
