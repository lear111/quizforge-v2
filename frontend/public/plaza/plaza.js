import {el} from '../admin/pages/format.js';
import {mountFeedback} from './feedback.js';
import {card,emptyState,loadingCards,tags,section,action,icon} from './view.js';
const $=s=>document.querySelector(s);
let next=null,busy=false,detailGeneration=0;
async function request(path,method='GET',body) {
  const response=await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers:body?{'Content-Type':'application/json'}:{},body:body?JSON.stringify(body):undefined});
  if(response.status===401){location.assign('/?returnTo='+encodeURIComponent('/plaza/'+location.hash));throw new Error('请先登录');}
  const data=response.status===204?null:await response.json();
  if(!response.ok)throw Object.assign(new Error(data?.error?.message??'请求失败'),{status:response.status});
  return data;
}
const message=error=>error instanceof TypeError?'无法连接服务器，请检查网络后重试':error.message;
async function work(run){
  if(busy)return;
  busy=true;$('#search button').disabled=true;$('#more').disabled=true;$('#feedback').textContent='';
  try{await run();}catch(error){$('#feedback').textContent=message(error);}
  finally{busy=false;$('#search button').disabled=false;$('#more').disabled=false;}
}
async function load(more=false){
  const values=new FormData($('#search'));
  const query=new URLSearchParams({q:values.get('q'),sort:values.get('sort')});
  if(more&&next)query.set('cursor',next);
  $('#result-count').textContent='正在读取题库…';$('#packs').setAttribute('aria-busy','true');
  if(!more&&!$('#packs').children.length)$('#packs').replaceChildren(...loadingCards());
  try{
    const data=await request('/api/plaza/packs?'+query);
    if(!more)$('#packs').replaceChildren();
    for(const pack of data.items)$('#packs').append(card(pack));
    next=data.next;$('#more').hidden=!next;
    const count=$('#packs').querySelectorAll('.plaza-card').length;
    $('#result-count').textContent=next?`已显示 ${count} 份题库`:`共 ${count} 份题库`;
    if(!data.items.length&&!more)$('#packs').append(emptyState('暂无符合条件的题库','尝试更换关键词，或返回查看全部题库。',()=>{$('#search').reset();void work(()=>load());},'查看全部题库'));
  }catch(error){
    $('#result-count').textContent='读取失败';
    if(!more)$('#packs').replaceChildren(emptyState('题库暂时无法读取',message(error),()=>work(()=>load())));
    throw error;
  }finally{$('#packs').setAttribute('aria-busy','false');}
}
// 只请求打开客户端；下载、校验和导入结果由客户端报告。
let localImportConfig;
async function localImport(pack){
  localImportConfig??=fetch('/api/config',{cache:'no-store',credentials:'same-origin'}).then(response=>response.json()).then(config=>config.localImport?.enabled===true).catch(()=>false);
  if(!await localImportConfig||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(pack.id))return [];
  const link=action('a','下载并导入到本地','在系统提示中允许打开 QuizForge','qbank',{href:`quizforge://hub/import?v=1&pack=${pack.id}&revision=${Number(pack.revision)}`,'aria-label':'下载并导入到本地'});
  link.onclick=()=>{$('#feedback').textContent='已请求打开 QuizForge，请在系统提示中允许打开。未安装客户端时可以下载文件后手动导入。';};
  return [link];
}
function showDetail(visible){$('#hero').hidden=visible;$('#library').hidden=visible;$('#detail').hidden=!visible;}
function openScreenshot(src,alt){const modal=$('#lightbox');modal.querySelector('img').src=src;modal.querySelector('img').alt=alt;modal.showModal();}
async function detail(){
  const generation=++detailGeneration,id=location.hash.slice(1);
  $('#feedback').textContent='';showDetail(!!id);if(!id)return;
  const holder=$('#detail');holder.replaceChildren(el('p',{class:'detail-loading',role:'status',text:'正在读取题库详情…'}));
  try{
    const data=await request('/api/plaza/packs/'+encodeURIComponent(id)),p=data.pack;
    const clientLinks=await localImport(p);
    if(generation!==detailGeneration)return;
    const favorite=action('button',data.favorite?'取消收藏':'收藏','在“我的题库”的收藏中快速找到它','star',{type:'button','aria-label':data.favorite?'取消收藏':'收藏','aria-pressed':String(data.favorite)});
    favorite.onclick=()=>work(async()=>{await request(`/api/plaza/packs/${id}/favorite`,data.favorite?'DELETE':'PUT');if(generation===detailGeneration)await detail();});
    const form=el('form',{'aria-label':'选择导入题库'}),count=el('b',{text:String(data.banks.length)});
    const submit=action('button','导入练习空间','导入所选题库，创建独立练习实例','space',{type:'submit',class:'action primary',form:'bank-selection','aria-label':'导入练习空间'});
    form.id='bank-selection';
    const selectAll=el('input',{type:'checkbox',checked:true,'aria-label':'全选题库'});
    form.append(el('div',{class:'bank-toolbar'},el('label',{class:'check-all'},selectAll,'全选题库'),el('span',{class:'sel-sum'},'已选 ',count,` / ${data.banks.length} 本`)));
    for(const bank of data.banks)form.append(el('label',{class:'plaza-bank bank'},
      el('input',{type:'checkbox',name:'banks',value:bank.uuid,checked:true}),
      el('span',{class:'bank-info'},el('span',{class:'bank-name',text:bank.title}),el('span',{class:'bank-meta',text:`${bank.questionCount} 道题`}))));
    const updateSelection=()=>{const selected=form.querySelectorAll('[name=banks]:checked').length;count.textContent=String(selected);selectAll.checked=selected===data.banks.length;selectAll.indeterminate=selected>0&&selected<data.banks.length;submit.disabled=selected===0;};
    selectAll.onchange=()=>{for(const input of form.querySelectorAll('[name=banks]'))input.checked=selectAll.checked;updateSelection();};
    form.onchange=updateSelection;updateSelection();
    form.onsubmit=event=>{event.preventDefault();void work(async()=>{submit.disabled=true;try{
      const banks=[...form.querySelectorAll('[name=banks]:checked')].map(input=>input.value);
      await request(`/api/plaza/packs/${id}/imports`,'POST',{banks});
      $('#feedback').replaceChildren('已导入独立练习实例。',el('a',{href:'/practice/',text:'进入练习空间'}));
    }finally{updateSelection();}});};
    const shots=el('div',{class:'shots'});
    for(const shot of data.screenshots){const src='/api/catalog/files/'+encodeURIComponent(shot.id),button=el('button',{class:'shot',type:'button','aria-label':`查看截图：${shot.name}`},el('img',{src,alt:shot.name,loading:'lazy'}));button.onclick=()=>openScreenshot(src,shot.name);shots.append(button);}
    if(!shots.children.length)shots.append(el('p',{class:'detail-catalog-note',text:'作者暂未上传截图。'}));
    const body=el('div',{class:'d-body'},section('题库介绍',el('p',{class:'intro-text plaza-description',text:data.description||p.summary})),
      section(`包含题库 · ${data.banks.length} 本`,el('div',{class:'bank-list'},form)),section('题库截图',shots),
      section('版本与更新说明',el('p',{class:'release-notes',text:data.releaseNotes||'暂无更新说明'})));
    holder.replaceChildren(
      el('nav',{class:'crumbs','aria-label':'面包屑'},el('a',{href:'/plaza/'},icon('back'),'发现题库'),el('span',{text:'/'}),el('span',{class:'cur',text:p.title})),
      el('div',{class:'detail-layout'},
        el('header',{class:'d-head'},el('div',{class:'badges'},...tags(p.tags).map(tag=>el('span',{class:'tag',text:tag}))),el('h1',{text:p.title}),
          el('div',{class:'d-meta'},el('span',{class:'author'},el('span',{class:'mini-avatar','aria-hidden':true,text:Array.from(p.ownerName||'Q')[0]}),p.ownerName||'发布者'),
            el('span',{text:String(p.createdAt||'').slice(0,10)}),el('span',{text:`第 ${p.revision} 版`}),el('span',{text:`下载 ${p.downloadCount} 次`})),el('p',{class:'d-summary',text:p.summary})),
        el('aside',{class:'side','aria-label':'获取题库'},el('div',{class:'panel'},
          el('div',{class:'file-chip'},el('span',{class:'file-icon'},icon('qbank')),el('div',{},el('div',{class:'file-name',text:p.title}),el('div',{class:'file-sub',text:`原始 .qbank 文件 · 第 ${p.revision} 版`}))),
          submit,...clientLinks,action('a','下载到本地','原始文件，手动导入或离线保存','download',{href:`/api/plaza/packs/${id}/archive`,'aria-label':'下载到本地'}),favorite,
          el('p',{class:'client-line'},'还没有客户端？',el('a',{href:'/download/',text:'下载软件'})))),body));
    // 评论独立加载，不能阻塞收藏、导入或导航。
    void mountFeedback(body,id,request);
  }catch(error){
    if(generation!==detailGeneration)return;
    holder.replaceChildren(el('nav',{class:'crumbs'},el('a',{href:'/plaza/',text:'返回题库广场'})),
      emptyState(error.status===404?'这份题库暂不可用':'题库详情读取失败',error.status===404?'作者已删除或该题库尚未公开，无法下载或导入。':message(error),()=>void detail()));
  }
}
$('#search').onsubmit=event=>{event.preventDefault();void work(()=>load());};
$('#more').onclick=()=>work(()=>load(true));
$('#menu').onclick=()=>{const open=$('#menu').getAttribute('aria-expanded')!=='true';$('#menu').setAttribute('aria-expanded',String(open));$('#menu').setAttribute('aria-label',open?'收起导航':'展开导航');$('#main-nav').classList.toggle('open',open);};
$('.skip-link').onclick=event=>{event.preventDefault();$('#main').setAttribute('tabindex','-1');$('#main').focus();};
$('#lightbox button').onclick=()=>$('#lightbox').close();
$('#lightbox').addEventListener('click',event=>{if(event.target===$('#lightbox'))$('#lightbox').close();});
document.addEventListener('keydown',event=>{if(event.key==='/'&&!['INPUT','TEXTAREA','SELECT'].includes(document.activeElement?.tagName)&&!$('#hero').hidden){event.preventDefault();$('#search input').focus();}});
// 导航独立于写操作忙碌状态；异步返回以代次检查，避免旧详情覆盖新选择。
window.addEventListener('hashchange',()=>void detail());
void work(()=>load());void detail();
