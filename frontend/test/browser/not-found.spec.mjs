import {test,expect} from '@playwright/test';

test('未知深层路径显示带样式的真实 404，静态资源不回退到首页',async({page,request})=>{
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  for(const path of ['/plaza/不存在','/library/abc','/practice/不存在','/unknown/deep/path','/plaza/unknown.js']){
    const response=await page.goto(path);expect(response.status()).toBe(404);
    await expect(page.getByRole('heading',{name:'页面不存在'})).toBeVisible();
    await expect(page.locator('body')).not.toContainText('正在检查登录状态');
    await expect(page.locator('body')).toHaveCSS('background-color','rgb(248, 248, 251)');
    expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
    expect(await page.locator('script').count()).toBe(0);
  }
  const css=await request.get('/404.css');expect(css.status()).toBe(200);expect(css.headers()['content-type']).toContain('text/css');
  const auth=await request.get('/auth.js');expect(auth.status()).toBe(200);expect(auth.headers()['content-type']).not.toContain('text/html');
  const api=await request.get('/api/me');expect(api.status()).toBe(401);expect(api.headers()['content-type']).toContain('application/json');
  const manifest=await request.get('/download/manifest.json');
  if(manifest.status()===404)expect(await manifest.text()).not.toContain('页面不存在');
  await page.getByRole('link',{name:'去题库广场'}).click();await expect(page.getByRole('heading',{name:'发现题库'})).toBeVisible();
  expect(errors).toEqual([]);
});
