// Versioned built-in component. The host freezes this source with history pages.
export const ANSWER_AREA_STYLE=`
.qf-answer-area{width:100%;max-width:100%;margin:12px 0;border:1px solid #dcd5ed;border-radius:12px;background:#fff;color:#334155;overflow:hidden;font:14px/1.5 system-ui,sans-serif}
.qf-answer-area-head{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:9px 12px;background:#faf8ff;border-bottom:1px solid #eee8f7}
.qf-answer-area-hint{font-size:12px;color:#786d8d}.qf-answer-area-tools{display:flex;gap:6px;flex-wrap:wrap;padding:8px 10px;border-bottom:1px solid #eee8f7;align-items:center}
.qf-answer-area button{border:1px solid #e5dfef;border-radius:7px;background:#fff;color:#64537e;padding:5px 9px;min-height:30px;cursor:pointer;font:inherit;font-size:12px}
.qf-answer-area button[aria-pressed=true]{background:#ede6fa;border-color:#9b7bd3;color:#673bb0}.qf-answer-area button:disabled{opacity:.45;cursor:default}
.qf-answer-area-surface{position:relative;width:100%;background:#fff;overflow:hidden}.qf-answer-area canvas,.qf-answer-area img{display:block;width:100%;height:auto;max-width:100%;margin:0}
.qf-answer-area canvas{outline:none}.qf-answer-area canvas:focus-visible{outline:2px solid #9671d7;outline-offset:-2px}.qf-answer-area-message{font-size:12px;margin:0;padding:6px 12px;color:#a53636}
.qf-answer-area-finger{font-size:12px;display:flex;align-items:center;gap:4px;white-space:nowrap}.qf-answer-area [hidden]{display:none!important}
`;

export function createAnswerAreaService({model,getContext,isDraft,resources,resize,onDirty,document=globalThis.document}) {
  const instances=new Map();let inactive=false,closed=false;
  const pending=new Set();
  const error=(code,message)=>Object.assign(new Error(message),{code});
  const editable=()=>!closed&&!inactive&&isDraft()&&getContext()?.capabilities?.canSave===true&&getContext()?.capabilities?.canWhiteboard===true;
  function mount(container,options={}) {
    if(closed)throw error('PAGE_CLOSED','题目页面已关闭');
    if(!container?.append||container.ownerDocument!==document||container.closest('[data-qf-answer-area]'))throw new TypeError('请提供题卡内独立的答题区容器');
    if(instances.size>=model.limits.areas||instances.has(options.id))throw new TypeError('答题区 ID 重复或数量超限');
    if(typeof options.onChange!=='function')throw new TypeError('请通过 onChange 保存本次正式答案');
    let value=model.create(options),destroyed=false,stroke=null,saveError=null,version=0,renderVersion=0,sequence=0,tool='pen';
    if(value.height+[...instances.values()].reduce((sum,item)=>sum+item.getDocument().height,0)>model.limits.totalHeight)throw new TypeError('本题答题区总高度超出限制');
    const writable=()=>!destroyed&&editable();
    const undo=[],redo=[],listeners=[];
    const root=document.createElement('section');root.className='qf-answer-area';root.dataset.qfAnswerArea=value.id;root.dataset.qfInteractive='true';
    const head=document.createElement('div');head.className='qf-answer-area-head';
    const title=document.createElement('strong');title.textContent=typeof options.label==='string'?options.label.slice(0,100):'手写答题区';
    const hint=document.createElement('span');hint.className='qf-answer-area-hint';head.append(title,hint);
    const toolbar=document.createElement('div');toolbar.className='qf-answer-area-tools';toolbar.setAttribute('role','toolbar');toolbar.setAttribute('aria-label','答题区书写工具');
    const surface=document.createElement('div');surface.className='qf-answer-area-surface';
    const canvas=document.createElement('canvas'),image=document.createElement('img');image.alt='提交时的手写答案';image.hidden=true;
    canvas.tabIndex=0;canvas.setAttribute('role','img');canvas.setAttribute('aria-label',title.textContent);surface.append(canvas,image);
    const message=document.createElement('p');message.className='qf-answer-area-message';message.setAttribute('role','status');message.hidden=true;
    root.append(head,toolbar,surface,message);container.append(root);
    const context=canvas.getContext('2d');if(!context){root.remove();throw error('CANVAS_UNAVAILABLE','浏览器不支持手写答题区');}
    const listen=(node,name,handler)=>{node.addEventListener(name,handler);listeners.push(()=>node.removeEventListener(name,handler));};
    const notify=text=>{message.textContent=text||'';message.hidden=!text;};
    const controls=[];
    function button(label,action){const node=document.createElement('button');node.type='button';node.textContent=label;node.setAttribute('aria-label',label);toolbar.append(node);controls.push(node);listen(node,'click',()=>{if(writable())action();});return node;}
    const pen=button('画笔',()=>{tool='pen';refresh();}),eraser=button('擦除',()=>{tool='eraser';refresh();});
    const undoButton=button('撤销',()=>editHistory(undo,redo)),redoButton=button('重做',()=>editHistory(redo,undo));
    let color='#334155';
    for(const [ink,label] of [['#334155','黑'],['#386db6','蓝'],['#d04444','红']]){const node=button(label,()=>{color=ink;tool='pen';refresh();});node.dataset.ink=ink;}
    button('清空',()=>{if(value.strokes.length)change({...value,strokes:[]});});
    const taller=button('加高',()=>{const height=Math.min(model.limits.height,value.height+240,Math.floor(model.limits.pixels/value.width));if(height>value.height)change({...value,height});});
    const retry=button('重试保存',()=>{void changed();});retry.hidden=true;
    const fingerLabel=document.createElement('label');fingerLabel.className='qf-answer-area-finger';const finger=document.createElement('input');finger.type='checkbox';finger.setAttribute('aria-label','手指书写');fingerLabel.append(finger,document.createTextNode('手指书写'));toolbar.append(fingerLabel);listen(finger,'change',refresh);
    function refresh(){
      if(destroyed)return;
      // Reserve the same row in every mode: ordinary draft annotations below
      // this card must not shift when the answer becomes readonly in history.
      const enabled=writable();toolbar.style.visibility=enabled?'visible':'hidden';toolbar.setAttribute('aria-hidden',String(!enabled));toolbar.inert=!enabled;canvas.style.touchAction=enabled&&finger.checked?'none':'pan-y';canvas.style.cursor=enabled?(tool==='eraser'?'cell':'crosshair'):'default';
      for(const node of controls)node.disabled=!enabled;
      undoButton.disabled=!enabled||!undo.length;redoButton.disabled=!enabled||!redo.length;taller.disabled=!enabled||value.height>=Math.min(model.limits.height,Math.floor(model.limits.pixels/value.width));
      pen.setAttribute('aria-pressed',String(tool==='pen'));eraser.setAttribute('aria-pressed',String(tool==='eraser'));
      for(const node of toolbar.querySelectorAll('[data-ink]'))node.setAttribute('aria-pressed',String(node.dataset.ink===color));
      retry.hidden=!saveError;
      hint.textContent=getContext()?.mode==='history'?'历史答案':getContext()?.capabilities?.canSave!==true?'已锁定':!getContext()?.capabilities?.canWhiteboard?'此题库未开放草稿书写':isDraft()?'区域内书写将作为答案保存':'切换草稿模式书写';
    }
    function paint(){
      if(destroyed)return;
      if(canvas.width!==value.width)canvas.width=value.width;if(canvas.height!==value.height)canvas.height=value.height;
      model.paint(context,stroke?{...value,strokes:[...value.strokes,stroke]}:value);refresh();
    }
    function changed(){
      const generation=++version,snapshot=model.normalize(value);onDirty?.();notify('');saveError=null;refresh();
      const task=Promise.resolve().then(()=>{if(destroyed||closed)throw error('PAGE_CLOSED','题目页面已关闭');return options.onChange?.(snapshot);}).then(reply=>{if(reply?.ok===false)throw error(reply.error?.code||'SAVE_FAILED',reply.error?.message||'保存失败');if(generation===version&&!destroyed){saveError=null;notify('');refresh();}}).catch(cause=>{if(generation===version&&!destroyed){saveError=cause;notify(cause.message||'保存失败，笔迹已保留，请重试');refresh();}}).finally(()=>pending.delete(task));
      pending.add(task);return task;
    }
    function change(next,{remember=true}={}){
      const normalized=model.normalize(next,{snapshot:false});
      if(normalized.height+[...instances.values()].filter(item=>item!==instance).reduce((sum,item)=>sum+item.getDocument().height,0)>model.limits.totalHeight)throw error('ANSWER_AREA_LIMIT','本题答题区总高度超出限制');
      if(remember){undo.push(value);if(undo.length>60)undo.shift();redo.length=0;}
      value=normalized;image.hidden=true;canvas.hidden=false;paint();resize?.();void changed();
    }
    function editHistory(from,to){if(!from.length)return;to.push(value);change(from.pop(),{remember:false});}
    function point(event){const rect=canvas.getBoundingClientRect();return {x:Math.round(Math.max(0,Math.min(value.width,(event.clientX-rect.left)*value.width/(rect.width||value.width)))*1000)/1000,y:Math.round(Math.max(0,Math.min(value.height,(event.clientY-rect.top)*value.height/(rect.height||value.height)))*1000)/1000,pressure:event.pointerType==='pen'&&event.pressure>0?Math.min(1,event.pressure):.5};}
    function erase(at){
      const hit=item=>item.points.some((p,index)=>{
        const previous=item.points[index-1]||p,dx=p.x-previous.x,dy=p.y-previous.y,length=dx*dx+dy*dy;
        const ratio=length?Math.max(0,Math.min(1,((at.x-previous.x)*dx+(at.y-previous.y)*dy)/length)):0;
        return Math.hypot(at.x-previous.x-ratio*dx,at.y-previous.y-ratio*dy)<12+item.width;
      });
      const selected=value.strokes.filter(item=>!hit(item));if(selected.length!==value.strokes.length)change({...value,strokes:selected});
    }
    function finish(commit=true){
      if(!stroke)return;const next=stroke;stroke=null;
      if(commit&&next.points.length){try{change({...value,strokes:[...value.strokes,next]});}catch(cause){stroke=next;notify(cause.message);}}
      else paint();
    }
    let pointerId=null;
    listen(canvas,'pointerdown',event=>{
      if(!writable()||event.button>0||event.pointerType==='touch'&&!finger.checked||pointerId!==null)return;
      event.preventDefault();pointerId=event.pointerId;try{canvas.setPointerCapture(pointerId);}catch{}
      if(tool==='eraser'){erase(point(event));return;}
      stroke={id:`stroke-${Date.now()}-${++sequence}`,color,width:3,points:[point(event)]};paint();
    });
    listen(canvas,'pointermove',event=>{
      if(pointerId!==event.pointerId||!writable())return;event.preventDefault();
      if(tool==='eraser'){erase(point(event));return;}
      if(!stroke)return;const samples=event.getCoalescedEvents?.();
      for(const sample of samples?.length?samples:[event]){const p=point(sample),last=stroke.points.at(-1);if(stroke.points.length<5000&&Math.hypot(p.x-last.x,p.y-last.y)>.35)stroke.points.push(p);}paint();
    });
    listen(canvas,'pointerup',event=>{if(pointerId!==event.pointerId)return;event.preventDefault();if(stroke){const p=point(event);if(stroke.points.length<5000)stroke.points.push(p);}pointerId=null;finish();try{canvas.releasePointerCapture(event.pointerId);}catch{}});
    listen(canvas,'pointercancel',event=>{if(pointerId!==event.pointerId)return;pointerId=null;finish(false);});
    function setDocument(next){
      const normalized=model.normalize(next);
      if(normalized.id!==value.id||normalized.targetId!==value.targetId||normalized.width!==value.width)throw error('ANSWER_AREA_ID_MISMATCH','答题区身份、目标或逻辑宽度不匹配');
      stroke=null;pointerId=null;value=normalized;version++;saveError=null;undo.length=redo.length=0;notify('');paint();resize?.();
      const generation=++renderVersion,assetId=value.snapshot?.attrs.assetId;
      image.hidden=true;canvas.hidden=false;
      if(assetId&&getContext()?.capabilities?.canSave!==true){
        const task=resources.get(assetId).then(resource=>{if(destroyed||generation!==renderVersion)return;image.src=resource.url;image.hidden=false;canvas.hidden=true;resize?.();}).catch(()=>{if(!destroyed&&generation===renderVersion)notify('提交图片暂时无法读取，已显示保留的笔迹。');}).finally(()=>pending.delete(task));pending.add(task);
      }
    }
    const instance={
      getDocument:()=>model.normalize(value),setDocument,
      isEmpty:()=>!value.strokes.length,
      clear(){if(!writable())throw error('READ_ONLY','当前答题区不能修改');change({...value,strokes:[]});},
      undo(){if(writable())editHistory(undo,redo);},redo(){if(writable())editHistory(redo,undo);},
      async flush(){if(stroke)finish();while(pending.size)await Promise.all([...pending]);if(stroke)throw error('ANSWER_AREA_LIMIT','笔迹超出限制，请先撤销或缩减后重试');if(saveError)throw saveError;},
      refresh(){if(!writable()&&stroke)finish(false);refresh();},
      destroy(){if(destroyed)return;destroyed=true;renderVersion++;listeners.forEach(stop=>stop());instances.delete(value.id);root.remove();resize?.();},
    };
    instances.set(value.id,instance);paint();resize?.();return Object.freeze(instance);
  }
  return {
    api:Object.freeze({version:'1.0.0',formatVersion:1,mount}),
    async flush(){for(const instance of instances.values())await instance.flush();},
    refresh(){for(const instance of instances.values())instance.refresh();},
    setActive(active){inactive=!active;for(const instance of instances.values())instance.refresh();},
    destroy(){closed=true;for(const instance of [...instances.values()])instance.destroy();},
  };
}
