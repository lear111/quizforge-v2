import {test, expect as baseExpect} from '@playwright/test';
import {mkdir, readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';

const expect = baseExpect.configure({timeout: 30000});

test('existing dialogs manage empty groups, names, moves, conflicts, downloads and irreversible group deletion', async ({page, context}, info) => {
  test.skip(process.env.QF_READ_BROWSER_TESTS !== '1', '需要独立的本机 Compose');
  test.setTimeout(240000);
  const {origin} = await registerByApi(page, 'library', '目录管理验收');
  const response = await page.request.post('/api/library/import', {
    headers: {Origin: origin},
    multipart: {file: {name: 'basic-types.qbank', mimeType: 'application/zip', buffer: await readFile(resolve('../samples/basic-types.qbank'))}},
  });
  expect(response.status(), await response.text()).toBe(200);
  const {collections} = await response.json();
  const bank = collections[0];
  await page.goto('/practice/');
  const showLibrary = async target => {
    if (await target.locator('#library-toggle').getAttribute('aria-expanded') !== 'true') await target.locator('#library-toggle').click();
    await expect(target.locator('#library-list')).toBeVisible();
  };
  const row = target => target.locator('.library-row').filter({has: target.locator(`.library-item[data-item-id="${bank.id}"]`)});
  const edit = async (target, title) => {
    await showLibrary(target);
    await row(target).getByRole('button', {name: `编辑 ${title}`, exact: true}).click();
  };
  const save = async target => {
    const saved = target.waitForResponse(result => result.url().endsWith('/api/library/manage') && result.request().method() === 'POST');
    await target.locator('#library-manage-confirm').click();
    return saved;
  };
  await showLibrary(page);
  await page.locator('#library-new-group').click();
  await page.locator('#library-manage-name').fill('新文件夹');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await expect(page.locator('[data-extension-group="新文件夹"]')).toBeVisible();
  await edit(page, bank.title);
  await page.locator('#library-manage-name').fill('我的显示名称');
  await page.locator('#library-manage-group').selectOption('新文件夹');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await expect(page.locator('.extension-group').filter({has: page.locator('[data-extension-group="新文件夹"]')})).toContainText('我的显示名称');

  const second = await context.newPage();
  await second.goto('/practice/');
  await showLibrary(second);
  await edit(second, '我的显示名称');
  await edit(page, '我的显示名称');
  await page.locator('#library-manage-name').fill('最新名称');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await second.locator('#library-manage-name').fill('过期改名');
  expect((await save(second)).status()).toBe(409);
  await expect(second.locator('#library-manage-error')).toContainText('其他页面');
  await expect(second.locator('#library-list')).toContainText('最新名称');
  await second.close();

  await edit(page, '最新名称');
  const downloaded = page.waitForEvent('download');
  await page.locator('#library-manage-export').click();
  const download = await downloaded;
  expect(download.suggestedFilename()).toMatch(/\.qbank$/);
  const archive = await readFile(await download.path());
  expect(archive.subarray(0, 2).toString()).toBe('PK');
  await expect(page.locator('#library-manage-status')).toContainText('已导出');
  await page.locator('#library-manage-close').click();
  await showLibrary(page);
  await page.getByRole('button', {name: '编辑分组 新文件夹', exact: true}).click();
  await page.locator('#library-manage-name').fill('重命名文件夹');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await showLibrary(page);
  const directory = resolve(process.env.QF_SCREENSHOT_DIR || '../.artifacts/local/library-manage');
  await mkdir(directory, {recursive: true});
  await page.screenshot({path: resolve(directory, `library-${info.project.name}.png`), fullPage: true});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.getByRole('button', {name: '编辑分组 重命名文件夹', exact: true}).click();
  await page.locator('#library-manage-delete').click();
  await expect(page.locator('#library-manage-hint')).toContainText('1 本题库');
  await expect(page.locator('#library-manage-hint')).toContainText('无法恢复');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await expect(page.locator(`.library-item[data-item-id="${bank.id}"]`)).toHaveCount(0);
  const reimport = await page.request.post('/api/library/import', {
    headers: {Origin: origin}, multipart: {file: {name: 'roundtrip.qbank', mimeType: 'application/zip', buffer: archive}},
  });
  expect(reimport.status(), await reimport.text()).toBe(200);
  expect((await reimport.json()).collections).toHaveLength(1);
});
