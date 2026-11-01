import {test} from 'node:test';
import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
import {harness as runnerHarness, basicRule, holdWorkers, token} from './helpers.js';
import {PoolMetrics, STOP_REASONS} from '../src/metrics.js';
import {blobPath} from '../src/blobs.js';

async function harness(t, options) {
  const h = await runnerHarness(t, options), request = h.request;
  // Timing assertions use gates/a fake metrics clock, not a worker startup deadline.
  h.request = (spec, op, input, extra) => request(spec, op, input, {deadlineMs: 60000, ...extra});
  return h;
}

async function until(check) {
  const end = performance.now() + 15000;
  while (!check()) { assert.ok(performance.now() < end, 'condition deadline'); await delay(10); }
}
async function scrape(h) {
  const result = await fetch(h.url + '/v1/metrics/prometheus', {headers: {'X-Internal-Token': token}});
  assert.equal(result.status, 200);
  assert.equal(result.headers.get('content-type'), 'text/plain; version=0.0.4; charset=utf-8');
  assert.equal(result.headers.get('cache-control'), 'no-store');
  return result.text();
}
function sample(text, name, labels = '') {
  const prefix = name + (labels ? `{${labels}}` : '') + ' ';
  const lines = text.split('\n').filter(line => line.startsWith(prefix));
  assert.equal(lines.length, 1, prefix);
  return Number(lines[0].slice(prefix.length));
}

test('Prometheus endpoint authenticates, has typed zero series, and leaves JSON metrics unchanged', async t => {
  const h = await harness(t);
  for (const value of ['', 'wrong']) {
    const response = await fetch(h.url + '/v1/metrics/prometheus', {headers: {'X-Internal-Token': value}});
    assert.equal(response.status, 401); assert.equal((await response.json()).code, 'UNAUTHENTICATED');
  }
  const text = await scrape(h);
  for (const type of ['qf_runner_queue_wait_seconds histogram', 'qf_runner_execution_seconds histogram',
    'qf_runner_worker_starts_total counter', 'qf_runner_worker_stops_total counter', 'qf_runner_workers gauge', 'qf_runner_queue_length gauge']) {
    assert.ok(text.includes('# TYPE ' + type));
  }
  assert.equal(sample(text, 'qf_runner_workers'), 0); assert.equal(sample(text, 'qf_runner_queue_length'), 0);
  for (const reason of STOP_REASONS) assert.equal(sample(text, 'qf_runner_worker_stops_total', `reason="${reason}"`), 0);
  assert.deepEqual((await h.get('/v1/metrics')).body, {operations: {}, timeouts: 0, workers: 0, queueLength: 0});
  assert.equal((await fetch(h.url + '/v1/metrics/prometheus', {method: 'POST', headers: {'X-Internal-Token': token}})).status, 404);
});

test('queue and execution histograms measure separate stages by op with live gauges and bounded labels', async t => {
  let clock = 0;
  const metrics = new PoolMetrics(() => clock);
  const h = await harness(t, {limit: 1, metrics}), spec = await h.pack();
  const receive = h.pool.receive.bind(h.pool), held = [];
  t.mock.method(h.pool, 'receive', (worker, chunk) => held.push([worker, chunk]));
  const hasFrame = () => Buffer.concat(held.map(([, chunk]) => chunk)).includes(10);
  const flush = () => { for (const [worker, chunk] of held.splice(0)) receive(worker, chunk); };
  const active = h.post(h.request(spec, 'project', {data: {secret: 'never-export-this'}, state: {submitted: false}}, {deadlineMs: 60000}));
  await until(hasFrame);
  clock = 100;
  const queued = h.post(h.request(spec, 'submit', {data: {}, answer: {}}, {deadlineMs: 60000}));
  await until(() => h.pool.queue.length === 1);
  let text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_workers'), 1); assert.equal(sample(text, 'qf_runner_queue_length'), 1);
  assert.equal(sample(text, 'qf_runner_worker_starts_total'), 1);
  assert.equal(sample(text, 'qf_runner_queue_wait_seconds_count', 'op="project"'), 1);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 0);
  assert.equal(sample(text, 'qf_runner_queue_wait_seconds_count', 'op="submit"'), 0);
  clock = 4000; flush(); assert.equal((await active).body.ok, true);
  await until(hasFrame);
  clock = 9000; flush(); assert.equal((await queued).body.ok, true);
  text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_queue_wait_seconds_sum', 'op="project"'), 0);
  assert.equal(sample(text, 'qf_runner_queue_wait_seconds_sum', 'op="submit"'), 3.9);
  assert.equal(sample(text, 'qf_runner_execution_seconds_sum', 'op="project"'), 4);
  assert.equal(sample(text, 'qf_runner_execution_seconds_sum', 'op="submit"'), 5);
  for (const op of ['project', 'submit']) {
    assert.equal(sample(text, 'qf_runner_execution_seconds_count', `op="${op}"`), 1);
    assert.equal(sample(text, 'qf_runner_execution_seconds_bucket', `op="${op}",le="2.5"`), 0);
    assert.equal(sample(text, 'qf_runner_execution_seconds_bucket', `op="${op}",le="5"`), 1);
    assert.equal(sample(text, 'qf_runner_execution_seconds_bucket', `op="${op}",le="+Inf"`), 1);
  }
  assert.equal(sample(text, 'qf_runner_queue_length'), 0);
  assert.doesNotMatch(text, /never-export-this|secret|test-request|questionSchema|rules\.js/);
  assert.ok(!text.includes(spec.digest)); assert.ok(!text.includes(token));
  // Direct pool users cannot create unbounded operation labels either.
  const job = {payload: {op: 'private-arbitrary-label'}};
  metrics.queued(job); metrics.assigned(job); metrics.dispatched(job); metrics.finished(job);
  text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="other"'), 1);
  assert.ok(!text.includes('private-arbitrary-label'));
});

test('dispatch encoding and parent response parsing are outside execution timing', () => {
  let clock = 0;
  const metrics = new PoolMetrics(() => clock), job = {payload: {op: 'project'}};
  metrics.queued(job);
  clock = 100; metrics.assigned(job);
  clock = 900; metrics.dispatched(job);
  const receivedAt = 1800;
  clock = 3000; metrics.finished(job, receivedAt); metrics.finished(job);
  const text = metrics.render({workers: new Set(), queue: []});
  assert.equal(sample(text, 'qf_runner_queue_wait_seconds_sum', 'op="project"'), 0.1);
  assert.equal(sample(text, 'qf_runner_execution_seconds_sum', 'op="project"'), 0.9);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 1);
});

test('LRU churn counts starts and stops without changing package grouping or the global cap', async t => {
  const h = await harness(t, {limit: 1});
  const first = await h.pack(), second = await h.pack(basicRule + '\n// other package');
  for (const spec of [first, second, first]) {
    assert.equal((await h.post(h.request(spec))).body.ok, true);
    assert.equal(h.pool.workers.size, 1);
    assert.equal(JSON.parse([...h.pool.workers][0].key).digest, spec.digest);
  }
  const text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_worker_starts_total'), 3);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="lru"'), 2);
  assert.equal(sample(text, 'qf_runner_workers'), 1);
});

test('maxRequests and idle retirements have distinct reasons and are not counted again on child close', async t => {
  const h = await harness(t, {limit: 1, maxRequests: 2, idleMs: 60000}), spec = await h.pack();
  for (let i = 0; i < 2; i++) assert.equal((await h.post(h.request(spec))).body.ok, true);
  assert.equal(h.pool.workers.size, 0);
  t.mock.timers.enable({apis: ['setTimeout']});
  const files = Object.fromEntries(['rules', 'questionSchema', 'answerSchema'].map(name => [name, blobPath(h.dir, spec[name].sha256)]));
  // Avoid mocking timers beneath the HTTP client's own keepalive/timeout machinery.
  assert.equal((await h.pool.execute(spec, files, 'project', {data: {}, state: {submitted: false}}, Date.now() + 60000, true)).ok, true);
  t.mock.timers.tick(60000);
  const text = h.pool.metrics.render(h.pool);
  assert.equal(sample(text, 'qf_runner_worker_starts_total'), 2);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="max_requests"'), 1);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="idle"'), 1);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="abnormal_exit"'), 0);
  assert.equal(sample(text, 'qf_runner_workers'), 0);
});

test('queued expiry has no dispatch sample; active deadline records execution and one timeout retirement', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack();
  t.mock.timers.enable({apis: ['Date', 'setTimeout'], now: Date.now()});
  const release = holdWorkers(t, h.pool);
  const active = h.post(h.request(spec, 'project', {data: {}, state: {submitted: false}}, {deadlineMs: 1000}));
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const queued = h.post(h.request(spec, 'submit', {data: {}, answer: {}}, {deadlineMs: 200}));
  await until(() => h.pool.queue.length === 1);
  t.mock.timers.tick(200); assert.equal((await queued).body.code, 'RULE_TIMEOUT');
  t.mock.timers.tick(800); assert.equal((await active).body.code, 'RULE_TIMEOUT');
  release();
  const text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_queue_wait_seconds_count', 'op="submit"'), 0);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="submit"'), 0);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 1);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="timeout"'), 1);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="abnormal_exit"'), 0);
  assert.equal(sample(text, 'qf_runner_queue_length'), 0); assert.equal(sample(text, 'qf_runner_workers'), 0);
});

test('hook timeout retires once, records execution, then service recovers', async t => {
  const h = await harness(t, {limit: 1});
  const spec = await h.pack('QF.defineType({project(){while(true){}},grade(){}})');
  assert.equal((await h.post(h.request(spec))).body.code, 'RULE_TIMEOUT');
  assert.equal((await h.post(h.request(await h.pack()))).body.ok, true);
  const text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="timeout"'), 1);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 2);
  assert.equal(sample(text, 'qf_runner_worker_starts_total'), 2);
});

test('unexpected process exit records execution and abnormal_exit once, then service recovers', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack();
  const release = holdWorkers(t, h.pool);
  const active = h.post(h.request(spec, 'project', {data: {}, state: {submitted: false}}, {deadlineMs: 60000}));
  await until(() => [...h.pool.workers].some(worker => worker.job));
  [...h.pool.workers][0].child.kill('SIGKILL');
  assert.equal((await active).body.ok, false);
  release();
  assert.equal((await h.post(h.request(spec))).body.ok, true);
  const text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="abnormal_exit"'), 1);
  assert.equal(sample(text, 'qf_runner_worker_starts_total'), 2);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 2);
});

test('output overflow has its own retirement reason', async t => {
  const h = await harness(t, {limit: 1});
  const spec = await h.pack('QF.defineType({project(){return "x".repeat(2097152)},grade(){}})');
  assert.equal((await h.post(h.request(spec))).body.code, 'RULE_OUTPUT_LIMIT');
  const text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="output_limit"'), 1);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="abnormal_exit"'), 0);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 1);
});

test('shutdown clears gauges, ends active execution and does not invent execution for queued work', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack();
  holdWorkers(t, h.pool);
  const active = h.post(h.request(spec, 'project', {data: {}, state: {submitted: false}}, {deadlineMs: 60000}));
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const queued = h.post(h.request(spec, 'submit', {data: {}, answer: {}}, {deadlineMs: 60000}));
  await until(() => h.pool.queue.length === 1);
  h.pool.close();
  assert.equal((await active).status, 503); assert.equal((await queued).status, 503);
  h.pool.close(); // Idempotent shutdown must not double count.
  const text = await scrape(h);
  assert.equal(sample(text, 'qf_runner_worker_stops_total', 'reason="shutdown"'), 1);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="project"'), 1);
  assert.equal(sample(text, 'qf_runner_execution_seconds_count', 'op="submit"'), 0);
  assert.equal(sample(text, 'qf_runner_workers'), 0); assert.equal(sample(text, 'qf_runner_queue_length'), 0);
});
