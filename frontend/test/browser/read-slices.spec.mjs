import {test,expect as baseExpect} from '@playwright/test';
// Real SDK restore and grading share the CI host with MySQL; wait for readiness, not a five-second machine-speed assumption.
const expect=baseExpect.configure({timeout:30000});
import {execFileSync} from 'node:child_process';
import {mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';

test('real sample import, approval fixture, open and render all five types',async({page},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','Requires an explicitly isolated Compose fixture');
  test.setTimeout(180000);
  const project=process.env.QF_COMPOSE_PROJECT;
  if(!project?.startsWith('qf-'))throw Error('Use a dedicated qf-* Compose project for the review fixture');
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await page.goto('/practice/');await page.getByRole('tab',{name:'注册',exact:true}).click();
  await page.locator('#register-email').fill(`read-${crypto.randomUUID()}@example.test`);
  await page.locator('#register-name').fill('切片验收');
  await page.locator('#register-password').fill('test-password-123');
  await page.locator('#register-confirm').fill('test-password-123');
  await page.getByRole('button',{name:'注册并登录',exact:true}).click();
  await expect(page).toHaveURL(/\/practice\/$/);
  const library=async()=>{if(await page.locator('#library-toggle').getAttribute('aria-expanded')!=='true')await page.locator('#library-toggle').click();await expect(page.locator('#library-list')).toBeVisible();};
  await library();
  await expect(page.locator('#library-import')).toBeVisible();
  await page.locator('#library-import-file').setInputFiles(resolve('../samples/basic-types.qbank'));
  const importedResponse=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/library/import'&&response.request().method()==='POST');
  await page.locator('#library-import-confirm').click();
  const imported=await importedResponse;expect(imported.status()).toBe(200);
  const {collections}=await imported.json();expect(collections).toHaveLength(5);
  await expect(page.locator('#library-import-dialog')).not.toBeVisible();
  // Review state is global per digest. Reset only this disposable fixture so desktop/mobile
  // and local reruns exercise the same pending -> approved path.
  const ids=collections.map(row=>row.id);for(const id of ids)expect(id).toMatch(/^[a-f0-9-]{36}$/);
  const review=status=>{
    const query=`INSERT INTO admin_extension_review (digest,extension_id,version,status,reviewer,reviewed_at) SELECT DISTINCT e.digest,e.extension_id,e.version,'${status}','read-slices-browser-fixture',NOW(3) FROM content_extension_package e JOIN content_question q ON q.extension_digest=e.digest JOIN practice_collection c ON c.bank_id=q.bank_id WHERE c.id IN (${ids.map(id=>"'"+id+"'").join(',')}) ON DUPLICATE KEY UPDATE status='${status}';`;
    execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
  };
  review('pending');await page.reload();await library();
  await expect(page.locator('#library-list')).toContainText('审核中');
  await page.locator('.library-item').first().click();await expect(page.locator('#notice')).toContainText('审核中');
  review('approved');
  await page.locator('#refresh').click();
  const slugs={'单选题':'single-choice','多选题':'multiple-choice','判断题':'true-false','简答题':'short-answer','简答题（答题区）':'answer-area'};
  const screenshots=resolve(process.env.QF_SCREENSHOT_DIR||'../.artifacts/screenshots');await mkdir(screenshots,{recursive:true});
  for(const item of collections){
    await library();await page.locator(`.library-item[data-item-id="${item.id}"]`).click();
    await expect(page.locator('#document-title')).toHaveText(item.title);
    await expect(page.locator('#collection-loading')).toBeHidden();
    await expect(page.locator('.question-error')).toHaveCount(0);
    const frame=page.frameLocator('.question-pane:not([hidden]) iframe.question-frame');
    await expect(frame.locator('body')).toContainText(/提交|答案|作答/);
    await expect(page.locator('#notice')).toContainText('尚未开放');
    if(item.title==='单选题')await expect(frame.locator('img[data-asset-id]')).toHaveCount(1);
    await expect(frame.locator('.qfrt-image-missing')).toHaveCount(0);
    // Check resource images; submitted-answer snapshots are intentionally empty/hidden before submission.
    await expect.poll(()=>frame.locator('img[data-asset-id]').evaluateAll(images=>images.every(image=>image.complete&&image.naturalWidth>0))).toBe(true);
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.screenshot({path:resolve(screenshots,`read-${info.project.name}-${slugs[item.title]}.png`),fullPage:true});
  }
  const sdk=await page.request.get('/api/sdk/quizforge.richtext/1.1.3');expect(sdk.ok()).toBe(true);expect((await sdk.json()).script.length).toBeGreaterThan(100);
  const image='/api/resources/96fbe7bbe9e76622b6236d0d0fbfddb92f022249ac38bdbbd908efd3bf3791a8';
  const range=await page.request.get(image,{headers:{Range:'bytes=0-7'}});expect(range.status()).toBe(206);
  expect([...await range.body()]).toEqual([137,80,78,71,13,10,26,10]);
  expect((await page.request.head(image)).headers()['content-length']).toBe('3688');
  expect((await page.request.get('/_qf_sdk/quizforge.richtext/1.1.3.json')).status()).toBe(404);
  expect((await page.request.get('/_qf_blobs/96/fb/96fbe7bbe9e76622b6236d0d0fbfddb92f022249ac38bdbbd908efd3bf3791a8')).status()).toBe(404);
  expect((await page.request.get('/shared/richtext/1.1.3/richtext.js')).status()).toBe(404);
  // Both tabs read revision 0 before either writes. Use actual extension controls and
  // the production write queue, including its 409 recovery UI; no mocked HTTP writes.
  const single=collections.find(item=>item.title==='单选题');
  const openSingle=async(tab,{submitted=false}={})=>{
    // start() automatically opens the first bank and closes mobile drawers when
    // it finishes. Wait for that transition before opening the drawer ourselves.
    await expect(tab.locator('.question-pane:not([hidden]):not([inert]) iframe.question-frame')).toHaveCount(1,{timeout:30000});
    await expect(tab.locator('#collection-loading')).toBeHidden();
    await expect(tab.locator('#library-list')).toHaveText(/\S/);
    if(await tab.locator('#library-toggle').getAttribute('aria-expanded')!=='true')await tab.locator('#library-toggle').click();
    await expect(tab.locator('.library')).toHaveAttribute('aria-hidden','false');
    await tab.locator(`.library-item[data-item-id="${single.id}"]`).click();
    await expect(tab.locator('#collection-loading')).toBeHidden();
    await expect(tab.locator('#document-title')).toHaveText('单选题');
    const frame=tab.frameLocator('.question-pane:not([hidden]) iframe.question-frame');
    if(!submitted)await expect(frame.getByRole('radio',{name:'选项 A',exact:true})).toBeEnabled();
    return frame;
  };
  const firstFrame=await openSingle(page);
  const other=await page.context().newPage();await other.goto('/practice/');const otherFrame=await openSingle(other);
  const action=response=>new URL(response.url()).pathname.endsWith('/actions')&&response.request().method()==='POST';
  const savedResponse=page.waitForResponse(action);
  await firstFrame.locator('label.choice').nth(0).click();
  const saved=await savedResponse;expect(saved.status()).toBe(200);
  const savedBody=await saved.json();expect(savedBody.revision).toBe(1);expect(savedBody.state.revision).toBe(1);
  expect(saved.request().postDataJSON()).toMatchObject({action:'draft',revision:0,data:{answer:{selectedOptionId:savedBody.state.answer.selectedOptionId}}});
  const conflictResponse=other.waitForResponse(action);await otherFrame.locator('label.choice').nth(1).click();
  const conflict=await conflictResponse;expect(conflict.status()).toBe(409);expect((await conflict.json()).error.code).toBe('REVISION_CONFLICT');
  await expect(other.locator('#save-recovery')).toBeVisible();await expect(other.locator('#recovery-message')).toContainText('冲突');
  await expect(otherFrame.getByRole('radio',{name:'选项 B',exact:true})).toBeChecked();
  const path=new URL(saved.url()).pathname.replace(/\/actions$/,'');
  const persisted=await page.request.get(path);expect((await persisted.json()).state).toMatchObject({revision:1,answer:savedBody.state.answer});
  await other.screenshot({path:resolve(screenshots,`draft-${info.project.name}-conflict.png`),fullPage:true});
  await page.reload();const restored=await openSingle(page);await expect(restored.getByRole('radio',{name:'选项 A',exact:true})).toBeChecked();
  await page.screenshot({path:resolve(screenshots,`draft-${info.project.name}-restored.png`),fullPage:true});await other.close();
  // Submit the restored wrong answer through the extension, then reload the frozen result.
  const submittedResponse=page.waitForResponse(response=>action(response)&&response.request().postDataJSON().action==='submit');
  await restored.getByRole('button',{name:'提交',exact:true}).click();
  const submitted=await submittedResponse;expect(submitted.status()).toBe(200);
  const submittedBody=await submitted.json();expect(submittedBody.state.status).toBe('submitted');
  expect(submittedBody.state.result.score).toBeLessThan(submittedBody.state.result.maxScore);
  await expect(page.locator('#outline-list .outline-item.incorrect')).toHaveCount(1);
  await expect(restored.locator('#result')).toBeVisible();
  await expect(restored.locator('label.choice.is-wrong')).toHaveCount(1);
  await page.screenshot({path:resolve(screenshots,`submit-${info.project.name}-graded.png`),fullPage:true});
  await page.reload();
  const frozen=await openSingle(page,{submitted:true});
  await expect(page.locator('#outline-list .outline-item.incorrect')).toHaveCount(1);
  await expect(frozen.locator('#result')).toBeVisible();
  await expect(frozen.getByRole('button',{name:'提交',exact:true})).toBeHidden();
  await expect(frozen.locator('label.choice.is-wrong')).toHaveCount(1);
  await page.screenshot({path:resolve(screenshots,`submit-${info.project.name}-refreshed.png`),fullPage:true});
  expect(errors).toEqual([]);
});
