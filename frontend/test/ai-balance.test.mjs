import {test} from 'node:test';
import assert from 'node:assert/strict';
import {formatMoney,ledgerItem} from '../public/ai-balance.js';
import {money,parseMoney} from '../public/admin/pages/format.js';

test('AI amounts are integers of 0.0001 yuan and keep two to four decimals',()=>{
  assert.equal(formatMoney(10000),'¥1.00');
  assert.equal(formatMoney(12345),'¥1.2345');
  assert.equal(formatMoney(12340),'¥1.234');
  assert.equal(formatMoney(-15000),'-¥1.50');
  assert.equal(formatMoney(-1),'-¥0.0001');
  for(const invalid of [1.5,NaN,undefined,'1'])assert.throws(()=>formatMoney(invalid));
  for(const value of [0,1,-1,12345,-98765,100000000])assert.equal(money(value),formatMoney(value));
});

test('admin amount input is parsed exactly without floating point',()=>{
  assert.equal(parseMoney('5'),50000);
  assert.equal(parseMoney('-1.5'),-15000);
  assert.equal(parseMoney('0.0001'),1);
  assert.equal(parseMoney('0.29'),2900);
  for(const invalid of ['','abc','1.23456','1e3','--1'])assert.equal(parseMoney(invalid),null);
});

test('ledger rows show kind, signed delta and the admin reason without the admin identity',()=>{
  const charge=ledgerItem({kind:'ai_call',delta:-120,balanceAfter:-20,createdAt:'2026-11-01T00:00:00Z'});
  assert.equal(charge.title,'AI 调用');
  assert.equal(charge.delta,'-¥0.012');
  assert.ok(charge.negative);
  assert.match(charge.caption,/余额 -¥0.002/);
  const admin=ledgerItem({kind:'admin',delta:50000,balanceAfter:60000,reason:'活动补偿',createdAt:'2026-11-01T00:00:00Z'});
  assert.equal(admin.title,'管理员调整：活动补偿');
  assert.equal(admin.delta,'+¥5.00');
});
