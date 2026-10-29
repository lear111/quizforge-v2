// A single plain text surface with an ink overlay. Older history keeps its source.
export const ANSWER_AREA_STYLE=`
.qf-answer-area{width:100%;max-width:100%;margin:16px 0;border:2px solid #9567cf;border-radius:12px;background:#fff;color:#334155;overflow:hidden;font:14px/1.5 system-ui,sans-serif;box-shadow:0 0 0 3px #9567cf10}
.qf-answer-area-head{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:10px 12px;background:#f2eafb;border-bottom:1px solid #cdb7e8;flex-wrap:wrap}
.qf-answer-area-title{display:flex;gap:8px;align-items:center}.qf-answer-area-badge{font-size:11px;font-weight:600;color:#fff;background:#8054b6;border-radius:4px;padding:2px 6px;white-space:nowrap}
.qf-answer-area-hint{font-size:12px;color:#69537f}.qf-answer-area-options{display:flex;gap:6px;align-items:center;padding:7px 10px;min-height:46px;border-bottom:1px dashed #b99bdc;flex-wrap:wrap;background:#fdfbff}
.qf-answer-area button{border:1px solid #d7c8e9;border-radius:7px;background:#fff;color:#654580;padding:5px 10px;min-height:30px;cursor:pointer;font:inherit;font-size:12px}
.qf-answer-area button[aria-pressed=true]{background:#8054b6;border-color:#8054b6;color:#fff}.qf-answer-area button:disabled{opacity:.45;cursor:default}.qf-answer-area-grow{margin-left:auto}
.qf-answer-area-surface{position:relative;width:100%;background:#fff;overflow:hidden}.qf-answer-area-plane{position:absolute;left:0;top:0;transform-origin:0 0}.qf-answer-area canvas,.qf-answer-area img,.qf-answer-area textarea{position:absolute;inset:0;display:block;width:100%;height:100%;max-width:100%;margin:0;box-sizing:border-box}
.qf-answer-area textarea{resize:none;border:0;border-radius:0;padding:16px;background:transparent;color:#263447;font:18px/29px Arial,"Microsoft YaHei","Noto Sans CJK SC",sans-serif;outline:none;overflow:hidden;white-space:pre-wrap;word-break:break-all;overflow-wrap:anywhere;tab-size:4}.qf-answer-area textarea:focus{box-shadow:inset 0 0 0 2px #ab83d3}.qf-answer-area canvas{z-index:1}
.qf-answer-area canvas{outline:none}.qf-answer-area canvas:focus-visible{outline:2px solid #9671d7;outline-offset:-2px}.qf-answer-area-message{font-size:12px;padding:8px 12px;margin:0;color:#a53636}.qf-answer-area-foot{display:flex;justify-content:space-between;gap:8px;align-items:center;padding:7px 12px;border-top:1px dashed #b99bdc;background:#faf7fd;color:#69537f;font-size:12px}
.qf-answer-area [hidden]{display:none!important}
`;

export function createAnswerAreaService({model,getContext,isDraft,resources,resize,onDirty,onFocus,onPan,document=globalThis.document,tools}) {
  const instances=new Map(),pending=new Set();let inactive=false,closed=false,focused=null;
  let tooling={tool:'pen',color:'#334155',width:3,penOnly:true};
  const error=(code,message)=>Object.assign(new Error(message),{code});
  const canSave=()=>!closed&&!inactive&&getContext()?.capabilities?.canSave===true;
  const canInk=()=>canSave()&&isDraft()&&getContext()?.capabilities?.canWhiteboard===true;
  function focus(instance){focused=instance;publishFocus();}
  function publishFocus(){onFocus?.(focused?.state()??null);}
  function setTools(next){
    if(!next||!['pen','eraser','hand','select-ink','capture'].includes(next.tool)||!/^#[a-f0-9]{6}$/i.test(next.color)||!Number.isFinite(next.width)||next.width<.5||next.width>16||typeof next.penOnly!=='boolean')return;
    tooling={tool:next.tool,color:next.color,width:next.width,penOnly:next.penOnly};
    for(const instance of instances.values())instance.refresh();
  }
  if(tools)setTools(tools);
  function mount(container,options={}){
    if(closed)throw error('PAGE_CLOSED','题目页面已关闭');
    if(!container?.append||container.ownerDocument!==document||container.closest('[data-qf-answer-area]'))throw new TypeError('请提供题卡内独立的答题区容器');
    if(instances.size>=model.limits.areas||instances.has(options.id))throw new TypeError('答题区 ID 重复或数量超限');
    if(typeof options.onChange!=='function')throw new TypeError('请通过 onChange 保存本次正式答案');
    let value=model.create(options),destroyed=false,stroke=null,pointerId=null,saveError=null,inputError=null,version=0,renderVersion=0,sequence=0;
    const allowText=options.allowText===true;
    if(value.height+[...instances.values()].reduce((sum,item)=>sum+item.getDocument().height,0)>model.limits.totalHeight)throw new TypeError('本题答题区总高度超出限制');
    const writable=()=>!destroyed&&canSave(),inkWritable=()=>writable()&&canInk();
    const undo=[],redo=[],listeners=[],panning=new Set();
    const node=(tag,className,text)=>{const element=document.createElement(tag);if(className)element.className=className;if(text)element.textContent=text;return element;};
    const root=node('section','qf-answer-area');root.dataset.qfAnswerArea=value.id;root.dataset.qfInteractive='true';root.setAttribute('aria-label',options.label||'正式答题区');
    const head=node('div','qf-answer-area-head'),title=node('div','qf-answer-area-title');
    title.append(node('span','qf-answer-area-badge','正式答题区'),node('strong','',typeof options.label==='string'?options.label.slice(0,100):'你的答案'));
    const hint=node('span','qf-answer-area-hint');head.append(title,hint);
    const surface=node('div','qf-answer-area-surface'),plane=node('div','qf-answer-area-plane'),canvas=node('canvas'),image=node('img'),text=node('textarea');
    canvas.tabIndex=0;canvas.setAttribute('role','img');canvas.setAttribute('aria-label',`${options.label||'正式答案'}手写区`);image.alt='提交时的手写答案';image.hidden=true;
    text.setAttribute('aria-label',`${options.label||'正式答案'}文字输入`);text.placeholder='在这里输入答案；草稿模式下可直接在同一框内手写。';text.maxLength=model.limits.text;text.hidden=!allowText;text.spellcheck=false;
    plane.append(text,canvas,image);surface.append(plane);
    const message=node('p','qf-answer-area-message');message.setAttribute('role','status');message.hidden=true;
    const foot=node('div','qf-answer-area-foot'),scope=node('span','','框内内容用于评分 · 框外仅作草稿');foot.append(scope);
    root.append(head,surface,message,foot);container.append(root);
    const context=canvas.getContext('2d');if(!context){root.remove();throw error('CANVAS_UNAVAILABLE','浏览器不支持答题区');}
    const listen=(target,name,handler)=>{target.addEventListener(name,handler);listeners.push(()=>target.removeEventListener(name,handler));};
    const notify=messageText=>{message.textContent=messageText||'';message.hidden=!messageText;};
    const button=(label,action,parent=foot)=>{const element=node('button','',label);element.type='button';parent.append(element);listen(element,'click',action);return element;};
    const retry=button('重试保存',()=>{if(writable())void changed();},foot);retry.hidden=true;
    function cancelPans(){for(const id of panning)onPan?.({phase:'pointercancel',pointerId:id,pointerType:'touch',x:0,y:0});panning.clear();}
    function scale(){const width=surface.getBoundingClientRect().width||value.width;plane.style.width=`${value.width}px`;plane.style.height=`${value.height}px`;plane.style.transform=`scale(${width/value.width})`;}
    const observer=typeof globalThis.ResizeObserver==='function'?new globalThis.ResizeObserver(scale):null;observer?.observe(surface);
    function refresh(){
      if(destroyed)return;
      const frozen=!!image.dataset.ready,intercept=inkWritable()&&(!allowText||tooling.tool!=='hand');
      text.readOnly=!writable();text.hidden=!allowText||frozen;canvas.hidden=frozen;image.hidden=!frozen;
      canvas.style.pointerEvents=intercept?'auto':'none';canvas.style.touchAction=isDraft()?'none':'pan-y';canvas.style.cursor=inkWritable()?(tooling.tool==='eraser'?'cell':tooling.tool==='pen'?'crosshair':'default'):'default';
      retry.hidden=!saveError;retry.disabled=!writable();
      hint.textContent=getContext()?.mode==='history'?'历史答案 · 只读':!writable()?'答案已锁定':isDraft()&&canInk()?(tooling.tool==='hand'&&allowText?'点击框内打字':'使用草稿工具栏书写；操作工具可打字'):allowText?'直接输入，自动保存':'切换草稿模式后书写';
      surface.style.aspectRatio=`${value.width} / ${value.height}`;
      scale();
      publishFocus();
    }
    function paint(){
      if(destroyed)return;
      if(canvas.width!==value.width)canvas.width=value.width;if(canvas.height!==value.height)canvas.height=value.height;
      model.paint(context,stroke?{...value,strokes:[...value.strokes,stroke]}:value,{background:false,text:!allowText});
      if(text.value!==(value.text||''))text.value=value.text||'';
      refresh();
    }
    function changed(){
      const generation=++version,snapshot=model.normalize(value);onDirty?.();notify('');saveError=null;refresh();
      const task=Promise.resolve().then(()=>{if(destroyed||closed)throw error('PAGE_CLOSED','题目页面已关闭');return options.onChange(snapshot);}).then(reply=>{if(reply?.ok===false)throw error(reply.error?.code||'SAVE_FAILED',reply.error?.message||'保存失败');if(generation===version&&!destroyed){saveError=null;notify('');refresh();}}).catch(cause=>{if(generation===version&&!destroyed){saveError=cause;notify(cause.message||'保存失败，输入已保留，请重试');refresh();}}).finally(()=>pending.delete(task));
      pending.add(task);return task;
    }
    function change(next,{remember=true}={}){
      const normalized=model.normalize(next,{snapshot:false});
      if(normalized.height+[...instances.values()].filter(item=>item!==instance).reduce((sum,item)=>sum+item.getDocument().height,0)>model.limits.totalHeight)throw error('ANSWER_AREA_LIMIT','本题答题区总高度超出限制');
      if(remember){undo.push(value);if(undo.length>60)undo.shift();redo.length=0;}
      value=normalized;inputError=null;delete image.dataset.ready;paint();resize?.();void changed();
    }
    function editHistory(from,to){if(!writable()||!from.length)return;to.push(value);change(from.pop(),{remember:false});}
    function point(event){const rect=canvas.getBoundingClientRect();return {x:Math.round(Math.max(0,Math.min(value.width,(event.clientX-rect.left)*value.width/(rect.width||value.width)))*1000)/1000,y:Math.round(Math.max(0,Math.min(value.height,(event.clientY-rect.top)*value.height/(rect.height||value.height)))*1000)/1000,pressure:event.pointerType==='pen'&&event.pressure>0?Math.min(1,event.pressure):.5};}
    function erase(at){
      const hit=item=>item.points.some((p,index)=>{const previous=item.points[index-1]||p,dx=p.x-previous.x,dy=p.y-previous.y,length=dx*dx+dy*dy,ratio=length?Math.max(0,Math.min(1,((at.x-previous.x)*dx+(at.y-previous.y)*dy)/length)):0;return Math.hypot(at.x-previous.x-ratio*dx,at.y-previous.y-ratio*dy)<12+item.width;});
      const selected=value.strokes.filter(item=>!hit(item));if(selected.length!==value.strokes.length)change({...value,strokes:selected});
    }
    function finish(commit=true){if(!stroke)return;const next=stroke;stroke=null;if(commit&&next.points.length){try{change({...value,strokes:[...value.strokes,next]});}catch(cause){stroke=next;notify(cause.message);}}else paint();}
    const pan=(phase,event)=>onPan?.({phase,pointerId:event.pointerId,pointerType:event.pointerType==='touch'?'touch':'mouse',x:event.clientX,y:event.clientY});
    listen(canvas,'pointerdown',event=>{
      panning.delete(event.pointerId);
      if(!destroyed&&!closed&&!inactive&&isDraft()&&onPan&&((tooling.tool==='hand'&&!allowText)||event.pointerType==='touch'&&tooling.penOnly)){
        event.preventDefault();panning.add(event.pointerId);try{canvas.setPointerCapture(event.pointerId);}catch{}pan('pointerdown',event);return;
      }
      if(inputError||!inkWritable()||!['pen','eraser'].includes(tooling.tool)||event.button>0||event.pointerType==='touch'&&tooling.penOnly||pointerId!==null)return;
      event.preventDefault();focus(instance);pointerId=event.pointerId;try{canvas.setPointerCapture(pointerId);}catch{}
      if(tooling.tool==='eraser'){erase(point(event));return;}
      stroke={id:`stroke-${Date.now()}-${++sequence}`,color:tooling.color,width:tooling.width,points:[point(event)]};paint();
    });
    listen(canvas,'pointermove',event=>{
      if(panning.has(event.pointerId)){event.preventDefault();pan('pointermove',event);return;}
      if(pointerId!==event.pointerId||!inkWritable())return;event.preventDefault();if(tooling.tool==='eraser'){erase(point(event));return;}if(!stroke)return;
      const samples=event.getCoalescedEvents?.();for(const sample of samples?.length?samples:[event]){const p=point(sample),last=stroke.points.at(-1);if(stroke.points.length<5000&&Math.hypot(p.x-last.x,p.y-last.y)>.35)stroke.points.push(p);}paint();
    });
    listen(canvas,'pointerup',event=>{if(panning.delete(event.pointerId)){event.preventDefault();pan('pointerup',event);try{canvas.releasePointerCapture(event.pointerId);}catch{}return;}if(pointerId!==event.pointerId)return;event.preventDefault();if(stroke&&stroke.points.length<5000)stroke.points.push(point(event));pointerId=null;finish();try{canvas.releasePointerCapture(event.pointerId);}catch{}});
    listen(canvas,'pointercancel',event=>{if(panning.delete(event.pointerId)){pan('pointercancel',event);return;}if(pointerId!==event.pointerId)return;pointerId=null;finish(false);});
    listen(text,'focus',()=>focus(instance));
    listen(text,'input',()=>{
      if(!writable()){text.value=value.text||'';return;}focus(instance);
      const next={...value,text:text.value},required=Math.ceil(model.textLayout(context,next).height),height=Math.max(value.height,required);
      if(height>Math.min(model.limits.height,Math.floor(model.limits.pixels/value.width))){inputError=error('ANSWER_AREA_LIMIT','文字超出答题区最大容量，请精简后重试；当前输入仍保留。');onDirty?.();notify(inputError.message);return;}
      try{inputError=null;change({...next,height});}catch(cause){inputError=cause;onDirty?.();notify(cause.message);}
    });
    listen(root,'keydown',event=>{if(event.target===text||!writable()||!(event.ctrlKey||event.metaKey)||event.key.toLowerCase()!=='z')return;event.preventDefault();event.shiftKey?instance.redo():instance.undo();});
    function setDocument(next){
      const normalized=model.normalize(next);
      if(normalized.id!==value.id||normalized.targetId!==value.targetId||normalized.width!==value.width)throw error('ANSWER_AREA_ID_MISMATCH','答题区身份、目标或逻辑宽度不匹配');
      cancelPans();stroke=null;pointerId=null;value=normalized;version++;saveError=null;inputError=null;undo.length=redo.length=0;notify('');delete image.dataset.ready;
      paint();resize?.();const generation=++renderVersion,assetId=value.snapshot?.attrs.assetId;
      if(assetId&&getContext()?.mode==='history'){
        const task=resources.get(assetId).then(resource=>{if(destroyed||generation!==renderVersion)return;image.src=resource.url;image.dataset.ready='true';refresh();resize?.();}).catch(()=>{if(!destroyed&&generation===renderVersion)notify('提交图片暂时无法读取，已显示保留的笔迹。');}).finally(()=>pending.delete(task));pending.add(task);
      }
    }
    const instance={
      getDocument:()=>model.normalize(value),setDocument,isEmpty:()=>!value.strokes.length&&!value.text?.trim(),
      clear(){if(!writable())throw error('READ_ONLY','当前答题区不能修改');change({...value,strokes:[],...(value.text===undefined?{}:{text:''})});},
      undo(){editHistory(undo,redo);},redo(){editHistory(redo,undo);},
      async flush(){if(stroke)finish();while(pending.size)await Promise.all([...pending]);if(stroke)throw error('ANSWER_AREA_LIMIT','笔迹超出限制，请先撤销或缩减后重试');if(saveError)throw saveError;if(inputError)throw inputError;},
      refresh(){if(!isDraft()||inactive||closed)cancelPans();if(!inkWritable()&&stroke)finish(false);refresh();},
      state:()=>({id:value.id,writable:writable()&&(allowText||canInk()),canUndo:undo.length>0,canRedo:redo.length>0}),
      destroy(){if(destroyed)return;cancelPans();destroyed=true;renderVersion++;observer?.disconnect();listeners.forEach(stop=>stop());instances.delete(value.id);if(focused===instance){focused=null;publishFocus();}root.remove();resize?.();},
    };
    instances.set(value.id,instance);paint();resize?.();return Object.freeze(instance);
  }
  return {
    api:Object.freeze({version:'1.2.0',formatVersion:1,mount}),setTools,
    command({id,action}={}){const instance=instances.get(id);if(action==='deactivate'){focused=null;publishFocus();}else if(instance&&instance.state().writable&&['undo','redo','clear'].includes(action))instance[action]();},
    async flush(){for(const instance of instances.values())await instance.flush();},
    refresh(){for(const instance of instances.values())instance.refresh();},
    setActive(active){inactive=!active;for(const instance of instances.values())instance.refresh();},
    destroy(){closed=true;for(const instance of [...instances.values()])instance.destroy();},
  };
}
