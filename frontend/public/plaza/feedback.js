import {el,time} from '../admin/pages/format.js';

/** 用户文字只通过 textContent 显示；评论与举报不支持 HTML。 */
export async function mountFeedback(holder,packId,request,{admin=false}={}) {
  const section=el('section',{'aria-label':'评论与举报',class:'plaza-feedback qa-panel d-section'});
  const notice=el('p',{role:'status'}),list=el('div',{'data-comments':true});
  const more=el('button',{class:'btn-outline',type:'button',text:'更多评论',hidden:true});
  const body=el('textarea',{name:'body',rows:'3',maxlength:'2000',required:true,'aria-label':'评论',placeholder:'分享使用体验，或向作者提问…'});
  const submit=el('button',{class:'btn-filled',type:'submit',text:'发表评论'});
  const form=el('form',{class:'qa-form comment-box'},body,el('div',{class:'comment-foot'},el('span',{text:'友善交流，请勿发布广告或个人敏感信息'}),submit));
  const reason=el('textarea',{name:'reason',rows:'3',maxlength:'1000',required:true});
  const reportButton=el('button',{class:'btn-outline',type:'submit',text:'提交举报'});
  const reportForm=el('form',{class:'qa-form'},el('label',{},'举报原因',reason),reportButton);
  const report=el('details',{},el('summary',{text:'举报题库包'}),reportForm);
  section.append(el('h2',{text:'交流与反馈'}),form,notice,list,more,report);holder.append(section);
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
      const heading=el('div',{class:'comment-heading'},el('b',{text:comment.author??'用户'}),el('time',{text:time(comment.createdAt)}));
      const item=el('article',{'data-comment-id':comment.id,class:'plaza-comment comment'},
        el('span',{class:'mini-avatar','aria-hidden':true,text:Array.from(comment.author||'Q')[0]}),
        el('div',{class:'comment-content'},heading,el('p',{class:'plaza-comment-body qa-prewrap',text:comment.body})));
      if(comment.canDelete||admin) {
        const remove=el('button',{class:'text-btn',type:'button',text:'删除评论'});
        remove.onclick=()=>action(remove,async()=>{
          if(!confirm('删除这条评论？'))return;
          await request(`${admin?'/api/admin/catalog':'/api/plaza'}/comments/${encodeURIComponent(comment.id)}`,'DELETE');
          await load();notice.textContent='评论已删除';
        });heading.append(remove);
      }
      list.append(item);
    }
    cursor=data.nextCursor;more.hidden=!cursor;
    if(!list.children.length)list.append(el('p',{class:'comment-empty',text:'暂无评论，来分享你的使用体验吧。'}));
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
