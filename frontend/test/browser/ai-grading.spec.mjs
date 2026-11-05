import {test,expect as baseExpect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';
import {registerByApi} from './accounts.mjs';
import {uploadAndImportFromLibrary} from './library-fixture.mjs';
const expect=baseExpect.configure({timeout:30000});

test('提交主观题后 AI 评分显示反馈与花费，人工评分覆盖，自动评分设置可保存',async({page},info)=>{
  test.skip(process.env.QF_AI_GRADING_BROWSER_TESTS!=='1','需要隔离 Compose 与本机 FakeUpstream');
  test.setTimeout(180000);
  const project=process.env.QF_COMPOSE_PROJECT;
  if(!project?.startsWith('qf-'))throw new Error('需要独立的 qf-* Compose 项目');
  const sql=query=>execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c',
    'exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
  const {origin,email}=await registerByApi(page,'ai-grading','AI 评分验收');
  sql(`UPDATE account_user SET is_admin=TRUE WHERE email='${email}';`);
  const added=await page.request.post('/api/admin/ai-keys',{headers:{Origin:origin},data:{
    apiKey:`sk-fake-browser-${crypto.randomUUID()}`,note:'AI 评分本机验收',inputPrice:30000,outputPrice:90000
  }});
  expect(added.status(),await added.text()).toBe(201);
  const key=(await added.json()).id;
  try {
    const {collections}=await uploadAndImportFromLibrary(page);
    const collection=collections.find(row=>row.title==='简答题');
    await page.goto('/practice/');
    // 明确选择本次验收的题库，不依赖启动时自动打开的另一道题。
    await expect(page.locator(`.library-item[data-item-id="${collection.id}"]`)).toBeAttached();
    await expect(page.locator('#collection-loading')).toBeHidden();
    if(await page.locator('#library-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#library-toggle').click();
    await page.locator(`.library-item[data-item-id="${collection.id}"]`).click();
    await expect(page.locator('#collection-loading')).toBeHidden();
    const frame=page.frameLocator('.question-pane:not([hidden]):not([inert]) iframe.question-frame');
    await frame.locator('.answer-field').click();
    await frame.locator('[contenteditable="true"]').fill('缓存可以减少重复计算，同时需要维护一致性。');
    await frame.getByRole('button',{name:'提交',exact:true}).click();
    await expect(frame.getByRole('button',{name:'AI 评分',exact:true})).toBeVisible();
    const created=page.waitForResponse(r=>r.url().endsWith('/ai/grade')&&r.request().method()==='POST');
    await frame.getByRole('button',{name:'AI 评分',exact:true}).click();
    const queued=await created;expect(queued.status(),await queued.text()).toBe(200);
    const taskId=(await queued.json()).taskId;
    const descriptor=await (await page.request.get(`/api/collections/bank/${collection.id}`)).json();
    const qid=descriptor.questions[0].id;
    await expect.poll(async()=>{
      const response=await page.request.get(`/api/collections/bank/${collection.id}/questions/${qid}/ai/tasks/${taskId}`);
      return (await response.json()).status;
    }).toBe('confirmed');
    await expect(frame.locator('.ai-feedback')).toContainText('本机假模型');
    await expect(frame.locator('.grade-value')).toHaveText('2 / 5 分');
    const directory=resolve(process.env.QF_SCREENSHOT_DIR||'../.artifacts/local/ai-grading');
    await mkdir(directory,{recursive:true});
    await page.screenshot({path:resolve(directory,`ai-grading-result-${info.project.name}.png`),fullPage:true});
    const slider=frame.getByRole('slider',{name:'人工评分',exact:true});
    await slider.focus();await slider.press('End');
    await expect(frame.locator('.grade-value')).toHaveText('5 / 5 分');
    await page.screenshot({path:resolve(directory,`ai-grading-${info.project.name}.png`),fullPage:true});
    await page.goto('/');
    await expect(page.locator('#ai-balance')).toHaveText('¥0.9925');
    await page.locator('#ai-auto-grade').check();
    await expect(page.locator('#ai-settings-status')).toHaveText('自动评分设置已保存');
    await page.reload();
    await expect(page.locator('#ai-auto-grade')).toBeChecked();
  } finally {
    await page.request.delete(`/api/admin/ai-keys/${key}`,{headers:{Origin:origin}});
  }
});
