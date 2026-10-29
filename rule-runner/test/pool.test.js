import {test} from 'node:test';
import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
import {harness, basicRule} from './helpers.js';

async function until(check) {
  const end = Date.now() + 10000;
  while (!check()) { assert.ok(Date.now() < end, 'condition deadline'); await delay(10); }
}
const slowRule = `QF.defineType({project(d){const end=Date.now()+(d.delay??0);while(Date.now()<end){};return d},grade(){return {score:1,maxScore:1,correct:true,feedback:null}}});`;

test('foreground queued work runs before background; global and package cap', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack(slowRule);
  const run = (id, priority, wait = 0) => h.post(h.request(spec, 'project', {data: {id, delay: wait}, state: {submitted: false}}, {requestId: id, priority}));
  const active = run('active', 'foreground', 400);
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const low = run('background', 'background');
  await until(() => h.pool.queue.length === 1);
  const high = run('foreground', 'foreground');
  await until(() => h.pool.queue.length === 2);
  assert.equal(h.pool.workers.size, 1);
  assert.ok((await active).body.ok); assert.ok((await high).body.ok); assert.ok((await low).body.ok);
  assert.deepEqual(h.logs.map(record => record.requestId), ['active', 'foreground', 'background']);
});

test('bounded waiting queue immediately returns 503 OVERLOADED', async t => {
  const h = await harness(t, {limit: 1, queueLimit: 1}), spec = await h.pack(slowRule);
  const body = h.request(spec, 'project', {data: {delay: 400}, state: {submitted: false}});
  const active = h.post(body);
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const queued = h.post(body);
  await until(() => h.pool.queue.length === 1);
  const overflow = await h.post(body);
  assert.equal(overflow.status, 503); assert.equal(overflow.body.code, 'OVERLOADED');
  assert.equal(h.pool.queue.length, 1);
  assert.equal((await active).body.ok, true); assert.equal((await queued).body.ok, true);
});

test('deadline includes waiting, kills active worker, then service recovers', async t => {
  const h = await harness(t, {limit: 1}), spec = await h.pack(slowRule);
  const active = h.post(h.request(spec, 'project', {data: {delay: 400}, state: {submitted: false}}, {deadlineMs: 100}));
  await until(() => [...h.pool.workers].some(worker => worker.job));
  const queued = await h.post(h.request(spec, 'project', {data: {}, state: {submitted: false}}, {deadlineMs: 20}));
  assert.equal(queued.body.code, 'RULE_TIMEOUT');
  assert.equal((await active).body.code, 'RULE_TIMEOUT');
  assert.equal((await h.post(h.request(await h.pack(basicRule)))).body.ok, true);
});

test('workers are grouped by package; at most two per package', async t => {
  const h = await harness(t, {limit: 3});
  // Finish fixture writes before readers start: the packages share schema blobs.
  const spec = await h.pack(basicRule), other = await h.pack(`${basicRule}\n// Second package`);
  const start = h.pool.start.bind(h.pool);
  let released = false;
  t.mock.method(h.pool, 'start', job => {
    const worker = start(job);
    // Hold real worker input until the queue/group assertions finish, without a busy loop.
    if (!released) worker.child.stdin.cork();
    return worker;
  });
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
  released = true;
  for (const worker of h.pool.workers) worker.child.stdin.uncork();
  const results = await Promise.all([...requests, second]);
  const diagnostic = JSON.stringify({results, logs: h.logs});
  for (const [index, result] of results.entries()) {
    assert.equal(result.status, 200, diagnostic);
    assert.equal(result.body.ok, true, diagnostic);
    assert.deepEqual(result.body.data.projected, [...inputs, otherInput][index].data, diagnostic);
  }
});

test('worker recycles after request count or idle timeout', async t => {
  const h = await harness(t, {limit: 1, maxRequests: 2, idleMs: 100}), spec = await h.pack();
  await h.post(h.request(spec));
  const first = [...h.pool.workers][0].child.pid;
  await h.post(h.request(spec));
  assert.equal(h.pool.workers.size, 0);
  await h.post(h.request(spec));
  assert.notEqual([...h.pool.workers][0].child.pid, first);
  await until(() => h.pool.workers.size === 0);
});
