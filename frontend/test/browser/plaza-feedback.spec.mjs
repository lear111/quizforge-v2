import {test,expect} from '@playwright/test';
import {registerByApi} from './accounts.mjs';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';
import {mkdir} from 'node:fs/promises';

// 只在自己的 Compose 中建立合成元数据；不上传真实题库或访问外部服务。
function sql(query) {
  const project=process.env.QF_COMPOSE_PROJECT;
  if(!project?.startsWith('qf-'))throw new Error('需要独立的 qf- Compose 项目');
  execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'exec','-T','mysql','sh','-c',
    'exec mysql --default-character-set=utf8mb4 --user="$MYSQL_USER" --password="$MYSQL_PASSWORD" "$MYSQL_DATABASE"'],{input:query,stdio:['pipe','pipe','pipe']});
}

test('plaza comments delete report admin removal and reporter notification',async({page,browser},info)=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','需要独立 Compose');
  const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  page.on('dialog',dialog=>dialog.accept());
  const account=await registerByApi(page,'feedback-admin','举报管理员');
  const owner=(await account.registered.json()).user.id;
  const pack=crypto.randomUUID(),title='评论举报验收-'+crypto.randomUUID();
  sql(`UPDATE account_user SET is_admin=TRUE WHERE id='${owner}';
    INSERT INTO catalog_pack(id,owner_id,latest_revision,approved_revision,status,visibility,created_at,updated_at)
      VALUES('${pack}','${owner}',1,1,'active','public',NOW(3),NOW(3));
    INSERT INTO catalog_pack_revision(pack_id,revision,title,summary,description,category,tags,release_notes,source,filename,
      blob_sha256,size,extension_digests,review_status,validation_status,submitted_by,submitted_at)
      VALUES('${pack}',1,'${title}','合成简介','评论与举报验收','其他','[]','说明','upload','synthetic.qbank',UNHEX(REPEAT('00',32)),1,'[]','approved','validated','${owner}',NOW(3));`);
  const memberContext=await browser.newContext({viewport:info.project.use.viewport,isMobile:info.project.use.isMobile});
  try {
    const member=await memberContext.newPage();
    member.on('pageerror',error=>errors.push(error.message));
    member.on('dialog',dialog=>dialog.accept());
    await registerByApi(member,'feedback-member','举报成员');
    let entered,release;
    const held=new Promise(done=>entered=done),gate=new Promise(done=>release=done);
    const matcher=url=>url.pathname===`/api/plaza/packs/${pack}/comments`;
    await member.route(matcher,async route=>{entered();await gate;await route.continue();});
    try {
      await member.goto('/plaza/#'+pack);
      await held;
      // 评论列表尚未返回时，收藏必须立即可用；用门控替代固定等待。
      await member.getByRole('button',{name:'收藏',exact:true}).click();
      await expect(member.getByRole('button',{name:'取消收藏',exact:true})).toBeVisible();
    } finally { release(); }
    await member.unrouteAll({behavior:'wait'});
    const section=member.getByRole('region',{name:'评论与举报'});
    const text='<b>这是纯文本</b>\n第二行';
    await section.locator('[name=body]').fill(text);
    await section.getByRole('button',{name:'发表评论'}).click();
    await expect(section.locator('.plaza-comment-body')).toHaveText(text);
    await expect(section.locator('.plaza-comment-body b')).toHaveCount(0);
    await section.getByRole('button',{name:'删除评论'}).click();
    await expect(section.locator('.plaza-comment')).toHaveCount(0);
    await section.locator('[name=body]').fill('供管理员删除的评论');
    await section.getByRole('button',{name:'发表评论'}).click();
    await expect(section.locator('.plaza-comment')).toHaveCount(1);
    await section.getByText('举报题库包',{exact:true}).click();
    await section.locator('[name=reason]').fill('合成举报原因，不含真实题目内容');
    await section.getByRole('button',{name:'提交举报'}).click();
    await expect(section.getByRole('status')).toContainText('举报已提交');
    expect(await member.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    const shots=resolve('../.artifacts/local/plaza-feedback/screenshots');
    await mkdir(shots,{recursive:true});
    await member.screenshot({path:resolve(shots,info.project.name+'-comments.png'),fullPage:true});

    await page.goto('/admin/#reports');
    await page.getByRole('link',{name:title,exact:true}).click();
    await expect(page.locator('#admin-main')).toContainText('合成举报原因');
    await page.getByRole('button',{name:'删除评论'}).click();
    await expect(page.locator('.plaza-comment')).toHaveCount(0);
    await page.locator('[name=note]').fill('确认问题，已下架');
    await page.locator('[name=decision]').selectOption('removed');
    await page.getByRole('button',{name:'确认处理'}).click();
    await expect(page.locator('#admin-toast')).toHaveText('已处理，已通知举报人');
    await expect(page.locator('#admin-main')).toContainText('已下架');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    await page.screenshot({path:resolve(shots,info.project.name+'-resolved.png'),fullPage:true});

    await member.goto('/practice/');
    await member.locator('.qf-bell').click();
    const notice=member.locator('.qf-notice').filter({hasText:'举报处理结果'});
    await expect(notice).toHaveCount(1);
    await notice.locator('.qf-notice-head').click();
    await expect(notice.locator('.qf-notice-body')).toContainText('确认问题，已下架');
    expect((await member.request.get('/api/plaza/packs/'+pack)).status()).toBe(404);
    expect(errors).toEqual([]);
  } finally { await memberContext.close(); }
});
