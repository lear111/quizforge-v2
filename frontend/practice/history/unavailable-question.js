// 停用拓展的历史由宿主呈现，不创建 iframe、不执行拓展或冻结数据中的代码。
export function mountUnavailableQuestion(container,payload,message='该题型拓展已被管理员停用，无法显示原页面') {
  const document=container.ownerDocument,frame=document.createElement('section');
  frame.className='history-unavailable-question';
  frame.style.cssText='padding:24px;overflow-wrap:anywhere';
  const text=(tag,value)=>{const node=document.createElement(tag);node.textContent=value;frame.append(node);};
  text('h2',payload.question?.title||'历史题目');
  text('p',message);
  const result=payload.state?.result||{},sources={browser:'浏览器',server:'服务器',rule:'拓展规则',manual:'人工',ai:'AI','ai-assisted':'AI 辅助'};
  text('p',`分数：${result.score??'待评分'} / ${result.maxScore??'未记录'} · 评分来源：${sources[result.gradingSource||payload.practiceSignals?.gradingSource]||'未记录'}`);
  text('h3','已保存作答');
  const answer=document.createElement('pre');answer.style.whiteSpace='pre-wrap';
  answer.textContent=JSON.stringify(payload.state?.answer??null,null,2);frame.append(answer);
  const ids=new Set();
  const visit=value=>{
    if(!value||typeof value!=='object')return;
    if(typeof value.attrs?.assetId==='string'&&/^[a-f0-9]{64}$/.test(value.attrs.assetId))ids.add(value.attrs.assetId);
    for(const child of Object.values(value))visit(child);
  };
  visit(payload);
  for(const id of ids){const image=document.createElement('img');image.src=`/api/resources/${id}`;image.alt='历史图片';image.loading='lazy';image.style.maxWidth='100%';frame.append(image);}
  container.append(frame);
  return {frame,ready:Promise.resolve(),flush:async()=>{},update(){},destroy(){frame.remove();}};
}
