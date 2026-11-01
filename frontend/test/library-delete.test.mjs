import {test} from 'node:test';
import assert from 'node:assert/strict';
import {changeLibrary} from '../practice/library/library-management.js';
import {renderOutlineView} from '../practice/outline/outline-view.js';

test('bank deletion calls the owned collection endpoint and reports success only after it succeeds',async()=>{
  const calls=[];
  const operation={kind:'bank',target:'item',action:'delete'};
  assert.deepEqual(await changeLibrary(async(...args)=>calls.push(args),operation,{id:'bank/1'}),{deleted:true});
  assert.equal(calls[0][0],'/api/collections/bank/bank%2F1');
  assert.equal(calls[0][1].method,'DELETE');
  const failure=new Error('存储更新失败');
  await assert.rejects(changeLibrary(async()=>{throw failure;},operation,{id:'bank/1'}),error=>error===failure);
});

test('closing the last bank renders an empty outline without a previous render',()=>{
  let cleared=0;
  const root={replaceChildren(){cleared++;}};
  const document={querySelector(){return root;}};
  renderOutlineView({document,pane:undefined});
  renderOutlineView({document,pane:undefined});
  assert.equal(cleared,1);
});
