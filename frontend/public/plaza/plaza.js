import {el} from '../admin/pages/format.js';
import {mountFeedback} from './feedback.js';
const $=s=>document.querySelector(s);
let next=null;
let busy=false;
async function request(path,method='GET',body) {
  const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});
  if(response.status===401){location.assign('/?returnTo='+encodeURIComponent('/plaza/'+location.hash));throw new Error('请先登录');}
  const data=response.status===204?null:await response.json();
  if(!response.ok)throw new Error(data?.error?.message??'请求失败');
  return data;
}
async function work(action){if(busy)return;busy=true;$('#search button').disabled=true;$('#more').disabled=true;try{await action();}catch(error){$('#feedback').textContent=error.message==='Failed to fetch'?'无法连接服务器':error.message;}finally{busy=false;$('#search button').disabled=false;$('#more').disabled=false;}}
async function load(more=false){
  const values=new FormData($('#search'));
  const query=new URLSearchParams({q:values.get('q'),sort:values.get('sort')});
  if(more&&next)query.set('cursor',next);
  const data=await request('/api/plaza/packs?'+query);
  if(!more)$('#packs').replaceChildren();
  for(const pack of data.items)$('#packs').append(el('article',{class:'plaza-card','data-pack-id':pack.id},
    el('h2',{},el('a',{href:'#'+pack.id,text:pack.title})),el('p',{text:pack.summary}),
    el('p',{text:`${pack.ownerName??'发布者'} · 第 ${pack.revision} 版 · 下载 ${pack.downloadCount} 次`})));
  next=data.next;$('#more').hidden=!next;
  if(!data.items.length&&!more)$('#packs').append(el('p',{text:'暂无符合条件的题库'}));
}
// “下载并导入到本地”：只请求打开客户端，下载、校验和导入结果由客户端报告（遗留契约 C-05 第 13 节）。
let localImportConfig;
async function localImport(pack){
  localImportConfig??=fetch('/api/config',{cache:'no-store'}).then(response=>response.json()).then(config=>config.localImport?.enabled===true).catch(()=>false);
  if(!await localImportConfig||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(pack.id))return [];
  const link=el('a',{href:`quizforge://hub/import?v=1&pack=${pack.id}&revision=${Number(pack.revision)}`,text:'下载并导入到本地'});
  link.onclick=()=>{$('#feedback').textContent='已请求打开 QuizForge，请在系统提示中允许打开。未安装客户端时可以下载文件后手动导入。';};
  return [link];
}
async function detail(){
  const id=location.hash.slice(1);if(!id){$('#detail').hidden=true;return;}
  const data=await request('/api/plaza/packs/'+encodeURIComponent(id));
  if(location.hash.slice(1)!==id)return;
  const p=data.pack;
  const holder=$('#detail');holder.hidden=false;
  const favorite=el('button',{type:'button',text:data.favorite?'取消收藏':'收藏'});
  favorite.onclick=()=>work(async()=>{await request(`/api/plaza/packs/${id}/favorite`,data.favorite?'DELETE':'PUT');await detail();});
  holder.replaceChildren(el('h2',{text:p.title}),el('p',{text:`${p.ownerName??'发布者'} · 第 ${p.revision} 版 · 下载 ${p.downloadCount} 次`}),
    el('p',{text:p.summary}),el('p',{text:'标签：'+JSON.parse(p.tags).join('、')}),el('p',{class:'plaza-description',text:data.description}),el('h3',{text:'更新说明'}),el('p',{text:data.releaseNotes||'暂无更新说明'}),
    ...data.screenshots.map(s=>el('img',{src:'/api/catalog/files/'+encodeURIComponent(s.id),alt:s.name})),
    el('div',{class:'library-actions'},el('a',{href:`/api/plaza/packs/${id}/archive`,text:'下载到本地'}),...await localImport(p),favorite));
  const form=el('form');
  for(const bank of data.banks)form.append(el('label',{class:'plaza-bank'},el('input',{type:'checkbox',name:'banks',value:bank.uuid,checked:true}),`${bank.title}（${bank.questionCount} 道题）`));
  const submit=el('button',{type:'submit',text:'导入练习空间'});form.append(submit);
  form.onsubmit=event=>{event.preventDefault();work(async()=>{submit.disabled=true;try{
    const banks=[...form.querySelectorAll(':checked')].map(e=>e.value);await request(`/api/plaza/packs/${id}/imports`,'POST',{banks});
    $('#feedback').replaceChildren('已导入独立练习实例。',el('a',{href:'/practice/',text:'进入练习空间'}));
  }finally{submit.disabled=false;}});};holder.append(form);
  // 评论独立加载，不能让它占用收藏、导入等操作的忙碌状态。
  void mountFeedback(holder,id,request);
}
$('#search').onsubmit=e=>{e.preventDefault();work(()=>load());};$('#more').onclick=()=>work(()=>load(true));
// 导航不能被搜索或收藏的忙碌状态丢弃；详情返回时再核对当前选择。
window.addEventListener('hashchange',()=>detail().catch(error=>{$('#feedback').textContent=error.message;}));
work(async()=>{await load();await detail();});
