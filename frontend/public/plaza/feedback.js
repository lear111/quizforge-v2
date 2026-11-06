import {el,time} from '../admin/pages/format.js';

/** 用户文字只通过 textContent 显示；评论与举报不支持 HTML。 */
export async function mountFeedback(holder,packId,request,{admin=false}={}) {
  const section=el('section',{'aria-label':'评论与举报',class:'plaza-feedback qa-panel'});
  const notice=el('p',{role:'status'}),list=el('div',{'data-comments':true});
  const more=el('button',{type:'button',text:'更多评论',hidden:true});
  const body=el('textarea',{name:'body',rows:'3',maxlength:'2000',required:true});
  const submit=el('button',{type:'submit',text:'发表评论'});
  const form=el('form',{class:'qa-form'},el('label',{},'评论',body),submit);
  const reason=el('textarea',{name:'reason',rows:'3',maxlength:'1000',required:true});
  const reportButton=el('button',{type:'submit',text:'提交举报'});
  const reportForm=el('form',{class:'qa-form'},el('label',{},'举报原因',reason),reportButton);
  const report=el('details',{},el('summary',{text:'举报题库包'}),reportForm);
  section.append(el('h3',{text:'评论'}),notice,list,more,form,report);holder.append(section);
  let cursor=null,busy=false,loadGeneration=0;
  async function action(button,fn) {
    if(busy)return;busy=true;button.disabled=true;notice.textContent='';
    try{await fn();}catch(error){notice.textContent=error.message;}finally{busy=false;button.disabled=false;}
  }
  async function load(append=false) {
    const generation=++loadGeneration;
    const data=await request(`/api/plaza/packs/${encodeURIComponent(packId)}/comments${append&&cursor?'?cursor='+encodeURIComponent(cursor):''}`);
    if(generation!==loadGeneration)return;
    if(!append)list.replaceChildren();
    for(const comment of data.items) {
      const item=el('article',{'data-comment-id':comment.id,class:'plaza-comment'},
        el('p',{text:`${comment.author??'用户'} · ${time(comment.createdAt)}`}),
        el('p',{class:'plaza-comment-body qa-prewrap',text:comment.body}));
      if(comment.canDelete||admin) {
        const remove=el('button',{type:'button',text:'删除评论'});
        remove.onclick=()=>action(remove,async()=>{
          if(!confirm('删除这条评论？'))return;
          await request(`${admin?'/api/admin/catalog':'/api/plaza'}/comments/${encodeURIComponent(comment.id)}`,'DELETE');
          await load();notice.textContent='评论已删除';
        });item.append(remove);
      }
      list.append(item);
    }
    cursor=data.nextCursor;more.hidden=!cursor;
    if(!list.children.length)list.append(el('p',{text:'暂无评论'}));
  }
  more.onclick=()=>action(more,()=>load(true));
  form.onsubmit=event=>{event.preventDefault();action(submit,async()=>{
    await request(`/api/plaza/packs/${encodeURIComponent(packId)}/comments`,'POST',{body:body.value});
    body.value='';await load();notice.textContent='评论已发表';
  });};
  reportForm.onsubmit=event=>{event.preventDefault();action(reportButton,async()=>{
    await request(`/api/plaza/packs/${encodeURIComponent(packId)}/report`,'POST',{reason:reason.value});
    reason.value='';notice.textContent='举报已提交，处理结果将通过站内通知告知你';
  });};
  try{await load();}catch(error){notice.textContent=error.message;}
}
