// 规则只在无同源权限的 iframe 所创建的 Worker 中执行，宿主只交换 JSON。
function workerBootstrap() {
  let type;
  const api = Object.freeze({major:1,minor:5,capabilities:Object.freeze([
    'practice','editor','editor-drafts','score','manual-review','ai-grading','resources','richtext',
    'navigation','lifecycle','outline-items','answer-state','appearance','answer-areas'
  ])});
  globalThis.QF = Object.freeze({api,defineType(value) {
    if (type || !value || typeof value.project !== 'function' || typeof value.grade !== 'function') throw Error('规则注册失败');
    type = value;
  }});
  for (const value of [Object,Array,Function,String,Number,Boolean,RegExp,Date,Promise,Map,Set,JSON,Reflect]) {
    if (value.prototype) Object.freeze(value.prototype);
    Object.freeze(value);
  }
  // Worker 没有 DOM/Cookie；不透明来源不能访问持久化存储，CSP 禁止网络。
  for (const key of ['fetch','XMLHttpRequest','WebSocket','EventSource','importScripts','indexedDB','caches','WebAssembly']) {
    Object.defineProperty(globalThis,key,{value:undefined,writable:false,configurable:false});
  }
  globalThis.addEventListener('message',event => {
    const {id,hook,args} = event.data;
    try {
      const fn = type?.[hook];
      if (fn == null) { postMessage({id,missing:true}); return; }
      if (typeof fn !== 'function') throw Error('规则钩子无效');
      const result = fn(...args);
      const text = JSON.stringify(['validateQuestion','validateAnswer'].includes(hook) ? result !== false : result);
      if (typeof text !== 'string' || new TextEncoder().encode(text).length > 2*1024*1024) throw Error('规则输出无效或过大');
      postMessage({id,text});
    } catch { postMessage({id,error:true}); }
  });
}

function sandboxBootstrap(session) {
  let worker;
  const send = value => parent.postMessage({...value,session},'*');
  addEventListener('message',event => {
    if (event.source !== parent || event.data?.session !== session) return;
    const value = event.data;
    if (value.kind === 'stop') { worker?.terminate(); return; }
    if (value.kind === 'start') {
      worker?.terminate();
      const url = URL.createObjectURL(new Blob([value.bootstrap,'\npostMessage({initializing:true});\n',value.source,'\npostMessage({ready:true});'],{type:'text/javascript'}));
      try {
        worker = new Worker(url);
        worker.onmessage = event => send(event.data);
        worker.onerror = () => send({error:true});
      } catch { send({error:true}); }
      URL.revokeObjectURL(url);
    } else if (value.kind === 'call') worker?.postMessage(value);
  });
  send({booted:true});
}

export function isolatedRules(source,{document=globalThis.document,timeoutMs=650,startupTimeoutMs=5000}={}) {
  const frame = document.createElement('iframe'),session = crypto.randomUUID(),nonce = crypto.randomUUID().replaceAll('-','');
  frame.hidden = true;
  frame.setAttribute('sandbox','allow-scripts');
  frame.referrerPolicy = 'no-referrer';
  const policy = `default-src 'none'; script-src 'nonce-${nonce}' blob:; worker-src blob:; connect-src 'none'; base-uri 'none'; form-action 'none'`;
  frame.srcdoc = `<!doctype html><meta http-equiv="Content-Security-Policy" content="${policy}"><script nonce="${nonce}">(${sandboxBootstrap.toString()})(${JSON.stringify(session)})<\/script>`;
  let sequence=0,closed=false,pending;
  const send = value => frame.contentWindow.postMessage({...value,session},'*');
  const failure = () => Object.assign(new Error('本地判分未完成，服务器将稍后补判'),{code:'BROWSER_GRADING_FAILED'});
  let resolveReady,rejectReady;
  const ready = new Promise((resolve,reject) => {resolveReady=resolve;rejectReady=reject;});
  // iframe / Worker 调度启动单独计时；规则顶层代码和每个钩子仍各限 650 ms。
  let initializing=false;
  let timer = setTimeout(() => {rejectReady(failure());close();},startupTimeoutMs);
  function listener(event) {
    if (event.source !== frame.contentWindow || event.data?.session !== session) return;
    const value=event.data;
    if (value.booted) send({kind:'start',bootstrap:`(${workerBootstrap.toString()})();`,source});
    else if (value.initializing && !initializing) {
      initializing=true;clearTimeout(timer);timer=setTimeout(() => {rejectReady(failure());close();},timeoutMs);
    }
    else if (value.ready) {clearTimeout(timer);resolveReady();}
    else if (value.error) {rejectReady(failure());pending?.reject(failure());close();}
    else if (pending?.id === value.id) {
      const item=pending;pending=null;clearTimeout(item.timer);
      try {
        if (!value.missing && (typeof value.text!=='string' || new TextEncoder().encode(value.text).length>2*1024*1024)) throw failure();
        item.resolve(value.missing?{missing:true}:{value:JSON.parse(value.text)});
      } catch {item.reject(failure());close();}
    }
  }
  function close() {
    if (closed) return;
    closed=true;clearTimeout(timer);clearTimeout(pending?.timer);
    send({kind:'stop'});globalThis.removeEventListener('message',listener);frame.remove();
  }
  globalThis.addEventListener('message',listener);
  document.body.append(frame);
  return {close,async call(hook,args) {
    await ready;
    if (closed || pending) throw failure();
    return new Promise((resolve,reject) => {
      const id=++sequence;
      pending={id,resolve,reject,timer:setTimeout(() => {reject(failure());close();},timeoutMs)};
      send({kind:'call',id,hook,args});
    });
  }};
}

export async function executeBrowserGrade(ticket,options) {
  const spec=ticket.package;
  if (spec?.apiVersion?.major!==1 || spec.apiVersion.minor>5) throw Error('规则 API 版本不支持');
  let realm=isolatedRules(ticket.source,options);
  try {
    const question=await realm.call('validateQuestion',[ticket.data]);
    const answer=await realm.call('validateAnswer',[ticket.answer,ticket.data]);
    if (question.value===false || answer.value===false) throw Error('规则拒绝此作答');
    const result=(await realm.call('grade',[ticket.data,ticket.answer])).value;
    const status=result?.gradingStatus??'graded';
    if (!['graded','pending'].includes(status) || !Number.isFinite(result?.maxScore)
        || (status==='pending'?result.score!==null:!Number.isFinite(result.score))) throw Error('判分结果无效');
    const state={submitted:true,result};
    const projected=(await realm.call('project',[ticket.data,state])).value;
    realm.close();
    // 与服务器一致，getScore 在全新的上下文执行，不继承 grade 留下的全局变量。
    realm=isolatedRules(ticket.source,options);
    if ((await realm.call('validateQuestion',[ticket.data])).value===false) throw Error('题目无效');
    const scoreState={status:'submitted',submitted:true,answer:ticket.answer,result};
    const scored=await realm.call('getScore',[ticket.data,scoreState]);
    const raw=scored.missing?result:scored.value;
    if (!Number.isFinite(raw?.maxScore) || (status==='pending'?raw.score!==null:!Number.isFinite(raw.score))) throw Error('分数无效');
    const score={score:raw.score,maxScore:raw.maxScore,gradingStatus:status};
    if (Object.hasOwn(raw,'outlineStates')) score.outlineStates=raw.outlineStates;
    return {result,projected,score};
  } finally {realm.close();}
}
