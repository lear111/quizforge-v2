// 页面不可用时仅显示提示，不呈现冻结数据，也不创建 iframe 或执行脚本。
export function mountUnavailableQuestion(container,message='该题的历史记录已丢失（题型拓展已被管理员停用）') {
  const document=container.ownerDocument,frame=document.createElement('section');
  frame.className='history-unavailable-question';
  frame.style.cssText='padding:24px;overflow-wrap:anywhere';
  const notice=document.createElement('p');notice.textContent=message;frame.append(notice);
  container.append(frame);
  return {frame,ready:Promise.resolve(),flush:async()=>{},update(){},destroy(){frame.remove();}};
}
