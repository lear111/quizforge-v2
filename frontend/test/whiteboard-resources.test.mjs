import {test} from 'node:test';
import assert from 'node:assert/strict';
import {WhiteboardModel,emptyDraft} from '../practice/draft/whiteboard-model.js';
import {layerDocument,layerPayload,mergeWhiteboard,protectedInk} from '../practice/draft/whiteboard-layers.js';
import {createAnswerInk} from '../practice/draft/answer-ink.js';
import {createWriteQueue} from '../practice/shared/write-queue.js';
import {captureAnswerSnapshots} from '../practice/bridge/answer-snapshots.js';

const stroke=id=>({id,color:'#334155',width:3,points:[{x:1,y:2,pressure:.5}]});
const board=()=>({draft:{...emptyDraft(),strokes:[stroke('draft')]},notes:{...emptyDraft(),strokes:[stroke('note')]},revision:7,draftLocked:false});

test('whiteboard responses preserve answer, revision, stamp and pending question data',()=>{
  const before={state:{answer:'unsaved',revision:3},stamp:{revision:3},question:{id:'q',data:'projected'},whiteboard:board()};
  const merged=mergeWhiteboard(before,{whiteboard:{...board(),revision:8},draft:emptyDraft()});
  assert.equal(merged.state,before.state);assert.equal(merged.question,before.question);assert.equal(merged.stamp,before.stamp);
  assert.equal(merged.whiteboard.revision,8);
});
test('notes and draft render together but only the selected layer is saved',()=>{
  const value=board(),shown=layerDocument(value,'notes');
  assert.deepEqual(shown.strokes.map(s=>s.id),['draft','note']);
  shown.strokes.push(stroke('new'));
  assert.deepEqual(layerPayload(value,'notes',shown).draft.strokes.map(s=>s.id),['note','new']);
  assert.deepEqual(layerPayload(value,'draft',layerDocument(value,'draft')).draft.strokes.map(s=>s.id),['draft']);
});
test('protected ink survives clear, eraser, selection edits and undo',()=>{
  const model=new WhiteboardModel({...emptyDraft(),strokes:[stroke('saved')]});
  model.protectStrokes([stroke('saved')]);model.appendStroke(stroke('new'));
  model.clear();assert.deepEqual(model.strokes.map(s=>s.id),['saved']);
  model.eraseAlong([{x:1,y:2}],20);assert.equal(model.strokes[0].id,'saved');
  model.editStrokes([{...stroke('saved'),width:10}]);assert.equal(model.strokes[0].width,3);
  model.undo();assert.equal(model.strokes[0].id,'saved');
});
test('the write queue uses independent answer and board revisions',async()=>{
  const sent=[];const revisions={answer:2,whiteboard:7};
  const queue=createWriteQueue({makeRequestId:()=>String(sent.length),revisionFor:(_qid,action)=>revisions[action==='whiteboard'?'whiteboard':'answer'],
    send:async(_qid,body)=>{sent.push(body);return body;}});
  await queue.enqueue('q','whiteboard',{draft:emptyDraft()});await queue.enqueue('q','draft',{answer:'answer'});
  assert.deepEqual(sent.map(value=>value.revision),[7,2]);
});

test('read-only notes inside an answer area never become formal answer ink',()=>{
  const value=board(),whiteboard=new WhiteboardModel(layerDocument(value,'draft'));
  whiteboard.protectStrokes(protectedInk(value,'draft'));
  const events=[],rect={left:0,top:0,width:200,height:200};
  const frame={getBoundingClientRect:()=>rect,clientWidth:200,clientHeight:200,closest:()=>null,
    qfSharedAnswerAreas:[{id:'area',document:{type:'qf-answer-area',formatVersion:1,id:'area',width:200,height:200,strokes:[]},rect:[0,0,200,200],writable:true,displayInk:true}],
    dispatchEvent:event=>events.push(event)};
  const container={querySelectorAll:()=>[frame],ownerDocument:{defaultView:{CustomEvent:class{constructor(type,options){this.type=type;this.detail=options.detail;}}}}};
  createAnswerInk({container,canvas:{getBoundingClientRect:()=>rect},whiteboard,getMode:()=>'draft',getCamera:()=>({x:0,y:0,zoom:1}),getScroll:()=>({x:0,y:0}),getSize:()=>({width:200,height:200})}).sync();
  assert.equal(events.length,1);
  assert.deepEqual(events[0].detail.strokes.map(s=>s.id),['draft']);
  assert.deepEqual(whiteboard.strokes.map(s=>s.id),['note','draft']);
});

test('a submission snapshot is uploaded before sending an immutable copy of the answer',async()=>{
  const events=[],original={responses:{part:{type:'qf-answer-area',formatVersion:1,id:'area',width:100,height:50,text:'typed',strokes:[]}}};
  const model={normalize:doc=>structuredClone(doc),paint:(_ctx,doc)=>events.push('paint:'+doc.text)};
  const document={createElement:()=>({getContext:()=>({}),toBlob:callback=>callback(new Blob(['png'],{type:'image/png'}))})};
  const resources={put:async()=>{events.push('upload');return {id:'a'.repeat(64)};}};
  const captured=await captureAnswerSnapshots(original,{model,resources,document});events.push('submit');
  assert.deepEqual(events,['paint:typed','upload','submit']);
  assert.equal(captured.responses.part.snapshot.attrs.assetId,'a'.repeat(64));
  assert.equal(original.responses.part.snapshot,undefined);
});
test('a screenshot upload failure prevents submission and preserves the original input',async()=>{
  const original={area:{type:'qf-answer-area',id:'area',width:100,height:50,text:'keep',strokes:[]}};
  let submitted=false;const error=new Error('offline');
  const options={model:{normalize:doc=>doc,paint(){}},document:{createElement:()=>({getContext:()=>({}),toBlob:callback=>callback(new Blob(['png']))})},resources:{put:async()=>{throw error;}}};
  await assert.rejects(captureAnswerSnapshots(original,options).then(()=>{submitted=true;}),value=>value===error);
  assert.equal(submitted,false);assert.equal(original.area.text,'keep');assert.equal(original.area.snapshot,undefined);
});
