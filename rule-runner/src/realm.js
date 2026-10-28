import vm from 'node:vm';
import {types} from 'node:util';
import {reject} from './validation.js';
import {RunnerError} from './errors.js';

const bootstrap = `
'use strict';
globalThis.__type = null;
globalThis.QF = Object.freeze({
  api: Object.freeze({major:1,minor:5,capabilities:Object.freeze([
    'practice','editor','editor-drafts','score','manual-review','ai-grading','resources','richtext',
    'navigation','lifecycle','outline-items','answer-state','appearance','answer-areas'
  ])}),
  defineType(type) {
    if (__type || !type || typeof type !== 'object' || Array.isArray(type) || typeof type.project !== 'function' || typeof type.grade !== 'function') throw Error('Invalid registration');
    for (const key of ['project','grade','validateQuestion','validateAnswer','getScore','review','prepareAiGrading'])
      if (type[key] != null && typeof type[key] !== 'function') throw Error('Invalid hook');
    if (type.getOutlineItems != null && typeof type.getOutlineItems !== 'function') {
      const error = Error('Invalid outline hook'); error.code = 'INVALID_OUTLINE_ITEMS'; throw error;
    }
    globalThis.__type = type;
  }
});
for (const value of [Object, Array, Function, String, Number, Boolean, RegExp, Date, Promise, Map, Set, JSON, Reflect]) {
  if (value.prototype) Object.freeze(value.prototype);
  Object.freeze(value);
}
`;

export class Realm {
  constructor(source, spec) {
    this.context = vm.createContext(Object.create(null), {codeGeneration: {strings: false, wasm: false}, microtaskMode: 'afterEvaluate'});
    this.run(bootstrap);
    this.run(source);
    if (!this.run('Boolean(__type)')) reject();
    this.canOutline = this.has('getOutlineItems');
    if (this.canOutline && (spec.outlineItemsDeclared !== true || spec.apiVersion.minor < 1)) reject('Invalid outline capability', 'INVALID_OUTLINE_ITEMS');
  }
  run(code) {
    try { return vm.runInContext(code, this.context, {timeout: 650, displayErrors: false, filename: 'extension-rules.js'}); }
    catch (error) {
      // Inspect only an own data property on a non-Proxy. Never run an extension
      // getter/prototype trap outside the VM's timeout while handling an exception.
      const inspectable = error !== null && ['object', 'function'].includes(typeof error) && !types.isProxy(error);
      const code = inspectable ? Object.getOwnPropertyDescriptor(error, 'code')?.value : undefined;
      if (code === 'ERR_SCRIPT_EXECUTION_TIMEOUT') throw new RunnerError('RULE_TIMEOUT', 'Rule hook exceeded its time limit');
      if (['INVALID_OUTLINE_ITEMS', 'INVALID_OUTLINE_STATES', 'SCORE_UNAVAILABLE'].includes(code)) throw new RunnerError(code, 'Extension rules rejected or could not process this data');
      throw new RunnerError('RULE_REJECTED', 'Extension rules rejected or could not process this data');
    }
  }
  has(hook) { return this.run(`typeof __type[${JSON.stringify(hook)}] === 'function'`); }
  set(input) { this.run(`globalThis.__input = JSON.parse(${JSON.stringify(JSON.stringify(input))});`); }
  call(hook, args) {
    const encoded = this.run(`JSON.stringify(__type[${JSON.stringify(hook)}](${args.map(key => `__input.${key}`).join(',')}))`);
    if (typeof encoded !== 'string') reject();
    return JSON.parse(encoded);
  }
  rejects(hook, args) { return this.run(`__type[${JSON.stringify(hook)}] ? __type[${JSON.stringify(hook)}](${args.map(key => `__input.${key}`).join(',')}) === false : false`); }
}
