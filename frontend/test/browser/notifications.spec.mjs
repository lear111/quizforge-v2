import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';

function promote(email) {
  const query=`UPDATE account_user SET is_admin = TRUE WHERE email = '${email.replace(/'/g,"''")}';`;
  execFileSync('docker',['compose','-p',process.env.QF_COMPOSE_PROJECT,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}

test('admins publish notifications; the bell shows new ones until the list is opened',async({page,browser},info)=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());
  const admin=await registerByApi(page,'notice-admin','通知管理员');
  promote(admin.email);
  const suffix=`${info.project.name}-${crypto.randomUUID().slice(0,8)}`;

  // 管理员发布一条置顶的联系方式和一条普通公告。
  await page.goto('/admin/#notifications');
  const form=page.locator('form[data-action="publish-notification"]');
  await form.locator('input[name="title"]').fill(`联系管理员 ${suffix}`);
  await form.locator('textarea[name="body"]').fill('有问题请发邮件。\n帮助页：https://example.test/help');
  await form.locator('input[name="pinned"]').check();
  await form.getByRole('button',{name:'发布'}).click();
  await expect(page.locator('#admin-toast')).toHaveText('通知已发布');
  await form.locator('input[name="title"]').fill(`维护公告 ${suffix}`);
  await form.locator('textarea[name="body"]').fill('<b>不是 HTML</b>');
  await form.getByRole('button',{name:'发布'}).click();
  await expect(page.locator('.qa-notice').first()).toContainText('维护公告');

  // 成员在练习空间看到红点；打开通知界面后红点消失，未查看的条目各自带红点。
  const memberContext=await browser.newContext({viewport:page.viewportSize()});
  const member=await memberContext.newPage();
  member.on('pageerror',error=>errors.push(error.message));
  await registerByApi(member,'notice-member','通知成员');
  await member.goto('/practice/');
  const bell=member.locator('.qf-bell');
  await expect(member.locator('.qf-bell-dot')).toBeVisible();
  await bell.click();
  await expect(member.locator('.qf-bell-dot')).toBeHidden();
  const items=member.locator('.qf-notice');
  await expect(items.first()).toContainText(`联系管理员 ${suffix}`);
  await expect(items.first().locator('.qf-notice-pin')).toHaveText('置顶');
  const contact=items.filter({hasText:`联系管理员 ${suffix}`});
  await expect(contact.locator('.qf-notice-unread')).toBeVisible();
  await contact.locator('.qf-notice-head').click();
  await expect(contact.locator('.qf-notice-body a')).toHaveAttribute('href','https://example.test/help');
  await expect(contact.locator('.qf-notice-body')).toContainText('有问题请发邮件。');
  await expect(contact.locator('.qf-notice-unread')).toBeHidden();
  const maintenance=items.filter({hasText:`维护公告 ${suffix}`});
  await expect(maintenance.locator('.qf-notice-unread')).toBeVisible();
  await maintenance.locator('.qf-notice-head').click();
  await expect(maintenance.locator('.qf-notice-body')).toHaveText('<b>不是 HTML</b>');
  await expect(maintenance.locator('.qf-notice-body b')).toHaveCount(0);

  // 刷新后红点不再出现；管理员删除公告后成员看不到它。
  await member.reload();
  await expect(member.locator('.qf-bell')).toBeVisible();
  await expect(member.locator('.qf-bell-dot')).toBeHidden();
  await page.locator('.qa-notice').filter({hasText:`维护公告 ${suffix}`}).getByRole('button',{name:'删除'}).click();
  await expect(page.locator('#admin-toast')).toHaveText('已删除');
  await member.locator('.qf-bell').click();
  await expect(member.locator('.qf-notice').filter({hasText:`联系管理员 ${suffix}`})).toHaveCount(1);
  await expect(member.locator('.qf-notice').filter({hasText:`维护公告 ${suffix}`})).toHaveCount(0);

  // 后台顶栏同样有铃铛。
  await expect(page.locator('#admin-bell .qf-bell')).toBeVisible();
  await memberContext.close();
  expect(errors).toEqual([]);
});
