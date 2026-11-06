// 设备授权确认页：允许或拒绝后跳回客户端（遗留契约 C-05 第 2 节）。
const panel=document.querySelector('[data-request]');
panel.addEventListener('click',async event=>{
  const button=event.target.closest('[data-decision]');if(!button||button.disabled)return;
  const controls=[...panel.querySelectorAll('button')],error=panel.querySelector('[role="alert"]');
  controls.forEach(item=>{item.disabled=true;});error.textContent='';
  try{
    const response=await fetch('/api/connect/authorize',{method:'POST',credentials:'same-origin',cache:'no-store',redirect:'error',headers:{'Content-Type':'application/json'},body:JSON.stringify({request:panel.dataset.request,decision:button.dataset.decision})});
    const result=await response.json();
    if(!response.ok)throw new Error(result.error?.message||'授权未完成，请重试');
    location.href=result.redirect;
    panel.querySelector('.consent-actions').hidden=true;
    error.textContent=button.dataset.decision==='allow'?'已授权，请回到 QuizForge。可以关闭此页面。':'已取消，可以关闭此页面。';
  }catch(failure){error.textContent=failure.message==='Failed to fetch'?'无法连接服务器，请重试':failure.message;controls.forEach(item=>{item.disabled=false;});}
});
