import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createAiClient,aiTaskMessage} from '../practice/ai/ai-client.js';

test('AI task hints distinguish queue, adoption, retry and recharge and show exact charged units',()=>{
  assert.match(aiTaskMessage({status:'queued',cost:0}),/排队/);
  assert.match(aiTaskMessage({status:'confirmed',cost:75}),/已采用.*¥0.0075/);
  assert.match(aiTaskMessage({status:'failed',error:{code:'AI_BALANCE_EXHAUSTED',message:'余额不足，请充值'}}),/充值/);
  assert.match(aiTaskMessage({status:'failed'}),/重试/);
});

test('existing SDK gets the same task envelope and refreshes an adopted question without grading again',async()=>{
  const calls=[],shown=[],updated=[];
  const task={taskId:'task-1',targetId:'part-1',status:'confirmed',cost:75};
  const client=createAiClient({path:'/api/collections/bank/c/questions/q',makeRequestId:()=> 'id',settled:async()=>{},
    request:async(path,options)=>{calls.push({path,options});return path.endsWith('/ai/current?targetId=part-1')?{task}:{state:{revision:4}};},
    onConfirmed:value=>updated.push(value),onTask:value=>shown.push(value),shouldRefreshTask:()=>true});
  assert.deepEqual(await client('ai-task',{targetId:'part-1'}),{ok:true,data:{task}});
  assert.equal(calls.length,2);
  assert.equal(shown[0],task);
  assert.deepEqual(updated,[{state:{revision:4}}]);
});
