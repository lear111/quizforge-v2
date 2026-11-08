import {test,expect} from '@playwright/test';
import {registerByApi} from './accounts.mjs';

// 安全响应头对所有路径生效（包括自己有 add_header 的 location），页面在 CSP 下没有违规。
// 练习、拓展 iframe、浏览器判分、听力等完整流程由其他浏览器测试在同一套响应头下覆盖。
const collectViolations=()=>{
  window.__cspViolations=[];
  document.addEventListener('securitypolicyviolation',event=>window.__cspViolations.push(`${event.violatedDirective} ${event.blockedURI}`));
};

test('security headers are sent everywhere and pages load without CSP violations',async({page,request})=>{
  test.skip(process.env.QF_READ_BROWSER_TESTS!=='1','Requires an explicitly isolated Compose fixture');
  test.setTimeout(120000);
  const common=headers=>{
    expect(headers['x-content-type-options']).toBe('nosniff');
    expect(headers['referrer-policy']).toBe('strict-origin-when-cross-origin');
    expect(headers['permissions-policy']).toContain('camera=()');
  };
  for(const path of ['/','/plaza/','/library/','/publish/','/admin/','/download/','/shared/richtext/service.json','/practice/']){
    const {headers}=await request.get(path,{maxRedirects:0}).then(response=>({headers:response.headers()}));
    common(headers);
    expect(headers['content-security-policy'],path).toContain("frame-ancestors 'none'");
    expect(headers['content-security-policy'],path).toContain("object-src 'none'");
  }
  const site=(await request.get('/')).headers()['content-security-policy'];
  expect(site).toContain("script-src 'self' https://challenges.cloudflare.com");
  expect(site).not.toContain("'unsafe-inline' blob:");
  const practice=(await request.get('/practice/')).headers()['content-security-policy'];
  expect(practice).toContain('connect-src \'self\' https:');
  expect(practice).toContain('media-src \'self\' https:');
  // 接口响应不能作为页面执行；错误响应也带响应头。
  const api=(await request.get('/api/health')).headers();
  common(api);
  expect(api['content-security-policy']).toContain("default-src 'none'");
  common((await request.get('/api/me')).headers());
  // 确认页只有应用自己更严格的一份 CSP（两份会同时生效）。
  const consent=(await request.get('/connect/authorize?v=2')).headers();
  expect(consent['content-security-policy']).toContain("form-action 'none'");
  expect(consent['content-security-policy']).not.toContain('https://challenges.cloudflare.com');

  await page.addInitScript(collectViolations);
  await registerByApi(page,'csp-user','安全头');
  for(const path of ['/','/plaza/','/library/','/publish/','/download/','/practice/']){
    await page.goto(path);
    await page.waitForLoadState('networkidle');
    expect(await page.evaluate(()=>window.__cspViolations),path).toEqual([]);
  }
});
