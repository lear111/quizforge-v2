import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';

function promote(email) {
  const query=`UPDATE account_user SET is_admin = TRUE WHERE email = '${email.replace(/'/g,"''")}';`;
  execFileSync('docker',['compose','-p',process.env.QF_COMPOSE_PROJECT,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}

const PNG=Buffer.from('89504e470d0a1a0a0000000d4948445200000001000000010806000000'+'1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082','hex');

test('publish → admin approves → publisher is notified → new version → rejected with a reason',async({page,browser},info)=>{
  // 发布要经过完整导入（规则服务校验），整个流程比其他测试长。
  test.setTimeout(240000);
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());
  const suffix=`${info.project.name}-${crypto.randomUUID().slice(0,8)}`;
  const title=`浏览器测试题库 ${suffix}`;
  await registerByApi(page,'publisher','发布者');

  // 发布者从本地上传 .qbank，填写广场信息与截图，提交审核。
  await page.goto('/publish/');
  await expect(page.getByRole('heading',{name:'发布题库'})).toBeVisible();
  await page.locator('#pack-archive').setInputFiles(resolve('../samples/basic-types.qbank'));
  await page.locator('#pack-title').fill(title);
  await page.locator('#pack-summary').fill('五种基本题型');
  await page.locator('#pack-description').fill('用于浏览器测试的题库。\n第二段。');
  await page.locator('#pack-category').selectOption('考试认证');
  await page.locator('#pack-tags').fill('测试，基础');
  await page.locator('#pack-screenshots').setInputFiles({name:'cover.png',mimeType:'image/png',buffer:PNG});
  await page.locator('#publish-form input[name="rights"]').check();
  await page.getByRole('button',{name:'提交审核'}).click();
  await expect(page.locator('#publish-notice')).toContainText('已提交审核（第 1 版）',{timeout:90000});
  const pack=page.locator('.pack-item').filter({hasText:title});
  await expect(pack.locator('li[data-revision="1"]')).toContainText('审核中');

  // 管理员在后台审核：先批准未审核的拓展，再通过该版本。
  const adminContext=await browser.newContext({viewport:page.viewportSize()});
  const adminPage=await adminContext.newPage();
  adminPage.on('pageerror',error=>errors.push(error.message));
  adminPage.on('dialog',dialog=>dialog.accept());
  const admin=await registerByApi(adminPage,'reviewer','审核员');
  promote(admin.email);
  await adminPage.goto('/admin/#pack-reviews');
  await expect(adminPage.getByRole('heading',{name:'题库审核'})).toBeVisible();
  await adminPage.getByRole('link',{name:title}).click();
  await expect(adminPage.getByRole('heading',{name:title})).toBeVisible();
  await expect(adminPage.locator('.qa-shots img')).toHaveCount(1);
  const approveButton=adminPage.locator('section[data-section="decision"]').getByRole('button',{name:'审核通过'});
  const extensionButtons=adminPage.getByRole('button',{name:'批准拓展'});
  let remaining=await extensionButtons.count();
  if(remaining)await expect(approveButton).toBeDisabled();
  // 每次批准后页面重新渲染：以剩余按钮数减少为准（提示文字与上一次相同，不能作为完成信号）。
  while(remaining) {
    await extensionButtons.first().click();
    await expect(extensionButtons).toHaveCount(remaining-1);
    remaining--;
  }
  await expect(approveButton).toBeEnabled();
  await approveButton.click();
  await expect(adminPage.locator('#admin-toast')).toHaveText('已通过，已通知发布者');
  await expect(adminPage.locator('h1 .qa-badge').filter({hasText:'已通过'})).toBeVisible();

  // 发布者收到站内通知，“我的发布”显示公开的版本。
  const notes=await (await page.request.get('/api/notifications')).json();
  // 置顶通知排在前面（其他测试可能发布过），按标题查找。
  expect(notes.items.map((item)=>item.title)).toContain(`题库「${title}」第 1 版审核通过`);
  await page.reload();
  await expect(pack.locator('.badge.public')).toHaveText('广场公开第 1 版');
  await expect(pack.locator('li[data-revision="1"]')).toContainText('已通过');

  // 上传整包新版本，沿用原截图。
  await pack.getByRole('button',{name:'上传新版本'}).click();
  const form=pack.locator('form.revision-form');
  await form.locator('input[name="archive"]').setInputFiles(resolve('../samples/central-outline.qbank'));
  await form.locator('textarea[name="releaseNotes"]').fill('换成大纲示例');
  await expect(form.locator('input[name="keepScreenshots"]')).toBeChecked();
  await form.locator('input[name="rights"]').check();
  await form.getByRole('button',{name:'提交新版本'}).click();
  await expect(page.locator('#publish-notice')).toContainText('第 2 版已提交审核',{timeout:90000});
  await expect(pack.locator('li[data-revision="2"]')).toContainText('审核中');
  await expect(pack.locator('.badge.public')).toHaveText('广场公开第 1 版');

  // 管理员拒绝第 2 版，必须填写原因；差异按 bank.json 的 UUID 计算。
  await adminPage.goto('/admin/#pack-reviews');
  await adminPage.locator('tr').filter({hasText:title}).filter({hasText:'第 2 版'}).getByRole('link').click();
  // central-outline 中的简答题与 basic-types 的简答题 UUID 相同：按 UUID 对应为“更新”，其余四本为“移除”。
  await expect(adminPage.locator('tr[data-bank-uuid="60000000-0000-4000-8000-000000000004"]')).toContainText('更新');
  await expect(adminPage.locator('tr[data-bank-uuid]').filter({hasText:'移除'})).toHaveCount(4);
  const reject=adminPage.locator('form[data-action="reject-revision"]');
  await reject.getByRole('button',{name:'拒绝'}).click();
  await expect(adminPage.locator('#admin-toast')).toHaveText('请填写拒绝原因');
  await reject.locator('textarea[name="reason"]').fill('第 3 题答案有误');
  await reject.getByRole('button',{name:'拒绝'}).click();
  await expect(adminPage.locator('#admin-toast')).toHaveText('已拒绝，已通知发布者');

  await page.reload();
  await expect(pack.locator('li[data-revision="2"]')).toContainText('未通过');
  await expect(pack.locator('li[data-revision="2"] .reject-reason')).toContainText('第 3 题答案有误');
  await expect(pack.locator('.badge.public')).toHaveText('广场公开第 1 版');
  const rejected=await (await page.request.get('/api/notifications')).json();
  expect(rejected.items.map((item)=>item.title)).toContain(`题库「${title}」第 2 版未通过审核`);

  expect(errors).toEqual([]);
  await adminContext.close();
});
