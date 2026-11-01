import {test,expect as baseExpect} from '@playwright/test';
import {mkdir,readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const expect=baseExpect.configure({timeout:30000});

test('account quota cards reflect real imports and deleting an owned bank releases space',async({page},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','Requires an explicitly isolated Compose fixture');
  test.setTimeout(120000);
  await page.goto('/');
  await page.getByRole('tab',{name:'注册',exact:true}).click();
  await page.locator('#register-email').fill(`quota-${crypto.randomUUID()}@example.test`);
  await page.locator('#register-name').fill('资源用量验收');
  await page.locator('#register-password').fill('test-password-123');
  await page.locator('#register-confirm').fill('test-password-123');
  await page.getByRole('button',{name:'注册并登录',exact:true}).click();
  await expect(page).toHaveURL(/\/practice\/$/);
  await page.goto('/');
  await expect(page.locator('.quota-card')).toHaveCount(1);
  await expect(page.locator('.quota-number').first()).toHaveText('0 B / 256.00 MiB');
  const response=await page.request.post('/api/library/import',{
    headers:{Origin:new URL(page.url()).origin},
    multipart:{file:{name:'basic-types.qbank',mimeType:'application/octet-stream',buffer:await readFile(resolve('../samples/basic-types.qbank'))}},
  });
  expect(response.status()).toBe(200);
  const {collections}=await response.json();expect(collections).toHaveLength(5);
  const usage=async()=>{const r=await page.request.get('/api/account/quota');expect(r.status()).toBe(200);return r.json();};
  const before=await usage();expect(before.storage.usedBytes).toBeGreaterThan(0);expect(before.dailyDownload.usedBytes).toBe(0);
  const catalog=await page.request.get('/api/catalog');expect(catalog.status()).toBe(200);
  const opensBank=(await catalog.json()).banks.some(bank=>!bank.error&&bank.availability?.available!==false);
  await page.locator('#quota-refresh').click();
  await expect(page.locator('.quota-number').first()).not.toHaveText('0 B / 256.00 MiB');
  await expect(page.locator('.quota-card')).toHaveCount(1);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  const screenshots=resolve(process.env.QF_SCREENSHOT_DIR||'../.artifacts/screenshots');await mkdir(screenshots,{recursive:true});
  await page.screenshot({path:resolve(screenshots,`quota-${info.project.name}.png`),fullPage:true});
  await page.getByRole('link',{name:'进入练习空间'}).click();
  await expect(page.locator('#library-list')).toContainText(collections[0].title);
  // Approved sample digests may already exist from another test. Initial bank loading
  // closes the mobile drawer, so wait for that transition before opening it ourselves.
  if(opensBank){
    await expect(page.locator('.question-pane:not([hidden]):not([inert]) iframe.question-frame')).toHaveCount(1);
    await expect(page.locator('#collection-loading')).toBeHidden();
  }
  if(await page.locator('#library-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#library-toggle').click();
  await expect(page.locator('.library')).toHaveAttribute('aria-hidden','false');
  const row=page.locator('.library-row').filter({has:page.locator(`.library-item[data-item-id="${collections[0].id}"]`)});
  await row.getByRole('button',{name:`编辑 ${collections[0].title}`,exact:true}).click();
  await page.locator('#library-manage-delete').click();
  await expect(page.locator('#library-manage-hint')).toContainText('历史记录与笔记');
  const deleted=page.waitForResponse(r=>r.request().method()==='DELETE'&&r.url().endsWith(collections[0].id));
  await page.getByRole('button',{name:'确认删除',exact:true}).click();
  expect((await deleted).status()).toBe(204);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await expect(page.locator(`.library-item[data-item-id="${collections[0].id}"]`)).toHaveCount(0);
  const after=await usage();expect(after.storage.usedBytes).toBeLessThan(before.storage.usedBytes);expect(after.dailyDownload.usedBytes).toBe(0);
});
