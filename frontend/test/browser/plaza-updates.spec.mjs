import {test,expect as baseExpect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';
const expect=baseExpect.configure({timeout:30000});

function sql(query) {
  execFileSync('docker',['compose','-p',process.env.QF_COMPOSE_PROJECT,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}

// 题库广场 3/4：作者发布新版本 → 审核通过 → 用户收到通知 → 打开题库看到提示 → 完成练习后再练一次 → 题目变为新版本。
// basic-types 与 central-outline 中的“简答题”UUID 相同：第 2 版的简答题从 1 道题变为 3 道题。
test('a new approved version notifies learners and replaces the bank after they restart',async({page,browser},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','Requires an explicitly isolated Compose fixture');
  test.setTimeout(300000);
  if(!process.env.QF_COMPOSE_PROJECT?.startsWith('qf-'))throw Error('Use an isolated qf-* Compose project');
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());
  const title=`版本更新测试 ${info.project.name}-${crypto.randomUUID().slice(0,8)}`;
  const notes=`第二版：简答题扩充为 3 道题（${crypto.randomUUID().slice(0,6)}）`;

  // 准备：作者发布第 1 版，管理员审核通过（接口完成，非本测试的关注点）。
  const author=await registerByApi(page,'version-author','版本作者');
  const published=await page.request.post('/api/catalog/packs',{headers:{Origin:author.origin},multipart:{
    title,summary:'版本更新测试',description:'用于题库版本自动切换的浏览器测试。',category:'其他',tags:'测试',rights:'yes',
    archive:{name:'basic-types.qbank',mimeType:'application/octet-stream',buffer:await readFile(resolve('../samples/basic-types.qbank'))}}});
  expect(published.status(),await published.text()).toBe(201);
  const {packId}=await published.json();

  const adminContext=await browser.newContext({viewport:page.viewportSize()});
  const adminPage=await adminContext.newPage();
  adminPage.on('pageerror',error=>errors.push(error.message));
  adminPage.on('dialog',dialog=>dialog.accept());
  const admin=await registerByApi(adminPage,'version-admin','版本审核员');
  sql(`UPDATE account_user SET is_admin = TRUE WHERE email = '${admin.email.replace(/'/g,"''")}';`);
  const waitForReview=async revision=>expect.poll(async()=>{
    const {items}=await (await adminPage.request.get('/api/admin/catalog/reviews')).json();
    return items.some(item=>item.packId===packId&&item.revision===revision);
  },{timeout:120000}).toBe(true);
  await waitForReview(1);
  const detail=await (await adminPage.request.get(`/api/admin/catalog/packs/${packId}/revisions/1`)).json();
  for(const extension of detail.extensions.filter(row=>row.status!=='approved')){
    expect((await adminPage.request.post(`/api/admin/extensions/${extension.digest}/approve`,{headers:{Origin:admin.origin}})).status()).toBe(200);
  }
  expect((await adminPage.request.post(`/api/admin/catalog/packs/${packId}/revisions/1/approve`,{headers:{Origin:admin.origin}})).status()).toBe(204);

  // 学习者从广场导入“简答题”，在练习空间作答并提交（有进行中的作答）。
  const learnerContext=await browser.newContext({viewport:page.viewportSize()});
  const learner=await learnerContext.newPage();
  learner.on('pageerror',error=>errors.push(error.message));
  learner.on('dialog',dialog=>dialog.accept());
  const account=await registerByApi(learner,'version-learner','版本学习者');
  const plaza=await (await learner.request.get(`/api/plaza/packs/${packId}`)).json();
  const bank=plaza.banks.find(row=>row.title==='简答题');
  const imported=await learner.request.post(`/api/plaza/packs/${packId}/imports`,{headers:{Origin:account.origin},data:{banks:[bank.uuid]}});
  expect(imported.status(),await imported.text()).toBe(200);
  const collection=(await imported.json()).collections[0].id;
  const open=async()=>{
    await learner.goto('/practice/');
    await expect(learner.locator('#collection-loading')).toBeHidden();
    if(await learner.locator('#library-toggle').getAttribute('aria-expanded')!=='true')await learner.locator('#library-toggle').click();
    await learner.locator(`.library-item[data-item-id="${collection}"]`).click();
    await expect(learner.locator('#collection-loading')).toBeHidden();
    await expect(learner.locator('.question-pane:not(.collection-candidate):not([hidden]):not([inert]) iframe.question-frame')).toBeVisible();
  };
  await open();
  await expect(learner.locator('#progress-text')).toHaveText('第 1 题 / 共 1 题');
  await expect(learner.locator('#collection-update')).toBeHidden();
  const frame=learner.frameLocator('.question-pane:not([hidden]):not([inert]) iframe.question-frame');
  await frame.locator('.answer-field').click();
  await frame.locator('[contenteditable="true"]').fill('缓存可以减少重复计算，需要维护数据一致性。');
  const submitted=learner.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/actions')&&response.request().postDataJSON().action==='submit');
  await frame.getByRole('button',{name:'提交',exact:true}).click();
  expect((await submitted).status()).toBe(200);

  // 作者在“我的发布”上传整包新版本，更新说明为必填。
  await page.goto('/publish/#mine');
  const card=page.locator('.pack-item').filter({hasText:title});
  await card.getByRole('button',{name:'上传新版本'}).click();
  const form=card.locator('form.revision-form');
  await form.locator('input[name="archive"]').setInputFiles(resolve('../samples/central-outline.qbank'));
  await form.locator('input[name="rights"]').check();
  await form.getByRole('button',{name:'提交新版本'}).click();
  await expect(page.locator('#publish-feedback')).toHaveText('请填写更新说明');
  await form.locator('textarea[name="releaseNotes"]').fill(notes);
  await form.getByRole('button',{name:'提交新版本'}).click();
  await expect(page.locator('#publish-notice')).toContainText('第 2 版已提交审核',{timeout:90000});

  // 管理员在后台审核通过第 2 版。
  await waitForReview(2);
  await adminPage.goto(`/admin/#pack-reviews/${packId}~2`);
  await expect(adminPage.getByRole('heading',{name:title})).toBeVisible();
  const approve=adminPage.locator('section[data-section="decision"]').getByRole('button',{name:'审核通过'});
  const extensionButtons=adminPage.getByRole('button',{name:'批准拓展'});
  for(let remaining=await extensionButtons.count();remaining;remaining--){
    await extensionButtons.first().click();
    await expect(extensionButtons).toHaveCount(remaining-1);
  }
  await approve.click();
  await expect(adminPage.locator('#admin-toast')).toHaveText('已通过，已通知发布者');

  // 学习者收到题库更新通知（内容含更新说明），打开题库看到“有新版本”的提示，题目仍是第 1 版。
  await open();
  await expect(learner.locator('.qf-bell-dot')).toBeVisible();
  const list=await (await learner.request.get('/api/notifications')).json();
  const update=list.items.find(item=>item.title.includes('已更新到第 2 版'));
  expect(update,JSON.stringify(list.items)).toBeTruthy();
  expect((await (await learner.request.get(`/api/notifications/${update.id}`)).json()).body).toContain(notes);
  await expect(learner.locator(`.library-item[data-item-id="${collection}"] .update-badge`)).toHaveText('有新版本');
  await expect(learner.locator('#collection-update')).toContainText('再练一次');
  await expect(learner.locator('#progress-text')).toHaveText('第 1 题 / 共 1 题');

  // 完成练习后再练一次：作答清空并切换到第 2 版。
  await learner.locator('#next').click();
  await expect(learner.locator('.practice-summary-card')).toBeVisible();
  const finishing=learner.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/finish'));
  await learner.getByRole('button',{name:'完成练习',exact:true}).click();expect((await finishing).status()).toBe(202);
  await expect(learner.getByRole('button',{name:'再练一次',exact:true})).toBeEnabled();
  const restarted=learner.waitForResponse(response=>new URL(response.url()).pathname.endsWith('/restart'));
  await learner.getByRole('button',{name:'再练一次',exact:true}).click();expect((await restarted).status()).toBe(202);
  await expect(learner.locator('#progress-text')).toHaveText('第 1 题 / 共 3 题');
  await expect(learner.locator('#collection-update')).toBeHidden();
  await open();
  await expect(learner.locator('#progress-text')).toHaveText('第 1 题 / 共 3 题');
  await expect(learner.locator(`.library-item[data-item-id="${collection}"] .update-badge`)).toHaveCount(0);

  expect(errors).toEqual([]);
  await adminContext.close();await learnerContext.close();
});
