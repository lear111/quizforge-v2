import {test,expect} from '@playwright/test';
import {createHash} from 'node:crypto';
import {writeFile,rm,mkdir} from 'node:fs/promises';
import {resolve} from 'node:path';

// Nginx 以只读方式挂载下载目录（默认 deploy/downloads，可由 QF_DOWNLOADS_DIR 覆盖）。测试写入合成安装包与清单，结束时删除。
const directory=resolve(process.env.QF_DOWNLOADS_DIR||'../deploy/downloads');
const installer=Buffer.from('MZ synthetic QuizForge installer for browser tests');
const sha256=createHash('sha256').update(installer).digest('hex');
const file='QuizForge-9.9.9-windows.exe';

test.afterEach(async()=>{
  await rm(resolve(directory,'manifest.json'),{force:true});
  await rm(resolve(directory,file),{force:true});
});

test('the download page lists installers from the manifest and Nginx serves the file directly',async({page,request})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  await mkdir(directory,{recursive:true});
  await rm(resolve(directory,'manifest.json'),{force:true});
  await page.goto('/download');
  await expect(page).toHaveURL(/\/download\/$/);
  await expect(page.locator('#download-status')).toHaveText('暂时没有可下载的安装包，请稍后再来。');

  await writeFile(resolve(directory,file),installer);
  await writeFile(resolve(directory,'manifest.json'),JSON.stringify({schemaVersion:1,items:[
    {platform:'android',version:'9.9.8',label:'',notes:'',file:'QuizForge-9.9.8-android.apk',bytes:10,sha256:'b'.repeat(64),updated:'2026-11-01T08:00:00Z'},
    {platform:'windows',version:'9.9.9',label:'正式版',notes:'修复登录\n新增手写板',file,bytes:installer.length,sha256,updated:'2026-11-01T08:00:00Z'},
    {platform:'windows',version:'0',file:'../secret',bytes:1,sha256},
  ]}));
  await page.reload();
  const cards=page.locator('.download-card');
  await expect(cards).toHaveCount(2);
  await expect(cards.first()).toHaveAttribute('data-platform','windows');
  await expect(cards.first().locator('h2')).toContainText('版本 9.9.9');
  await expect(cards.first().locator('.hash')).toHaveText(sha256);
  await expect(cards.first().locator('.notes p')).toHaveText('修复登录\n新增手写板');
  await expect(cards.first().locator('.download-button')).toHaveAttribute('href',`/download/files/${file}`);

  // 安装包由 Nginx 直接提供，强制下载；非法文件名一律 404。
  const response=await request.get(`/download/files/${file}`);
  expect(response.status()).toBe(200);
  expect(Buffer.from(await response.body()).equals(installer)).toBe(true);
  expect(response.headers()['content-disposition']).toBe('attachment');
  expect(response.headers()['content-type']).toBe('application/vnd.microsoft.portable-executable');
  for(const path of ['/download/files/.gitkeep','/download/files/a/b.exe','/download/files/','/download/files/manifest.json.part'])
    expect((await request.get(path)).status(),path).toBe(404);
  // Nginx 先解码并规范化路径：带 .. 的请求落到其他 location，读不到下载目录以外的文件。
  for(const path of ['/download/files/..%2F..%2F..%2Fetc%2Fpasswd','/download/files/%2e%2e/%2e%2e/%2e%2e/etc/passwd']) {
    const body=await (await request.get(path)).text();
    expect(body,path).not.toContain('root:');
  }
  expect(errors).toEqual([]);
});
