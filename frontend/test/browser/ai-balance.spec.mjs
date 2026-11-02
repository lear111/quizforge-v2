import {test,expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';

function promote(email) {
  const project=process.env.QF_COMPOSE_PROJECT;
  const query=`UPDATE account_user SET is_admin = TRUE WHERE email = '${email.replace(/'/g,"''")}';`;
  execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}

test('AI balance: default credit, admin codes and adjustments, redeem on the account page',async({page,browser})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());
  const admin=await registerByApi(page,'ai-admin','AI 管理员');
  promote(admin.email);

  const memberContext=await browser.newContext();
  const memberPage=await memberContext.newPage();
  memberPage.on('pageerror',error=>errors.push(error.message));
  const member=await registerByApi(memberPage,'ai-member','AI 成员');

  // 账号页：注册即获得 1 元默认额度。
  await memberPage.goto('/');
  await expect(memberPage.locator('#ai-balance')).toHaveText('¥1.00');
  await expect(memberPage.locator('#ai-overdrawn')).toBeHidden();
  await memberPage.locator('#ai-ledger-section summary').click();
  await expect(memberPage.locator('#ai-ledger li')).toHaveCount(1);
  await expect(memberPage.locator('#ai-ledger li').first()).toContainText('默认额度');

  // 管理员生成兑换码：完整码只在本次显示。
  await page.goto('/admin/#ai-codes');
  await expect(page.getByRole('heading',{name:'AI 兑换码'})).toBeVisible();
  const form=page.locator('form[data-action="create-ai-codes"]');
  await form.locator('input[name="count"]').fill('2');
  await form.locator('input[name="amount"]').fill('5');
  // 兑换码对所有管理员可见，多次运行会累积：用本次唯一的备注筛选。
  const note=`浏览器测试 ${crypto.randomUUID().slice(0,8)}`;
  const rows=page.locator('tr[data-code-id]').filter({hasText:note});
  await form.locator('input[name="note"]').fill(note);
  await form.locator('button[type="submit"]').click();
  const codes=(await page.getByRole('textbox',{name:'本次兑换码'}).inputValue()).split('\n');
  expect(codes).toHaveLength(2);
  for(const code of codes)expect(code).toMatch(/^AI-[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}$/);
  await expect(rows).toHaveCount(2);
  await expect(page.locator('tbody')).not.toContainText(codes[0].slice(8));

  // 成员在账号页兑换（大小写与连字符不敏感）。
  await memberPage.locator('#redeem-code').fill(codes[0].toLowerCase().replaceAll('-',' '));
  await memberPage.locator('#redeem-form button[type="submit"]').click();
  await expect(memberPage.locator('#ai-status')).toHaveText('兑换成功，已增加 ¥5.00。');
  await expect(memberPage.locator('#ai-balance')).toHaveText('¥6.00');
  await memberPage.locator('#redeem-code').fill(codes[0]);
  await memberPage.locator('#redeem-form button[type="submit"]').click();
  await expect(memberPage.locator('#ai-status')).toHaveText('兑换码无效、已使用或已过期');

  // 停用第二个码。
  await page.reload();
  await rows.filter({hasText:'可用'}).getByRole('button',{name:'停用'}).click();
  await expect(page.locator('#admin-toast')).toHaveText('已停用');
  await expect(rows.filter({hasText:'已停用'})).toHaveCount(1);
  await expect(rows.filter({hasText:'已兑换'})).toContainText('AI 成员');

  // 管理员扣减到负数：账号页显示欠费。
  await page.goto('/admin/#users');
  await page.getByRole('searchbox',{name:'搜索用户'}).fill(member.email.split('@')[1]);
  await page.getByRole('button',{name:'搜索',exact:true}).click();
  await page.getByRole('link',{name:'AI 成员'}).click();
  const ai=page.locator('section[data-section="ai"]');
  await expect(ai).toContainText('当前余额 ¥6.00');
  await ai.locator('input[name="delta"]').fill('-7.5');
  await ai.locator('button[type="submit"]').click();
  await expect(page.locator('#admin-toast')).toHaveText('请填写调整原因');
  await ai.locator('textarea[name="reason"]').fill('浏览器测试扣减');
  await ai.locator('button[type="submit"]').click();
  await expect(page.locator('#admin-toast')).toHaveText('余额已调整');
  await expect(page.locator('section[data-section="ai"]')).toContainText('当前余额 -¥1.50');
  await expect(page.locator('section[data-section="ai"] .qa-badge')).toHaveText('欠费');

  await memberPage.reload();
  await expect(memberPage.locator('#ai-balance')).toHaveText('-¥1.50');
  await expect(memberPage.locator('#ai-overdrawn')).toBeVisible();
  await memberPage.locator('#ai-ledger-section summary').click();
  await expect(memberPage.locator('#ai-ledger li').first()).toContainText('管理员调整：浏览器测试扣减');
  await expect(memberPage.locator('#ai-ledger li')).toHaveCount(3);

  // 操作日志中显示中文的操作名。
  await page.goto('/admin/#audit');
  for(const label of ['调整 AI 余额','生成 AI 兑换码','停用 AI 兑换码'])await expect(page.locator('tbody')).toContainText(label);

  // 非管理员不能访问兑换码接口。
  expect((await memberPage.request.get('/api/admin/ai-codes')).status()).toBe(403);
  expect(errors).toEqual([]);
  await memberContext.close();
});
