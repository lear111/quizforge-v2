import {test,expect} from '@playwright/test';
import {registerByApi} from './accounts.mjs';
import {uploadAndImportFromLibrary} from './library-fixture.mjs';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {readFile,mkdir} from 'node:fs/promises';

test('plaza browse search detail import and favorite original download',async({page,browser},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立 Compose');
  test.setTimeout(180000);
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const owner=await registerByApi(page,'plaza-owner','广场发布者');
  const {packId}=await uploadAndImportFromLibrary(page);
  const title='广场浏览验收-'+crypto.randomUUID();
  const response=await page.request.post(`/api/library/packs/${packId}/publish`,{headers:{Origin:owner.origin},data:{revision:1,rights:true,title,summary:'合成搜索简介',description:'公开详情介绍',category:'其他',tags:'测试'}});
  expect(response.status(),await response.text()).toBe(202);const published=(await response.json()).packId;
  await expect.poll(async()=>{const r=await page.request.get('/api/library/packs/'+published);return(await r.json()).revisions[0].banks.length;},{timeout:60000}).toBeGreaterThan(0);
  const project=process.env.QF_COMPOSE_PROJECT;
  execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c','exec mysql --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:`UPDATE catalog_pack_revision SET review_status='approved' WHERE pack_id='${published}'; UPDATE catalog_pack SET approved_revision=1 WHERE id='${published}';`,stdio:['pipe','pipe','pipe']});
  const learner=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile});const tab=await learner.newPage();tab.on('pageerror',e=>errors.push(e.message));
  await tab.goto('/plaza/');
  await expect(tab.getByRole('link',{name:title,exact:true})).toBeVisible();
  let entered,release,continued;
  const held=new Promise(done=>entered=done),gate=new Promise(done=>release=done),finished=new Promise(done=>continued=done);
  const matcher=url=>url.pathname==='/api/plaza/packs'&&url.searchParams.get('q')===title;
  await tab.route(matcher,async route=>{entered();await gate;await route.continue();continued();});
  await tab.locator('[name=q]').fill(title);await tab.getByRole('button',{name:'搜索',exact:true}).click();await held;
  try {
    // 搜索尚未返回时，已有卡片仍可导航；不能把 hashchange 当重复提交丢弃。
    await tab.getByRole('link',{name:title,exact:true}).click();
    await expect(tab.locator('#detail')).toContainText('公开详情介绍');
  } finally {release();}
  await finished;await tab.unroute(matcher);
  await expect(tab.locator('#packs .plaza-card')).toHaveCount(1);
  await registerByApi(tab,'plaza-learner','广场学习者');await tab.goto('/plaza/#'+published);
  await tab.getByRole('button',{name:'收藏',exact:true}).click();await expect(tab.getByRole('button',{name:'取消收藏',exact:true})).toBeVisible();
  await tab.getByRole('button',{name:'导入练习空间',exact:true}).click();await expect(tab.locator('#feedback')).toContainText('已导入独立练习实例');
  await expect(tab.locator('#detail')).toContainText('1 道题');
  expect(await tab.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  const shots=resolve('../.artifacts/local/plaza/screenshots');await mkdir(shots,{recursive:true});await tab.screenshot({path:resolve(shots,info.project.name+'-plaza.png'),fullPage:true});
  await tab.goto('/library/');const favorites=tab.locator('#favorites');await expect(favorites).toContainText(title);
  const event=tab.waitForEvent('download');await favorites.getByRole('link',{name:'下载到本地'}).click();const file=await event;
  expect(await readFile(await file.path())).toEqual(await readFile(resolve('../samples/basic-types.qbank')));
  await tab.goto('/practice/');await expect(tab.locator('.question-pane:not([hidden]) iframe.question-frame:not([data-candidate])')).toBeVisible({timeout:30000});
  expect(errors).toEqual([]);await learner.close();
});
