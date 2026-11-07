import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const root=path.resolve(import.meta.dirname,'..'),args=process.argv.slice(2),flag=args.indexOf('--jsdom-from');
const require=createRequire(flag>=0?path.resolve(args[flag+1]):path.join(root,'package.json'));
const {JSDOM,VirtualConsole}=require('jsdom'),context={window:{}};
const data=fs.readFileSync(path.join(root,'assets/mock-data.js'),'utf8'),screens=fs.readFileSync(path.join(root,'assets/screens.js'),'utf8');
vm.runInNewContext(data,context);const groups=context.window.PROTOTYPE.groups;
function fixture(t,group,size,scene){
 const file=path.join(root,`${group.number}-${group.id}-${size}.html`),errors=[],virtualConsole=new VirtualConsole();
 virtualConsole.on('jsdomError',error=>errors.push(error));
 const dom=new JSDOM(fs.readFileSync(file,'utf8'),{url:pathToFileURL(file).href+'?scene='+encodeURIComponent(scene),runScripts:'outside-only',pretendToBeVisual:true,virtualConsole}),w=dom.window;
 w.HTMLElement.prototype.scrollIntoView=function(){};
 w.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
 w.HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
 for(const name of ['fetch','XMLHttpRequest','WebSocket','EventSource'])Object.defineProperty(w,name,{value:()=>{throw new Error('Forbidden network call in prototype: '+name);}});
 w.eval(data);w.eval(screens);t.after(()=>{dom.window.close();assert.deepEqual(errors,[]);});
 const get=selector=>w.document.querySelector(selector),click=selector=>{const node=get(selector);assert(node,selector);node.click();return node;};
 return {w,get,click,modal:()=>get('#modal'),scene(value){const select=get('#scene');select.value=value;select.dispatchEvent(new w.Event('change',{bubbles:true}));}};
}
for(const group of groups)for(const size of ['desktop','mobile'])for(const scene of group.scenes){
 test(`${group.number} ${group.id} ${size}: ${scene}`,t=>{
  const f=fixture(t,group,size,scene);assert.equal(f.get('#scene').value,scene);assert(f.get('.v2-practice-nav,.site-header'));assert(f.get('main'));
  assert(!f.get('iframe'),'No extension or Bridge implementation');
  for(const link of f.w.document.querySelectorAll('a[href]')){const href=link.getAttribute('href');if(href.startsWith('#'))continue;assert(fs.existsSync(path.join(root,href.split('?')[0])),href);}
  assert(!f.w.document.querySelector('[data-ui="network-settings"],[data-ui="development"],[data-ui="edit-question"]'));
  if(group.kind==='practice')assert(f.get('.workspace-main'));
 });
}
for(const size of ['desktop','mobile']){
 test(`${size}: shared tabs keep input and resize limits are enforced`,t=>{
  const f=fixture(t,groups[0],size,'作答中'),shell=f.get('.prototype-app'),handle=f.get('.panel-resize');
  shell.getBoundingClientRect=()=>({width:1200,height:734});f.get('#bank-sidebar').getBoundingClientRect=()=>({width:232});
  f.get('.response-area textarea').value='尚未提交的答题内容';f.get('#ai-message').value='尚未发送的提问';
  const key=value=>handle.dispatchEvent(new f.w.KeyboardEvent('keydown',{key:value,bubbles:true}));
  key('End');assert.equal(handle.getAttribute('aria-valuenow'),size==='mobile'?'634':'560');
  key('Home');assert.equal(handle.getAttribute('aria-valuenow'),'240');
  key(size==='mobile'?'ArrowUp':'ArrowLeft');assert.equal(handle.getAttribute('aria-valuenow'),'260');
  f.click('[data-ui="panel-outline"]');assert.equal(f.get('[data-panel="outline"]').hidden,false);assert.equal(f.get('[data-panel="ai"]').hidden,true);
  assert.equal(f.get('.outline-item.updated small').textContent,'更新');
  f.click('[data-ui="panel-ai"]');assert.equal(f.get('[data-panel="ai"]').hidden,false);
  assert.equal(f.get('.response-area textarea').value,'尚未提交的答题内容');assert.equal(f.get('#ai-message').value,'尚未发送的提问');
  assert.equal(handle.getAttribute('aria-valuenow'),'260');
 });
 test(`${size}: selected bank keys, join confirmation and empty selection`,t=>{
  const f=fixture(t,groups[1],size,'新版本审核中');f.click('[data-ui="join-selected"]');assert(f.modal().open);assert.match(f.get('#modal-body').textContent,/函数与极限/);assert.doesNotMatch(f.get('#modal-body').textContent,/导数与微分/);f.click('[data-ui="modal-close"]');
  const all=f.click('#select-all');all.dispatchEvent(new f.w.Event('change',{bubbles:true}));assert.match(f.get('#selection-label').textContent,/3 \/ 3/);f.click('[data-ui="join-selected"]');assert.match(f.get('#modal-body').textContent,/积分及应用/);f.click('[data-ui="modal-close"]');
  all.click();all.dispatchEvent(new f.w.Event('change',{bubbles:true}));f.click('[data-ui="join-selected"]');assert(!f.modal().open);assert.match(f.get('#demo-signal').textContent,/至少一本/);
 });
 test(`${size}: automatic updates without a manual update prompt`,t=>{
  const f=fixture(t,groups[3],size,'练习中');assert(!f.get('[data-ui="update-now"]'));assert.doesNotMatch(f.w.document.body.textContent,/有新版本|立即更新/);
  f.scene('已自动更新');assert.match(f.get('.overview-meta').textContent,/1\.3/);assert.match(f.get('.question-list').textContent,/本题已更新/);
  f.click('[data-ui="history"]');assert.match(f.get('#modal-body').textContent,/因题库更新而中断/);f.click('[data-ui="modal-close"]');f.scene('作者已移除');assert.match(f.get('.callout').textContent,/历史仍然保留/);
 });
 test(`${size}: conflict refresh explicitly protects typed unsaved input`,t=>{
  const f=fixture(t,groups[4],size,'保存冲突');f.get('.response-area textarea').value='<script>private()</script> 我的输入';f.click('[data-ui="refresh-question"]');assert.equal(f.get('#modal-body textarea').value,'<script>private()</script> 我的输入');assert(!f.get('#modal-body script'));
  f.click('[data-ui="modal-close"]');assert.match(f.get('.response-area textarea').value,/我的输入/);f.click('[data-ui="refresh-question"]');f.click('[data-ui="confirm-refresh"]');assert.equal(f.get('#scene').value,'保存恢复');assert.match(f.get('.response-area textarea').value,/服务端最新记录/);
 });
 test(`${size}: summary completion and pending grading`,t=>{
  const f=fixture(t,groups[5],size,'待评分');assert.equal(f.get('[data-ui="finish"]').disabled,true);f.scene('本轮进行中');f.click('[data-ui="finish"]');assert.match(f.get('#modal-body').textContent,/保存为已完成历史/);f.click('[data-ui="confirm-finish"]');assert.equal(f.get('#scene').value,'本轮已完成');f.click('[data-ui="completed-history"]');assert.match(f.get('#modal-body').textContent,/只读快照/);
 });
 test(`${size}: AI isolation, pure-text follow-up, close/reopen and failures`,t=>{
  const f=fixture(t,groups[6],size,'答题中');assert.match(f.get('.tutor-context').textContent,/不直接给答案/);f.get('#ai-message').value='<img src=x onerror=bad()> 私人追问';f.click('[data-ui="ai-send"]');assert.match(f.get('.conversation').textContent,/私人追问/);assert(!f.get('.conversation img'));
  f.click('[data-ui="next"]');assert.match(f.get('.tutor-context').textContent,/第 4 题/);assert.doesNotMatch(f.get('.conversation').textContent,/私人追问/);f.click('[data-ui="ai-close"]');assert(!f.get('#shared-panel').classList.contains('open'));f.click('[data-ui="outline-toggle"]');assert(f.get('#shared-panel').classList.contains('open'));f.click('[data-ui="panel-outline"]');assert(f.get('.outline-list'));f.click('[data-ui="panel-ai"]');assert(f.get('#tutor-panel'));
  f.scene('额度不足');assert(f.get('[data-ui="ai-send"]').disabled);f.click('[data-ui="redeem"]');assert(f.modal().open);f.click('[data-ui="modal-close"]');f.scene('请求超时');assert.match(f.get('.tutor-error').textContent,/不受影响/);f.click('[data-ui="ai-retry"]');assert.equal(f.get('#scene').value,'答题中');
 });
 test(`${size}: upload fixture, pending version and deletion consequences`,t=>{
  const f=fixture(t,groups[7],size,'上传新版本');f.click('[data-ui="choose-file"]');assert.match(f.get('#mock-file-name').textContent,/1\.3/);assert(f.get('#publish-button').disabled);f.click('#rights');f.get('#rights').dispatchEvent(new f.w.Event('change',{bubbles:true}));assert(!f.get('#publish-button').disabled);f.click('[data-ui="publish"]');assert.equal(f.get('#scene').value,'新版本审核中');assert.match(f.get('.success-panel').textContent,/1\.2/);f.scene('我的分享');f.click('[data-ui="delete-share"]');assert.match(f.get('#modal-body').textContent,/继续保留/);
 });
 if(size==='mobile')test('phone navigation and side panels can be opened and dismissed',t=>{
  const f=fixture(t,groups[0],size,'作答中');f.click('[data-ui="library-toggle"]');assert(f.get('#bank-sidebar').classList.contains('open'));f.click('[data-ui="library-toggle"]');assert(!f.get('#bank-sidebar').classList.contains('open'));
 });
}
