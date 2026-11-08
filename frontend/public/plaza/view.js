import {el} from '../admin/pages/format.js';

// 来自旧 Hub catalog-view.mjs 的卡片、详情与目录布局；动态文字仍只写 textContent。
const paths={qbank:'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8l-5-5ZM14 3v5h5M9 13h6M9 17h4',download:'M12 3v12m-4-4 4 4 4-4M4 17v3h16v-3',space:'M7 18a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 8a4 4 0 0 1-1 10H7Zm2.5-5L12 10.5l2.5 2.5M12 10.5V16',back:'M19 12H5m6-6-6 6 6 6',search:'M20 20l-5-5M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0',star:'m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3l-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z'};
export function icon(name){
  const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
  svg.setAttribute('class','icon');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');
  const path=document.createElementNS(svg.namespaceURI,'path');path.setAttribute('d',paths[name]||paths.qbank);svg.append(path);return svg;
}
export function tags(raw){try{const value=typeof raw==='string'?JSON.parse(raw):raw;return Array.isArray(value)?value.filter(v=>typeof v==='string'):[];}catch{return [];}}
const day=value=>String(value||'').slice(0,10);
export function card(pack){
  const href='#'+encodeURIComponent(pack.id);
  return el('article',{class:'quiz-card plaza-card','data-pack-id':pack.id},
    el('a',{class:'card-art',href,'aria-label':`查看${pack.title}详情`},el('span',{class:'cover-fallback'},icon('qbank'))),
    el('div',{class:'card-main'},
      el('h3',{class:'card-title'},el('a',{href,text:pack.title})),
      el('p',{class:'card-desc',text:pack.summary}),
      el('div',{class:'author-row'},el('span',{class:'author',text:pack.ownerName||'发布者'}),el('time',{class:'date',text:day(pack.createdAt)}))),
    el('div',{class:'card-bottom'},el('span',{class:'meta-item'},icon('download'),`${pack.downloadCount} 次下载`),
      el('div',{class:'card-buttons'},el('a',{class:'btn-outline',href,'aria-label':`查看${pack.title}详情`,text:'详情'}),el('a',{class:'btn-filled',href:`/api/plaza/packs/${encodeURIComponent(pack.id)}/archive`,text:'下载'}))));
}
export function emptyState(title,message,onRetry,label='重新加载'){
  const button=el('button',{class:'reset-button',type:'button',text:label});button.onclick=onRetry;
  return el('div',{class:'empty-state'},el('div',{class:'empty-icon'},icon('search')),el('h3',{text:title}),el('p',{text:message}),button);
}
export function loadingCards(){return Array.from({length:3},()=>el('div',{class:'loading-card','aria-hidden':true},...['cover','','short',''].map(kind=>el('div',{class:'skeleton '+kind}))));}
export function section(title,...children){return el('section',{class:'d-section'},el('h2',{text:title}),...children);}
export function action(tag,title,subtitle,symbol,attributes={}){
  return el(tag,{class:'action',...attributes},el('span',{class:'a-icon'},icon(symbol)),el('span',{},el('b',{text:title}),el('small',{text:subtitle})));
}
