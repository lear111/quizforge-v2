// Local outline feedback is separate from confirmed answers and revisions.
// Only the extension knows which of its private answer fields belong to a child.
export function reportAnswerPreview(pane,value){
  const invalid=()=>Object.assign(new Error('作答状态仅允许 status:unanswered/draft，以及本题大纲中的 targetId；省略 targetId 表示当前大题'),{code:'INVALID_ANSWER_STATE'});
  if(!value||typeof value!=='object'||Array.isArray(value)||!['unanswered','draft'].includes(value.status)||Object.keys(value).some(key=>!['status','targetId'].includes(key)))throw invalid();
  const qid=pane.questionId,row=pane.collection.questions.find(row=>row.id===qid),confirmed=pane.collection.states[qid],previous=pane.answerPreviews?.get(qid);
  const base=previous||confirmed?.answerState||{status:confirmed?.status==='draft'?'draft':'unanswered',outlineStates:confirmed?.outlineStates||[]};
  const entry={status:base.status,outlineStates:structuredClone(base.outlineStates||[])};
  if(Object.hasOwn(value,'targetId')){
    if(typeof value.targetId!=='string'||!(row?.outlineItems||[]).some(item=>item.id===value.targetId))throw invalid();
    const states=new Map(entry.outlineStates.map(item=>[item.id,item.status]));states.set(value.targetId,value.status);
    entry.outlineStates=[...states].map(([id,status])=>({id,status}));
    entry.status=entry.outlineStates.some(item=>item.status==='draft')?'draft':'unanswered';
  }else entry.status=value.status;
  entry.stateId=String(pane.answerStateSequence=(pane.answerStateSequence||0)+1);
  (pane.answerPreviews??=new Map()).set(pane.questionId,entry);
  (pane.answerStateSnapshots??=new Map()).set(entry.stateId,{qid,entry});
  while(pane.answerStateSnapshots.size>256)pane.answerStateSnapshots.delete(pane.answerStateSnapshots.keys().next().value);
  return entry;
}
export function answerStateForSave(pane,qid,stateId){
  if(stateId===undefined)return null;
  const snapshot=pane.answerStateSnapshots?.get(stateId);
  if(snapshot?.qid!==qid)throw Object.assign(new Error('作答状态快照已失效，请重新上报当前答案的状态'),{code:'INVALID_ANSWER_STATE'});
  return structuredClone({status:snapshot.entry.status,outlineStates:snapshot.entry.outlineStates});
}
export function previewDraftAnswer(pane,qid,stateId){
  const previous=pane.answerPreviews?.get(qid);
  // A queued older answer must not acknowledge a newer input report.
  if(previous?.stateId)return previous.stateId===stateId?previous:null;
  const entry={status:'draft'};
  (pane.answerPreviews??=new Map()).set(qid,entry);
  return entry;
}
export function confirmAnswerPreview(pane,qid,entry){
  if(pane.answerPreviews?.get(qid)===entry)pane.answerPreviews.delete(qid);
}
