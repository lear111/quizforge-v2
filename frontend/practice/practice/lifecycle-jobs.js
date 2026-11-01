// 只轮询任务行，完成后取回已持久化的结果。
export async function waitLifecycleJob(path,jobId,{request,onProgress=()=>{},delay=ms=>new Promise(resolve=>setTimeout(resolve,ms)),attempts=1200}={}) {
  for(let i=0;i<attempts;i++) {
    const job=await request(`${path}/jobs/${encodeURIComponent(jobId)}`);onProgress(job);
    if(job.status==='completed')return job.result;
    if(job.status==='failed')throw Object.assign(new Error(job.error?.message||'处理失败，作答已保留，请重试'),{code:job.error?.code||'JOB_FAILED',jobFailed:true});
    await delay(500);
  }
  throw Object.assign(new Error('后台仍在处理，可刷新页面查看进度'),{code:'JOB_STILL_RUNNING'});
}
