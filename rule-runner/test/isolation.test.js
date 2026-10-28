import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {harness, basicRule} from './helpers.js';

const attacks = {
  'infinite loop': 'while(true){}',
  'infinite allocation': 'const a=[];for(;;)a.push(new Array(100000).fill(Math.random()))',
  'require access': "require('node:fs').readFileSync('/etc/passwd')",
  'process access': 'process.env.SECRET',
  'constructor escape': "({}).constructor.constructor('return process')()",
  'eval disabled': "eval('1+1')",
  'WebAssembly disabled': 'new WebAssembly.Module(new Uint8Array([0,97,115,109,1,0,0,0]))',
  'prototype pollution': "'use strict'; Object.prototype.polluted=true",
  'disable-proto': "'use strict';({}).__proto__.polluted=true",
  'oversized output': "return 'x'.repeat(3*1024*1024)",
  'promise microtask loop': 'Promise.resolve().then(()=>{while(true){}})',
  'path and stack in exception': "throw Error('/etc/private/secret.js at /tmp/rules.js:100')",
};
for (const [name, body] of Object.entries(attacks)) test(`isolation: ${name}; service recovers`, async t => {
  const h = await harness(t, {limit: 1});
  const spec = await h.pack(`QF.defineType({grade(){return {score:1,maxScore:1,correct:true,feedback:null}},project(d){'use strict';if(d.attack){${body}};return {ok:true}}});`);
  const actual = await h.post(h.request(spec, 'project', {data: {attack: true}, state: {submitted: false}}, {deadlineMs: 2500}));
  assert.equal(actual.status, 200);
  assert.equal(actual.body.ok, false, name);
  if (name === 'infinite loop' || name === 'promise microtask loop') assert.equal(actual.body.code, 'RULE_TIMEOUT');
  if (name === 'oversized output') assert.equal(actual.body.code, 'RULE_OUTPUT_LIMIT');
  if (['infinite loop', 'promise microtask loop', 'oversized output'].includes(name)) assert.equal(h.pool.workers.size, 0);
  assert.doesNotMatch(actual.body.message, /\/(etc|tmp)|\.js:|\bat .*\(|[A-Z]:\\/);
  assert.equal((await h.post(h.request(spec, 'project', {data: {attack: false}, state: {submitted: false}}))).body.ok, true);
  const healthy = await h.pack();
  assert.equal((await h.post(h.request(healthy))).body.ok, true);
  assert.equal((await h.get('/v1/health')).body.ok, true);
});

test('getScore has no globals from grade, answer validation, or a previous request', async t => {
  const h = await harness(t, {limit: 1});
  const spec = await h.pack(`QF.defineType({
    project(){return {}}, validateAnswer(){globalThis.fromValidation=true},
    grade(){globalThis.fromGrade=true;return {score:1,maxScore:1,correct:true,feedback:null}},
    getScore(){if (!globalThis.fromGrade && !globalThis.fromValidation) throw Error('no prior global');return {score:1,maxScore:1}}
  });`);
  const input = {data: {}, answer: {}};
  assert.equal((await h.post(h.request(spec, 'submit', input))).body.ok, true);
  for (const [op, payload] of [
    ['submit', {...input, withScore: true}],
    ['validateAnswer', {...input, outlineAfterValidation: true}],
    ['scoreBatch', {questions: [{data: {}, state: {status: 'unanswered'}}]}],
  ]) assert.equal((await h.post(h.request(spec, op, payload))).body.ok, false);
  assert.equal((await h.post(h.request(await h.pack(basicRule)))).body.ok, true);
});

test('worker permissions are exact package files, no write/child grants, and empty environment', async t => {
  const h = await harness(t, {limit: 1});
  const spec = await h.pack();
  assert.equal((await h.post(h.request(spec))).body.ok, true);
  const [worker] = h.pool.workers;
  const args = worker.child.spawnargs;
  assert.ok(args.includes('--permission'));
  assert.ok(args.includes('--disable-proto=throw'));
  assert.ok(args.includes('--max-old-space-size=96'));
  assert.deepEqual(worker.child.spawnfile, process.execPath);
  assert.ok(!args.some(arg => /allow-fs-write|allow-child|allow-worker|allow-fs-read=\*$/.test(arg)));
  for (const asset of [spec.rules, spec.questionSchema, spec.answerSchema]) assert.ok(args.some(arg => arg.endsWith(asset.sha256)));
  assert.ok(!args.includes('--allow-fs-read=' + h.dir));
  // No secret-bearing inherited environment is passed to spawn.
  assert.equal(worker.child.spawnargs.some(arg => arg.includes('QF_RULE_RUNNER_TOKEN')), false);
  if (process.platform === 'linux') assert.equal((await readFile(`/proc/${worker.child.pid}/environ`)).length, 0);
});
