import {test, expect} from '@playwright/test';
import {registerByApi} from './accounts.mjs';

test('raw upload above the ordinary nginx ceiling is accepted before background rejection', async ({page}) => {
  test.skip(process.env.QF_READ_BROWSER_TESTS !== '1', '需要独立的本机 Compose');
  test.setTimeout(90000);
  const {origin} = await registerByApi(page, 'queue', '上传队列验收');
  // 超过普通 API 的 64 MiB；无效 ZIP 也必须先接收，后台再给出审核结果。
  const response = await page.request.post('/api/library/uploads?filename=large.qbank', {
    headers: {Origin: origin, 'Content-Type': 'application/octet-stream'},
    data: Buffer.alloc(65 * 1024 * 1024),
  });
  expect(response.status(), await response.text()).toBe(202);
  expect(response.headers()['server-timing']).toMatch(/^upload;dur=\d+$/);
  const {packId, revision} = await response.json();
  expect(packId).toMatch(/^[a-f0-9-]{36}$/);
  expect(revision).toBe(1);
  await expect.poll(async () => {
    const state = await page.request.get(`/api/library/uploads/${packId}/revisions/1`);
    expect(state.status()).toBe(200);
    return (await state.json()).status;
  }, {timeout: 60000}).toBe('rejected');
});
