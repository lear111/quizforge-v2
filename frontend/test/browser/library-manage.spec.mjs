import {test, expect as baseExpect} from '@playwright/test';
import {mkdir, readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
import {registerByApi} from './accounts.mjs';
import {uploadAndImportFromLibrary} from './library-fixture.mjs';

const expect = baseExpect.configure({timeout: 30000});

test('existing dialogs manage empty groups, names, moves, conflicts and irreversible group deletion', async ({page, context}, info) => {
  test.skip(process.env.QF_READ_BROWSER_TESTS !== '1', '需要独立的本机 Compose');
  test.setTimeout(240000);
  const {origin} = await registerByApi(page, 'library', '目录管理验收');
  const {collections} = await uploadAndImportFromLibrary(page);
  // 目录管理不验证审核流程；独立准备本测试的样例，避免依赖先运行的读题/考试测试。
  const project = process.env.QF_COMPOSE_PROJECT;
  if (!project?.startsWith('qf-')) throw new Error('需要独立的本机 Compose 项目');
  for (const item of collections) expect(item.id).toMatch(/^[a-f0-9-]{36}$/);
  const ids = collections.map(item => `'${item.id}'`).join(',');
  const approval = `UPDATE admin_extension_review r JOIN content_question q ON q.extension_digest=r.digest JOIN practice_collection c ON c.bank_id=q.bank_id SET r.status='approved' WHERE c.id IN (${ids});`;
  execFileSync('docker', ['compose', '-p', project, '-f', resolve('../deploy/docker-compose.yml'), 'exec', '-T', 'mysql', 'sh', '-c', 'exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'], {input: approval, stdio: ['pipe', 'pipe', 'pipe']});
  const bank = collections[0];
  await page.goto('/practice/');
  const showLibrary = async target => {
    // 首次题卡提交会调用 closeDrawers；等候真正激活的题卡，避免它关闭刚打开的手机抽屉。
    await expect(target.locator('.question-pane:not(.collection-candidate):not([hidden]):not([inert]) iframe.question-frame')).toBeVisible();
    await expect(target.locator('#collection-loading')).toBeHidden();
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
  await expect(page.locator('#library-import')).toHaveCount(0);
  await expect(page.getByRole('link',{name:'从“我的题库”导入'})).toBeVisible();
  await expect(page.locator('#library-manage-export')).toHaveCount(0);
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

  await showLibrary(page);
  await page.getByRole('button', {name: '编辑分组 新文件夹', exact: true}).click();
  await page.locator('#library-manage-name').fill('重命名文件夹');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await showLibrary(page);
  const directory = resolve(process.env.QF_SCREENSHOT_DIR || '../.artifacts/local/library-manage');
  await mkdir(directory, {recursive: true});
  await page.screenshot({path: resolve(directory, `library-${info.project.name}.png`), fullPage: true, animations: 'disabled'});
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.getByRole('button', {name: '编辑分组 重命名文件夹', exact: true}).click();
  await page.locator('#library-manage-delete').click();
  await expect(page.locator('#library-manage-hint')).toContainText('1 本题库');
  await expect(page.locator('#library-manage-hint')).toContainText('无法恢复');
  expect((await save(page)).status()).toBe(200);
  await expect(page.locator('#library-manage-dialog')).not.toBeVisible();
  await expect(page.locator(`.library-item[data-item-id="${bank.id}"]`)).toHaveCount(0);
});
