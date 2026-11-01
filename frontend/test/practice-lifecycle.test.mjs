import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mountPracticeSummary} from '../practice/practice/practice-summary.js';
import {contextFor} from '../practice/practice/practice-context.js';

// The card only needs basic DOM operations. Exercise the real rendering/callbacks without a browser dependency.
class Element {
  children=[];listeners=new Map();hidden=false;disabled=false;
  classList={toggle(){}};
  constructor(document){this.ownerDocument=document;}
  append(...nodes){this.children.push(...nodes);}
  setAttribute(){}
  addEventListener(name,fn){this.listeners.set(name,fn);}
  removeEventListener(name){this.listeners.delete(name);}
  remove(){this.removed=true;}
  click(){return this.disabled?Promise.resolve():this.listeners.get('click')?.();}
}
function card(summary,options={}){
  const document={createElement:()=>new Element(document)},root=new Element(document);
  const mounted=mountPracticeSummary(root,summary,options);
  const find=className=>{const visit=node=>node.className?.split(' ').includes(className)?node:node.children.map(visit).find(Boolean);return visit(root);};
  return {mounted,find,root};
}
const summary={questionCount:5,submittedCount:2,pendingCount:1,score:2,maxScore:15,answeredUnsubmittedCount:1,
  roundId:'round',summaryVersion:'v1',finished:false,canFinish:false,canRestart:true};

test('unfinished round offers restart while unsupported batch finish is disabled',async()=>{
  let restarts=0;
  const {find}=card(summary,{onFinish:()=>{throw Error('unsupported finish');},onRestart:value=>{assert.equal(value,summary);restarts++;}});
  assert.equal(find('practice-summary-finish').disabled,true);
  assert.match(find('practice-summary-finish').textContent,/尚未开放/);
  assert.equal(find('practice-summary-restart').hidden,false);
  await find('practice-summary-restart').click();assert.equal(restarts,1);
  assert.equal(find('practice-summary-grading').textContent,'待评分 1 题');
  assert.match(find('practice-summary-note').textContent,/按 0 分计/);
});

test('restart is single flight and failed restart keeps the summary with an error and retry',async()=>{
  let release,calls=0;
  const wait=new Promise(resolve=>release=resolve);
  const {find}=card(summary,{onRestart:async()=>{calls++;await wait;throw Error('请刷新汇总');}});
  const button=find('practice-summary-restart'),first=button.click();
  assert.equal(button.disabled,true);await button.click();assert.equal(calls,1);
  release();await first;assert.equal(button.disabled,false);
  assert.equal(find('practice-summary-error').hidden,false);
  assert.equal(find('practice-summary-error').textContent,'请刷新汇总');
});

test('finished and historical summaries keep their original restart and read-only behavior',async()=>{
  let calls=0;const finished={...summary,finished:true,historyId:'round'};
  const {find,mounted}=card(finished,{onRestart:()=>{calls++;}});
  assert.equal(find('practice-summary-restart').hidden,true);
  await find('practice-summary-finish').click();assert.equal(calls,1);
  mounted.destroy();
  const history=card(finished,{readonly:true,onRestart:()=>{throw Error('history cannot reset');}});
  assert.equal(history.find('practice-summary-finish'),undefined);
  assert.equal(history.find('practice-summary-restart'),undefined);
});

test('capabilities enable retry and review only where the server allows, never in frozen history',()=>{
  const pane={view:'practice',kind:'bank',collection:{features:{}},payload:{question:{id:'q'},state:{status:'submitted'},
    capabilities:{canSave:false,canSubmit:false,canRetry:true,canReview:true}}};
  assert.equal(contextFor(pane).capabilities.canRetry,true);assert.equal(contextFor(pane).capabilities.canReview,true);
  pane.payload.capabilities.canRetry=false;
  assert.equal(contextFor(pane).capabilities.canRetry,false);assert.equal(contextFor(pane).capabilities.canReview,true);
  pane.view='history';pane.historyQuestion={payload:pane.payload};
  assert.equal(contextFor(pane).capabilities.canRetry,false);assert.equal(contextFor(pane).capabilities.canReview,false);
});
