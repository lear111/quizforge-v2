import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mountPracticeSummary} from '../practice/practice/practice-summary.js';
import {contextFor,collectionFeature} from '../practice/practice/practice-context.js';

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
  roundId:'round',summaryVersion:'v1',finished:false,canFinish:true,canRestart:false};

test('unfinished practice offers finish, keeps restart hidden, and permits pending scores',async()=>{
  let finishes=0;
  const {find}=card(summary,{onFinish:()=>{finishes++;},onRestart:()=>{throw Error('not finished');}});
  assert.equal(find('practice-summary-finish').disabled,false);
  assert.equal(find('practice-summary-restart').hidden,true);
  await find('practice-summary-finish').click();assert.equal(finishes,1);
  assert.equal(find('practice-summary-grading').textContent,'待评分 1 题');
  const pending=card({...summary,answeredUnsubmittedCount:0},{onFinish:()=>{finishes++;}});
  await pending.find('practice-summary-finish').click();assert.equal(finishes,2);
});
test('processing summary disables writes and displays job progress',()=>{
  const {find}=card({...summary,processing:true,jobProgress:'2 / 10'},{onFinish:()=>{throw Error('locked');}});
  assert.equal(find('practice-summary-finish').disabled,true);
  assert.ok(find('practice-summary-note').textContent.includes('2 / 10'));
});

test('restart is single flight and failed restart keeps the summary with an error and retry',async()=>{
  let release,calls=0;
  const wait=new Promise(resolve=>release=resolve);
  const {find}=card({...summary,finished:true,canRestart:true},{onRestart:async()=>{calls++;await wait;throw Error('请刷新汇总');}});
  const button=find('practice-summary-finish'),first=button.click();
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


test('historical feature switches belong to the frozen collection',()=>{
  const pane={view:'history',collection:{features:{whiteboard:false}},historyEntry:{collection:{features:{whiteboard:true}}}};
  assert.equal(collectionFeature(pane,'whiteboard'),true);
});


test('question validators include the phase that determines write capabilities',async()=>{
  const {sameQuestionStamp}=await import('../practice/bridge/extension-pages.js');
  const stamp={revision:0,contentVersion:'content',packageVersion:'package',practicePhase:'completed'};
  assert.equal(sameQuestionStamp(stamp,{...stamp,practicePhase:'active'}),false);
  assert.equal(sameQuestionStamp(stamp,{...stamp}),true);
});
