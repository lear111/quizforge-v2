/** Keep the status visible until the first extension frame is ready. */
export function createCollectionLoading(document) {
  const node=document.querySelector('#collection-loading'),title=node.querySelector('.collection-loading-title'),detail=node.querySelector('.collection-loading-detail');
  let generation=0;
  return {
    begin(name) {
      const current=++generation;
      title.textContent=name||'';
      detail.textContent='正在校验并加载题库…';
      node.hidden=false;
      node.parentElement.setAttribute('aria-busy','true');
      return {
        setDetail(text){if(current===generation)detail.textContent=text;},
        finish(){if(current!==generation)return;node.hidden=true;node.parentElement.removeAttribute('aria-busy');},
      };
    },
  };
}
