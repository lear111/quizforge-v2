import {test} from 'node:test';
import assert from 'node:assert/strict';
import {writeFile, mkdir, symlink} from 'node:fs/promises';
import http from 'node:http';
import path from 'node:path';
import {harness, token, basicRule} from './helpers.js';
import {blobPath} from '../src/blobs.js';
import {LIMITS} from '../src/errors.js';

test('protocol and required fields reject without executing rules', async t => {
  const h = await harness(t), spec = await h.pack();
  const cases = [
    ['unsupported protocol', {...h.request(spec), protocolVersion: 2}, 'UNSUPPORTED_PROTOCOL'],
    ['missing op', {...h.request(spec), op: undefined}, 'INVALID_REQUEST'],
    ['unknown op', {...h.request(spec), op: 'constructor'}, 'INVALID_REQUEST'],
    ['missing package', {...h.request(spec), package: undefined}, 'INVALID_REQUEST'],
    ['missing asset', {...h.request(spec), package: {...spec, rules: undefined}}, 'INVALID_REQUEST'],
    ['path traversal', {...h.request(spec), package: {...spec, rules: {sha256: '../secret'}}}, 'INVALID_REQUEST'],
    ['missing input', {...h.request(spec), input: undefined}, 'INVALID_REQUEST'],
    ['missing data', h.request(spec, 'project', {state: {submitted: false}}), 'INVALID_REQUEST'],
    ['missing requestId', {...h.request(spec), requestId: undefined}, 'INVALID_REQUEST'],
    ['invalid deadline', {...h.request(spec), deadlineMs: -1}, 'INVALID_REQUEST'],
    ['invalid priority', {...h.request(spec), priority: 'urgent'}, 'INVALID_REQUEST'],
    ['unsupported API', h.request({...spec, apiVersion: {major: 2, minor: 0}}), 'UNSUPPORTED_API_VERSION'],
    ['invalid API', h.request({...spec, apiVersion: {major: 1, minor: -1}}), 'INVALID_API_VERSION'],
  ];
  for (const [name, body, code] of cases) await t.test(name, async () => {
    const actual = await h.post(body);
    assert.equal(actual.status, 200); assert.equal(actual.body.ok, false); assert.equal(actual.body.code, code);
    assert.equal(typeof actual.body.message, 'string'); assert.ok(!('stack' in actual.body));
  });
});

test('token is required for execute, health and metrics', async t => {
  const h = await harness(t), spec = await h.pack();
  for (const value of ['', 'wrong']) {
    assert.equal((await h.post(h.request(spec), {'X-Internal-Token': value})).status, 401);
    for (const endpoint of ['/v1/health', '/v1/metrics']) assert.equal((await h.get(endpoint, {'X-Internal-Token': value})).status, 401);
  }
  assert.equal(h.pool.workers.size, 0);
  assert.equal((await h.get('/v1/health')).status, 200);
});

test('hash mismatch is checked again for an already pooled package', async t => {
  const h = await harness(t), spec = await h.pack();
  assert.equal((await h.post(h.request(spec))).body.ok, true);
  await writeFile(blobPath(h.dir, spec.rules.sha256), 'throw Error("changed")');
  const result = await h.post(h.request(spec));
  assert.equal(result.status, 200); assert.equal(result.body.code, 'HASH_MISMATCH');
});

test('missing and symlinked blobs are rejected', async t => {
  const h = await harness(t), spec = await h.pack();
  const missing = {...spec, rules: {sha256: 'f'.repeat(64)}};
  assert.equal((await h.post(h.request(missing))).body.code, 'RULE_ASSET_UNAVAILABLE');
  const dest = blobPath(h.dir, 'e'.repeat(64));
  await mkdir(path.dirname(dest), {recursive: true});
  await symlink(blobPath(h.dir, spec.rules.sha256), dest);
  assert.equal((await h.post(h.request({...spec, rules: {sha256: 'e'.repeat(64)}}))).body.code, 'RULE_ASSET_UNAVAILABLE');
});

test('input, rule and schema byte limits; service stays healthy', async t => {
  const h = await harness(t), spec = await h.pack();
  assert.equal((await h.post(h.request(spec, 'project', {data: 'x'.repeat(LIMITS.input), state: {submitted: false}}))).body.code, 'RULE_INPUT_TOO_LARGE');
  for (const field of ['rules', 'questionSchema', 'answerSchema']) {
    const asset = await h.blob(' '.repeat(LIMITS.asset + 1));
    assert.equal((await h.post(h.request({...spec, [field]: asset}))).body.code, 'RULE_ASSET_TOO_LARGE');
  }
  assert.equal((await h.post(h.request(spec))).body.ok, true);
});

test('chunked oversized request produces bounded JSON failure', async t => {
  const h = await harness(t);
  const response = await new Promise((resolve, reject) => {
    const req = http.request(h.url + '/v1/execute', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-Internal-Token': token}}, res => {
      let body = ''; res.on('data', chunk => { body += chunk; }); res.on('end', () => resolve({status: res.statusCode, body: JSON.parse(body)}));
    });
    req.on('error', reject);
    for (let index = 0; index < 129; index++) req.write('x'.repeat(65536));
    req.end();
  });
  assert.equal(response.status, 200); assert.equal(response.body.code, 'RULE_INPUT_TOO_LARGE');
});

test('output limit includes the full envelope without rejecting smaller valid replies', async t => {
  const h = await harness(t, {limit: 1});
  const spec = await h.pack('QF.defineType({project(d){return d.text},grade(){}})');
  const small = await h.post(h.request(spec, 'project', {data: {text: 'x'.repeat(LIMITS.output - 100)}, state: {submitted: false}}));
  assert.equal(small.body.ok, true);
  assert.ok(Buffer.byteLength(JSON.stringify(small.body)) <= LIMITS.output);
  const envelopeOnly = LIMITS.output - Buffer.byteLength(JSON.stringify({ok: true, data: {projected: ''}}));
  for (const size of [envelopeOnly, LIMITS.output]) {
    const large = await h.post(h.request(spec, 'project', {data: {text: 'x'.repeat(size)}, state: {submitted: false}}));
    assert.equal(large.body.code, 'RULE_OUTPUT_LIMIT'); assert.equal(h.pool.workers.size, 0);
  }
  assert.equal((await h.post(h.request(spec, 'project', {data: {text: 'recovered'}, state: {submitted: false}}))).body.ok, true);
});

test('malformed JSON and wrong content type have safe errors', async t => {
  const h = await harness(t);
  for (const contentType of ['application/json', 'text/plain']) {
    const res = await fetch(h.url + '/v1/execute', {method: 'POST', headers: {'X-Internal-Token': token, 'Content-Type': contentType}, body: '{'});
    assert.equal(res.status, 200); assert.equal((await res.json()).code, 'INVALID_REQUEST');
  }
});

test('metrics and structured logs contain only metadata', async t => {
  const h = await harness(t), spec = await h.pack(basicRule);
  const result = await h.post(h.request(spec, 'project', {data: {secret: 'never-log-this'}, state: {submitted: false}}));
  assert.equal(result.body.ok, true); assert.equal(typeof result.body.elapsedMs, 'number');
  const timeout = await h.pack('QF.defineType({project(){while(true){}},grade(){}})');
  assert.equal((await h.post(h.request(timeout))).body.code, 'RULE_TIMEOUT');
  const metrics = (await h.get('/v1/metrics')).body;
  assert.equal(metrics.operations.project.count, 2); assert.equal(metrics.timeouts, 1);
  assert.ok(metrics.operations.project.sumMs > 0); assert.equal(metrics.operations.project.buckets['+Inf'], 2);
  assert.equal(metrics.queueLength, 0); assert.ok(metrics.workers <= 2);
  assert.doesNotMatch(JSON.stringify(h.logs), /never-log-this|secret|rules\.js|questionSchema/);
  assert.deepEqual(Object.keys(h.logs[0]).sort(), ['requestId', 'op', 'digest', 'elapsedMs', 'code'].sort());
});
