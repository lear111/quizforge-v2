export const MAX_AUDIO_BYTES=64*1024*1024;

// 只检查字面地址；域名不做 DNS 预查，与 Java NetworkAudioUrl 的限制一致。
export function validNetworkAudioUrl(value){
  if(typeof value!=='string'||value.length>2048||!value.startsWith('https://')||/[\s\x00-\x1f\x7f#]/u.test(value))return false;
  try{
    const parsed=new URL(value);
    if(parsed.href!==value||parsed.username||parsed.password)return false;
    let host=parsed.hostname;
    if(host.startsWith('[')){
      const parts=host.slice(1,-1).split('::'),left=parts[0]?parts[0].split(':'):[],right=parts[1]?parts[1].split(':'):[];
      const words=[...left,...Array(parts.length===2?8-left.length-right.length:0).fill('0'),...right].map(v=>parseInt(v,16));
      if(words.length!==8||(words[0]&0xe000)!==0x2000)return false;
      return !(words[0]===0x2001&&(words[1]<0x200||words[1]===0xdb8)||words[0]===0x2002||words[0]===0x3fff&&(words[1]&0xf000)===0);
    }
    if(host.endsWith('.'))host=host.slice(0,-1);
    if(/^\d+\.\d+\.\d+\.\d+$/.test(host)){
      const [a,b,c]=host.split('.').map(Number);
      return !(a===0||a===10||a===127||a>=224||a===100&&b>=64&&b<=127||a===169&&b===254||a===172&&b>=16&&b<=31||a===192&&(b===168||b===0&&(c===0||c===2)||b===88&&c===99)||a===198&&(b===18||b===19||b===51&&c===100)||a===203&&b===0&&c===113);
    }
    return host.includes('.')&&host!=='localhost'&&!host.endsWith('.localhost')&&!host.endsWith('.local');
  }catch{return false;}
}

export class NetworkAudioError extends Error{
  constructor(code,message){super(message);this.name='NetworkAudioError';this.code=code;}
}
const reject=(code,message)=>{throw new NetworkAudioError(code,message);};

// 先完整读取受限的 Blob；不会把远端 URL 直接交给 <audio>，也不使用宿主 API 的认证 request。
export async function downloadNetworkAudio(url,{signal,onProgress=()=>{},fetchImpl=globalThis.fetch}={}){
  if(!validNetworkAudioUrl(url))reject('URL','网络音频地址无效，请联系题库作者。');
  const controller=new AbortController(),cancel=()=>controller.abort(signal?.reason);
  if(signal?.aborted)cancel();else signal?.addEventListener('abort',cancel,{once:true});
  let reader;
  try{
    const response=await fetchImpl(url,{signal:controller.signal,credentials:'omit',referrerPolicy:'no-referrer',mode:'cors',redirect:'error'});
    if(!response.ok)reject('ACCESS',`无法访问网络音频（HTTP ${response.status}），请重试。`);
    const mime=(response.headers.get('Content-Type')||'').split(';',1)[0].trim().toLowerCase();
    if(!/^audio\/[a-z0-9!#$&^_.+\-]+$/.test(mime))reject('TYPE','网络材料不是音频，请联系题库作者。');
    const length=response.headers.get('Content-Length'),total=length&&/^\d+$/.test(length)?Number(length):null;
    if(total>MAX_AUDIO_BYTES)reject('LIMIT','网络音频超过 64 MiB 大小上限。');
    if(!response.body)reject('ACCESS','无法访问网络音频内容，请重试。');
    reader=response.body.getReader();
    const chunks=[];let loaded=0;onProgress({loaded,total});
    while(true){
      const {done,value}=await reader.read();if(done)break;
      loaded+=value.byteLength;
      if(loaded>MAX_AUDIO_BYTES)reject('LIMIT','网络音频超过 64 MiB 大小上限。');
      chunks.push(value);onProgress({loaded,total});
    }
    if(controller.signal.aborted)throw new DOMException('已取消下载','AbortError');
    return new Blob(chunks,{type:mime});
  }catch(error){
    controller.abort();
    if(reader)await reader.cancel().catch(()=>{});
    if(signal?.aborted||error?.name==='AbortError'||error instanceof NetworkAudioError)throw error;
    reject('CORS','无法访问网络音频，可能未允许跨域，请检查网络或联系题库作者。');
  }finally{
    signal?.removeEventListener('abort',cancel);
    reader?.releaseLock();
  }
}
