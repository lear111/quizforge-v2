import {test} from 'node:test';
import assert from 'node:assert/strict';
import {harness, legacy, loadFixture} from './helpers.js';

const doc = {type: 'doc', content: [{type: 'paragraph', content: [{type: 'text', text: '示例作答'}]}]};
function answerFor(name, data) {
  if (name === '单选题') return {selectedOptionId: data.correctOptionId};
  if (name === '多选题') return {selectedOptionIds: data.correctOptionIds};
  if (name === '判断题') return {value: data.correctAnswer};
  return {responses: Object.fromEntries(data.parts.map(part => [part.id, name === '简答题' ? doc : {
    type: 'qf-answer-area', formatVersion: 1, id: 'response-' + part.id, width: 760,
    height: part.answerHeight ?? 480, strokes: [], images: [], text: '示例作答',
  }]))};
}

for (const name of ['单选题', '多选题', '判断题', '简答题', '简答题（答题区）']) {
  test(`legacy parity: ${name}`, async t => {
    const h = await harness(t);
    const {bank, spec} = await loadFixture(h, name);
    const data = bank.questions[0].data, answer = answerFor(name, data);
    const result = (await legacy(spec, h.dir, 'submit', {data, answer})).data?.result;
    assert.ok(result, 'valid sample answer must reach grade');
    const state = {status: 'submitted', submitted: true, answer, result};
    const questions = bank.questions.map(question => question.data);
    const inputs = {
      capabilities: {},
      validateBank: {questions, contextResetOffsets: questions.length > 1 ? [1] : []},
      outlineBatch: {questions, contextResetOffsets: questions.length > 1 ? [1] : []},
      scoreBatch: {questions: [{data, state: {status: 'unanswered', result: null}}, {data, state: {status: 'draft', answer, result: null}}, {data, state}]},
      project: {data, state: {submitted: false, result: null}},
      projectBatch: {questions: [{data, state: {submitted: false, result: null}}, {data, state}]},
      validateAnswer: {data, answer, outlineAfterValidation: true, outlineRevision: 7},
      submit: {data, answer}, review: {data, answer, review: {score: 1, feedback: 'review', ...(data.parts ? {targetId: data.parts[0].id} : {})}, state},
      prepareAiGrading: {data, answer, state, ...(data.parts ? {targetId: data.parts[0].id} : {})},
    };
    for (const [op, input] of Object.entries(inputs)) await t.test(op, async () => {
      const expected = await legacy(spec, h.dir, op, input);
      const actual = await h.post(h.request(spec, op, input));
      assert.equal(actual.status, 200);
      assert.equal(actual.body.ok, expected.ok, JSON.stringify({op, expected, actual}));
      if (expected.ok) assert.deepEqual(actual.body.data, expected.data);
      else assert.equal(actual.body.code, expected.code ?? 'RULE_REJECTED');
    });
    await t.test('submit withScore equals separate legacy scoreBatch', async () => {
      const expected = await legacy(spec, h.dir, 'scoreBatch', {questions: [{data, state}]});
      const actual = await h.post(h.request(spec, 'submit', {data, answer, withScore: true}));
      assert.equal(actual.body.ok, true, JSON.stringify(actual));
      assert.deepEqual(actual.body.data.result, result);
      assert.deepEqual(actual.body.data.score, expected.data.scores[0]);
      assert.deepEqual(actual.body.data.projected, (await legacy(spec, h.dir, 'submit', {data, answer})).data.projected);
    });
    await t.test('invalid question and answer reject like legacy', async () => {
      for (const [op, input] of [['validateBank', {questions: [{}]}], ['validateAnswer', {data, answer: {invalid: true}}]]) {
        assert.equal((await legacy(spec, h.dir, op, input)).ok, false);
        const actual = await h.post(h.request(spec, op, input));
        assert.equal(actual.status, 200); assert.equal(actual.body.ok, false);
      }
    });
    if (data.parts) await t.test('automatic AI targets', async () => {
      const input = {...inputs.prepareAiGrading, automatic: true};
      const actual = await h.post(h.request(spec, 'prepareAiGrading', input));
      assert.deepEqual(actual.body.data, (await legacy(spec, h.dir, 'prepareAiGrading', input)).data);
      assert.equal(actual.body.ok, true);
    });
  });
}
