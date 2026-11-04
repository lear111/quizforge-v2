import {executeBrowserGrade} from './browser-rules.js';

// 本地失败不提交伪造的结果；保留服务器等待状态，交给现有后台任务兜底。
export async function gradeTicket(path,ticket,{request,execute=executeBrowserGrade}={}) {
  let outcome;
  try {outcome=await execute(ticket);} catch {return null;}
  const result=await request(`${path}/grading/reports`,{method:'POST',body:JSON.stringify({items:[{
    questionId:ticket.questionId,generation:ticket.generation,outcome
  }]})});
  return result.items[0];
}

export async function gradePending(path,{request,execute=executeBrowserGrade,onProgress=()=>{}}={}) {
  let completed=0;
  for (;;) {
    const pending=await request(`${path}/grading/pending`);
    if (!pending.items.length) return completed;
    const reports=[];
    for (const ticket of pending.items) {
      try {reports.push({questionId:ticket.questionId,generation:ticket.generation,outcome:await execute(ticket)});}
      catch {/* 失败的这一题等待服务器补判，其他题仍可完成。 */}
    }
    if (!reports.length) return completed;
    // 单批同时受题数和 UTF-8 字节上限约束，避免多道大投影拼出超限请求。
    let batch=[];
    const flush=async()=>{
      if (!batch.length) return;
      await request(`${path}/grading/reports`,{method:'POST',body:JSON.stringify({items:batch})});batch=[];
    };
    for (const report of reports) {
      if (new TextEncoder().encode(JSON.stringify({items:[...batch,report]})).length>8*1024*1024) await flush();
      batch.push(report);
    }
    await flush();
    completed+=reports.length;onProgress(completed);
    // 本批有失败时不立刻重复执行坏规则，等待下一次打开或服务器兜底。
    if (reports.length!==pending.items.length) return completed;
  }
}
