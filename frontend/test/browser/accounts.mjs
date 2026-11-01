import {expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {resolve} from 'node:path';

// 注册默认需要邮箱 / 短信验证码（L4 D-1）。浏览器测试运行在 QF_VERIFICATION_MODE=fake 的 Compose 中，
// 假实现把验证码写进应用日志；这里按脱敏后的邮箱（每个测试唯一的域名）读取最新的注册验证码。

/** 每个测试唯一的邮箱：域名唯一，使日志中的脱敏地址也唯一。 */
export function uniqueEmail(label) {
  return `${label}@${crypto.randomUUID()}.example.test`;
}

function masked(email) {
  return `${email[0]}***${email.slice(email.indexOf('@'))}`;
}

/** 读取发给该邮箱的最新注册验证码；日志可能稍有延迟，在截止时间内重读。 */
export async function devCode(email) {
  const project=process.env.QF_COMPOSE_PROJECT;
  if(!project)throw new Error('QF_COMPOSE_PROJECT is required to read development verification codes');
  const marker=`[开发验证码] ${masked(email)} 注册验证码：`;
  const deadline=Date.now()+15000;
  while(Date.now()<deadline) {
    const logs=execFileSync('docker',['compose','-p',project,'-f',resolve('../deploy/docker-compose.yml'),'logs','app','--no-color','--since','10m'],{encoding:'utf8',maxBuffer:64*1024*1024});
    const line=logs.split('\n').filter(row=>row.includes(marker)).at(-1);
    if(line)return line.slice(line.indexOf(marker)+marker.length).trim().slice(0,6);
    await new Promise(done=>setTimeout(done,250));
  }
  throw new Error(`no development code for ${masked(email)}`);
}

/** 通过接口注册并登录（页面的请求上下文共享 Cookie）。 */
export async function registerByApi(page,label,name,password='password123') {
  await page.goto('/');
  const origin=new URL(page.url()).origin;
  const email=uniqueEmail(label);
  expect((await page.request.post('/api/auth/email-code',{headers:{Origin:origin},data:{identifier:email}})).status()).toBe(200);
  const registered=await page.request.post('/api/auth/register',{headers:{Origin:origin},data:{identifier:email,name,password,code:await devCode(email)}});
  expect(registered.status()).toBe(200);
  return {origin,email,registered};
}

/** 在账号页的注册表单中完成注册（调用前已切换到“注册”标签）。 */
export async function registerByForm(page,label,name,password='test-password-123') {
  const email=uniqueEmail(label);
  await page.locator('#register-email').fill(email);
  await page.locator('#register-name').fill(name);
  await page.locator('#register-password').fill(password);
  await page.locator('#register-confirm').fill(password);
  await page.locator('#register-form [data-send-code]').click();
  await expect(page.locator('#register-form [data-send-code]')).toContainText('秒后重发');
  await page.locator('#register-code').fill(await devCode(email));
  await page.getByRole('button',{name:'注册并登录',exact:true}).click();
  return email;
}
