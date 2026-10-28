import {test} from 'node:test';
import assert from 'node:assert/strict';
import {harness, legacy} from './helpers.js';

test('stateful validation preserves explicit batch context boundaries', async t => {
  const h = await harness(t);
  const source = `let calls=0; QF.defineType({validateQuestion(){return ++calls===1},getOutlineItems(){return [{id:'part',label:'第一问'}]},project(){return {maxScore:2}},grade(){return {score:2,maxScore:2,correct:true,feedback:null}}});`;
  const spec = await h.pack(source);
  for (const op of ['validateBank', 'outlineBatch']) for (const offsets of [[], [1]]) {
    const input = {questions: [{}, {}], contextResetOffsets: offsets};
    const actual = (await h.post(h.request(spec, op, input))).body;
    const expected = await legacy(spec, h.dir, op, input);
    assert.equal(actual.ok, expected.ok); assert.equal(actual.ok, offsets.length === 1);
    if (expected.ok) assert.deepEqual(actual.data, expected.data);
  }
});

test('score fallback, pending result and optional grading status match legacy', async t => {
  const h = await harness(t);
  const spec = await h.pack(`QF.defineType({project(){return {maxScore:2}},grade(){return {gradingStatus:'pending',score:null,maxScore:2,correct:null,feedback:null}}});`);
  const result = {gradingStatus: 'pending', score: null, maxScore: 2, correct: null, feedback: null};
  for (const [op, input] of [
    ['scoreBatch', {questions: [{data: {}, state: {status: 'unanswered'}}, {data: {}, state: {status: 'submitted', result}}]}],
    ['validateAnswer', {data: {}, answer: {}, outlineAfterValidation: true, outlineRevision: 3, projectAfterValidation: true}],
    ['submit', {data: {}, answer: {}}],
  ]) {
    const actual = (await h.post(h.request(spec, op, input))).body;
    const expected = await legacy(spec, h.dir, op, input);
    assert.equal(actual.ok, true, JSON.stringify(actual)); assert.deepEqual(actual.data, expected.data);
  }
});

test('invalid outline items and states preserve error codes', async t => {
  const h = await harness(t);
  const cases = [
    ['getOutlineItems(){return [{id:"x",label:"X"},{id:"x",label:"Y"}]}', 'outlineBatch', {questions: [{}]}, 'INVALID_OUTLINE_ITEMS'],
    ['getOutlineItems(){return [{id:"x",label:"<b>bad</b>"}]}', 'outlineBatch', {questions: [{}]}, 'INVALID_OUTLINE_ITEMS'],
    ['getScore(){return {score:0,maxScore:1,outlineStates:[{id:"x",status:"correct"}]}}', 'scoreBatch', {questions: [{data: {}, state: {status: 'draft'}}]}, 'INVALID_OUTLINE_STATES'],
    ['getScore(){return {score:0,maxScore:null}}', 'scoreBatch', {questions: [{data: {}, state: {status: 'unanswered'}}]}, 'SCORE_UNAVAILABLE'],
  ];
  for (const [hook, op, input, code] of cases) {
    const spec = await h.pack(`QF.defineType({project(){return {}},grade(){return {score:0,maxScore:1,correct:false,feedback:null}},${hook}})`);
    const actual = (await h.post(h.request(spec, op, input))).body;
    assert.equal(actual.code, code);
    assert.equal((await legacy(spec, h.dir, op, input)).code, code);
  }
});

test('undeclared outline hook and API v1.0 cannot use outline-items', async t => {
  const h = await harness(t);
  const source = 'QF.defineType({project(){return {}},grade(){},getOutlineItems(){return []}})';
  for (const fields of [{outlineItemsDeclared: false}, {apiVersion: {major: 1, minor: 0}}]) {
    const spec = await h.pack(source, true, true, fields);
    assert.equal((await h.post(h.request(spec, 'capabilities', {}))).body.code, 'INVALID_OUTLINE_ITEMS');
  }
});

test('AJV strict keywords and disabled formats match legacy settings', async t => {
  const h = await harness(t);
  const source = 'QF.defineType({project(d){return d},grade(){}})';
  for (const [schema, ok] of [[{type: 'string', format: 'email'}, true], [{type: 'string', unknownKeyword: true}, false]]) {
    const spec = await h.pack(source, schema);
    const input = {data: 'not an email', state: {submitted: false}};
    assert.equal((await h.post(h.request(spec, 'project', input))).body.ok, ok);
    assert.equal((await legacy(spec, h.dir, 'project', input)).ok, ok);
  }
});

test('AI review rejects getScore inconsistent with review result', async t => {
  const h = await harness(t);
  const spec = await h.pack(`QF.defineType({project(){return {}},grade(){},prepareAiGrading(){return {}},
    review(){return {score:1,maxScore:2,correct:false,feedback:null}},getScore(){return {score:2,maxScore:2}}})`);
  const input = {data: {}, answer: {}, review: {score: 1}, state: {}};
  assert.equal((await h.post(h.request(spec, 'review', input))).body.code, 'SCORE_UNAVAILABLE');
  assert.equal((await legacy(spec, h.dir, 'review', input)).code, 'SCORE_UNAVAILABLE');
});
