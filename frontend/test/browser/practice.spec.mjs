import {test,expect} from '@playwright/test';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';

async function register(page) {
  await page.goto('/practice/');
  await expect(page).toHaveURL(/\/\?returnTo=/);
  await page.getByRole('tab',{name:'注册',exact:true}).click();
  await page.locator('#register-email').fill(`frontend-${crypto.randomUUID()}@example.test`);
  await page.locator('#register-name').fill('界面验证');
  await page.locator('#register-password').fill('test-password-123');
  await page.locator('#register-confirm').fill('test-password-123');
  await page.getByRole('button',{name:'注册并登录',exact:true}).click();
  await expect(page).toHaveURL(/\/practice\/$/);
}
async function openLibrary(page) {
  await expect(page.locator('#library-list')).toHaveText(/\S/);
  if(await page.locator('#library-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#library-toggle').click();
  await expect(page.locator('#library-label')).toBeVisible();
}

test('real Cookie login, refresh, shell, missing import, appearance and logout',async({page},info)=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  page.on('request',request=>{if(new URL(request.url()).pathname.startsWith('/api/'))expect(request.headers()['x-quizforge-token']).toBeUndefined();});
  await register(page);
  await openLibrary(page);
  await expect(page.locator('#library-list')).toContainText(/尚未开放|还没有题库/);
  await expect(page.locator('#network-settings-open')).toHaveCount(0);
  await expect(page.locator('#edit-open')).toHaveCount(0);
  await page.reload();await expect(page).toHaveURL(/\/practice\/$/);await openLibrary(page);
  await page.locator('#appearance-open').click();await expect(page.locator('#appearance-dialog')).toBeVisible();
  await page.getByRole('radio',{name:'默认',exact:true}).click();
  await page.locator('#appearance-close').click();await expect(page.locator('#appearance-dialog')).not.toBeVisible();
  const screenshots=resolve(process.env.QF_SCREENSHOT_DIR||'../.artifacts/screenshots');await mkdir(screenshots,{recursive:true});
  await page.screenshot({path:resolve(screenshots,`${info.project.name}.png`),fullPage:true});
  if(info.project.name==='mobile') {
    await page.locator('#scrim').click({position:{x:360,y:100}});
    await expect.poll(async()=>{const box=await page.locator('.library').boundingBox();return box.x+box.width;}).toBeLessThanOrEqual(0);
    await page.screenshot({path:resolve(screenshots,'mobile-shell.png'),fullPage:true});
    await openLibrary(page);
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await page.locator('#library-import-file').setInputFiles({name:'unsupported.qbank',mimeType:'application/vnd.quizforge.qbank',buffer:Buffer.from('not a zip')});
  await page.locator('#library-import-confirm').click();
  await expect(page.locator('#library-import-error')).toBeVisible();
  await expect(page.locator('#library-import-error')).not.toHaveText(/undefined|Cannot|Exception/);
  await page.locator('#library-import-cancel').click();
  await page.locator('#online-logout').click();await expect(page).toHaveURL(/\/$/);
  await expect(page.locator('#login-form')).toBeVisible();
  await page.goto('/practice/');await expect(page).toHaveURL(/\/\?returnTo=/);
  expect(errors).toEqual([]);
});

test('an implemented catalog survives an unavailable collection slice',async({page})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/api/library/index',route=>route.fulfill({status:501,body:'not implemented'}));
  await page.route('**/api/catalog',route=>route.fulfill({json:{banks:[{id:'future-bank',title:'下一切片的题库',questionCount:1}],extensions:[]}}));
  await register(page);await openLibrary(page);
  await page.locator('.library-item[data-item-id="future-bank"]').click();
  await expect(page.locator('#notice')).toContainText('尚未开放');
  await expect(page.locator('#collection-loading')).toBeHidden();
  await expect(page.locator('#library-list')).toContainText('下一切片的题库');
  await expect(page.locator('#welcome')).toBeVisible();expect(errors).toEqual([]);
});
