// Serialized into the sandbox with bootstrapV1; keep the helper self-contained.
export async function captureAnswerSnapshots(answer,{model,resources,document,Image:ImageConstructor,timeoutMs=12000}) {
  const result=structuredClone(answer),pending=[result];
  const fail=message=>Object.assign(new Error(message),{code:'ANSWER_SNAPSHOT_FAILED'});
  const image=async id=>{
    const resource=await resources.get(id);
    return new Promise((resolve,reject)=>{
      const element=new ImageConstructor(),timer=setTimeout(()=>reject(fail('答案图片加载超时，请重试')),timeoutMs);
      element.onload=()=>{clearTimeout(timer);resolve(element);};element.onerror=()=>{clearTimeout(timer);reject(fail('答案图片加载失败，请重试'));};element.src=resource.url;
    });
  };
  const encode=(canvas,type,quality)=>new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(fail('答案截图超时，请重试')),timeoutMs);
    canvas.toBlob(blob=>{clearTimeout(timer);blob?resolve(blob):reject(fail('答案截图失败，请重试'));},type,quality);
  });
  while(pending.length){
    const value=pending.pop();if(!value||typeof value!=='object')continue;
    if(value.type==='qf-answer-area'){
      const doc=model.normalize(value,{snapshot:false}),images=new Map();
      for(const item of doc.images||[])if(!images.has(item.attrs.assetId))images.set(item.attrs.assetId,await image(item.attrs.assetId));
      const canvas=document.createElement('canvas');canvas.width=doc.width;canvas.height=doc.height;
      try{
        model.paint(canvas.getContext('2d'),doc,{images});
        let blob=await encode(canvas,'image/png');
        if(blob.size>4*1024*1024)blob=await encode(canvas,'image/jpeg',.85);
        if(blob.size>4*1024*1024)throw fail('答案截图超过 4 MiB，请精简图片后重试');
        const uploaded=await resources.put(blob);
        if(!/^[a-f0-9]{64}$/.test(uploaded?.id||''))throw fail('答案截图上传失败，请重试');
        value.snapshot={type:'image',attrs:{assetId:uploaded.id}};
      }finally{canvas.width=0;canvas.height=0;}
      continue;
    }
    pending.push(...Object.values(value));
  }
  return result;
}
