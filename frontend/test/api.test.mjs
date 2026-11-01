import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

async function client(fetch,extra={}) {
  const redirects=[];
  const context=vm.createContext({fetch,crypto,FormData,Blob,AbortController,setTimeout,clearTimeout,TypeError,URL,
    location:{pathname:'/practice/',search:'?bank=one',hash:'#q1',assign:path=>redirects.push(path)},
    sessionStorage:{getItem(){throw Error('Auth storage must not be read');},setItem(){throw Error('Auth storage must not be written');}},...extra});
  const modules=new Map();
  async function load(url) {
    if(modules.has(url.href))return modules.get(url.href);
    const module=new vm.SourceTextModule(await readFile(url,'utf8'),{context,identifier:url.href});modules.set(url.href,module);
    await module.link((specifier,ref)=>load(new URL(specifier,ref.identifier)));return module;
  }
  const module=await load(new URL('../practice/shared/api.js',import.meta.url));await module.evaluate();
  const online=await load(new URL('../practice/shared/online.js',import.meta.url));await online.evaluate();
  return {...module.namespace,...online.namespace,redirects};
}

test('Cookie auth preserves action body, revision, request ID and stamp headers',async()=>{
  let sent;
  const api=await client(async(path,options)=>{sent={path,options};return Response.json({state:{revision:3}});});
  const body=JSON.stringify({requestId:'same-id',revision:2,action:'draft',data:{answer:'a'}});
  const result=await api.request('/api/collections/bank/one/questions/q/actions',{method:'POST',body,headers:{'X-QuizForge-Question-Stamp':'stamp'}});
  assert.equal(result.state.revision,3);assert.equal(sent.options.body,body);assert.equal(sent.options.credentials,'same-origin');
  assert.equal(sent.options.headers['X-QuizForge-Question-Stamp'],'stamp');
  assert.equal(sent.options.headers['Content-Type'],'application/json');
  assert.ok(!Object.keys(sent.options.headers).some(key=>/token/i.test(key)));
});

test('401 redirects to account page with full return route',async()=>{
  const api=await client(async()=>new Response('',{status:401}));
  await assert.rejects(api.request('/api/me'),error=>error.status===401&&error.code==='UNAUTHENTICATED');
  assert.equal(api.redirects[0],'/?returnTo='+encodeURIComponent('/practice/?bank=one#q1'));
});

for(const status of [404,501])test(`${status} HTML response becomes a friendly feature error`,async()=>{
  const api=await client(async()=>new Response('<html>missing</html>',{status}));
  await assert.rejects(api.request('/api/future'),error=>error.status===status&&error.code==='FEATURE_NOT_AVAILABLE'&&error.message.includes('尚未开放'));
});

test('missing index and catalog produce a marked empty state, never fake banks',async()=>{
  const paths=[];const api=await client(async path=>{paths.push(path);return new Response('',{status:404});});
  const value=await api.loadLibraryIndex(api.request);
  assert.deepEqual(paths,['/api/library/index','/api/catalog']);assert.equal(value.unavailable,true);assert.equal(value.banks.length,0);
});

test('catalog fallback keeps real banks and always removes example/development entries',async()=>{
  const api=await client(async path=>path==='/api/library/index'?new Response('',{status:501}):Response.json({banks:[{id:'real',title:'真实题库'}],extensions:[{id:'example'}],developmentEnabled:true}));
  const value=await api.loadLibraryIndex(api.request);
  assert.equal(value.banks[0].id,'real');assert.equal(value.extensions.length,0);assert.equal(value.developmentEnabled,false);assert.equal(value.unavailable,false);
});

test('network errors and 503 are not disguised as an unimplemented empty catalog',async()=>{
  const api=await client(async()=>{throw new TypeError('failed fetch');});
  await assert.rejects(api.loadLibraryIndex(api.request),error=>error.code==='NETWORK_ERROR');
  const busy=await client(async()=>Response.json({error:{code:'RULE_UNAVAILABLE',message:'稍后重试'}},{status:503}));
  await assert.rejects(busy.loadLibraryIndex(busy.request),error=>error.status===503&&error.message==='稍后重试');
});

test('409 conflicts remain failures with their original code and message',async()=>{
  const api=await client(async()=>Response.json({error:{code:'CONTENT_CONFLICT',message:'题目内容已更改'}},{status:409}));
  await assert.rejects(api.request('/api/actions',{method:'POST',body:'{}'}),error=>error.status===409&&error.code==='CONTENT_CONFLICT');
});

test('multipart lets fetch generate its boundary; 204 logout succeeds',async()=>{
  let headers;
  const api=await client(async(_path,options)=>{headers=options.headers;return new Response(null,{status:204});});
  const body=new FormData();body.append('file',new Blob(['qbank']),'one.qbank');
  assert.equal(await api.request('/api/library/import',{method:'POST',body}),null);
  assert.equal(headers['Content-Type'],undefined);
});

test('audio preflight does not consume the response body as JSON or Blob',async()=>{
  const response=new Response(null,{status:200});
  const api=await client(async()=>response);
  assert.equal(await api.request('/api/listening/audio',{method:'HEAD',responseType:'response'}),response);
  assert.equal(response.bodyUsed,false);
});

test('image reads share Cookie transport and negotiate image content',async()=>{
  let sent;
  const api=await client(async(path,options)=>{sent={path,options};return new Response(new Blob(['x'],{type:'image/png'}));},{
    FileReader:class {readAsDataURL(blob){this.result=`data:${blob.type};base64,eA==`;this.onload();}},
  });
  const resource=await api.readResource('a'.repeat(64));
  assert.equal(resource.mime,'image/png');assert.equal(resource.data,'eA==');
  assert.equal(sent.options.headers.Accept,'image/*');assert.equal(sent.options.credentials,'same-origin');
});

for(const [route,expected] of [
  ['/practice/?bank=a#q2','/practice/?bank=a#q2'],
  ['https://evil.example/practice/',null],['//evil.example/practice/',null],
  ['/practice/../admin/',null],['/practice-lookalike/',null],['javascript:alert(1)',null],
])test(`account return URL stays inside practice: ${route}`,async()=>{
  const source=await readFile(new URL('../public/auth.js',import.meta.url),'utf8');
  const helper=source.slice(source.indexOf('function practiceReturn'),source.indexOf('const byId'));
  const result=vm.runInNewContext(helper+'practiceReturn()',{
    URL,URLSearchParams,location:{origin:'https://quiz.example',search:'?returnTo='+encodeURIComponent(route)},
  });
  assert.equal(result,expected);
});
