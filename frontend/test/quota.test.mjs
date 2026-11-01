import {test} from 'node:test';
import assert from 'node:assert/strict';
import {formatBytes,quotaCards} from '../public/quota.js';

test('quota shows private-bank storage only and ignores obsolete daily-download counters',()=>{
  const cards=quotaCards({storage:{usedBytes:1024,limitBytes:268435456},dailyDownload:{usedBytes:0,limitBytes:268435456},day:'2026-11-01'});
  assert.equal(cards.length,1);
  assert.equal(cards[0].text,'1.00 KiB / 256.00 MiB');
  assert.equal(cards[0].title,'存储空间');
  assert.match(cards[0].caption,/仅私人上传的题库/);
});
test('zero limit and lowered limit preserve used amount while bounding the progress bar',()=>{
  const cards=quotaCards({storage:{usedBytes:4096,limitBytes:1024},dailyDownload:{usedBytes:128,limitBytes:0},day:'2026-11-02'});
  assert.equal(cards[0].text,'4.00 KiB / 1.00 KiB');
  assert.equal(cards[0].value,1024);
  const zero=quotaCards({storage:{usedBytes:128,limitBytes:0}})[0];
  assert.equal(zero.max,1);
  assert.equal(zero.value,0);
});
test('invalid usage is rejected rather than showing a misleading zero',()=>{
  for(const invalid of [-1,NaN,Infinity,undefined,'10'])assert.throws(()=>formatBytes(invalid));
  assert.throws(()=>quotaCards({}));
});
