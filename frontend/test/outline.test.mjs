import {test} from 'node:test';
import assert from 'node:assert/strict';
import {consecutiveQuestionGroups} from '../practice/outline/outline-groups.js';

const row=(id,index,name,type='type')=>({id,outlineGroupIndex:index,outlineGroupName:name,type:{id:type,name:type}});
test('adjacent identically named author groups remain separate by outline index',()=>{
  const groups=consecutiveQuestionGroups([row('a',0,'阅读'),row('b',1,'阅读')]);
  assert.deepEqual(groups.map(g=>[g.index,g.name,g.items.map(i=>i.question.id)]),[[0,'阅读',['a']],[1,'阅读',['b']]]);
});
test('different extension types do not split one author group',()=>{
  const groups=consecutiveQuestionGroups([row('a',0,'综合','single'),row('b',0,'综合','essay')]);
  assert.equal(groups.length,1);assert.deepEqual(groups[0].items.map(i=>i.number),[1,2]);
});
test('unnamed author groups keep boundaries without invented headings',()=>{
  const groups=consecutiveQuestionGroups([row('a',0,null),row('b',1,null)]);
  assert.equal(groups.length,2);assert.deepEqual(groups.map(g=>g.name),[null,null]);
});
test('question numbering follows supplied outline order continuously across groups',()=>{
  const rows=[row('third',0,'一'),row('first',1,'二'),row('second',1,'二')];
  assert.deepEqual(consecutiveQuestionGroups(rows).flatMap(g=>g.items.map(i=>[i.question.id,i.number])),[['third',1],['first',2],['second',3]]);
});
test('pre-migration rows get no invented extension-type heading',()=>{
  const groups=consecutiveQuestionGroups([{id:'a',type:{name:'单选题'}},{id:'b',type:{name:'简答题'}}]);
  assert.equal(groups.length,1);assert.equal(groups[0].name,null);
});
test('empty question list has no groups',()=>assert.deepEqual(consecutiveQuestionGroups([]),[]));
