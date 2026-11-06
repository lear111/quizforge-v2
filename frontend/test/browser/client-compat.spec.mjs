import {test,expect as baseExpect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';
const expect=baseExpect.configure({timeout:30000});

function sql(query) {
  execFileSync('docker',['compose','-p',process.env.QF_COMPOSE_PROJECT,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "$1"','--',query],{stdio:'pipe'});
}
const sha256=value=>createHash('sha256').update(value).digest('hex');
const challenge=value=>createHash('sha256').update(value).digest('base64url');
const random=()=>crypto.randomUUID().replaceAll('-','')+crypto.randomUUID().replaceAll('-','');

// 客户端兼容（遗留契约 C-05）经过真实的 Nginx：request 夹具没有 Cookie 和 Origin，与已发布客户端的请求一样。
test('a published client signs in, uses AI and downloads the exact approved file through nginx',async({page,browser,request},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','Requires an explicitly isolated Compose fixture');
  test.setTimeout(300000);
  if(!process.env.QF_COMPOSE_PROJECT?.startsWith('qf-'))throw Error('Use an isolated qf-* Compose project');
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());

  // 用户注册后退出，从客户端发起授权时才登录。
  const password='password123';
  const account=await registerByApi(page,'client-user','客户端用户',password);
  await page.context().clearCookies();

  const verifier=random(),state=random().slice(0,32),redirect='http://127.0.0.1:8787/hub/connect/callback',name=`测试电脑 ${info.project.name}`;
  const query=new URLSearchParams({v:'1',client:'web',redirect_uri:redirect,state,code_challenge:challenge(verifier),code_challenge_method:'S256',device_name:name});
  await page.route('http://127.0.0.1:8787/**',route=>route.fulfill({status:200,contentType:'text/plain',body:'ok'}));
  await page.goto('/connect/authorize?'+query);
  await expect(page).toHaveURL(/\/\?returnTo=/);
  await page.locator('#login-email').fill(account.email);
  await page.locator('#login-password').fill(password);
  await page.locator('#login-form button[type="submit"]').click();

  // 登录后回到确认页；手机宽度下没有横向滚动。
  await expect(page.getByRole('heading',{name:'授权这台设备登录？'})).toBeVisible();
  await expect(page.locator('dd').first()).toContainText(name);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
  await page.getByRole('button',{name:'授权登录'}).click();
  await page.waitForURL(/127\.0\.0\.1:8787\/hub\/connect\/callback/);
  const callback=new URL(page.url());
  expect(callback.searchParams.get('state')).toBe(state);
  const code=callback.searchParams.get('code');
  expect(code).toMatch(/^[A-Za-z0-9_-]{43}$/);

  // 客户端用授权码换取令牌；错误的 verifier 使授权码作废，所以这里只换一次。
  const exchanged=await request.post('/api/connect/token',{data:{v:1,client:'web',code,code_verifier:verifier,redirect_uri:redirect}});
  expect(exchanged.status(),await exchanged.text()).toBe(200);
  const {token,device,user}=await exchanged.json();
  expect(token).toMatch(/^qfd_[A-Za-z0-9_-]{43}$/);
  expect(device.name).toBe(name);
  expect(user.name).toBe('客户端用户');
  const bearer={Authorization:'Bearer '+token};
  const me=await (await request.get('/api/connect/me',{headers:bearer})).json();
  expect(me.device.id).toBe(device.id);
  expect(me.ai.balance).toBe(10000);

  // 网页接口不接受设备令牌。
  expect((await request.get('/api/me',{headers:bearer})).status()).toBe(401);

  // 账号页“已连接设备”：显示、改名。
  await page.goto('/');
  const row=page.locator('#device-list li');
  await expect(row).toHaveCount(1);
  await expect(row.locator('input')).toHaveValue(name);
  await expect(row.locator('small')).toContainText('电脑');
  await row.locator('input').fill('改过的名字');
  await row.getByRole('button',{name:'保存名称'}).click();
  await expect(page.locator('#device-status')).toHaveText('名称已保存。');
  expect((await (await request.get('/api/connect/me',{headers:bearer})).json()).device.name).toBe('改过的名字');

  // 管理员发布并通过一个题库包。
  const adminContext=await browser.newContext({viewport:page.viewportSize()});
  const adminPage=await adminContext.newPage();
  adminPage.on('pageerror',error=>errors.push(error.message));
  const admin=await registerByApi(adminPage,'client-admin','客户端管理员');
  sql(`UPDATE account_user SET is_admin = TRUE WHERE email = '${admin.email.replace(/'/g,"''")}';`);

  // AI 网关：请求经过 Nginx 的独立上限与超时，假上游只在 Compose 内部（CI 与本机验收都使用）。
  if(process.env.QF_AI_GRADING_BROWSER_TESTS==='1'){
    const added=await adminPage.request.post('/api/admin/ai-keys',{headers:{Origin:admin.origin},data:{apiKey:`sk-fake-client-${crypto.randomUUID()}`,note:'客户端兼容验收',inputPrice:30000,outputPrice:90000}});
    expect(added.status(),await added.text()).toBe(201);
    const {id:key}=await added.json();
    const image=`data:image/png;base64,${Buffer.alloc(1024).toString('base64')}`;
    const completion=await request.post('/api/ai/v1/chat/completions',{headers:bearer,data:{model:'quizforge-official',stream:false,max_tokens:100,response_format:{type:'json_object'},
      messages:[{role:'system',content:'规则'},{role:'user',content:[{type:'text',text:'看图'},{type:'image_url',image_url:{url:image,detail:'auto'}}]}]}});
    expect(completion.status(),await completion.text()).toBe(200);
    const answer=await completion.json();
    expect(answer.choices[0].message.content).toContain('score');
    expect(answer.choices[0].finish_reason).toBe('stop');
    expect(answer.quizforge).toEqual({cost:75,balance:9925});
    expect((await request.post('/api/ai/v1/chat/completions',{headers:bearer,data:{model:'quizforge-official',stream:true,messages:[{role:'user',content:'你好'}]}})).status()).toBe(400);
    // 超过 Nginx 上限的请求也按契约返回 JSON。
    const huge=await request.post('/api/ai/v1/chat/completions',{headers:bearer,data:{model:'quizforge-official',messages:[{role:'user',content:'x'.repeat(15*1024*1024)}]}});
    expect(huge.status()).toBe(413);
    expect((await huge.json()).error.code).toBe('AI_REQUEST_TOO_LARGE');
    await adminPage.request.delete(`/api/admin/ai-keys/${key}`,{headers:{Origin:admin.origin}});
  }

  const bytes=await readFile(resolve('../samples/basic-types.qbank'));
  const title=`客户端导入 ${info.project.name}-${crypto.randomUUID().slice(0,8)}`;
  const published=await adminPage.request.post('/api/catalog/packs',{headers:{Origin:admin.origin},multipart:{
    title,summary:'固定版本导入',description:'客户端兼容的浏览器测试。',category:'其他',tags:'测试',rights:'yes',
    archive:{name:'basic-types.qbank',mimeType:'application/octet-stream',buffer:bytes}}});
  expect(published.status(),await published.text()).toBe(201);
  const {packId}=await published.json();
  await expect.poll(async()=>{
    const value=await (await adminPage.request.get(`/api/plaza/packs/${packId}`)).status();
    return value;
  },{timeout:120000}).toBe(200);

  // 广场详情页的本地导入链接：固定格式，不带其他内容。
  await page.goto(`/plaza/#${packId}`);
  const local=page.getByRole('link',{name:'下载并导入到本地'});
  await expect(local).toHaveAttribute('href',`quizforge://hub/import?v=1&pack=${packId}&revision=1`);

  // 固定版本清单与下载：匿名，不跟随重定向，响应体逐字节一致。
  const manifest=await request.get(`/api/packs/${packId}/local-import?revision=1`,{maxRedirects:0});
  expect(manifest.status(),await manifest.text()).toBe(200);
  expect(manifest.headers()['content-type'].split(';')[0]).toBe('application/json');
  const pinned=await manifest.json();
  expect(pinned).toEqual({schemaVersion:1,pack:{id:packId,revision:1,title},archive:{name:'basic-types.qbank',size:bytes.length,sha256:sha256(bytes)}});
  const download=await request.get(`/api/packs/${packId}/download?revision=1&sha256=${pinned.archive.sha256}`,{maxRedirects:0,headers:{'Accept-Encoding':'gzip, identity'}});
  expect(download.status()).toBe(200);
  const headers=download.headers();
  expect(headers['content-type'].split(';')[0]).toBe('application/octet-stream');
  expect(Number(headers['content-length'])).toBe(bytes.length);
  expect(headers['content-encoding']).toBeUndefined();
  expect(headers['x-accel-redirect']).toBeUndefined();
  const body=await download.body();
  expect(sha256(body)).toBe(pinned.archive.sha256);
  expect(Buffer.compare(body,bytes)).toBe(0);
  const stale=await request.get(`/api/packs/${packId}/local-import?revision=2`);
  expect(stale.status()).toBe(409);
  expect((await stale.json()).code).toBe('LOCAL_IMPORT_REVISION_CHANGED');

  // 已发布客户端的“题库广场”入口。
  await page.goto('/manage/#library');
  await expect(page).toHaveURL(/\/plaza\/#library$/);

  // 在账号页撤销后令牌立即失效。
  await page.goto('/');
  await page.locator('#device-list li').getByRole('button',{name:'撤销'}).click();
  await expect(page.locator('#device-status')).toHaveText('已撤销。');
  const revoked=await request.get('/api/connect/me',{headers:bearer});
  expect(revoked.status()).toBe(401);
  expect((await revoked.json()).error.code).toBe('DEVICE_TOKEN_INVALID');

  expect(errors).toEqual([]);
  await adminContext.close();
});
