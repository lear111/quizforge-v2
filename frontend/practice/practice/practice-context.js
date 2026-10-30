// Pure view state: shared by the shell, outline, and extension bridge.
export const collectionFeature=(pane,name)=>pane?.collection?.features?.[name]!==false;
export const examActive=pane=>pane?.exam?.status==='in-progress'&&!pane.examDeadlineReached;
// Authoring is unavailable online; richtext answer editing remains in the Bridge.
export const canEditCollection=()=>false;
export const practiceSaveLabel=pane=>collectionFeature(pane,'history')?'已保存':'当前会话';
export function canViewDraft(pane){
  if(!pane||pane.summaryShown||pane.view==='practice'&&pane.payload?.capabilities?.canWhiteboard===false)return false;
  return pane.view==='history'?!!pane.historyQuestion?.payload&&pane.historyEntry?.collection?.features?.whiteboard!==false:pane.view==='practice'&&!!pane.payload&&collectionFeature(pane,'whiteboard');
}
export function draftViewActive(pane){return canViewDraft(pane)&&!!(pane.view==='history'?pane.historyDraftMode:pane.draftMode);}
export function visibleQuestions(pane){return pane?.view==='history-deleted'?[]:pane?.view==='history'?pane.historyView?.questions||[]:pane?.collection.questions||[];}
export function visibleQuestionId(pane){return pane?.summaryShown?null:pane?.view==='history'?pane.historyQuestionId:pane?.questionId;}
export function hasScoreSummary(pane){return !!pane&&pane.exam?.status!=='in-progress'&&(pane.view==='history'?!!pane.historyEntry?.summary:['practice','summary'].includes(pane.view));}
export function navigationIndex(pane){const rows=visibleQuestions(pane);return pane?.summaryShown?rows.length:rows.findIndex(q=>q.id===visibleQuestionId(pane));}
export function stateFor(pane,qid){return (pane.view==='history'?pane.historyView?.states:pane.collection.states)?.[qid]||{status:'unanswered',answer:null,result:null,revision:0};}
export function outlineStateFor(pane,qid){const confirmed=stateFor(pane,qid),preview=pane.view==='practice'&&!pane.summaryShown&&(!pane.exam||examActive(pane))&&confirmed.status!=='submitted'?pane.answerPreviews?.get(qid):null;const feedback=preview||(confirmed.status!=='submitted'?confirmed.answerState:null);return feedback?{...confirmed,...feedback}:confirmed;}
export function contextFor(pane){const history=pane.view==='history',value=history?pane.historyQuestion.payload:pane.payload,state=value.state,exam=pane.exam,grading=exam?.status==='grading',editable=!exam||examActive(pane);return {mode:history?'history':exam?'exam':['extension','development'].includes(pane.kind)?'example':'practice',...(exam?{exam:{id:exam.id,status:exam.status,deadline:exam.deadline}}:{}),...(pane.development?{development:{folder:pane.development.folder}}:{}),question:value.question,answer:state.answer,result:state.result,status:state.status,aiTask:history?null:value.aiTask,capabilities:{canSave:value.capabilities?.canSave!==false&&!history&&editable&&state.status!=='submitted',canSubmit:value.capabilities?.canSubmit!==false&&!history&&!exam&&state.status!=='submitted',canRetry:value.capabilities?.canRetry!==false&&!history&&!exam&&state.status==='submitted',canReview:!history&&(!exam||grading)&&value.capabilities?.canReview===true,canAiGrade:!history&&(!exam||grading)&&value.capabilities?.canAiGrade===true,canEdit:!history&&canEditCollection(pane),canWhiteboard:value.capabilities?.canWhiteboard!==false&&!history&&editable&&collectionFeature(pane,'whiteboard'),canHistory:!history&&exam?.status!=='in-progress'&&collectionFeature(pane,'history')}};}
