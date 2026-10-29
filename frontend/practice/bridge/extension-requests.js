import {collectionFeature,examActive} from '../practice/practice-context.js';
const denied=(code,message)=>Promise.resolve({ok:false,error:{code,message}});

// Dispatches the public extension protocol. The shell owns persistence and lifecycle.
export function createExtensionRouter({readResource,uploadResource,loadSdk,save,reportAnswerState,ai,navigate,summary,schedule=fn=>setTimeout(fn,0)}){
  return function route(pane,method,args){
    if(method==='resource-get')return readResource(args?.id,pane).then(data=>({ok:true,data}));
    if(method==='resource-put'){
      if(!['practice','edit'].includes(pane.view))return denied('READ_ONLY','历史记录不能上传图片');
      if(pane.view==='edit'&&!collectionFeature(pane,'editing'))return denied('FEATURE_DISABLED','题库未开放编辑');
      return uploadResource(args,pane).then(data=>({ok:true,data}));
    }
    if(method==='sdk-editor'){
      const assets=pane.view==='edit'?pane.editState?.editor:pane.view==='history'?pane.historyQuestion?.page:pane.pageAssets;
      const dependency=assets?.dependencies?.find(value=>value.id===args?.id&&value.version===args?.version);
      if(!dependency)return denied('SDK_UNAVAILABLE','未声明此组件依赖');
      return loadSdk(dependency.id,dependency.version).then(data=>({ok:true,data}));
    }
    if(pane.view!=='practice')return denied('READ_ONLY','当前页面不接受作答修改');
    if(!args||typeof args!=='object')return denied('INVALID_REQUEST','请求格式不正确');
    if(method==='answer-state'){
      if(pane.pageAssets?.apiVersion?.minor<3||pane.pageAssets?.apiVersion?.major!==1)return denied('UNSUPPORTED_API_VERSION','即时作答状态需要 API 1.3');
      if(pane.pageAssets.answerStateDeclared!==true)return denied('API_CAPABILITY_REQUIRED','拓展尚未声明 answer-state 能力');
      if(pane.payload?.state?.status==='submitted'||pane.exam&&!examActive(pane))return denied('READ_ONLY','已提交答案不能上报作答状态');
      if(typeof reportAnswerState!=='function')return denied('ACTION_UNAVAILABLE','当前版本未提供此操作');
      return reportAnswerState(pane,args);
    }
    if(pane.exam){
      if(method.startsWith('ai-')&&pane.exam.status!=='grading')return denied('EXAM_ACTION_DISABLED','交卷后才能评分');
      if(method==='save'&&(args.purpose==='submit'||args.purpose==='review'&&pane.exam.status!=='grading'||args.purpose==='draft'&&!examActive(pane))||method==='action'&&args.action==='retry')return denied('EXAM_ACTION_DISABLED','考试请使用宿主的交卷按钮，交卷后答案锁定');
    }
    if(method.startsWith('ai-'))return ai(pane,method,args);
    if(method==='save'){
      if(args.purpose==='review'&&args.data?.review)return save(pane,'review',{review:args.data.review});
      if(!['draft','submit'].includes(args.purpose)||!args.data||!Object.hasOwn(args.data,'answer'))return denied('INVALID_REQUEST','保存请求必须包含答案');
      if(args.answerStateId!==undefined){
        if(pane.pageAssets?.apiVersion?.major!==1||pane.pageAssets.apiVersion.minor<3)return denied('UNSUPPORTED_API_VERSION','即时作答状态需要 API 1.3');
        if(pane.pageAssets.answerStateDeclared!==true)return denied('API_CAPABILITY_REQUIRED','拓展尚未声明 answer-state 能力');
        if(typeof args.answerStateId!=='string'||!/^\d{1,16}$/.test(args.answerStateId))return denied('INVALID_REQUEST','作答状态编号无效');
        return save(pane,args.purpose,{answer:args.data.answer},{answerStateId:args.answerStateId});
      }
      return save(pane,args.purpose,{answer:args.data.answer});
    }
    if(method==='action'&&args.action==='retry')return save(pane,'retry',{});
    if(method==='action'&&['previous','next','goToQuestion'].includes(args.action)){
      const rows=pane.collection.questions,index=rows.findIndex(q=>q.id===pane.questionId);
      const target=args.action==='goToQuestion'?rows.find(q=>q.id===args.params?.questionId):rows[index+(args.action==='previous'?-1:1)];
      if(!target&&args.action==='next'&&index===rows.length-1){schedule(()=>summary(pane));return Promise.resolve({ok:true,data:{status:'navigating'}});}
      if(!target)return denied('NAVIGATION_UNAVAILABLE','没有对应题目');
      // The initiating frame must receive its reply before flush/dispose begins.
      schedule(()=>navigate(pane,target.id));
      return Promise.resolve({ok:true,data:{status:'navigating'}});
    }
    return denied('ACTION_UNAVAILABLE','当前版本未提供此操作');
  };
}
