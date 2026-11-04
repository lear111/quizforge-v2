import test from 'node:test';
import assert from 'node:assert/strict';
import {gradeTicket,gradePending} from '../practice/grading/browser-grading.js';

test('本地执行失败不上报，留给服务器兜底',async()=>{
  let requests=0;
  assert.equal(await gradeTicket('/bank',{}, {execute:async()=>{throw Error('超时');},request:async()=>{requests++;}}),null);
  assert.equal(requests,0);
});

test('成功上报包含本题代次与完整结果，返回已持久化的响应',async()=>{
  const ticket={questionId:'q',generation:'2:3'},outcome={result:{},projected:{},score:{score:0,maxScore:1,gradingStatus:'graded'}};
  const value=await gradeTicket('/bank',ticket,{execute:async()=>outcome,request:async(path,options)=>{
    assert.equal(path,'/bank/grading/reports');assert.equal(options.method,'POST');
    assert.deepEqual(JSON.parse(options.body),{items:[{questionId:'q',generation:'2:3',outcome}]});
    return {items:[{revision:9}]};
  }});
  assert.deepEqual(value,{revision:9});
});

test('按服务器有界分页连续批量上报，不重复处理已完成题目',async()=>{
  const pending=Array.from({length:43},(_,i)=>({questionId:`q${i}`,generation:'1:0'})),batches=[],progress=[];
  const count=await gradePending('/exam',{execute:async ticket=>({result:ticket.questionId}),onProgress:value=>progress.push(value),request:async(path,options)=>{
    if(path.endsWith('/pending'))return {items:pending.slice(0,20)};
    const items=JSON.parse(options.body).items;batches.push(items.length);pending.splice(0,items.length);return {items:[]};
  }});
  assert.equal(count,43);assert.deepEqual(batches,[20,20,3]);assert.deepEqual(progress,[20,40,43]);
});

test('一题本地失败仍提交其他题，不在当前页面无限重试坏规则',async()=>{
  let reads=0;const reports=[];
  const count=await gradePending('/exam',{execute:async ticket=>{if(ticket.questionId==='bad')throw Error('超时');return {};},request:async(path,options)=>{
    if(path.endsWith('/pending')){reads++;return {items:[{questionId:'bad',generation:'1:0'},{questionId:'good',generation:'1:0'}]};}
    reports.push(...JSON.parse(options.body).items);return {};
  }});
  assert.equal(count,1);assert.equal(reads,1);assert.equal(reports[0].questionId,'good');
});

test('上报中断向调用者抛出，保持等待状态，不制造成功',async()=>{
  const error=Error('断网');
  await assert.rejects(gradeTicket('/bank',{questionId:'q',generation:'1:0'},{execute:async()=>({}),request:async()=>{throw error;}}),value=>value===error);
});

test('批次按 UTF-8 大小拆分，避免多道大投影超过 8 MiB',async()=>{
  const pending=Array.from({length:6},(_,i)=>({questionId:`q${i}`,generation:'1:0'})),sizes=[];
  const count=await gradePending('/exam',{execute:async()=>({projected:'中'.repeat(600000)}),request:async(path,options)=>{
    if(path.endsWith('/pending'))return {items:pending.slice()};
    const items=JSON.parse(options.body).items;sizes.push(new TextEncoder().encode(options.body).length);
    pending.splice(0,items.length);return {};
  }});
  assert.equal(count,6);assert.equal(sizes.length,2);assert.ok(sizes.every(size=>size<=8*1024*1024));
});
