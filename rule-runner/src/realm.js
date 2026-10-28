import vm from 'node:vm';
import {reject} from './validation.js';

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
    new vm.Script(source, {filename: 'extension-rules.js'}).runInContext(this.context, {timeout: 650, displayErrors: false});
    if (!this.run('Boolean(__type)')) reject();
    this.canOutline = this.has('getOutlineItems');
    if (this.canOutline && (spec.outlineItemsDeclared !== true || spec.apiVersion.minor < 1)) reject('Invalid outline capability', 'INVALID_OUTLINE_ITEMS');
  }
  run(code) { return vm.runInContext(code, this.context, {timeout: 650, displayErrors: false}); }
  has(hook) { return this.run(`typeof __type[${JSON.stringify(hook)}] === 'function'`); }
  set(input) { this.run(`globalThis.__input = JSON.parse(${JSON.stringify(JSON.stringify(input))});`); }
  call(hook, args) { return JSON.parse(this.run(`JSON.stringify(__type[${JSON.stringify(hook)}](${args.map(key => `__input.${key}`).join(',')}))`)); }
  rejects(hook, args) { return this.run(`__type[${JSON.stringify(hook)}] ? __type[${JSON.stringify(hook)}](${args.map(key => `__input.${key}`).join(',')}) === false : false`); }
}
