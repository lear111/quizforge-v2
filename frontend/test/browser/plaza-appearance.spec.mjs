import {test,expect} from '@playwright/test';
import {readFile,mkdir} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {pathToFileURL} from 'node:url';

// 只渲染合成元数据；旧 Hub 的脚本不启动，不连接旧站点或加载外部图片。
const id='10000000-0000-4000-8000-000000000001';
const packs=['计算机基础 · 核心知识练习','考研英语 · 阅读与表达','高等数学 · 每日练习'].map((title,index)=>({id:index?id.slice(0,-1)+(index+1):id,title,summary:['从基础概念到综合应用，用练习串联知识。','在阅读与表达中积累，循序渐进提升语言能力。','精选基础题型，适合日常巩固与自我检测。'][index],ownerName:['学习者小林','英语学习小组','数学笔记'][index],createdAt:'2026-11-01T08:00:00Z',revision:2,downloadCount:128-index*31,tags:'["基础练习","自主学习"]'}));
const svg='<svg xmlns="http://www.w3.org/2000/svg" width="800" height="400"><rect width="800" height="400" fill="#f6f3ff"/><rect x="90" y="60" width="620" height="280" rx="20" fill="white" stroke="#ded5f0"/><rect x="125" y="100" width="250" height="14" rx="7" fill="#754ce2"/><rect x="125" y="150" width="550" height="10" rx="5" fill="#ddd8ea"/><rect x="125" y="180" width="450" height="10" rx="5" fill="#ddd8ea"/><rect x="125" y="240" width="180" height="50" rx="12" fill="#f1ecff"/><rect x="325" y="240" width="180" height="50" rx="12" fill="#f1ecff"/></svg>';
const banks=[{uuid:'bank-a',title:'概念与基础',questionCount:60},{uuid:'bank-b',title:'综合应用',questionCount:40}];
const detail={pack:packs[0],favorite:false,description:'从基础开始，建立清晰的知识结构。\n本题库包含概念练习与综合应用，可按需选择导入。',releaseNotes:'第二版：补充综合练习，调整题目说明。',banks,screenshots:[{id:'synthetic-shot',name:'练习页面预览'}]};
const comment={id:'comment-a',author:'一起学习',createdAt:'2026-11-02T08:00:00Z',body:'目录清晰，很适合分阶段练习。',canDelete:false};
async function fixture(page){
  await page.route('**/api/**',async route=>{
    const url=new URL(route.request().url());
    if(url.pathname.startsWith('/api/catalog/files/'))return route.fulfill({contentType:'image/svg+xml',body:svg});
    if(url.pathname==='/api/config')return route.fulfill({json:{localImport:{enabled:false}}});
    if(url.pathname.endsWith('/comments'))return route.fulfill({json:{items:[comment],nextCursor:null}});
    if(url.pathname==='/api/plaza/packs/'+id)return route.fulfill({json:detail});
    if(url.pathname==='/api/plaza/packs')return route.fulfill({json:{items:packs,next:null}});
    return route.fulfill({status:404,json:{error:{message:'不存在'}}});
  });
}
async function shot(page,directory,name){
  await page.evaluate(()=>scrollTo({top:0,behavior:'instant'}));
  await page.screenshot({path:join(directory,name+'.png'),fullPage:true,animations:'disabled'});
}

test('Hub 广场新旧列表、详情与手机对比截图',async({page,context},info)=>{
  const directory=resolve('../.artifacts/local/plaza-ui-hub/screenshots');await mkdir(directory,{recursive:true});
  await fixture(page);await page.goto('/plaza/');
  await expect(page.locator('.plaza-card')).toHaveCount(3);
  if(info.project.name==='mobile'){
    await page.getByRole('button',{name:'展开导航'}).click();
    await expect(page.getByRole('navigation',{name:'主导航'}).getByRole('link',{name:'我的题库'})).toBeVisible();
    await page.getByRole('button',{name:'收起导航'}).click();
  }
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await shot(page,directory,info.project.name+'-new-list');
  await page.getByRole('link',{name:packs[0].title,exact:true}).click();
  await expect(page.locator('#detail h1')).toHaveText(packs[0].title);
  await expect(page.locator('.plaza-comment-body')).toHaveText(comment.body);
  await expect(page.locator('.shot img')).toBeVisible();
  await page.locator('.shot').click();await expect(page.getByRole('dialog',{name:'题库截图'})).toBeVisible();
  await page.getByRole('button',{name:'关闭大图'}).click();
  await page.getByRole('checkbox',{name:'全选题库',exact:true}).uncheck();
  await expect(page.getByRole('button',{name:'导入练习空间',exact:true})).toBeDisabled();
  await page.getByRole('checkbox',{name:'全选题库',exact:true}).check();
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await shot(page,directory,info.project.name+'-new-detail');

  // 旧版原文件放在仓库外；CI 仅取固定提交的静态源码，绝不运行旧 hub.js。
  if(!process.env.QF_HUB_REFERENCE){test.info().annotations.push({type:'说明',description:'未配置仓库外的 QF_HUB_REFERENCE；只生成 V2 截图'});return;}
  const root=resolve(process.env.QF_HUB_REFERENCE),source=await import(pathToFileURL(join(root,'catalog-view.mjs')).href);
  const html=(await readFile(join(root,'index.html'),'utf8')).replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'').replace(/<link\b[^>]*>/gi,'').replace('<head>','<head><base href="http://reference.qf.test/">');
  const old=await context.newPage();
  await old.route('**/*',route=>route.request().url().includes('/media/')?route.fulfill({contentType:'image/svg+xml',body:svg}):route.abort());
  await old.setContent(html);await old.addStyleTag({content:await readFile(join(root,'hub.css'),'utf8')});
  const oldPacks=packs.map(pack=>({...pack,author:pack.ownerName,updated:pack.createdAt,downloads:pack.downloadCount,status:'approved',version:String(pack.revision),archive:{name:'基础练习.qbank',size:1048576,sha256:'a'.repeat(64)}}));
  await old.evaluate(cards=>{
    document.querySelector('#libraryGrid').innerHTML=cards;
    document.querySelector('#discoveryFilters').hidden=false;document.querySelector('#sortWrap').hidden=false;
    document.querySelector('#resultCount').textContent='共 3 份题库';
  },oldPacks.map(source.card).join(''));
  await shot(old,directory,info.project.name+'-old-list');
  const oldDetail=source.detail({...oldPacks[0],description:detail.description,screenshots:[{id:'synthetic-shot'}]},[{author:comment.author,created:comment.createdAt,body:comment.body}],{native:['comments-write','reports-write','cloud-import'],cloudEnabled:true,practiceOrigin:'http://localhost'},{id:'synthetic-user'});
  await old.evaluate(({html,catalog})=>{
    document.querySelector('#heroSection').hidden=true;document.querySelector('#library').hidden=true;
    const target=document.querySelector('#detailView');target.hidden=false;target.innerHTML=html;
    target.querySelector('[data-bank-catalog]').innerHTML=catalog;target.querySelector('[data-bank-catalog-summary]').textContent='2 本';
  },{html:oldDetail,catalog:source.bankCatalogView(banks.map(bank=>({...bank,id:bank.uuid})))});
  await expect(old.locator('.shot img')).toBeVisible();
  await expect.poll(()=>old.locator('.shot img').evaluate(image=>image.naturalWidth)).toBeGreaterThan(0);
  await shot(old,directory,info.project.name+'-old-detail');
  await old.close();
});

test('广场加载、空状态、网络失败重试与删除提示',async({page})=>{
  let mode='loading',entered,release,lastSort;
  const held=new Promise(done=>entered=done),gate=new Promise(done=>release=done);
  await page.route('**/api/plaza/packs?*',async route=>{
    lastSort=new URL(route.request().url()).searchParams.get('sort');
    if(mode==='loading'){entered();await gate;}
    if(mode==='error')return route.abort();
    return route.fulfill({json:{items:mode==='empty'?[]:packs,next:null}});
  });
  await page.goto('/plaza/');await held;
  try{await expect(page.locator('#packs')).toHaveAttribute('aria-busy','true');await expect(page.locator('.loading-card')).toHaveCount(3);}finally{release();}
  await expect(page.locator('.plaza-card')).toHaveCount(3);
  mode='empty';await page.getByRole('combobox',{name:'题库排序'}).selectOption('downloads');
  await page.getByRole('button',{name:'搜索',exact:true}).click();await expect(page.locator('.empty-state')).toContainText('暂无符合条件');
  expect(lastSort).toBe('downloads');
  mode='error';await page.getByRole('button',{name:'查看全部题库'}).click();await expect(page.locator('.empty-state')).toContainText('无法连接服务器');
  mode='ready';await page.getByRole('button',{name:'重新加载'}).click();await expect(page.locator('.plaza-card')).toHaveCount(3);
  await page.route('**/api/plaza/packs/'+id,route=>route.fulfill({status:404,json:{error:{message:'不存在'}}}));
  await page.getByRole('link',{name:packs[0].title,exact:true}).click();await expect(page.locator('#detail')).toContainText('作者已删除');
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
