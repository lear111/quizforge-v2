import {expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';

/** 只在独立 Compose 中批准本次包引用的拓展；不触及其他用户的内容。 */
export function approvePackage(packId) {
  const project = process.env.QF_COMPOSE_PROJECT;
  if (!project?.startsWith('qf-')) throw new Error('需要独立的 qf-* Compose 项目');
  expect(packId).toMatch(/^[a-f0-9-]{36}$/);
  const sql = `UPDATE admin_extension_review r JOIN content_question q ON q.extension_digest=r.digest JOIN catalog_pack_bank b ON b.bank_id=q.bank_id SET r.status='approved' WHERE b.pack_id='${packId}';`;
  execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c',
    'exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:sql,stdio:['pipe','pipe','pipe']});
}

export async function uploadAndImportFromLibrary(page, file = resolve('../samples/basic-types.qbank')) {
  await page.goto('/library/');
  const accepted = page.waitForResponse(response => new URL(response.url()).pathname === '/api/library/uploads' && response.request().method() === 'POST');
  await page.locator('#upload-file').setInputFiles(file);
  const upload = await accepted;
  expect(upload.status()).toBe(202);
  // XHR 对象完成后 Chromium 可能释放响应体；以已显示的资源 ID 和独立 GET 验证结果。
  await expect(page.locator('[data-pack-id]').first()).toBeVisible();
  const packId = await page.locator('[data-pack-id]').first().getAttribute('data-pack-id');
  await expect.poll(async () => {
    const response = await page.request.get(`/api/library/packs/${packId}`);
    return (await response.json()).revisions[0].banks.length;
  },{timeout:60000}).toBeGreaterThan(0);
  approvePackage(packId);
  await page.reload();
  await page.locator(`[data-pack-id="${packId}"]`).getByRole('button').click();
  const imported = page.waitForResponse(response => new URL(response.url()).pathname === `/api/library/packs/${packId}/practice`);
  await page.locator('#detail [data-revision="1"]').getByRole('button',{name:'导入练习空间',exact:true}).click();
  const response = await imported;
  expect(response.status(),await response.text()).toBe(200);
  const data = await response.json();
  return {packId,collections:data.collections};
}
