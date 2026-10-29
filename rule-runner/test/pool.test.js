import {test} from 'node:test';
import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
import {harness, basicRule, holdWorkers} from './helpers.js';

async function until(check) {
  const end = performance.now() + 10000;
  while (!check()) { assert.ok(performance.now() < end, 'condition deadline'); await delay(10); }
}

test('foreground queued work runs before background; global and package cap', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack();
  const release = holdWorkers(t, h.pool);
  const run = (id, priority) => h.post(h.request(spec, 'project', {data: {id}, state: {submitted: false}}, {requestId: id, priority}));
  const active = run('active', 'foreground');
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const low = run('background', 'background');
  await until(() => h.pool.queue.length === 1);
  const high = run('foreground', 'foreground');
  await until(() => h.pool.queue.length === 2);
  assert.equal(h.pool.workers.size, 1);
  release();
  assert.ok((await active).body.ok); assert.ok((await high).body.ok); assert.ok((await low).body.ok);
  assert.deepEqual(h.logs.map(record => record.requestId), ['active', 'foreground', 'background']);
});

test('bounded waiting queue immediately returns 503 OVERLOADED', async t => {
  const h = await harness(t, {limit: 1, queueLimit: 1}), spec = await h.pack();
  const release = holdWorkers(t, h.pool);
  const body = h.request(spec);
  const active = h.post(body);
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const queued = h.post(body);
  await until(() => h.pool.queue.length === 1);
  const overflow = await h.post(body);
  assert.equal(overflow.status, 503); assert.equal(overflow.body.code, 'OVERLOADED');
  assert.equal(h.pool.queue.length, 1);
  release();
  assert.equal((await active).body.ok, true); assert.equal((await queued).body.ok, true);
});

test('deadline includes waiting, kills active worker, then service recovers', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack();
  t.mock.timers.enable({apis: ['Date', 'setTimeout'], now: Date.now()});
  const release = holdWorkers(t, h.pool);
  const active = h.post(h.request(spec, 'project', {data: {}, state: {submitted: false}}, {deadlineMs: 1000}));
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const [worker] = h.pool.workers;
  const queued = h.post(h.request(spec, 'project', {data: {}, state: {submitted: false}}, {deadlineMs: 200}));
  await until(() => h.pool.queue.length === 1);
  t.mock.timers.tick(200);
  assert.equal((await queued).body.code, 'RULE_TIMEOUT');
  assert.ok(h.pool.workers.has(worker), 'queued timeout must not kill the active worker');
  t.mock.timers.tick(800);
  assert.equal((await active).body.code, 'RULE_TIMEOUT');
  assert.equal(h.pool.workers.size, 0);
  release();
  assert.equal((await h.post(h.request(await h.pack(basicRule)))).body.ok, true);
});

test('workers are grouped by package; at most two per package', async t => {
  const h = await harness(t, {limit: 3});
  // Finish fixture writes before readers start: the packages share schema blobs.
  const spec = await h.pack(basicRule), other = await h.pack(`${basicRule}\n// Second package`);
  const release = holdWorkers(t, h.pool);
  const inputs = Array.from({length: 3}, (_, index) => ({data: {package: 'first', index}, state: {submitted: false}}));
  const requests = inputs.map((input, index) => h.post(h.request(spec, 'project', input, {requestId: `first-${index}`})));
  await until(() => h.pool.queue.length === 1);
  assert.equal(h.pool.workers.size, 2);
  assert.ok([...h.pool.workers].every(worker => JSON.parse(worker.key).digest === spec.digest));
  const otherInput = {data: {package: 'second'}, state: {submitted: false}};
  const second = h.post(h.request(other, 'project', otherInput, {requestId: 'second'}));
  await until(() => h.pool.workers.size === 3);
  assert.deepEqual([...h.pool.workers].map(worker => JSON.parse(worker.key).digest).sort(), [spec.digest, spec.digest, other.digest].sort());
  assert.equal(h.pool.queue.length, 1);
  assert.equal(h.pool.queue[0].payload.spec.digest, spec.digest);
  release();
  const results = await Promise.all([...requests, second]);
  const diagnostic = JSON.stringify({results, logs: h.logs});
  for (const [index, result] of results.entries()) {
    assert.equal(result.status, 200, diagnostic);
    assert.equal(result.body.ok, true, diagnostic);
    assert.deepEqual(result.body.data.projected, [...inputs, otherInput][index].data, diagnostic);
  }
});

test('worker recycles after request count or idle timeout', async t => {
  const h = await harness(t, {limit: 1, maxRequests: 2, idleMs: 60000}), spec = await h.pack();
  await h.post(h.request(spec));
  const first = [...h.pool.workers][0].child.pid;
  await h.post(h.request(spec));
  assert.equal(h.pool.workers.size, 0);
  t.mock.timers.enable({apis: ['setTimeout']});
  await h.post(h.request(spec));
  assert.notEqual([...h.pool.workers][0].child.pid, first);
  t.mock.timers.tick(60000);
  assert.equal(h.pool.workers.size, 0);
});
