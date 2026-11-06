import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mountUnavailableQuestion} from '../practice/history/unavailable-question.js';
import {createHistoryView} from '../practice/history/history-view.js';

test('停用历史由宿主纯文本呈现，不创建 iframe 或脚本，图片仅指向授权接口',async()=>{
  const nodes=[];
  const document={createElement(tag){const node={tag,style:{},children:[],append(...values){this.children.push(...values);},remove(){this.removed=true;}};nodes.push(node);return node;}};
  const host=document.createElement('div');host.ownerDocument=document;
  const payload={question:{id:'q',title:'<img src=x onerror=alert(1)>'},state:{answer:{snapshot:{attrs:{assetId:'a'.repeat(64)}},evil:{attrs:{assetId:'https://evil.test/a'}}},result:{score:1,maxScore:2}},practiceSignals:{gradingSource:'manual'}};
  const plugin=mountUnavailableQuestion(host,payload);await plugin.ready;
  assert.equal(nodes.some(node=>['iframe','script'].includes(node.tag)),false);
  assert.equal(nodes.find(node=>node.tag==='h2').textContent,payload.question.title);
  assert.ok(nodes.some(node=>node.textContent?.includes('人工')));
  assert.deepEqual(nodes.filter(node=>node.tag==='img').map(node=>node.src),['/api/resources/'+'a'.repeat(64)]);
  plugin.destroy();assert.equal(plugin.frame.removed,true);
});

test('停用标记无需 SDK，重新批准后的历史仍走正常页面校验',()=>{
  const extension={id:'basic',version:'1.0.0'},payload={question:{id:'q',title:'题目'},extension,state:{}};
  const record={collection:{questions:[{id:'q'}]},questions:[{pageKey:'digest',payload}],pages:{digest:{extension,unavailable:true}}};
  assert.equal(createHistoryView(record).entry('q').page.unavailable,true);
  record.pages.digest={extension,apiVersion:{major:1,minor:5},script:'approved'};
  assert.equal(createHistoryView(record).entry('q').page.script,'approved');
});
