import {unavailable} from './online.js';
// History-disabled practice uses an ephemeral page ID, separate from account authentication.
const practiceSession=globalThis.crypto?.randomUUID?.()||'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,char=>{const value=globalThis.crypto?.getRandomValues?globalThis.crypto.getRandomValues(new Uint8Array(1))[0]&15:Math.floor(Math.random()*16);return (char==='x'?value:(value&3)|8).toString(16);});
export async function request(path, options = {}) {
  const {timeoutMs=12000,responseType='json',signal,...fetchOptions}=options;
  const headers={Accept:responseType==='blob'?'application/vnd.quizforge.qbank':'application/json',...options.headers,'X-QuizForge-Practice-Session':practiceSession};
  if(options.body&&!(options.body instanceof FormData)&&!headers['Content-Type'])headers['Content-Type']='application/json';
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),Math.min(135000,Math.max(1000,timeoutMs)));
  const cancel=()=>controller.abort();signal?.addEventListener('abort',cancel,{once:true});if(signal?.aborted)cancel();
  try {
    if(controller.signal.aborted)throw new Error('Request cancelled');
    const response=await fetch(path,{...fetchOptions,headers,signal:controller.signal,credentials:'same-origin',cache:'no-store'});
    if(response.status===401) {
      location.assign('/?returnTo='+encodeURIComponent(location.pathname+location.search+location.hash));
      throw Object.assign(new Error('会话已失效，请重新登录。'),{code:'UNAUTHENTICATED',status:401});
    }
    if(response.status===404||response.status===501)throw unavailable(response.status);
    if(response.status===204)return null;
    if(response.ok&&responseType==='response')return response;
    if(response.ok&&responseType==='blob')return await response.blob();
    let result;
    try{result=await response.json();}catch(error){if(controller.signal.aborted)throw error;throw Object.assign(new Error('服务没有返回有效数据'),{code:'INVALID_RESPONSE'});}
    if(!response.ok)throw Object.assign(new Error(result.error?.message||'操作失败'),{code:result.error?.code||'REQUEST_FAILED',status:response.status});
    return result;
  }catch(error){
    if(signal?.aborted)throw Object.assign(new Error('读取已取消。'),{name:'AbortError',code:'REQUEST_CANCELLED'});
    if(controller.signal.aborted)throw Object.assign(new Error((fetchOptions.method||'GET').toUpperCase()==='GET'?'加载等待超时，请稍后重试。':'连接等待超时，当前输入已保留，请重试保存。'),{code:'REQUEST_TIMEOUT'});
    if(error instanceof TypeError)throw Object.assign(new Error('无法连接服务器'),{code:'NETWORK_ERROR'});
    throw error;
  }finally{clearTimeout(timer);signal?.removeEventListener('abort',cancel);}
}
export const collectionPath=(kind,id)=>`/api/collections/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`;
export const questionPath=(kind,id,qid)=>`${collectionPath(kind,id)}/questions/${encodeURIComponent(qid)}`;
export function makeRequestId(){return crypto.randomUUID?.()||`${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;}
export async function readResource(id) {
  if(!/^[a-f0-9]{64}$/.test(id))throw new Error('图片资源编号无效');
  const blob=await request('/api/resources/'+id,{responseType:'blob',headers:{Accept:'image/*'}});
  if(blob.size>4*1024*1024)throw new Error('图片超过 4 MiB 限制');
  const data=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=()=>reject(new Error('图片读取失败'));reader.readAsDataURL(blob);});
  return {id,mime:blob.type,size:blob.size,data};
}
