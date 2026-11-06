import {test} from 'node:test';
import assert from 'node:assert/strict';
import {updateBadge,updateNotice} from '../practice/library/update-labels.js';

test('plaza banks show new-version, removed and deleted states; current and private banks show nothing',()=>{
  assert.equal(updateBadge({status:'pending',revision:3}),'有新版本');
  assert.match(updateNotice({status:'pending',revision:3}),/第 3 版.*再练一次/);
  assert.equal(updateBadge({status:'removed'}),'作者已移除');
  assert.match(updateNotice({status:'removed'}),/停在当前版本.*练习照常可用/);
  assert.equal(updateBadge({status:'deleted'}),'作者已删除');
  assert.match(updateNotice({status:'deleted'}),/删除这个题库包.*练习照常可用/);
  for(const quiet of [{status:'current'},null,undefined,{status:'unknown'}]){
    assert.equal(updateBadge(quiet),null);
    assert.equal(updateNotice(quiet),null);
  }
});
