const STORAGE_KEY='quizforge-library-width';
const MIN_WIDTH=180,MAX_WIDTH=480,MIN_MAIN_WIDTH=320;

export function libraryWidthBounds(viewportWidth,outlineWidth=0){
  return {min:MIN_WIDTH,max:Math.max(MIN_WIDTH,Math.min(MAX_WIDTH,viewportWidth-outlineWidth-MIN_MAIN_WIDTH))};
}

export function bindLibraryResize({shell,library,handle,window:win=window}){
  const document=library.ownerDocument,outline=shell.querySelector('.outline');
  let preferredWidth=null,drag=null,shield=null;
  try{
    const saved=win.localStorage.getItem(STORAGE_KEY),value=Number(saved);
    if(saved!==null&&Number.isFinite(value)&&value>=MIN_WIDTH&&value<=MAX_WIDTH)preferredWidth=value;
  }catch{}
  const enabled=()=>win.innerWidth>800&&!library.hidden&&!shell.classList.contains('draft-fullscreen');
  function bounds(){
    const right=win.innerWidth>1100&&outline&&!outline.hidden?outline.getBoundingClientRect().width:0;
    return libraryWidthBounds(shell.getBoundingClientRect().width||win.innerWidth,right);
  }
  function currentWidth(){
    return Number.parseFloat(shell.style.getPropertyValue('--library-width'))||library.getBoundingClientRect().width||232;
  }
  function sync(){
    if(!enabled()){
      if(drag)finish(false);
      handle.hidden=true;
      return;
    }
    handle.hidden=false;
    const {min,max}=bounds();
    if(preferredWidth!==null)shell.style.setProperty('--library-width',`${Math.round(Math.max(min,Math.min(max,preferredWidth)))}px`);
    else shell.style.removeProperty('--library-width');
    handle.setAttribute('aria-valuemin',String(min));handle.setAttribute('aria-valuemax',String(max));
    handle.setAttribute('aria-valuenow',String(Math.round(currentWidth())));
  }
  function setWidth(width){
    const {min,max}=bounds();preferredWidth=Math.round(Math.max(min,Math.min(max,width)));sync();
  }
  function persist(){
    try{if(preferredWidth===null)win.localStorage.removeItem(STORAGE_KEY);else win.localStorage.setItem(STORAGE_KEY,String(preferredWidth));}catch{}
  }
  function finish(save=true){
    if(!drag)return;
    const previous=drag;drag=null;
    if(!save)preferredWidth=previous.previousWidth;
    if(handle.hasPointerCapture?.(previous.pointerId))handle.releasePointerCapture(previous.pointerId);
    shield?.remove();shield=null;shell.classList.remove('library-resizing');
    sync();if(save)persist();
  }
  function pointerDown(event){
    if(!enabled()||drag||event.button!==0||event.isPrimary===false)return;
    event.preventDefault();
    drag={pointerId:event.pointerId,startX:event.clientX,startWidth:currentWidth(),previousWidth:preferredWidth};
    shield=document.createElement('div');shield.className='library-resize-shield';shield.setAttribute('aria-hidden','true');document.body.append(shield);
    shell.classList.add('library-resizing');handle.focus({preventScroll:true});
    try{handle.setPointerCapture?.(event.pointerId);}catch{}
  }
  function pointerMove(event){
    if(!drag||event.pointerId!==drag.pointerId)return;
    if(!enabled()){finish(false);return;}
    event.preventDefault();setWidth(drag.startWidth+event.clientX-drag.startX);
  }
  function pointerUp(event){if(drag&&event.pointerId===drag.pointerId)finish();}
  function pointerCancel(event){if(drag&&event.pointerId===drag.pointerId)finish(false);}
  function lostCapture(){finish();}
  function blur(){finish();}
  function reset(){if(!enabled())return;finish(false);preferredWidth=null;sync();persist();}
  function keyDown(event){
    if(!enabled())return;
    if(event.key==='Escape'&&drag){event.preventDefault();finish(false);return;}
    const step=event.shiftKey?32:16,{min,max}=bounds();
    const value=event.key==='ArrowLeft'?currentWidth()-step:event.key==='ArrowRight'?currentWidth()+step:
      event.key==='Home'?min:event.key==='End'?max:null;
    if(value===null)return;event.preventDefault();setWidth(value);persist();
  }
  const listeners=[[handle,'pointerdown',pointerDown],[win,'pointermove',pointerMove],[win,'pointerup',pointerUp],
    [win,'pointercancel',pointerCancel],[handle,'lostpointercapture',lostCapture],[win,'blur',blur],
    [handle,'dblclick',reset],[handle,'keydown',keyDown],[win,'resize',sync]];
  for(const [target,type,listener]of listeners)target.addEventListener(type,listener);
  const observer=new win.MutationObserver(sync);
  observer.observe(shell,{attributes:true,attributeFilter:['class']});
  observer.observe(library,{attributes:true,attributeFilter:['hidden']});
  if(outline)observer.observe(outline,{attributes:true,attributeFilter:['hidden']});
  sync();
  return {destroy(){finish(false);observer.disconnect();for(const [target,type,listener]of listeners)target.removeEventListener(type,listener);}};
}
