import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mergeDraftResponse} from '../practice/practice/draft-response.js';

test('draft delta preserves the loaded projection, board and capabilities while updating state and stamp',()=>{
  const previous={question:{data:{prompt:'安全投影'}},whiteboard:{draftLocked:false},capabilities:{canSubmit:true},state:{revision:0},stamp:{revision:0}};
  const saved={revision:1,state:{revision:1,answer:'我的答案'},stamp:{revision:1},question:{data:{answer:'不得替换'}}};
  const result=mergeDraftResponse(previous,saved);
  assert.equal(result.question,previous.question);
  assert.equal(result.whiteboard,previous.whiteboard);
  assert.equal(result.capabilities,previous.capabilities);
  assert.equal(result.state,saved.state);
  assert.equal(result.stamp,saved.stamp);
  assert.equal(previous.state.revision,0);
});

test('a draft delta cannot create a readable question cache entry without an existing projection',()=>{
  assert.equal(mergeDraftResponse(null,{state:{revision:1},stamp:{revision:1}}),null);
});
