import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mountUnavailableQuestion} from '../practice/history/unavailable-question.js';
import {createHistoryView} from '../practice/history/history-view.js';
import {canViewDraft} from '../practice/practice/practice-context.js';

test('停用历史仅显示丢失提示，不呈现题目、作答、分数、图片或脚本',async()=>{
  const nodes=[];
  const document={createElement(tag){const node={tag,style:{},children:[],append(...values){this.children.push(...values);},remove(){this.removed=true;}};nodes.push(node);return node;}};
  const host=document.createElement('div');host.ownerDocument=document;
  const plugin=mountUnavailableQuestion(host);await plugin.ready;
  assert.deepEqual(nodes.map(node=>node.tag),['div','section','p']);
  assert.equal(plugin.frame.children[0].textContent,'该题的历史记录已丢失（题型拓展已被管理员停用）');
  plugin.destroy();assert.equal(plugin.frame.removed,true);
});

test('不可用提示按纯文本显示',()=>{
  const document={createElement(tag){return {tag,style:{},children:[],append(...values){this.children.push(...values);}};}};
  const host=document.createElement('div');host.ownerDocument=document;
  const message='<img src=x onerror=alert(1)>';
  const plugin=mountUnavailableQuestion(host,message);
  assert.equal(plugin.frame.children[0].textContent,message);
  assert.deepEqual(plugin.frame.children.map(node=>node.tag),['p']);
});

test('停用标记无需 SDK，重新批准后的历史仍走正常页面校验',()=>{
  const extension={id:'basic',version:'1.0.0'},payload={question:{id:'q',title:'题目'},extension,state:{}};
  const record={collection:{questions:[{id:'q'}]},questions:[{pageKey:'digest',payload}],pages:{digest:{extension,unavailable:true}}};
  const pane={view:'history',historyQuestion:createHistoryView(record).entry('q'),historyEntry:record};
  assert.equal(pane.historyQuestion.page.unavailable,true);
  assert.equal(canViewDraft(pane),false);
  record.pages.digest={extension,apiVersion:{major:1,minor:5},script:'approved'};
  pane.historyQuestion=createHistoryView(record).entry('q');
  assert.equal(pane.historyQuestion.page.script,'approved');
  assert.equal(canViewDraft(pane),true);
  assert.equal(pane.historyQuestion.payload,payload);
});
