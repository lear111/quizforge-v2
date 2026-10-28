import Ajv from 'ajv';
import {Realm} from './realm.js';
import {apiVersion, reject, object, gradeResult, outlineStates, outlineItems, scoreValue, list, boundaries} from './validation.js';

// A request owns its validators and realms. No extension functions cross into host JS.
export class Operations {
  constructor(source, questionSchema, answerSchema, spec) {
    this.spec = {...spec, apiVersion: apiVersion(spec.apiVersion)};
    this.source = source;
    const ajv = new Ajv({allErrors: false, strict: true, validateFormats: false});
    this.validQuestion = ajv.compile(questionSchema);
    this.validAnswer = ajv.compile(answerSchema);
    this.reset();
  }
  reset() { this.vm = new Realm(this.source, this.spec); }
  question(data) {
    if (!this.validQuestion(data)) reject();
    this.vm.set({data});
    if (this.vm.rejects('validateQuestion', ['data'])) reject();
  }
  answer(data, answer) {
    if (!this.validAnswer(answer)) reject();
    this.vm.set({data, answer});
    if (this.vm.rejects('validateAnswer', ['answer', 'data'])) reject();
  }
  capabilities() { return {canAiGrade: this.vm.has('prepareAiGrading') && this.vm.has('review'), canOutlineItems: this.vm.canOutline}; }
  batch(input, visit, outline = false) {
    list(input.questions, outline ? 'INVALID_OUTLINE_ITEMS' : 'RULE_REJECTED');
    const offsets = input.contextResetOffsets ?? [];
    boundaries(offsets, input.questions.length);
    let boundary = 0;
    return input.questions.map((question, index) => {
      if (index === offsets[boundary]) { this.reset(); boundary++; }
      return visit(question);
    });
  }
  validateBank(input) {
    this.batch(input, question => this.question(question));
    return {valid: true};
  }
  outlineBatch(input) {
    if (this.spec.apiVersion.minor < 1 || this.spec.outlineItemsDeclared !== true) reject('Invalid outline capability', 'INVALID_OUTLINE_ITEMS');
    return {outlineItems: this.batch(input, data => {
      this.question(data); this.vm.set({data});
      let items;
      try { items = this.vm.canOutline ? this.vm.call('getOutlineItems', ['data']) : []; }
      catch (error) { if (error.code === 'ERR_SCRIPT_EXECUTION_TIMEOUT') throw error; reject('Invalid outline hook', 'INVALID_OUTLINE_ITEMS'); }
      return outlineItems(items);
    }, true)};
  }
  score(data, state) {
    if (!object(state) || !['unanswered', 'draft', 'submitted'].includes(state.status)) reject();
    this.question(data);
    state = {...state, submitted: state.status === 'submitted'};
    const status = state.submitted ? gradeResult(state.result) : 'unsubmitted';
    this.vm.set({data, state});
    let score;
    if (this.vm.has('getScore')) score = this.vm.call('getScore', ['data', 'state']);
    else if (state.submitted) score = {score: state.result?.score, maxScore: state.result?.maxScore};
    else {
      this.vm.set({data, state: {submitted: false, result: null}});
      score = {score: 0, maxScore: this.vm.call('project', ['data', 'state'])?.maxScore};
    }
    return scoreValue(score, status, state.submitted, this.spec.answerStateDeclared === true);
  }
  scoreBatch(input) {
    list(input.questions);
    return {scores: input.questions.map(question => this.score(question?.data, question?.state))};
  }
  project(input) {
    this.question(input.data); this.vm.set(input);
    return {projected: this.vm.call('project', ['data', 'state'])};
  }
  projectBatch(input) {
    list(input.questions);
    return {projected: input.questions.map(question => {
      if (!question || typeof question.state?.submitted !== 'boolean') reject();
      return this.project(question).projected;
    })};
  }
  validateAnswer(input) {
    this.question(input.data); this.answer(input.data, input.answer);
    const output = {valid: true};
    if (input.projectAfterValidation === true) {
      this.reset();
      output.projected = this.project({data: input.data, state: {submitted: false, result: null}}).projected;
    }
    if (input.outlineAfterValidation === true && this.spec.answerStateDeclared !== true) {
      this.reset();
      output.outlineScore = this.score(input.data, {status: 'draft', submitted: false, ...(input.outlineRevision === undefined ? {} : {revision: input.outlineRevision}), answer: input.answer, result: null});
    }
    return output;
  }
  submit(input) {
    this.question(input.data); this.answer(input.data, input.answer);
    this.vm.set({data: input.data, answer: input.answer});
    const result = this.vm.call('grade', ['data', 'answer']);
    gradeResult(result);
    this.vm.set({data: input.data, state: {submitted: true, result}});
    const output = {result, projected: this.vm.call('project', ['data', 'state'])};
    if (input.withScore === true) {
      this.reset();
      output.score = this.score(input.data, {status: 'submitted', submitted: true, answer: input.answer, result});
    }
    return output;
  }
  review(input) {
    this.question(input.data); this.answer(input.data, input.answer);
    if (!object(input.review) || !Number.isFinite(input.review.score) || !this.vm.has('review')) reject();
    this.vm.set({...input, context: {state: input.state}});
    const result = this.vm.call('review', ['data', 'answer', 'review', 'context']);
    gradeResult(result, false);
    this.vm.set({data: input.data, state: {submitted: true, result}});
    if (this.vm.has('prepareAiGrading')) {
      const score = this.vm.has('getScore') ? this.vm.call('getScore', ['data', 'state']) : result;
      if (!object(score) || score.score !== result.score || score.maxScore !== result.maxScore
          || (Object.hasOwn(score, 'gradingStatus') && score.gradingStatus !== (result.gradingStatus ?? 'graded'))) reject('Score is unavailable', 'SCORE_UNAVAILABLE');
      if (Object.hasOwn(score, 'outlineStates')) outlineStates(score.outlineStates, true);
    }
    return {result, projected: this.vm.call('project', ['data', 'state'])};
  }
  prepareAiGrading(input) {
    this.question(input.data); this.answer(input.data, input.answer);
    if (!this.vm.has('prepareAiGrading') || !this.vm.has('review')) reject();
    gradeResult(input.state?.result);
    const context = {state: input.state, ...(input.targetId == null ? {} : {targetId: input.targetId}), ...(input.automatic === true ? {automatic: true} : {})};
    this.vm.set({...input, context});
    return {gradingInput: this.vm.call('prepareAiGrading', ['data', 'answer', 'context'])};
  }
}
