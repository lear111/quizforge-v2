import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

// 账号页按与服务器相同的规则识别邮箱或手机号，用于选择发码接口与人机验证动作名。
async function channelOf() {
  const source=await readFile(new URL('../public/auth.js',import.meta.url),'utf8');
  const helper=source.slice(source.indexOf('function channelOf'),source.indexOf('function requireIdentifier'));
  return vm.runInNewContext(`${helper};channelOf`);
}

for (const [raw,expected] of [
  ['alice@example.com','email'],[' Alice@Example.COM ','email'],
  ['13800138000','phone'],['138 0013 8000','phone'],['+8613800138000','phone'],['8613800138000','phone'],['138-0013-8000','phone'],
  ['12800138000',null],['1380013800',null],['alice',null],['a@b',null],['',null],
])test(`identifier channel: ${JSON.stringify(raw)}`,async()=>{
  assert.equal((await channelOf())(raw),expected);
});

test('account page never embeds provider secrets and only loads the fixed Turnstile script',async()=>{
  const source=await readFile(new URL('../public/auth.js',import.meta.url),'utf8');
  const urls=[...source.matchAll(/https?:\/\/[^'"`\s]+/g)].map(match=>match[0]);
  assert.deepEqual(urls,['https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit']);
  for (const forbidden of ['secret','pepper','SecretKey','AccessKey'])assert.ok(!source.includes(forbidden),forbidden);
});
