import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';

// 管理员只能通过服务器命令行设置；测试直接改测试库中的标记。
function promote(email) {
  const project=process.env.QF_COMPOSE_PROJECT;
  const query=`UPDATE account_user SET is_admin = TRUE WHERE email = '${email.replace(/'/g,"''")}';`;
  execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}

test('administrators manage users, invitations and see the audit log; others see nothing',async({page,browser})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  const admin=await registerByApi(page,'admin','后台管理员');
  promote(admin.email);

  const memberContext=await browser.newContext();
  const memberPage=await memberContext.newPage();
  const member=await registerByApi(memberPage,'member','普通成员');

  // 非管理员：只显示说明，不渲染导航与后台内容。
  await memberPage.goto('/admin/');
  await expect(memberPage.getByRole('heading',{name:'无权访问'})).toBeVisible();
  await expect(memberPage.locator('#admin-nav')).toBeHidden();
  await expect(memberPage.locator('.qa-table')).toHaveCount(0);

  await page.goto('/admin/');
  await expect(page.locator('#admin-nav .qa-nav')).toHaveText(['用户管理','登录与注册设置','邀请码','操作日志']);
  await expect(page.getByRole('heading',{name:'用户管理'})).toBeVisible();

  // 搜索并打开成员详情。
  await page.getByRole('searchbox',{name:'搜索用户'}).fill(member.email.split('@')[1]);
  await page.getByRole('button',{name:'搜索',exact:true}).click();
  await page.getByRole('link',{name:'普通成员'}).click();
  await expect(page.getByRole('heading',{name:'普通成员'})).toBeVisible();
  await expect(page.locator('.qa-facts')).toContainText(member.email);

  // 封禁：需要填写原因；成员的会话立即失效。
  page.on('dialog',dialog=>dialog.accept());
  await page.locator('form[data-action="block"] textarea').fill('浏览器测试封禁');
  await page.locator('form[data-action="block"] button[type="submit"]').click();
  await expect(page.locator('#admin-toast')).toHaveText('已封禁');
  await expect(page.getByRole('heading',{name:'普通成员'}).locator('.qa-badge')).toHaveText('已封禁');
  expect((await memberPage.request.get('/api/me')).status()).toBe(401);

  // 调整存储额度。
  await page.locator('form[data-action="quota"] input').fill('5');
  await page.locator('form[data-action="quota"] button[type="submit"]').click();
  await expect(page.locator('#admin-toast')).toHaveText('存储额度已更新');
  await expect(page.locator('.qa-facts')).toContainText('5.00 MiB（单独设置）');

  // 邀请码：完整码只在本次显示。
  await page.locator('#admin-nav').getByRole('link',{name:'邀请码'}).click();
  await page.locator('form[data-action="create-invitations"] input[name="count"]').fill('2');
  await page.getByRole('button',{name:'生成',exact:true}).click();
  await expect(page.getByRole('textbox',{name:'本次邀请码'})).toHaveValue(/^QF-[A-F0-9-]+\nQF-[A-F0-9-]+$/);

  // 登录与注册设置：只读取，不修改（避免影响其他浏览器测试的注册）。
  await page.locator('#admin-nav').getByRole('link',{name:'登录与注册设置'}).click();
  await expect(page.getByLabel('注册需要邮箱 / 短信验证码')).toBeChecked();

  // 操作日志记录了以上写操作。
  await page.locator('#admin-nav').getByRole('link',{name:'操作日志'}).click();
  for(const action of ['封禁用户','调整存储额度','生成邀请码'])await expect(page.locator('.qa-table').getByText(action).first()).toBeVisible();

  await memberContext.close();
  expect(errors).toEqual([]);
});
