import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {validNetworkAudioUrl,downloadNetworkAudio,MAX_AUDIO_BYTES} from '../practice/listening/network-audio.js';
const url='https://media.example.com/a.mp3';
const vectors=JSON.parse(await readFile(new URL('./fixtures/network-audio-urls.json',import.meta.url),'utf8'));
for(const vector of vectors)test(`network address ${JSON.stringify(vector.url)}`,()=>assert.equal(validNetworkAudioUrl(vector.url),vector.valid));
test('network URL length boundary is exactly 2048',()=>{
  const value='https://media.example.com/'+ 'a'.repeat(2048-'https://media.example.com/'.length);
  assert.equal(validNetworkAudioUrl(value),true);assert.equal(validNetworkAudioUrl(value+'a'),false);
});
const response=(body,headers={'Content-Type':'audio/mpeg'},status=200)=>new Response(body,{headers,status});
test('anonymous complete download preserves bytes and reports progress',async()=>{
  let request;const progress=[];
  const blob=await downloadNetworkAudio(url,{fetchImpl:async(_url,options)=>{request=options;return response(new Uint8Array([1,2,3]),{'Content-Type':'audio/mpeg; x=y','Content-Length':'3'});},onProgress:p=>progress.push(p)});
  assert.equal(request.credentials,'omit');assert.equal(request.referrerPolicy,'no-referrer');assert.equal(request.mode,'cors');assert.equal(request.redirect,'error');
  assert.equal(request.headers,undefined);assert.equal(blob.type,'audio/mpeg');assert.deepEqual([...new Uint8Array(await blob.arrayBuffer())],[1,2,3]);
  assert.deepEqual(progress,[{loaded:0,total:3},{loaded:3,total:3}]);
});
test('waits for all bytes before returning a Blob, including unknown length',async()=>{
  let release,observed;const gate=new Promise(r=>release=r),started=new Promise(r=>observed=r);let finished=false;
  const body=new ReadableStream({async start(controller){controller.enqueue(new Uint8Array([1]));observed();await gate;controller.enqueue(new Uint8Array([2]));controller.close();}});
  const pending=downloadNetworkAudio(url,{fetchImpl:async()=>response(body),onProgress:p=>{if(p.loaded===1)observed();}}).then(blob=>{finished=true;return blob;});
  await started;assert.equal(finished,false);release();assert.equal((await pending).size,2);
});
test('rejects excessive Content-Length before reading and aborts the request',async()=>{
  let signal,reads=0;
  await assert.rejects(downloadNetworkAudio(url,{fetchImpl:async(_url,opts)=>{signal=opts.signal;return response(new ReadableStream({pull(){reads++;}}),{'Content-Type':'audio/wav','Content-Length':String(MAX_AUDIO_BYTES+1)});}}),{code:'LIMIT'});
  assert.equal(signal.aborted,true);assert.ok(reads<=1);
});
test('counts actual bytes without length and cancels immediately after the limit',async()=>{
  let chunks=0,cancelled=false;const chunk=new Uint8Array(1024*1024);
  const body=new ReadableStream({pull(c){chunks++;c.enqueue(chunk);},cancel(){cancelled=true;}});
  await assert.rejects(downloadNetworkAudio(url,{fetchImpl:async()=>response(body)}),{code:'LIMIT'});
  assert.ok(chunks<=66);assert.equal(cancelled,true);
});
test('accepts exactly 64 MiB',async()=>{
  let n=0;const chunk=new Uint8Array(1024*1024);
  const body=new ReadableStream({pull(c){if(n++<64)c.enqueue(chunk);else c.close();}});
  assert.equal((await downloadNetworkAudio(url,{fetchImpl:async()=>response(body)})).size,MAX_AUDIO_BYTES);
});
for(const [name,fetchImpl,code] of [
  ['unavailable',async()=>response('',{},503),'ACCESS'],
  ['not audio',async()=>response('<html>',{'Content-Type':'text/html'}),'TYPE'],
  ['CORS or network failure',async()=>{throw new TypeError('Failed to fetch');},'CORS'],
])test(name,()=>assert.rejects(downloadNetworkAudio(url,{fetchImpl}),{code}));
test('caller cancellation cancels the pending stream',async()=>{
  const controller=new AbortController();let entered;
  const started=new Promise(r=>entered=r);
  const pending=downloadNetworkAudio(url,{signal:controller.signal,fetchImpl:async(_url,{signal})=>{
    return response(new ReadableStream({start(stream){signal.addEventListener('abort',()=>stream.error(new DOMException('取消','AbortError')),{once:true});entered();}}));
  }});
  await started;controller.abort();await assert.rejects(pending,{name:'AbortError'});
});
test('unsafe URL is rejected before fetch',async()=>{
  let count=0;await assert.rejects(downloadNetworkAudio('https://localhost/a.mp3',{fetchImpl:async()=>{count++;}}),{code:'URL'});assert.equal(count,0);
});
