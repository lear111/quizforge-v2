import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {registerByApi,registerByForm} from './accounts.mjs';
import {approvePackage} from './library-fixture.mjs';

// 账号首页与我的题库改版（旧 Hub 风格）：FR-01、FR-11 的流程不变，桌面与 375px 手机都没有横向溢出。
// 截图只含合成数据，随 CI 的浏览器测试产物上传。
const screenshots=resolve(process.env.QF_SCREENSHOT_DIR||'../.artifacts/screenshots/hub-ui');
const snapshot=async(page,info,name)=>{
  await mkdir(screenshots,{recursive:true});
  await page.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));
  await page.screenshot({path:resolve(screenshots,`${name}-${info.project.name}.png`),fullPage:true,animations:'disabled'});
};
const noOverflow=async(page,label)=>{
  // 手机模拟下内容溢出时 innerWidth 会随之变大，因此与固定的视口宽度比较。
  const scroll=await page.evaluate(()=>document.documentElement.scrollWidth);
  expect(scroll,`${label} 横向溢出`).toBeLessThanOrEqual(page.viewportSize().width);
};
function promote(email) {
  const query=`UPDATE account_user SET is_admin = TRUE WHERE email = '${email.replace(/'/g,"''")}';`;
  execFileSync('docker',['compose','-p',process.env.QF_COMPOSE_PROJECT,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}
const violations=()=>{
  window.__cspViolations=[];
  document.addEventListener('securitypolicyviolation',event=>window.__cspViolations.push(`${event.violatedDirective} ${event.blockedURI}`));
};

test('account forms: login, register and reset keep their behaviour in the Hub layout',async({page},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立本机 Compose');
  test.setTimeout(90000);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(violations);
  await page.goto('/');
  await expect(page.getByRole('heading',{name:'欢迎回来'})).toBeVisible();
  await expect(page.locator('#notification-slot')).toBeHidden();
  await expect(page.getByRole('link',{name:'下载软件'})).toBeVisible();
  await noOverflow(page,'登录');
  await snapshot(page,info,'account-login');

  // 错误提示：账号格式与错误密码。
  await page.locator('#login-email').fill('not-an-account');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await expect(page.locator('#feedback')).toHaveText('请填写正确的邮箱或中国大陆手机号');
  await expect(page.locator('#login-email')).toHaveAttribute('aria-invalid','true');
  await page.locator('#login-email').fill(`nobody@${crypto.randomUUID()}.example.test`);
  await page.locator('#login-password').fill('wrong-password-123');
  await page.getByRole('button',{name:'登录',exact:true}).click();
  await expect(page.locator('#feedback')).toBeVisible();
  await expect(page.locator('#login-password')).toHaveAttribute('aria-invalid','true');
  await noOverflow(page,'登录错误');
  await snapshot(page,info,'account-login-error');

  // 注册：标签切换与字段。
  await page.getByRole('tab',{name:'注册',exact:true}).click();
  await expect(page.getByRole('heading',{name:'创建你的账号'})).toBeVisible();
  await expect(page.locator('#feedback')).toBeHidden();
  await expect(page.locator('#register-code')).toBeVisible();
  await noOverflow(page,'注册');
  await snapshot(page,info,'account-register');
  await page.locator('#register-email').fill(`a@${crypto.randomUUID()}.example.test`);
  await page.locator('#register-password').fill('test-password-123');
  await page.locator('#register-confirm').fill('another-password-123');
  await page.locator('#register-code').fill('000000');
  await page.getByRole('button',{name:'注册并登录',exact:true}).click();
  await expect(page.locator('#feedback')).toHaveText('两次输入的密码不一致');

  // 找回密码：标签隐藏，人机验证始终显示；可以返回登录。
  await page.getByRole('tab',{name:'登录',exact:true}).click();
  await page.getByRole('button',{name:'忘记密码？'}).click();
  await expect(page.getByRole('heading',{name:'找回密码'})).toBeVisible();
  await expect(page.locator('#tabs')).toBeHidden();
  await expect(page.locator('#reset-panel [data-captcha]')).toBeVisible();
  await noOverflow(page,'找回密码');
  await snapshot(page,info,'account-reset');
  await page.getByRole('button',{name:'返回登录'}).click();
  await expect(page.getByRole('heading',{name:'欢迎回来'})).toBeVisible();

  expect(await page.evaluate(()=>window.__cspViolations)).toEqual([]);
  expect(errors).toEqual([]);
});

test('account home: entries, balance, devices, storage, bell and logout',async({page},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立本机 Compose');
  test.setTimeout(120000);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(violations);
  await page.goto('/');
  await page.getByRole('tab',{name:'注册',exact:true}).click();
  const email=await registerByForm(page,'hub-home','首页验收');
  await expect(page).toHaveURL(/\/practice\//);

  await page.goto('/');
  await expect(page.getByRole('heading',{name:'欢迎回来，首页验收'})).toBeVisible();
  await expect(page.locator('#identity')).toHaveText(`已登录：首页验收（${email}）`);
  await expect(page.locator('#account-avatar')).toHaveText('首');
  for(const [name,href] of [['我的题库','/library/'],['进入练习空间','/practice/'],['题库广场','/plaza/'],['发布题库 / 我的发布','/publish/'],['下载软件','/download/']]) {
    await expect(page.locator('.entry-grid').getByRole('link',{name,exact:true})).toHaveAttribute('href',href);
  }
  await expect(page.locator('#admin-link')).toBeHidden();
  await expect(page.locator('#ai-balance')).toHaveText('¥1.00');
  await page.locator('#ai-ledger-section summary').click();
  await expect(page.locator('#ai-ledger li')).toContainText('默认额度');
  await expect(page.locator('.quota-number')).toHaveText('0 B / 256.00 MiB');
  await expect(page.locator('#device-empty')).toBeVisible();
  await expect(page.locator('#contact-summary')).toHaveText('绑定手机号');
  await expect(page.locator('.qf-bell')).toBeVisible();
  await page.locator('.qf-bell').click();
  await expect(page.locator('.qf-notice-panel')).toBeVisible();
  await noOverflow(page,'通知面板');
  await page.locator('.qf-notice-close').click();
  await noOverflow(page,'登录后的首页');
  await snapshot(page,info,'account-home');
  await page.locator('#contact-section summary').click();
  await expect(page.locator('#contact-form')).toBeVisible();
  await noOverflow(page,'绑定联系方式');

  // 管理员多一个后台入口。
  promote(email);
  await page.reload();
  await expect(page.locator('.entry-grid').getByRole('link',{name:'管理后台',exact:true})).toHaveAttribute('href','/admin/');

  await page.getByRole('button',{name:'退出登录',exact:true}).click();
  await expect(page.getByRole('tab',{name:'登录',exact:true})).toBeVisible();
  await expect(page.locator('#signed-in-view')).toBeHidden();
  await expect(page.locator('#notification-slot')).toBeHidden();
  expect(await page.evaluate(()=>window.__cspViolations)).toEqual([]);
  expect(errors).toEqual([]);
});

test('my library: public, private and favorites sections, statuses, per-bank import and publish',async({page},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立本机 Compose');
  test.setTimeout(240000);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(violations);
  await registerByApi(page,'hub-library','我的题库改版');
  await page.goto('/library/');
  await expect(page.locator('#public-packs')).toContainText('还没有公开题库');
  await expect(page.locator('#private-packs')).toContainText('还没有题库包，先上传一本吧。');
  await expect(page.locator('#favorites')).toContainText('还没有收藏');
  await noOverflow(page,'我的题库（空）');
  await snapshot(page,info,'library-empty');
  // 手机上导航收进菜单。
  if(info.project.name==='mobile') {
    await expect(page.getByRole('link',{name:'题库广场'})).toBeHidden();
    await page.getByRole('button',{name:'展开导航'}).click();
    await expect(page.getByRole('link',{name:'题库广场'})).toBeVisible();
    await page.getByRole('button',{name:'展开导航'}).click();
  }

  // 未通过：显示状态与原因，可以在原条目重新上传。
  const rejected=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/library/uploads');
  await page.locator('#upload-file').setInputFiles({name:'bad.qbank',mimeType:'application/octet-stream',buffer:Buffer.from('invalid')});
  expect((await rejected).status()).toBe(202);
  const badCard=page.locator('#private-packs [data-pack-id]').first();
  await expect(badCard).toBeVisible();
  await expect(page.locator('#notice')).toContainText('原文件已保存');
  await expect(badCard).toContainText('已审核 · 未通过',{timeout:60000});
  await expect(badCard.locator('.reject-note')).toBeVisible();
  await expect(page.locator('#detail [data-revision="1"] .reject-note')).toBeVisible();
  await expect(page.getByRole('button',{name:'在此条目重新上传'})).toBeVisible();
  await expect(page.locator('#detail [data-revision="1"]').getByRole('button',{name:'导入练习空间',exact:true})).toBeDisabled();
  await noOverflow(page,'未通过');
  await page.getByRole('button',{name:'收起'}).click();
  await expect(page.locator('#detail')).toBeHidden();

  // 待审核 → 通过：按本选择导入。
  const accepted=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/library/uploads');
  await page.locator('#upload-file').setInputFiles(resolve('../samples/basic-types.qbank'));
  expect((await accepted).status()).toBe(202);
  const card=page.locator('#private-packs [data-pack-id]').filter({hasNotText:'未通过'}).first();
  await expect(card).toBeVisible();
  const id=await card.getAttribute('data-pack-id');
  await expect.poll(async()=>(await (await page.request.get(`/api/library/packs/${id}`)).json()).revisions[0].banks.length,{timeout:60000}).toBeGreaterThan(0);
  await snapshot(page,info,'library-pending');
  approvePackage(id);
  await page.reload();
  const approved=page.locator(`[data-pack-id="${id}"]`);
  await expect(approved).toContainText('已审核 · 通过',{timeout:60000});
  await approved.getByRole('button').click();
  const revision=page.locator('#detail [data-revision="1"]');
  await expect(revision.locator('.bank-option')).toHaveCount(5);
  await expect(revision).not.toContainText('null');
  await expect(revision.locator('.sel-sum')).toHaveText('已选 5 / 5 本');
  await revision.getByRole('checkbox',{name:'全选'}).uncheck();
  await expect(revision.locator('.sel-sum')).toHaveText('已选 0 / 5 本');
  await revision.locator('input[name=banks]').nth(1).check();
  await revision.locator('input[name=banks]').nth(3).check();
  await expect(revision.locator('.sel-sum')).toHaveText('已选 2 / 5 本');
  const imported=page.waitForResponse(response=>new URL(response.url()).pathname===`/api/library/packs/${id}/practice`);
  await revision.getByRole('button',{name:'导入练习空间',exact:true}).click();
  const response=await imported;
  expect(response.status()).toBe(200);
  expect((await response.json()).collections).toHaveLength(2);
  await expect(page.locator('#notice')).toContainText('已创建 2 本独立练习');
  await noOverflow(page,'题库详情');
  await snapshot(page,info,'library-detail');

  // 发布到广场：表单无溢出，发布后出现在公开分区。
  await revision.getByRole('button',{name:'直接发布到广场',exact:true}).click();
  const publish=page.locator('[data-publish-form]');
  await publish.locator('[name=title]').fill('改版公开题库');
  await publish.locator('[name=summary]').fill('浏览器合成样例');
  await publish.locator('[name=description]').fill('仅用于本机验证原文件发布');
  await publish.locator('[name=rights]').check();
  await noOverflow(page,'发布表单');
  await snapshot(page,info,'library-publish');
  await publish.getByRole('button',{name:'用原文件发布',exact:true}).click();
  await expect(page.locator('#notice')).toContainText('公开版本仍需管理员审核');
  await expect(page.locator('#public-packs')).toContainText('改版公开题库');
  await expect(page.locator('#public-packs')).toContainText('我的公开发布');
  await expect(page.locator('#public-count')).toHaveText('共 1 个');
  await noOverflow(page,'三个分区');
  await page.getByRole('button',{name:'收起'}).click();
  await snapshot(page,info,'library-sections');

  // 删除资源：确认后从分区消失。
  await approved.getByRole('button').click();
  page.once('dialog',dialog=>dialog.accept());
  await page.getByRole('button',{name:'删除此资源',exact:true}).click();
  await expect(page.locator(`[data-pack-id="${id}"]`)).toHaveCount(0);
  await expect(page.locator('#notice')).toContainText('资源已删除');

  expect(await page.evaluate(()=>window.__cspViolations)).toEqual([]);
  expect(errors).toEqual([]);
});

test('long text, devices and favorite states stay inside the layout',async({page},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立本机 Compose');
  test.setTimeout(90000);
  const long='很长的名称'.repeat(30);
  const json=(route,value)=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(value)});
  await registerByApi(page,'hub-long','长文本验收');
  await page.route('**/api/connect/devices',route=>json(route,{devices:[
    {id:'d1',name:'示例电脑',client:'web',created:'2026-10-20T08:00:00Z',lastUsed:'2026-11-07T08:00:00Z'},
    {id:'d2',name:long.slice(0,40),client:'android',created:'2026-10-22T08:00:00Z',lastUsed:'2026-11-06T08:00:00Z'},
  ]}));
  await page.goto('/');
  await expect(page.locator('#device-list .device')).toHaveCount(2);
  await expect(page.locator('#device-list .device').nth(1).getByRole('button',{name:'撤销'})).toBeVisible();
  await noOverflow(page,'设备列表');
  await snapshot(page,info,'account-home-devices');

  await page.route('**/api/library/packs',route=>json(route,{items:[
    {id:'a1',title:long,visibility:'public',status:'approved',reason:null,revision:2,originalAvailable:true},
    {id:'a2',title:'等待后台校验',visibility:'public',status:'pending',reason:null,revision:1,originalAvailable:true},
    {id:'a3',title:'历史导入条目',visibility:'private',status:'approved',reason:null,revision:1,originalAvailable:false},
    {id:'a4',title:'未通过的题库',visibility:'private',status:'rejected',reason:long,revision:1,originalAvailable:true},
  ]}));
  await page.route('**/api/library/uploads/*/revisions/*',route=>json(route,{status:'pending',reason:null}));
  await page.route('**/api/library/favorites',route=>json(route,{next:null,items:[
    {id:'f1',title:long,status:'approved',revision:3,seenRevision:2,releaseNotes:long},
    {id:'f2',title:'已被作者删除的题库',status:'deleted',revision:1,seenRevision:1,releaseNotes:null},
    {id:'f3',title:'普通收藏',status:'approved',revision:1,seenRevision:1,releaseNotes:null},
  ]}));
  await page.goto('/library/');
  await expect(page.locator('#public-packs [data-pack-id]')).toHaveCount(2);
  await expect(page.locator('#private-packs [data-pack-id]')).toHaveCount(2);
  await expect(page.locator('[data-pack-id="a2"] [data-status]')).toHaveText('待审核');
  await expect(page.locator('[data-pack-id="a3"]')).toContainText('原文件不可用');
  await expect(page.locator('[data-pack-id="a4"] .reject-note')).toBeVisible();
  await expect(page.locator('[data-favorite="f1"] [data-favorite-update="f1"]')).toContainText('有新版本：第 3 版');
  await expect(page.locator('[data-favorite="f1"]').getByRole('link',{name:'下载到本地'})).toBeVisible();
  await expect(page.locator('[data-favorite="f2"]')).toContainText('作者已删除');
  await noOverflow(page,'我的题库长文本');
  await snapshot(page,info,'library-states');
});
