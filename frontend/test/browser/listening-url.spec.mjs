import {test,expect as baseExpect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';
const expect=baseExpect.configure({timeout:30000});

test.beforeEach(async({page})=>{
  test.skip(!process.env.QF_AUDIO_TEST_URL,'需要独立 Compose 的 HTTPS 音频服务');
  test.setTimeout(180000);
  // 观察真实 HTMLAudioElement；不替换下载、解码和播放行为。
  await page.addInitScript(()=>{
    const NativeAudio=window.Audio;window.__qfAudio=[];
    window.Audio=function(...args){const audio=new NativeAudio(...args);window.__qfAudio.push(audio);return audio;};
    window.Audio.prototype=NativeAudio.prototype;
  });
});

async function setup(page){
  const project=process.env.QF_COMPOSE_PROJECT;
  if(!project?.startsWith('qf-'))throw Error('必须使用独立 qf-* 项目');
  const key=crypto.randomUUID(),audioOrigin=process.env.QF_AUDIO_TEST_URL;
  const fixture=JSON.parse(execFileSync(process.platform==='win32'?'python':'python3',
    [resolve('test/audio-server/fixture.py'),audioOrigin,key],{encoding:'utf8',maxBuffer:8*1024*1024}));
  const {origin}=await registerByApi(page,'audio','网络听力验收');
  const imported=await page.request.post('/api/library/import',{headers:{Origin:origin},multipart:{file:{name:'audio.qbank',mimeType:'application/zip',buffer:Buffer.from(fixture.archive,'base64')}}});
  expect(imported.status(),await imported.text()).toBe(200);
  const collection=(await imported.json()).collections[0];
  expect(collection.id).toMatch(/^[a-f0-9-]{36}$/);
  const sql=`INSERT INTO admin_extension_review (digest,extension_id,version,status) SELECT DISTINCT e.digest,e.extension_id,e.version,'approved' FROM content_extension_package e JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id WHERE c.id='${collection.id}' ON DUPLICATE KEY UPDATE status='approved';`;
  execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:sql,stdio:['pipe','pipe','pipe']});
  const base=`/api/collections/bank/${collection.id}`;
  const overview=await (await page.request.get(base)).json();
  expect(overview.listening.materials[0].url).toBe(`${audioOrigin}/audio/good.wav?key=${key}`);
  expect(overview.listening.materials.at(-1).url).toBeUndefined();
  await page.context().addCookies([{name:'audio-secret',value:'synthetic-test-only',url:audioOrigin}]);
  const open=async()=>{
    await page.goto('/practice/');
    await expect(page.locator('.question-pane:not([hidden]):not([inert]) iframe.question-frame')).toBeVisible();
    await expect(page.locator('#collection-loading')).toBeHidden();
  };
  await open();
  const root=page.locator('.qf-listening');
  const expand=async()=>{await expect(root).toBeVisible();if(await root.getAttribute('data-compact')==='true')await root.locator('.qfl-expand').click();};
  await expand();
  const select=async id=>{await root.locator('.qfl-material').click();await root.locator(`[data-material-id="${id}"]`).click();};
  const ready=async()=>{
    await expect(root.locator('.qfl-status')).toBeHidden();
    await expect.poll(()=>page.evaluate(()=>window.__qfAudio.some(a=>a.src.startsWith('blob:')&&a.duration===3))).toBe(true);
  };
  // 测试控制走宿主机 loopback；正式材料 URL 仍由 Chromium 解析到这个本机服务。
  const control=async action=>page.request.get(`https://127.0.0.1:${new URL(audioOrigin).port}/control/${action}?key=${key}`,{ignoreHTTPSErrors:true});
  return {...fixture,base,origin,root,select,ready,control,open,expand};
}

test('HTTPS audio downloads anonymously in full, reports progress and cancels on switch or close',async({page},info)=>{
  const f=await setup(page);await f.ready();
  await f.root.locator('.qfa-toggle').click();
  await expect.poll(()=>page.evaluate(()=>window.__qfAudio.some(a=>!a.paused))).toBe(true);
  const requests=await (await f.control('requests')).json();
  expect(requests.find(r=>r.path==='/audio/good.wav')).toEqual({path:'/audio/good.wav',cookie:false,referrer:false,authorization:false});
  const frame=page.frameLocator('.question-pane:not([hidden]):not([inert]) iframe.question-frame');
  expect(await frame.locator('body').evaluate(()=>JSON.stringify(window.QF_PREVIEW))).not.toContain('audio.qf.test');
  expect(await frame.locator('body').evaluate(async url=>{try{await fetch(url);return false;}catch{return true;}},process.env.QF_AUDIO_TEST_URL+'/health')).toBe(true);
  await f.select('gated-wav');
  await expect(f.root.locator('.qfl-status')).toContainText(/正在下载网络音频 [1-9]/);
  expect(await page.evaluate(()=>window.__qfAudio.at(-1).getAttribute('src'))).toBeFalsy();
  await f.control('release');await f.ready();
  await f.select('good-wav');await f.ready();
  await f.control('reset');await f.select('gated-wav');
  await expect(f.root.locator('.qfl-status')).toContainText('正在下载网络音频');
  const switched=page.waitForEvent('requestfailed',{predicate:r=>r.url().includes('/audio/gated.wav')});
  await f.select('good-wav');await switched;await f.control('release');await f.ready();
  await f.control('reset');await f.select('gated-wav');
  await expect(f.root.locator('.qfl-status')).toContainText('正在下载网络音频');
  const closed=page.waitForEvent('requestfailed',{predicate:r=>r.url().includes('/audio/gated.wav')});
  await page.locator('#next').click();await expect(f.root).toBeHidden();await closed;await f.control('release');
  await f.open();await f.expand();await f.ready();
  const directory=resolve('../.artifacts/local/listening-url/screenshots');await mkdir(directory,{recursive:true});
  await page.screenshot({path:resolve(directory,`audio-${info.project.name}.png`),fullPage:true});
});

test('HTTPS audio distinguishes access, CORS, type and both size limits; retry and unknown length work',async({page})=>{
  const f=await setup(page);await f.ready();
  for(const [id,message] of [['unavailable','无法访问'],['no-cors-wav','可能未允许跨域'],['not-audio','不是音频'],['large-header','超过 64 MiB'],['large-body','超过 64 MiB']]){
    await f.select(id);await expect(f.root.locator('.qfl-status')).toContainText(message);
    await expect(f.root.locator('.qfl-retry')).toBeVisible();
  }
  await f.select('retry-wav');await expect(f.root.locator('.qfl-status')).toContainText('无法访问');
  await f.root.locator('.qfl-retry').click();await f.ready();
  await f.select('no-length-wav');await f.ready();
});

test('history freezes the original network URL and warns that remote content may change',async({page})=>{
  const f=await setup(page);await f.ready();
  const actions=`${f.base}/questions/${encodeURIComponent(f.qid)}/actions`;
  const submitted=await page.request.post(actions,{headers:{Origin:f.origin},data:{action:'submit',requestId:crypto.randomUUID(),revision:0,data:{answer:f.answer}}});
  expect(submitted.status(),await submitted.text()).toBe(200);
  const run=async kind=>{
    const summary=await (await page.request.get(`${f.base}/summary`)).json();
    const accepted=await page.request.post(`${f.base}/${kind}`,{headers:{Origin:f.origin},data:{requestId:crypto.randomUUID(),roundId:summary.roundId,summaryVersion:summary.summaryVersion}});
    expect(accepted.status(),await accepted.text()).toBe(202);
    const {jobId}=await accepted.json();let progress;
    await expect.poll(async()=>{progress=await(await page.request.get(`${f.base}/jobs/${jobId}`)).json();return progress.status;}).toBe('completed');
    return progress.result;
  };
  await run('finish');const archived=await run('restart');
  const detail=await(await page.request.get(`${f.base}/history/${archived.historyId}`)).json();
  expect(detail.collection.listening.materials[0].url).toBe(`${process.env.QF_AUDIO_TEST_URL}/audio/good.wav?key=${f.key}`);
  await f.open();await page.locator('#history-open').click();await page.locator('.history-row').click();
  await f.expand();await f.ready();
  await expect(f.root.locator('.qfl-warning')).toHaveText('网络音频，内容可能已变化');
});
