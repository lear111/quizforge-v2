import {el,time,badge} from './format.js';
import {mountFeedback} from '../../plaza/feedback.js';

const labels={pending:'未处理',removed:'已下架',dismissed:'已驳回',handled:'已处理'};
export async function render(container, context) {
  if(context.param){await detail(container,context,context.param);return;}
  container.append(el('h1',{text:'举报处理'}));
  const list=el('div'),more=el('button',{type:'button',class:'qa-button',text:'加载更多'});
  container.append(list,more);let cursor=null;
  async function load(){
    const data=await context.request('/api/admin/catalog/reports'+(cursor?'?cursor='+encodeURIComponent(cursor):''));
    for(const report of data.items)list.append(el('article',{class:'qa-panel','data-report-id':report.id},
      el('h2',{},el('a',{href:'#reports/'+report.id,text:report.title??'题库包'}),badge(labels[report.status])),
      el('p',{text:`${report.reporter??'用户'} · ${time(report.createdAt)}`}),
      el('p',{class:'qa-prewrap',text:report.reason})));
    cursor=data.nextCursor;more.hidden=!cursor;
    if(!data.items.length&&!list.children.length)list.append(el('p',{text:'暂无举报'}));
  }
  more.onclick=async()=>{more.disabled=true;try{await load();}catch(error){context.toast(error.message,'danger');}finally{more.disabled=false;}};
  await load();
}

async function detail(container,context,id){
  const report=await context.request('/api/admin/catalog/reports/'+encodeURIComponent(id));
  container.append(el('a',{href:'#reports',text:'← 返回举报列表'}),el('h1',{text:'举报详情'}),
    el('section',{class:'qa-panel'},el('h2',{text:report.title??'题库包'}),
      el('a',{href:'/plaza/#'+encodeURIComponent(report.packId),target:'_blank',rel:'noopener',text:'查看题库包'}),
      el('p',{text:`举报人：${report.reporter??'用户'} · ${time(report.createdAt)}`}),
      el('p',{class:'qa-prewrap',text:report.reason}),badge(labels[report.status])));
  if(report.status==='pending'){
    const select=el('select',{name:'decision'},...Object.entries({removed:'下架题库包',dismissed:'驳回举报',handled:'已处理'}).map(([value,text])=>el('option',{value,text})));
    const note=el('textarea',{name:'note',rows:'3',maxlength:'1000',required:true});
    const submit=el('button',{type:'submit',class:'qa-button danger',text:'确认处理'});
    const form=el('form',{class:'qa-panel qa-form'},el('label',{},'处理方式',select),el('label',{},'处理说明',note),submit);
    form.onsubmit=async event=>{event.preventDefault();if(submit.disabled)return;
      if(!confirm(select.value==='removed'?'下架整个题库包？已导入的练习实例会保留。':'确认提交处理结果？'))return;
      submit.disabled=true;
      try{await context.request(`/api/admin/catalog/reports/${encodeURIComponent(id)}/resolve`,'POST',{decision:select.value,note:note.value});
        context.toast('已处理，已通知举报人','success');container.replaceChildren();await detail(container,context,id);
      }catch(error){context.toast(error.message,'danger');submit.disabled=false;}
    };container.append(form);
  }else container.append(el('section',{class:'qa-panel'},el('p',{class:'qa-prewrap',text:report.resolution}),
    el('p',{text:`${report.reviewer??'管理员'} · ${time(report.reviewedAt)}`})));
  if(report.packStatus==='active')await mountFeedback(container,report.packId,context.request,{admin:true});
}
