import {test} from 'node:test';
import assert from 'node:assert/strict';
import {waitLifecycleJob} from '../practice/practice/lifecycle-jobs.js';
test('job polling loads progress only and returns the frozen completion result',async()=>{
  const statuses=[{status:'queued',completed:0},{status:'running',completed:1},{status:'completed',result:{finished:true}}],seen=[];let delays=0;
  const result=await waitLifecycleJob('/api/collections/bank/id','job',{request:async path=>{assert.equal(path,'/api/collections/bank/id/jobs/job');return statuses.shift();},onProgress:job=>seen.push(job.status),delay:async()=>delays++});
  assert.deepEqual(result,{finished:true});assert.deepEqual(seen,['queued','running','completed']);assert.equal(delays,2);
});
test('job failure preserves its code and never retries an operation',async()=>{
  let calls=0;await assert.rejects(waitLifecycleJob('/c','j',{request:async()=>{calls++;return {status:'failed',error:{code:'RULE_UNAVAILABLE',message:'作答已保留'}};}}),error=>error.jobFailed===true&&error.code==='RULE_UNAVAILABLE'&&error.message==='作答已保留');assert.equal(calls,1);
});
test('poll budget exhaustion leaves the server task running and tells the user',async()=>{
  await assert.rejects(waitLifecycleJob('/c','j',{request:async()=>({status:'running'}),delay:async()=>{},attempts:2}),error=>error.code==='JOB_STILL_RUNNING');
});
