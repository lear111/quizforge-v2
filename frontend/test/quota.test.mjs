import {test} from 'node:test';
import assert from 'node:assert/strict';
import {formatBytes,quotaCards} from '../public/quota.js';

test('quota cards show logical storage and Beijing daily downloads with binary units',()=>{
  const cards=quotaCards({storage:{usedBytes:1024,limitBytes:268435456},dailyDownload:{usedBytes:0,limitBytes:268435456},day:'2026-11-01'});
  assert.equal(cards.length,2);
  assert.equal(cards[0].text,'1.00 KiB / 256.00 MiB');
  assert.equal(cards[1].title,'今日下载流量');
  assert.match(cards[1].caption,/2026-11-01（北京时间）/);
});
test('zero limit and lowered limit preserve used amount while bounding the progress bar',()=>{
  const cards=quotaCards({storage:{usedBytes:4096,limitBytes:1024},dailyDownload:{usedBytes:128,limitBytes:0},day:'2026-11-02'});
  assert.equal(cards[0].text,'4.00 KiB / 1.00 KiB');
  assert.equal(cards[0].value,1024);
  assert.equal(cards[1].max,1);
  assert.equal(cards[1].value,0);
});
test('invalid usage is rejected rather than showing a misleading zero',()=>{
  for(const invalid of [-1,NaN,Infinity,undefined,'10'])assert.throws(()=>formatBytes(invalid));
  assert.throws(()=>quotaCards({day:'<img src=x>'}));
});
