export const ANSWER_AREA_STYLE=`
.qf-answer-shared{margin:16px 0;color:#334155;font:14px/1.5 system-ui,sans-serif}.qf-answer-shared-instruction{margin:0 0 10px;color:#716280;font-size:13px}
.qf-answer-shared-surface{position:relative;width:100%;overflow:hidden;background:#fff;border:2px solid #9567cf;border-radius:10px;box-shadow:0 0 0 3px #9567cf10}
.qf-answer-shared-plane{position:absolute;left:0;top:0;transform-origin:0 0}.qf-answer-shared textarea,.qf-answer-shared img{position:absolute;inset:0;display:block;width:100%;height:100%;margin:0;box-sizing:border-box}
.qf-answer-shared textarea{border:0;padding:16px;resize:none;outline:none;overflow:hidden;background:transparent;color:#263447;font:18px/29px Arial,"Microsoft YaHei","Noto Sans CJK SC",sans-serif;white-space:pre-wrap;word-break:break-all;overflow-wrap:anywhere;font-kerning:none;tab-size:4}
.qf-answer-shared-message{font-size:12px;color:#a53636}.qf-answer-shared button{margin-top:8px;border:1px solid #d7c8e9;border-radius:7px;padding:6px 10px;background:white;color:#654580}.qf-answer-shared [hidden]{display:none!important}
`;

// This service owns text and crop geometry only. All pen input stays in the
// host's single whiteboard canvas and undo stack.
export function createSharedAnswerAreaService({model,getContext,isDraft,resources,resize,onDirty,onRegions,onSync,document=globalThis.document}){
  const instances=new Map(),pending=new Set();let inactive=false,closed=false,reporting=false;
  const fail=(code,message)=>Object.assign(new Error(message),{code});
  const writable=()=>!closed&&!inactive&&getContext()?.capabilities?.canSave===true;
  function report(){if(reporting||closed)return;reporting=true;try{onRegions?.([...instances.values()].map(instance=>instance.describe()).filter(Boolean));}finally{reporting=false;}}
  function mount(container,options={}){
    if(!container?.append||container.ownerDocument!==document||container.closest('[data-qf-answer-area]')||instances.has(options.id)||instances.size>=16||typeof options.onChange!=='function')throw new TypeError('请提供独立容器、唯一答题区 ID 和 onChange');
    let value=model.create(options),destroyed=false,ready=false,saveError=null,inputError=null,version=0,imageVersion=0,layoutFrame=null,layoutWidth=-1;
    const view=document.defaultView,requestFrame=view.requestAnimationFrame?.bind(view)||view.setTimeout.bind(view),cancelFrame=view.cancelAnimationFrame?.bind(view)||view.clearTimeout.bind(view);
    const root=document.createElement('section');root.className='qf-answer-shared';root.dataset.qfAnswerArea=value.id;root.dataset.qfAnswerShared='true';root.setAttribute('aria-label',options.label||'答题区');
    const hint=document.createElement('p');hint.className='qf-answer-shared-instruction';hint.textContent='下方答题区的打字以及手写输入，都将作为答案进行评分';
    const surface=document.createElement('div');surface.className='qf-answer-shared-surface';surface.dataset.qfAnswerCrop='true';
    const plane=document.createElement('div');plane.className='qf-answer-shared-plane';const text=document.createElement('textarea');text.setAttribute('aria-label',options.label||'答案');text.maxLength=model.limits.text;text.spellcheck=false;
    const image=document.createElement('img');image.alt='提交时的完整答案';image.hidden=true;plane.append(text,image);surface.append(plane);
    const message=document.createElement('p');message.className='qf-answer-shared-message';message.setAttribute('role','status');message.hidden=true;
    const retry=document.createElement('button');retry.type='button';retry.textContent='重试保存';retry.hidden=true;root.append(hint,surface,message,retry);container.append(root);
    const listeners=[];function listen(target,name,fn){target.addEventListener(name,fn);listeners.push(()=>target.removeEventListener(name,fn));}
    const measure=document.createElement('canvas').getContext('2d'); // measurement only, never an ink surface
    function notify(error){message.textContent=error?.message||'';message.hidden=!error;retry.hidden=!saveError;}
    const measuredWidth=()=>surface.clientWidth||Math.max(0,surface.getBoundingClientRect().width-4)||value.width;
    function setStyle(target,key,next){if(target.style[key]!==next)target.style[key]=next;}
    function refresh(){
      if(destroyed)return;text.readOnly=!writable();text.hidden=options.allowText===false||!image.hidden;retry.disabled=!writable();
      setStyle(plane,'width',`${value.width}px`);setStyle(plane,'height',`${value.height}px`);
      const width=measuredWidth();layoutWidth=width;setStyle(plane,'transform',`scale(${Math.round(width/value.width*1000000)/1000000})`);
      setStyle(surface,'height',`${Math.round((width*value.height/value.width+4)*1000)/1000}px`);
      resize?.();report();
    }
    function widthChanged(){
      // Never mutate the observed box during ResizeObserver delivery. Our own
      // height adjustment needs no further work unless the usable width changed.
      if(destroyed||closed||layoutFrame!==null||measuredWidth()===layoutWidth)return;
      layoutFrame=requestFrame(()=>{layoutFrame=null;if(!destroyed&&!closed)refresh();});
    }
    function changed(){
      const generation=++version,snapshot=model.normalize(value);saveError=null;notify(inputError);onDirty?.();
      const task=Promise.resolve().then(()=>{if(destroyed||closed)throw fail('PAGE_CLOSED','题目已关闭');return options.onChange(snapshot);}).then(reply=>{if(reply?.ok===false)throw fail(reply.error?.code||'SAVE_FAILED',reply.error?.message||'保存失败');if(generation===version)saveError=null;}).catch(error=>{if(generation===version&&!destroyed){saveError=error;notify(error);}}).finally(()=>pending.delete(task));pending.add(task);return task;
    }
    listen(text,'input',()=>{
      if(!writable()){text.value=value.text||'';return;}
      const next={...value,text:text.value};const height=measure?Math.max(value.height,Math.ceil(model.textLayout(measure,next).height)):value.height;
      try{value=model.normalize({...next,height},{snapshot:false});inputError=null;image.hidden=true;refresh();void changed();}catch(error){inputError=error;onDirty?.();notify(error);}
    });
    listen(retry,'click',()=>{if(writable())void changed();});
    const Observer=document.defaultView?.ResizeObserver,observer=Observer?new Observer(widthChanged):null;observer?.observe(surface);
    const instance={
      describe(){if(!ready)return null;const rect=surface.getBoundingClientRect(),scale=rect.width/(surface.offsetWidth||rect.width||1),border=2*scale;return {id:value.id,document:model.normalize(value),rect:[rect.left+border,rect.top+border,Math.max(0,rect.width-border*2),Math.max(0,rect.height-border*2)],writable:writable(),displayInk:image.hidden};},
      getDocument:()=>model.normalize(value),isEmpty:()=>!value.strokes.length&&!value.text?.trim(),
      setDocument(next){const normalized=model.normalize(next);if(normalized.id!==value.id||normalized.width!==value.width||normalized.targetId!==value.targetId)throw fail('ANSWER_AREA_ID_MISMATCH','答题区身份不匹配');value=normalized;ready=true;version++;saveError=inputError=null;text.value=value.text||'';image.hidden=true;notify(null);refresh();const generation=++imageVersion,assetId=value.snapshot?.attrs?.assetId;
        if(assetId&&!writable()){const task=resources.get(assetId).then(resource=>{if(!destroyed&&generation===imageVersion){image.src=resource.url;image.hidden=false;refresh();}}).catch(error=>notify(error)).finally(()=>pending.delete(task));pending.add(task);}
      },
      setInk(next){if(!writable()||inputError)return;try{const normalized=model.normalize({...value,strokes:next.strokes,inkSource:'draft',anchor:next.anchor},{snapshot:false});if(JSON.stringify(normalized)===JSON.stringify(value))return;value=normalized;void changed();report();}catch(error){inputError=error;onDirty?.();notify(error);}},
      clear(){throw fail('HOST_INK_COMMAND','请使用宿主草稿工具清除笔迹，文字可在输入框内删除');},undo(){},redo(){},
      async flush(){if(layoutFrame!==null){cancelFrame(layoutFrame);layoutFrame=null;refresh();}await onSync?.();while(pending.size)await Promise.all([...pending]);if(inputError)throw inputError;if(saveError)throw saveError;},
      refresh,destroy(){if(destroyed)return;destroyed=true;observer?.disconnect();if(layoutFrame!==null)cancelFrame(layoutFrame);layoutFrame=null;listeners.forEach(stop=>stop());instances.delete(value.id);root.remove();report();resize?.();}
    };instances.set(value.id,instance);refresh();Promise.resolve().then(()=>{if(!destroyed&&!ready){ready=true;refresh();}});return Object.freeze(instance);
  }
  return {api:Object.freeze({version:'1.3.1',formatVersion:1,mount}),setInk(value){instances.get(value?.id)?.setInk(value);},setTools(){},command(){},refresh(){for(const instance of instances.values())instance.refresh();},setActive(value){inactive=!value;this.refresh();},async flush(){for(const instance of instances.values())await instance.flush();},destroy(){closed=true;for(const instance of [...instances.values()])instance.destroy();}};
}
