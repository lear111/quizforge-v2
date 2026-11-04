import {el,time,shortId} from './format.js';

const STATES = {queued:'排队中',running:'正在处理',failed:'处理失败'};
export async function render(container, context) {
  const holder = el('section',{class:'qa-panel'});
  container.append(el('h1',{text:'导入校验队列'}),holder);
  async function refresh() {
    const data = await context.request('/api/admin/import-jobs');
    const refreshButton = el('button',{type:'button',class:'qa-button',text:'刷新'});
    refreshButton.addEventListener('click', async () => {
      refreshButton.disabled = true;
      try { await refresh(); } catch(error) { context.toast(error.message,'danger'); }
      finally { refreshButton.disabled = false; }
    });
    holder.replaceChildren(el('p',{text:`排队 ${data.counts.queued} · 正在处理 ${data.counts.running} · 处理失败 ${data.counts.failed}（列表最多显示 200 条）`}),refreshButton,
      el('div',{class:'qa-table-wrap'},el('table',{class:'qa-table'},
        el('thead',{},el('tr',{},...['题库包 / 版本','状态','已尝试次数','创建 / 下次处理','原因','操作'].map(text=>el('th',{scope:'col',text})))),
        el('tbody',{},...data.items.map(job=>{
          const retry = job.status === 'failed' ? el('button',{type:'button',class:'qa-button',text:'重试'}) : null;
          retry?.addEventListener('click',async()=>{
            retry.disabled = true;
            try {
              await context.request(`/api/admin/import-jobs/${encodeURIComponent(job.id)}/retry`,'POST');
              context.toast('任务已重新入队');
              await refresh();
            } catch(error) { context.toast(error.message,'danger'); }
            finally { retry.disabled = false; }
          });
          return el('tr',{'data-job-id':job.id},el('td',{text:`${shortId(job.packId)} / 第 ${job.revision} 版`}),
            el('td',{text:STATES[job.status] ?? job.status}),el('td',{text:String(job.attempts)}),
            el('td',{},time(job.createdAt),el('br'),time(job.availableAt)),el('td',{text:job.failureCode ?? '—'}),el('td',{},retry));
        })))));
  }
  await refresh();
}
