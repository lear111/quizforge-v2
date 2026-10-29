const MiB=1024*1024,MAX_INPUT=20*MiB,MAX_OUTPUT=4*MiB,MAX_EDGE=2560;

async function decodePhoto(file,win) {
  if(win.createImageBitmap) {
    try { const bitmap=await win.createImageBitmap(file,{imageOrientation:'from-image'});return {image:bitmap,width:bitmap.width,height:bitmap.height,close:()=>bitmap.close()}; }
    catch { /* Native image decoding is the fallback on browsers with partial bitmap support. */ }
  }
  const image=new win.Image(),url=win.URL.createObjectURL(file);
  try {
    await new Promise((resolve,reject)=>{
      const timer=win.setTimeout(()=>{image.src='';reject(new Error('照片读取超时，请重试。'));},15000);
      image.onload=()=>{win.clearTimeout(timer);resolve();};
      image.onerror=()=>{win.clearTimeout(timer);reject(new Error('无法读取照片，请使用 JPEG、PNG 或 WebP 格式。'));};image.src=url;
    });
    return {image,width:image.naturalWidth,height:image.naturalHeight,close:()=>{image.src='';}};
  } finally { win.URL.revokeObjectURL(url); }
}

export async function preparePhoto(file,win=globalThis) {
  if(!file||!file.type?.startsWith('image/')||file.size<=0||file.size>MAX_INPUT)throw new Error('请选择不超过 20 MiB 的照片。');
  const decoded=await decodePhoto(file,win),canvas=win.document.createElement('canvas');
  try {
    const {width,height}=decoded;
    if(!Number.isFinite(width)||!Number.isFinite(height)||width<=0||height<=0||width*height>40000000)throw new Error('照片分辨率过大或无效，请降低相机分辨率后重试。');
    if(['image/jpeg','image/png','image/webp'].includes(file.type)&&file.size<=MAX_OUTPUT&&Math.max(width,height)<=MAX_EDGE)return file;
    let ratio=Math.min(1,MAX_EDGE/Math.max(width,height));
    for(let attempt=0;attempt<8;attempt++) {
      canvas.width=Math.max(1,Math.round(width*ratio));canvas.height=Math.max(1,Math.round(height*ratio));
      const context=canvas.getContext('2d');if(!context)throw new Error('当前浏览器无法处理照片，请先缩小照片再插入图片。');
      context.fillStyle='#ffffff';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(decoded.image,0,0,canvas.width,canvas.height);
      const quality=attempt<3?[.9,.8,.65][attempt]:.75;
      const blob=await new Promise((resolve,reject)=>{
        const timer=win.setTimeout(()=>reject(new Error('照片处理超时，请重试。')),12000);
        canvas.toBlob(value=>{win.clearTimeout(timer);value?resolve(value):reject(new Error('照片处理失败，请使用 JPEG 图片重试。'));},'image/jpeg',quality);
      });
      if(blob.size>0&&blob.size<=MAX_OUTPUT&&blob.type==='image/jpeg')return new win.File([blob],(file.name||'答案照片').replace(/\.[^.]*$/,'')+'.jpg',{type:'image/jpeg',lastModified:file.lastModified});
      if(attempt>=2)ratio*=.75;
    }
    throw new Error('照片仍超过 4 MiB，请缩小照片后重试。');
  } finally { decoded.close();canvas.width=0;canvas.height=0; }
}
