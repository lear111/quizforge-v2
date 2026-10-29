// Relative question steps. Presses that arrive while an earlier switch is
// loading are merged, so holding a key jumps once instead of mounting every
// intermediate card.
export function createQuestionStepper(run){
  let pending=0,scheduled=null;
  const take=()=>{const offset=pending;pending=0;scheduled=null;return offset;};
  return {
    step(offset){
      if(!Number.isInteger(offset)||!offset)return scheduled||Promise.resolve();
      pending+=offset;
      return scheduled ||= run(take);
    },
    get pending(){return pending;},
  };
}

// Index of a relative step: the summary sits after the last question.
export function stepTarget(index,offset,count,{summary=false}={}){
  if(index<0||!count||!offset)return null;
  const target=Math.max(0,Math.min(summary?count:count-1,index+offset));
  return target===index?null:target;
}

// Next first: forward is the usual direction while practicing.
export function adjacentQuestionIds(rows,index,radius=1){
  const ids=[];
  if(index<0)return ids;
  for(let distance=1;distance<=radius;distance++)for(const target of [index+distance,index-distance])if(rows[target]?.id)ids.push(rows[target].id);
  return ids;
}

const editable=target=>!!target?.closest?.('input,textarea,select,[contenteditable]:not([contenteditable="false"]),[role="textbox"],[role="slider"],[role="spinbutton"]');

// Plain arrow keys on the host page. Keys typed inside a question frame never
// reach this document; editable fields, dialogs and the draft canvas keep them.
export function navigationShortcut(event,{document,draft=false}={}){
  if(event.defaultPrevented||event.isComposing||event.altKey||event.ctrlKey||event.metaKey||event.shiftKey)return 0;
  const offset=event.key==='ArrowLeft'?-1:event.key==='ArrowRight'?1:0;
  if(!offset||draft||editable(event.target))return 0;
  if(event.target?.closest?.('dialog,.qf-whiteboard-tool-row,[role="separator"]')||document?.querySelector('dialog[open]'))return 0;
  return offset;
}

export function scheduleIdle(callback,{timeout=1200,win=globalThis}={}){
  if(typeof win.requestIdleCallback==='function'){const id=win.requestIdleCallback(callback,{timeout});return ()=>win.cancelIdleCallback?.(id);}
  const id=setTimeout(callback,200);return ()=>clearTimeout(id);
}
