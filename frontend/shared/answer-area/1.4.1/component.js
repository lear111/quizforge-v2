export const ANSWER_AREA_STYLE=`
.qf-answer-shared{margin:16px 0;color:#334155;font:14px/1.5 system-ui,sans-serif;border:2px solid #9567cf;border-radius:14px;position:relative;background:#fff;box-shadow:0 0 0 3px #9567cf10}
.qf-answer-shared-head{padding:10px 12px;border-radius:12px 12px 0 0;background:#f5effc;border-bottom:1px solid #d9c8ef;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:8px}
.qf-answer-shared-heading{flex:1;min-width:150px}.qf-answer-shared-heading strong{font-size:14px}.qf-answer-shared-instruction{margin:3px 0 0;color:#716280;font-size:12px}
.qf-answer-shared-actions{display:flex;gap:6px;align-items:center;flex-wrap:wrap}.qf-answer-shared button,.qf-answer-pick{position:relative;border:1px solid #d7c8e9;border-radius:7px;padding:5px 9px;background:white;color:#654580;cursor:pointer;font:inherit}.qf-answer-shared button:disabled{opacity:.5;cursor:default}
.qf-answer-shared-picker{position:absolute;inset:0;width:100%;height:100%;opacity:0;cursor:pointer}
.qf-answer-shared-surface{position:relative;width:100%;overflow:hidden;background:#fff;border-radius:0 0 12px 12px}
.qf-answer-shared-plane{position:absolute;left:0;top:0;transform-origin:0 0}.qf-answer-shared textarea,.qf-answer-shared-snapshot{position:absolute;inset:0;display:block;width:100%;height:100%;margin:0;box-sizing:border-box}
.qf-answer-shared textarea{z-index:1;border:0;padding:16px;resize:none;outline:none;overflow:hidden;background:transparent;color:#263447;font:18px/29px Arial,"Microsoft YaHei","Noto Sans CJK SC",sans-serif;white-space:pre-wrap;word-break:break-all;overflow-wrap:anywhere;font-kerning:none;tab-size:4;touch-action:manipulation}
.qf-answer-shared-pictures{position:absolute;inset:0;pointer-events:none}.qf-answer-shared-picture{position:absolute;display:block;pointer-events:none}.qf-answer-image-overlay{position:absolute;left:0;z-index:4;transform-origin:0 0;pointer-events:none}.qf-answer-image-handle{position:absolute;border:2px solid #8055bd;box-sizing:border-box;touch-action:none;cursor:move;background:transparent;pointer-events:auto}
.qf-answer-shared .qf-answer-image-corner{position:absolute;width:26px;height:26px;padding:0;border:2px solid white;border-radius:50%;background:#8055bd;box-shadow:0 0 0 1px #8055bd;transform:translate(-50%,-50%) scale(var(--qf-image-ui-scale,1));touch-action:none}.qf-answer-image-corner[data-corner=nw]{left:0;top:0;cursor:nwse-resize}.qf-answer-image-corner[data-corner=ne]{left:100%;top:0;cursor:nesw-resize}.qf-answer-image-corner[data-corner=sw]{left:0;top:100%;cursor:nesw-resize}.qf-answer-image-corner[data-corner=se]{left:100%;top:100%;cursor:nwse-resize}
.qf-answer-shared .qf-answer-image-remove{position:absolute;width:34px;height:34px;padding:0;transform:translateX(-50%) scale(var(--qf-image-ui-scale,1));transform-origin:top center;border-radius:50%;background:white;color:#b34242;box-shadow:0 2px 10px #35294130;font:24px/1 system-ui;pointer-events:auto;touch-action:manipulation}.qf-answer-image-confirm{position:absolute;transform:translateX(-50%) scale(var(--qf-image-ui-scale,1));transform-origin:top center;width:220px;padding:12px;border:1px solid #dbc9ef;border-radius:10px;background:white;box-shadow:0 4px 16px #35294130;pointer-events:auto;font:14px/1.5 system-ui}.qf-answer-image-confirm p{margin:0 0 8px}.qf-answer-image-confirm button{margin-right:8px}.qf-answer-shared-snapshot{z-index:3}
.qf-answer-shared-message{margin:0;padding:8px 12px;color:#a53636;font-size:12px}.qf-answer-shared-retry{margin:0 12px 8px}.qf-answer-shared [hidden]{display:none!important}
`;

// The only ink canvas remains in the host. This component owns the answer's
// fixed coordinate plane, text, image resources and its growing crop rectangle.
export function createSharedAnswerAreaService({model,getContext,isDraft,resources,resize,onDirty,onRegions,onSync,document=globalThis.document}){
  const instances=new Map(),pending=new Set();let inactive=false,closed=false,reporting=false;
  const fail=(code,message)=>Object.assign(new Error(message),{code});
  const writable=()=>!closed&&!inactive&&getContext()?.capabilities?.canSave===true;
  function report(){if(reporting||closed)return;reporting=true;try{onRegions?.([...instances.values()].map(instance=>instance.describe()).filter(Boolean));}finally{reporting=false;}}
  function mount(container,options={}){
    if(!container?.append||container.ownerDocument!==document||container.closest('[data-qf-answer-area]')||instances.has(options.id)||instances.size>=16||typeof options.onChange!=='function')throw new TypeError('请提供独立容器、唯一答题区 ID 和 onChange');
    let value=model.create(options),destroyed=false,ready=false,saveError=null,inputError=null,uploadError=null,version=0,epoch=0,layoutFrame=null,layoutWidth=-1,selected=null,drag=null,imageSequence=0,picturesSignature=null,uploadQueue=Promise.resolve(),lastTap=null,tapStart=null,confirming=false,deferredInk=null,imageUploads=0;
    const view=document.defaultView,requestFrame=view.requestAnimationFrame?.bind(view)||view.setTimeout.bind(view),cancelFrame=view.cancelAnimationFrame?.bind(view)||view.clearTimeout.bind(view);
    const listeners=[],loaded=new Map(),loading=new Map();
    function listen(target,name,fn){target.addEventListener(name,fn);listeners.push(()=>target.removeEventListener(name,fn));}
    function element(tag,className,text){const node=document.createElement(tag);if(className)node.className=className;if(text)node.textContent=text;return node;}
    const root=element('section','qf-answer-shared');root.dataset.qfAnswerArea=value.id;root.dataset.qfAnswerShared='true';root.setAttribute('aria-label',options.label||'答题区');
    const head=element('header','qf-answer-shared-head'),heading=element('div','qf-answer-shared-heading'),title=element('strong','',options.label||'答题区'),hint=element('p','qf-answer-shared-instruction','下方区域的文字、手写和图片均作为答案进行评分。');heading.append(title,hint);
    const actions=element('div','qf-answer-shared-actions');head.append(heading,actions);
    const surface=element('div','qf-answer-shared-surface');surface.dataset.qfAnswerCrop='true';
    const plane=element('div','qf-answer-shared-plane'),pictures=element('div','qf-answer-shared-pictures'),text=element('textarea');text.setAttribute('aria-label',options.label||'答案');text.maxLength=model.limits.text;text.spellcheck=false;
    const image=element('img','qf-answer-shared-snapshot');image.alt='提交时的完整答案';image.hidden=true;
    const overlay=element('div','qf-answer-image-overlay');overlay.hidden=true;
    const handle=element('div','qf-answer-image-handle');handle.dataset.qfInteractive='true';handle.setAttribute('aria-label','移动选中的图片');
    for(const [corner,label]of [['nw','左上角'],['ne','右上角'],['sw','左下角'],['se','右下角']]){const grip=element('button','qf-answer-image-corner');grip.type='button';grip.dataset.corner=corner;grip.dataset.qfInteractive='true';grip.setAttribute('aria-label',label+'缩放图片');handle.append(grip);}
    const remove=element('button','qf-answer-image-remove','×');remove.type='button';remove.setAttribute('aria-label','删除所选图片');remove.dataset.qfInteractive='true';
    const confirm=element('div','qf-answer-image-confirm');confirm.hidden=true;confirm.setAttribute('role','alertdialog');confirm.setAttribute('aria-label','确认删除图片');confirm.dataset.qfInteractive='true';confirm.append(element('p','','删除这张图片？'));
    overlay.append(handle,remove,confirm);plane.append(pictures,text,image);surface.append(plane);
    const message=element('p','qf-answer-shared-message');message.setAttribute('role','status');message.hidden=true;
    const retry=element('button','qf-answer-shared-retry','重试保存');retry.type='button';retry.hidden=true;
    root.append(head,surface,overlay,message,retry);container.append(root);
    const measure=document.createElement('canvas').getContext('2d');
    function notify(error){message.textContent=error?.message||'';message.hidden=!error;retry.hidden=!saveError;}
    function currentError(){return inputError||uploadError||saveError;}
    function button(parent,label,fn){const node=element('button','',label);node.type='button';node.onclick=fn;parent.append(node);return node;}
    const pickers=[];
    for(const [label,camera] of [['拍照',true],['上传图片',false]]){
      const node=element('label','qf-answer-pick',label),input=element('input','qf-answer-shared-picker');node.style.position='relative';actions.append(node);input.type='file';input.accept=camera?'image/*':'image/png,image/jpeg,image/webp,image/gif';input.setAttribute('aria-label',label);if(camera)input.setAttribute('capture','environment');node.append(input);pickers.push([node,input]);
      listen(input,'change',()=>{const file=input.files?.[0];input.value='';if(file)void insertImage(file);});
    }
    const measuredWidth=()=>surface.clientWidth||surface.getBoundingClientRect().width||value.width;
    const maxHeight=()=>Math.min(model.limits.height,Math.floor(model.limits.pixels/value.width));
    function setStyle(target,key,next){if(target.style[key]!==next)target.style[key]=next;}
    function position(target,item){for(const key of ['x','y','width','height'])setStyle(target,key==='x'?'left':key==='y'?'top':key,`${item[key]}px`);}
    function track(task){pending.add(task);task.finally(()=>pending.delete(task)).catch(()=>{});return task;}
    function changed(){
      const generation=++version,snapshot=model.normalize(value);saveError=null;notify(currentError());onDirty?.();
      return track(Promise.resolve().then(()=>{if(destroyed||closed)throw fail('PAGE_CLOSED','题目已关闭');return options.onChange(snapshot);}).then(reply=>{if(reply?.ok===false)throw fail(reply.error?.code||'SAVE_FAILED',reply.error?.message||'保存失败');if(generation===version)saveError=null;}).catch(error=>{if(generation===version&&!destroyed){saveError=error;notify(error);}}));
    }
    function loadSource(url){return new Promise((resolve,reject)=>{const img=new view.Image(),timer=view.setTimeout(()=>{img.src='';reject(fail('IMAGE_TIMEOUT','图片读取超时，请重试'));},15000);img.onload=()=>{view.clearTimeout(timer);resolve(img);};img.onerror=()=>{view.clearTimeout(timer);reject(fail('IMAGE_DECODE','图片无法读取，请选择 JPEG、PNG 或 WebP 图片'));};img.src=url;});}
    function loadAsset(id){
      if(loaded.has(id))return Promise.resolve(loaded.get(id));if(loading.has(id))return loading.get(id);
      const task=Promise.resolve().then(()=>resources.get(id)).then(async resource=>{const img=await loadSource(resource.url);if(!destroyed)loaded.set(id,img);return img;}).finally(()=>loading.delete(id));loading.set(id,task);return task;
    }
    function selectedImage(){return value.images?.find(item=>item.id===selected);}
    function selectImage(id){if(imageUploads||!writable()||options.allowImages!==true||!image.hidden||inputError||!value.images?.some(item=>item.id===id))return;selected=id;confirming=false;text.blur();refreshSelection();resize?.();report();}
    function deselectImage(){if(!selected&&!confirming)return;cancelDrag();selected=null;confirming=false;lastTap=tapStart=null;refreshSelection();resize?.();report();}
    function imageAt(event){const bounds=surface.getBoundingClientRect(),scale=bounds.width/value.width;if(!scale)return null;const x=(event.clientX-bounds.left)/scale,y=(event.clientY-bounds.top)/scale;return [...(value.images||[])].reverse().find(item=>x>=item.x&&x<=item.x+item.width&&y>=item.y&&y<=item.y+item.height);}
    function renderPictures(){
      const signature=JSON.stringify([(value.images||[]).map(item=>[item.id,item.attrs.assetId]),writable(),image.hidden]);
      if(signature===picturesSignature){for(const [index,item]of (value.images||[]).entries())position(pictures.children[index],item);refreshSelection();return;}
      picturesSignature=signature;const nodes=[];
      for(const item of value.images||[]){const node=element('img','qf-answer-shared-picture');node.alt='答案图片';position(node,item);const cached=loaded.get(item.attrs.assetId);if(cached)node.src=cached.src;else {const generation=epoch;track(loadAsset(item.attrs.assetId).then(img=>{if(!destroyed&&epoch===generation)node.src=img.src;}).catch(error=>{if(!destroyed&&epoch===generation){inputError=error;notify(error);}}));}nodes.push(node);}
      pictures.replaceChildren(...nodes);refreshSelection();
    }
    function refreshSelection(){
      const item=selectedImage(),visible=!!item&&writable()&&image.hidden;overlay.hidden=!visible;if(!visible){selected=null;confirming=false;return;}
      const scale=measuredWidth()/value.width;setStyle(overlay,'top',`${surface.offsetTop}px`);setStyle(overlay,'width',`${value.width}px`);setStyle(overlay,'transform',`scale(${scale})`);overlay.style.setProperty('--qf-image-ui-scale',String(1/scale));position(handle,item);
      setStyle(remove,'left',`${item.x+item.width/2}px`);setStyle(remove,'top',`${item.y+item.height+8}px`);remove.hidden=confirming;
      setStyle(confirm,'left',`${Math.max(Math.min(value.width/2,110/scale),Math.min(value.width-Math.min(value.width/2,110/scale),item.x+item.width/2))}px`);setStyle(confirm,'top',`${item.y+item.height+8}px`);confirm.hidden=!confirming;
    }
    button(confirm,'删除',()=>{if(!writable()||inputError||!confirming)return;const id=selected;selected=null;confirming=false;commit({...value,images:value.images.filter(item=>item.id!==id)});});
    button(confirm,'取消',()=>{confirming=false;refreshSelection();resize?.();report();});
    listen(remove,'click',event=>{event.preventDefault();event.stopPropagation();if(writable()){confirming=true;refreshSelection();resize?.();report();}});
    listen(surface,'dblclick',event=>{const item=imageAt(event);if(item&&writable()){event.preventDefault();selectImage(item.id);}});
    listen(surface,'pointerdown',event=>{if(event.pointerType==='mouse')return;const item=imageAt(event);tapStart=item?{id:item.id,x:event.clientX,y:event.clientY,pointer:event.pointerId}:null;});
    listen(surface,'pointerup',event=>{if(!tapStart||tapStart.pointer!==event.pointerId)return;const tap=tapStart;tapStart=null;if(Math.hypot(event.clientX-tap.x,event.clientY-tap.y)>8){lastTap=null;return;}const now=Date.now();if(lastTap?.id===tap.id&&now-lastTap.time<=350&&Math.hypot(event.clientX-lastTap.x,event.clientY-lastTap.y)<=24){event.preventDefault();lastTap=null;selectImage(tap.id);}else lastTap={...tap,time:now};});
    listen(surface,'pointercancel',()=>{tapStart=lastTap=null;});
    listen(document,'pointerdown',event=>{if(selected&&!overlay.contains(event.target))deselectImage();});
    listen(document,'keydown',event=>{if(selected&&event.key==='Escape'){event.preventDefault();deselectImage();}});
    function refresh(){
      if(destroyed)return;if(drag&&!writable()){value=drag.document;drag=null;deferredInk=null;}text.readOnly=!writable();text.hidden=options.allowText===false||!image.hidden;retry.disabled=!writable();
      for(const [node,input]of pickers){node.hidden=!writable()||options.allowImages!==true;input.disabled=!writable()||options.allowImages!==true;}
      setStyle(plane,'width',`${value.width}px`);setStyle(plane,'height',`${value.height}px`);
      const width=measuredWidth();layoutWidth=width;setStyle(plane,'transform',`scale(${Math.round(width/value.width*1000000)/1000000})`);setStyle(surface,'height',`${Math.round(width*value.height/value.width*1000)/1000}px`);
      renderPictures();resize?.();report();
    }
    function scheduleLayout(force=false){if(destroyed||closed||layoutFrame!==null||!force&&measuredWidth()===layoutWidth)return;layoutFrame=requestFrame(()=>{layoutFrame=null;if(!destroyed&&!closed)refresh();});}
    function commit(next){if(inputError&&text.value!==(value.text||'')&&next.text===value.text)throw inputError;if(next.height>maxHeight())throw fail('ANSWER_AREA_FULL','答题区已达到高度上限，请缩小图片或精简内容；当前输入仍保留，尚未提交');value=model.normalize(next,{snapshot:false});inputError=null;image.hidden=true;refresh();void changed();}
    listen(text,'input',()=>{
      if(!writable()){text.value=value.text||'';return;}
      const next={...value,text:text.value},height=measure?Math.max(value.height,Math.ceil(model.textLayout(measure,next).height)+16):value.height;
      try{commit({...next,height});text.scrollTop=0;}catch(error){inputError=error;onDirty?.();notify(error);}
    });
    async function prepareFile(file){
      if(!file||!file.type?.startsWith('image/')||file.size<=0||file.size>20*1024*1024)throw fail('IMAGE_SIZE_LIMIT','请选择不超过 20 MiB 的图片');
      const url=view.URL.createObjectURL(file);let decoded;
      try{
        decoded=await loadSource(url);const width=decoded.naturalWidth,height=decoded.naturalHeight;if(!(width>0&&height>0)||width*height>40000000)throw fail('IMAGE_SIZE_LIMIT','图片分辨率过大，请缩小后重试');
        if(['image/jpeg','image/png','image/webp'].includes(file.type)&&file.size<=4*1024*1024&&Math.max(width,height)<=2560)return {file,width,height};
        const canvas=document.createElement('canvas');let ratio=Math.min(1,2560/Math.max(width,height));
        for(let attempt=0;attempt<8;attempt++){
          canvas.width=Math.max(1,Math.round(width*ratio));canvas.height=Math.max(1,Math.round(height*ratio));const context=canvas.getContext('2d');context.fillStyle='#fff';context.fillRect(0,0,canvas.width,canvas.height);context.drawImage(decoded,0,0,canvas.width,canvas.height);
          const blob=await new Promise((resolve,reject)=>{const timer=view.setTimeout(()=>reject(fail('IMAGE_TIMEOUT','图片处理超时，请重试')),12000);canvas.toBlob(result=>{view.clearTimeout(timer);result?resolve(result):reject(fail('IMAGE_DECODE','图片处理失败'));},'image/jpeg',attempt<3?[.9,.8,.65][attempt]:.75);});
          if(blob.size&&blob.size<=4*1024*1024)return {file:new view.File([blob],'答案图片.jpg',{type:'image/jpeg'}),width:canvas.width,height:canvas.height};if(attempt>=2)ratio*=.75;
        }
        throw fail('IMAGE_SIZE_LIMIT','图片仍超过 4 MiB，请缩小后重试');
      }finally{view.URL.revokeObjectURL(url);if(decoded)decoded.src='';}
    }
    function insertImage(file){
      if(!writable())return Promise.reject(fail('READ_ONLY','当前答案只读'));if(options.allowImages!==true)return Promise.reject(fail('IMAGES_DISABLED','此题型尚未启用答题区图片'));if(inputError){notify(inputError);return Promise.resolve();}const generation=epoch;imageUploads++;onDirty?.();uploadError=null;
      return track(uploadQueue=uploadQueue.catch(()=>{}).then(()=>prepareFile(file)).then(async prepared=>{
        if(destroyed||closed||generation!==epoch)return;
        if((value.images?.length||0)>=model.limits.images)throw fail('IMAGE_COUNT_LIMIT','每个答题区最多插入 8 张图片');
        const y=Math.max(16,measure?model.textLayout(measure,value).height:32,...(value.images||[]).map(item=>item.y+item.height+16),...value.strokes.flatMap(stroke=>stroke.points.map(point=>Math.min(value.height,point.y+16))));
        const scale=Math.min(1,(value.width-32)/prepared.width,(maxHeight()-y-16)/prepared.height);if(scale<=0)throw fail('ANSWER_AREA_FULL','答题区空间不足，请删除部分内容后重试');
        const width=Math.floor(prepared.width*scale),height=Math.floor(prepared.height*scale);if(width<1||height<1)throw fail('ANSWER_AREA_FULL','答题区空间不足');
        const resource=await resources.put(prepared.file);if(destroyed||closed||generation!==epoch||!writable())return;
        if(!/^[a-f0-9]{64}$/.test(resource.id))throw fail('IMAGE_RESOURCE_INVALID','图片保存失败，请重试');await loadAsset(resource.id);if(destroyed||closed||generation!==epoch||!writable())return;
        const item={type:'image',id:`image-${Date.now()}-${++imageSequence}`,attrs:{assetId:resource.id},x:16,y,width,height};selected=null;uploadError=null;commit({...value,height:Math.max(value.height,Math.ceil(y+height+16)),images:[...(value.images||[]),item]});
      }).catch(error=>{if(!destroyed&&generation===epoch){uploadError=error;notify(error);}}).finally(()=>{imageUploads--;}));
    }
    listen(text,'paste',event=>{if(options.allowImages!==true)return;const files=[...(event.clipboardData?.files||[])].filter(file=>file.type.startsWith('image/'));if(files.length){event.preventDefault();if(writable())for(const file of files)void insertImage(file);}});
    function applyDeferredInk(){const next=deferredInk;deferredInk=null;if(next)instance.setInk(next);}
    function cancelDrag(){if(!drag)return;value=drag.document;drag=null;applyDeferredInk();refresh();}
    listen(handle,'pointerdown',event=>{if(drag||event.button!==0||!writable()||!selectedImage()||confirming)return;event.preventDefault();event.stopPropagation();drag={pointer:event.pointerId,x:event.clientX,y:event.clientY,item:{...selectedImage()},document:model.normalize(value),corner:event.target.closest?.('[data-corner]')?.dataset.corner||null};try{handle.setPointerCapture(event.pointerId);}catch{}report();});
    function moveImage(event){
      if(!drag||drag.pointer!==event.pointerId)return;event.preventDefault();const scale=measuredWidth()/value.width,item=drag.item,dx=(event.clientX-drag.x)/scale,dy=(event.clientY-drag.y)/scale;let next;if(dx===0&&dy===0){value=drag.document;renderPictures();scheduleLayout(true);return;}
      if(drag.corner){
        const west=drag.corner.includes('w'),north=drag.corner.includes('n'),anchorX=west?item.x+item.width:item.x,anchorY=north?item.y+item.height:item.y;
        const desired=1+((west?-dx:dx)*item.width+(north?-dy:dy)*item.height)/(item.width**2+item.height**2),limit=Math.min((west?anchorX:value.width-anchorX)/item.width,(north?anchorY:maxHeight()-anchorY)/item.height),factor=Math.max(Math.min(limit,1,Math.max(32/item.width,32/item.height)),Math.min(limit,desired));
        const width=item.width*factor,height=item.height*factor;next={...item,x:west?anchorX-width:anchorX,y:north?anchorY-height:anchorY,width,height};
      }else next={...item,x:Math.max(0,Math.min(value.width-item.width,item.x+dx)),y:Math.max(0,Math.min(maxHeight()-item.height,item.y+dy))};
      try{value=model.normalize({...value,height:Math.max(drag.document.height,Math.min(maxHeight(),Math.ceil(next.y+next.height+16))),images:value.images.map(row=>row.id===item.id?next:row)},{snapshot:false});renderPictures();refreshSelection();scheduleLayout(true);}catch(error){inputError=error;notify(error);}
    }
    listen(handle,'pointermove',moveImage);
    listen(handle,'pointerup',event=>{if(!drag||drag.pointer!==event.pointerId)return;moveImage(event);const previous=drag.document;drag=null;applyDeferredInk();if(JSON.stringify(value)===JSON.stringify(previous))refresh();else commit(value);});
    listen(handle,'pointercancel',()=>{cancelDrag();refresh();});
    listen(handle,'lostpointercapture',()=>{if(drag){cancelDrag();refresh();}});
    listen(retry,'click',()=>{if(writable())void changed();});
    const Observer=view.ResizeObserver,observer=Observer?new Observer(()=>scheduleLayout()):null;observer?.observe(surface);listen(view,'resize',()=>scheduleLayout(true));if(view.visualViewport?.addEventListener)listen(view.visualViewport,'resize',()=>scheduleLayout(true));
    const instance={
      describe(){if(!ready)return null;const rect=surface.getBoundingClientRect();return {id:value.id,document:model.normalize(drag?.document||value),imagePreview:!!drag,rect:[rect.left,rect.top,rect.width,rect.height],writable:writable(),displayInk:image.hidden,autoGrow:true,imagesEditable:options.allowImages===true,selectedImageId:selected,imageControls:overlay.hidden?[]:[...overlay.querySelectorAll('[data-qf-interactive]')].filter(node=>!node.hidden&&!node.closest('[hidden]')).map(node=>{const box=node.getBoundingClientRect();return [box.left,box.top,box.width,box.height];})};},
      imageCommand(command){if(command.action==='select-image')selectImage(command.imageId);else if(command.action==='deactivate-image')deselectImage();},
      getDocument:()=>model.normalize(value),isEmpty:()=>!value.strokes.length&&!value.text?.trim()&&!value.images?.length,insertImage,
      setDocument(next){const normalized=model.normalize(next);if(normalized.id!==value.id||normalized.width!==value.width||normalized.targetId!==value.targetId)throw fail('ANSWER_AREA_ID_MISMATCH','答题区身份不匹配');if(JSON.stringify(normalized)===JSON.stringify(value)&&ready)return;drag=null;deferredInk=null;value=normalized;ready=true;epoch++;version++;saveError=inputError=uploadError=null;text.value=value.text||'';image.hidden=true;notify(null);refresh();const generation=epoch,assetId=value.snapshot?.attrs?.assetId;
        if(assetId&&!writable())track(resources.get(assetId).then(resource=>{if(!destroyed&&generation===epoch){image.src=resource.url;image.hidden=false;refresh();}}).catch(error=>{if(generation===epoch){inputError=error;notify(error);}}));
      },
      setInk(next){if(!writable()||inputError)return;if(drag){deferredInk=next;return;}try{
        let height=value.height;const fresh=next.strokes.filter(stroke=>!value.strokes.some(previous=>previous.id===stroke.id)&&stroke.points[0].x>=0&&stroke.points[0].x<=value.width&&stroke.points[0].y>=0&&stroke.points[0].y<=value.height);
        if(fresh.length){const bottom=Math.max(...fresh.flatMap(stroke=>stroke.points.map(point=>point.y+stroke.width/2)));if(bottom>=height-48)height=Math.min(maxHeight(),Math.max(height,Math.ceil(bottom+160)));if(bottom>maxHeight())throw fail('ANSWER_AREA_FULL','笔迹超出答题区上限，请返回检查；尚未提交');}
        const normalized=model.normalize({...value,height,strokes:next.strokes,inkSource:'draft',anchor:next.anchor},{snapshot:false});if(JSON.stringify(normalized)===JSON.stringify(value))return;value=normalized;void changed();refresh();
      }catch(error){inputError=error;onDirty?.();notify(error);}},
      clear(){throw fail('HOST_INK_COMMAND','请使用草稿工具清除笔迹，文字和图片在答题区内删除');},undo(){},redo(){},
      async flush(){if(layoutFrame!==null){cancelFrame(layoutFrame);layoutFrame=null;refresh();}await onSync?.();while(pending.size)await Promise.all([...pending]);if(drag)throw fail('IMAGE_BUSY','请先完成图片移动');if(currentError())throw currentError();},
      refresh,destroy(){if(destroyed)return;destroyed=true;epoch++;observer?.disconnect();if(layoutFrame!==null)cancelFrame(layoutFrame);layoutFrame=null;listeners.forEach(stop=>stop());instances.delete(value.id);root.remove();report();resize?.();}
    };instances.set(value.id,instance);refresh();Promise.resolve().then(()=>{if(!destroyed&&!ready){ready=true;refresh();}});return Object.freeze(instance);
  }
  return {api:Object.freeze({version:'1.4.1',formatVersion:1,mount}),setInk(value){instances.get(value?.id)?.setInk(value);},setTools(){},command(value){instances.get(value?.id)?.imageCommand(value);},refresh(){for(const instance of instances.values())instance.refresh();},setActive(value){inactive=!value;this.refresh();},async flush(){for(const instance of instances.values())await instance.flush();},destroy(){closed=true;for(const instance of [...instances.values()])instance.destroy();}};
}
