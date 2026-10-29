import {examActive,collectionFeature} from '../practice/practice-context.js';

export function examTimeLabel(exam,now=Date.now()){
  const seconds=Math.max(0,(exam.deadline?Math.ceil:Math.floor)((exam.deadline?Date.parse(exam.deadline)-now:now-Date.parse(exam.startedAt))/1000));
  const minutes=Math.floor(seconds/60),remainder=String(seconds%60).padStart(2,'0');
  return `${exam.deadline?'剩余':'已用'} ${minutes}:${remainder}`;
}
export function canStartExam(pane){return !!pane?.collection&&!pane.exam&&['bank','development-bank'].includes(pane.kind)&&collectionFeature(pane,'exam')&&pane.view==='practice';}

export function bindExamMode({document,request,active,flush,open,refresh,onError,collectionPath,makeRequestId,onDeadline,now=Date.now}){
  const create=(tag,text,className)=>{const node=document.createElement(tag);if(text)node.textContent=text;if(className)node.className=className;return node;};
  const actions=document.querySelector('.document-actions'),start=create('button','开始考试','header-action'),status=create('span','','exam-status'),submit=create('button','交卷','header-action'),back=create('button','返回练习','header-action');
  start.id='exam-start';submit.id='exam-submit';status.id='exam-status';back.id='exam-return';status.setAttribute('role','status');
  for(const button of [start,submit,back])button.type='button';actions.prepend(start,status,submit,back);
  const dialog=create('dialog',null,'exam-dialog'),form=create('form'),header=create('div',null,'exam-dialog-heading'),heading=create('h2','开始考试'),close=create('button','×','exam-dialog-close'),intro=create('p','设置本次考试时长，或继续未完成的考试。','exam-intro');
  heading.id='exam-dialog-title';dialog.setAttribute('aria-labelledby',heading.id);close.type='button';close.setAttribute('aria-label','关闭考试设置');header.append(heading,close);
  const settings=create('section',null,'exam-settings'),label=create('label','考试时长','exam-duration-label'),durationControl=create('div',null,'exam-duration-control'),duration=create('input'),unit=create('span','分钟'),unlimitedLabel=create('label',null,'exam-unlimited'),unlimited=create('input'),hint=create('p','到时自动交卷；交卷后锁定答案，统一评分。','exam-hint');
  duration.type='number';duration.min='1';duration.max='1440';duration.value='60';duration.required=true;duration.id='exam-duration';duration.setAttribute('aria-label','考试时长（分钟）');label.htmlFor=duration.id;
  durationControl.append(duration,unit);unlimited.type='checkbox';unlimitedLabel.append(unlimited,document.createTextNode('不限时'));settings.append(label,durationControl,unlimitedLabel,hint);
  const recordsSection=create('section',null,'exam-resume-section'),recordsHeading=create('h3','未完成考试'),list=create('div',null,'exam-records'),error=create('p',null,'exam-error'),buttons=create('div',null,'dialog-actions'),cancel=create('button','取消'),confirm=create('button','开始考试');
  recordsSection.hidden=true;recordsSection.append(recordsHeading,list);error.hidden=true;error.setAttribute('role','alert');cancel.type='button';confirm.type='submit';buttons.append(cancel,confirm);form.append(header,intro,settings,recordsSection,error,buttons);dialog.append(form);document.body.append(dialog);
  const deleteDialog=create('dialog',null,'exam-delete-dialog'),deleteHeading=create('h2','删除这次考试？'),deleteDetail=create('p',null,'exam-delete-detail'),deleteHint=create('p','删除后无法继续这次考试，已保存的答案和草稿将不再显示。普通练习和其他历史记录会保留。'),deleteButtons=create('div',null,'dialog-actions'),deleteCancel=create('button','取消'),deleteConfirm=create('button','删除考试','danger-button');
  deleteHeading.id='exam-delete-title';deleteDialog.setAttribute('aria-labelledby',deleteHeading.id);deleteCancel.type='button';deleteCancel.autofocus=true;deleteConfirm.type='button';deleteButtons.append(deleteCancel,deleteConfirm);deleteDialog.append(deleteHeading,deleteDetail,deleteHint,deleteButtons);document.body.append(deleteDialog);
  let source=null,command=null,busy=false,polling=false,lastPoll=0,records=[],listGeneration=0,deleteDecision=null,deadlineTimer=null,scheduledPane=null,scheduledAt=null;const offsets=new Map();
  const setError=message=>{error.textContent=message||'';error.hidden=!message;};
  const recordTime=row=>new Date(row.startedAt).toLocaleString('zh-CN',{year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
  const observe=exam=>{if(exam?.serverTime)offsets.set(exam.id,Date.parse(exam.serverTime)-now());};
  function sync(){
    const pane=active(),exam=pane?.exam,time=now()+(offsets.get(exam?.id)||0),expired=exam?.status==='in-progress'&&!!exam.deadline&&time>=Date.parse(exam.deadline);
    if(exam){
      if(expired&&!pane.examDeadlineReached){pane.examDeadlineReached=true;onDeadline?.(pane);}
      if(pane.frameHost)pane.frameHost.inert=expired;
      pane.whiteboard?.setReadOnly?.(expired||exam.status!=='in-progress');
    }
    const deadline=exam?.status==='in-progress'&&exam.deadline&&!expired?Date.parse(exam.deadline)-(offsets.get(exam.id)||0):null;
    if(deadline!==scheduledAt||pane!==scheduledPane||deadline!==null&&!deadlineTimer){
      clearTimeout(deadlineTimer);deadlineTimer=null;scheduledAt=deadline;scheduledPane=pane;
      if(Number.isFinite(deadline))deadlineTimer=setTimeout(()=>{deadlineTimer=null;sync();pollExam();},Math.max(0,deadline-now()));
    }
    start.hidden=!canStartExam(pane);start.disabled=busy||!!pane?.loading;
    status.hidden=!exam;submit.hidden=!exam||exam.status==='completed'||pane?.view==='history';back.hidden=!exam;back.disabled=busy;
    if(exam){const issue=exam.gradingErrors?.length||exam.aiErrors?.length;status.textContent=expired?'时间已到 · 正在自动交卷':examActive(pane)?examTimeLabel(exam,time):exam.status==='completed'?'考试已完成':issue?'评分失败，待处理':'已交卷 · 评分中';submit.textContent=expired?'正在交卷…':examActive(pane)?'交卷':issue?'重试评分':'评分中';submit.disabled=busy||!!pane.loading||!examActive(pane)&&!issue;}
    for(const button of dialog.querySelectorAll('button'))button.disabled=busy;unlimited.disabled=busy;duration.disabled=busy||unlimited.checked;
    list.setAttribute('aria-busy',String(busy));
    return expired;
  }
  function decideDelete(accepted){const resolve=deleteDecision;deleteDecision=null;deleteDialog.close();resolve?.(accepted);}
  deleteCancel.addEventListener('click',()=>decideDelete(false));deleteConfirm.addEventListener('click',()=>decideDelete(true));
  deleteDialog.addEventListener('cancel',event=>{event.preventDefault();decideDelete(false);});deleteDialog.addEventListener('close',()=>{if(deleteDecision)decideDelete(false);});
  dialog.addEventListener('cancel',event=>{if(busy)event.preventDefault();});
  for(const button of [cancel,close])button.addEventListener('click',()=>{if(!busy)dialog.close();});
  unlimited.addEventListener('change',()=>{duration.disabled=unlimited.checked;hint.textContent=unlimited.checked?'不限时考试持续计时，由你手动交卷。':'到时自动交卷；交卷后锁定答案，统一评分。';});
  function renderRecords(){
    list.replaceChildren();const unfinished=records.filter(row=>!['completed','deleted'].includes(row.status));recordsSection.hidden=!unfinished.length;
    for(const row of unfinished){
      const item=create('div',null,'exam-record-row'),button=create('button',null,'exam-record'),body=create('span',null,'exam-record-body'),title=create('span',row.status==='in-progress'?'继续考试':'查看评分','exam-record-title'),detail=create('span',`开始于 ${recordTime(row)} · ${row.durationMinutes==null?'不限时':row.durationMinutes+' 分钟'}`,'exam-record-detail'),badge=create('span',row.status==='in-progress'?'进行中':row.gradingErrors?.length||row.aiErrors?.length?'评分失败':'评分中','exam-record-status'),remove=create('button',null,'exam-record-delete');
      button.type='button';remove.type='button';body.append(title,detail);button.append(body,badge);remove.title='删除考试';remove.setAttribute('aria-label',`删除 ${recordTime(row)} 的考试`);
      remove.innerHTML='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h18M9 6V4h6v2M5 6l1 14h12l1-14M10 10v6M14 10v6"/></svg>';
      button.addEventListener('click',async()=>{if(busy)return;busy=true;sync();dialog.close();try{await open('exam',row.id);}catch(e){onError(e);}finally{busy=false;sync();}});
      remove.addEventListener('click',async()=>{
        if(busy)return;busy=true;setError();sync();deleteDetail.textContent=`${row.status==='in-progress'?'未完成考试':'待处理评分'} · ${recordTime(row)}`;deleteDialog.showModal();
        try{
          if(!await new Promise(resolve=>{deleteDecision=resolve;}))return;
          try{await request(`${collectionPath('exam',row.id)}/exam`,{method:'DELETE'});}catch(e){if(e.status!==404||e.code==='FEATURE_NOT_AVAILABLE')throw e;}
          records=records.filter(record=>record.id!==row.id);renderRecords();
        }catch(e){
          if(e.code==='EXAM_COMPLETED')try{await loadRecords(source);}catch{}
          setError(e.code==='REQUEST_TIMEOUT'?'连接超时，请重新打开考试设置确认是否已删除。':e.message);
        }finally{busy=false;sync();(list.querySelector('.exam-record-delete')||confirm).focus();}
      });
      item.append(button,remove);list.append(item);
    }
  }
  async function loadRecords(bank){const generation=++listGeneration;const value=await request(`${collectionPath(bank.kind,bank.id)}/exams`);if(source!==bank||generation!==listGeneration||!dialog.open)return;records=value.records||[];renderRecords();}
  start.addEventListener('click',async()=>{try{
    if(busy)return;source=active();if(!canStartExam(source))return;busy=true;sync();await flush(source);command=null;setError();records=[];renderRecords();dialog.showModal();await loadRecords(source);
  }catch(e){if(dialog.open)setError(e.message);else onError(e);}finally{busy=false;sync();}});
  form.addEventListener('submit',async event=>{event.preventDefault();if(busy||!source)return;busy=true;sync();setError();try{
    const minutes=unlimited.checked?null:Number(duration.value);if(minutes!==null&&(!Number.isInteger(minutes)||minutes<1||minutes>1440))throw new Error('请输入 1–1440 分钟，或选择不限时');
    if(!command||command.durationMinutes!==minutes)command={requestId:makeRequestId(),durationMinutes:minutes};
    const value=await request(`${collectionPath(source.kind,source.id)}/exams`,{method:'POST',body:JSON.stringify(command),timeoutMs:135000});observe(value);dialog.close();await open('exam',value.id);
  }catch(e){if(dialog.open)setError(e.message);else onError(e);}finally{busy=false;sync();}});
  submit.addEventListener('click',async()=>{const pane=active();if(!pane?.exam||busy)return;if(examActive(pane)&&!globalThis.confirm('交卷后将锁定全部答案，确定交卷吗？'))return;busy=true;sync();try{
    if(examActive(pane))await flush(pane);
    const value=await request(`${collectionPath('exam',pane.id)}/exam`,{method:'POST',body:JSON.stringify({requestId:makeRequestId()}),timeoutMs:135000});observe(value);await refresh(pane,value);
  }catch(e){onError(e);}finally{busy=false;sync();}});
  back.addEventListener('click',()=>{const exam=active()?.exam;if(exam)open(exam.bankKind,exam.bankId).catch(onError);});
  sync();
  async function pollExam(){const expired=sync(),pane=active();if(!pane?.exam||pane.exam.status==='completed'||polling||busy||!expired&&now()-lastPoll<3000)return;polling=true;lastPoll=now();try{const value=await request(`${collectionPath('exam',pane.id)}/exam`);observe(value);await refresh(pane,value);}catch(e){status.textContent=expired?'时间已到，答案已锁定 · 正在重试交卷状态':'考试连接中断，正在重试';}finally{polling=false;}}
  const timer=setInterval(pollExam,1000);
  return {sync,observe,destroy(){clearInterval(timer);clearTimeout(deadlineTimer);if(deleteDecision)decideDelete(false);dialog.remove();deleteDialog.remove();for(const node of [start,status,submit,back])node.remove();}};
}
