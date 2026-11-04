import { el, bytes, time } from '../admin/pages/format.js';

export const STATUS = Object.freeze({ pending:'待审核', approved:'已审核 · 通过', rejected:'已审核 · 未通过',
  superseded:'已被新版本替代', withdrawn:'已撤回' });
const $ = selector => document.querySelector(selector);
const entries = new Map();
let openId = null;
let after = null;
let busy = false;
let refreshing = false;
let retryPack = null;

async function request(path, method = 'GET', body) {
  let response;
  try {
    response = await fetch(path, { method, credentials:'same-origin', cache:'no-store',
      headers:body === undefined ? {} : {'Content-Type':'application/json'},
      body:body === undefined ? undefined : JSON.stringify(body) });
  } catch { throw new Error('无法连接服务器'); }
  if (response.status === 401) {
    location.assign('/?returnTo=' + encodeURIComponent('/library/'));
    throw new Error('请先登录');
  }
  const data = response.status === 204 ? null : await response.json();
  if (!response.ok) throw new Error(data?.error?.message ?? '请求未能完成');
  return data;
}

function message(text, error = false) {
  $('#feedback').hidden = !error;
  $('#notice').hidden = error;
  const target = $(error ? '#feedback' : '#notice');
  target.textContent = text;
  if (error) target.focus();
}

async function action(button, work) {
  if (busy) return;
  busy = true;
  button.disabled = true;
  try { await work(); } catch (error) { message(error.message, true); }
  finally { busy = false; button.disabled = false; }
}

function status(entry) {
  return el('p', {class:`library-status ${entry.status}`, 'data-status':entry.id,
    text:STATUS[entry.status] ?? '待审核'});
}

function renderList() {
  $('#packages').replaceChildren(...[...entries.values()].map(entry => el('article', {class:'library-card', 'data-pack-id':entry.id},
    el('h2', {text:entry.title}), el('p', {text:entry.visibility === 'private' ? '私人题库包' : '我的公开发布'}),
    status(entry), entry.reason ? el('p', {text:entry.reason}) : null,
    !entry.originalAvailable ? el('p', {text:'原文件不可用'}) : null,
    el('button', {class:'secondary', type:'button', text:'查看题库与提交记录', onclick:() => showDetail(entry.id).catch(error => message(error.message, true))}))));
  if (!entries.size) $('#packages').append(el('p', {text:'还没有题库包，先上传一本吧。'}));
}

async function load(more = false) {
  const result = await request('/api/library/packs' + (more && after ? '?after=' + encodeURIComponent(after) : ''));
  if (!more) entries.clear();
  for (const entry of result.items) entries.set(entry.id, entry);
  after = result.items.at(-1)?.id ?? null;
  $('#more').hidden = result.items.length < 50;
  renderList();
}

async function showDetail(id) {
  const data = await request(`/api/library/packs/${encodeURIComponent(id)}`);
  openId = id;
  const holder = $('#detail');
  holder.hidden = false;
  const entry = entries.get(id);
  holder.replaceChildren(el('h2', {text:entry?.title ?? '题库包'}),
    el('p', {text:'每次导入都会创建独立的练习实例。删除此资源不会删除已导入的练习与历史。'}));
  for (const version of data.revisions) {
    const section = el('section', {'data-revision':version.revision});
    section.append(el('h3', {text:`第 ${version.revision} 版 · ${STATUS[version.status] ?? '待审核'}`}),
      el('p', {text:`${version.filename} · ${bytes(version.size)} · ${time(version.submittedAt)}`}),
      version.reason ? el('p', {text:version.reason}) : el('span'));
    const actions = el('div', {class:'library-actions'});
    if (version.originalAvailable) actions.append(el('a', {class:'secondary',
      href:`/api/library/packs/${encodeURIComponent(id)}/revisions/${version.revision}/archive`, text:'下载原文件'}));
    else section.append(el('p', {text:'原文件不可用：此条目由历史导入补建，不会重新打包冒充原文件。'}));
    if (version.revision === entry?.revision && version.status === 'rejected') {
      const reupload = el('button', {class:'secondary', type:'button', text:'在此条目重新上传', onclick:() => pick(id)});
      // 公开版本需要保留其广场信息，使用已有的整包新版本表单。
      actions.append(entry.visibility === 'private' ? reupload : el('a', {class:'secondary',href:`/publish/#mine`,text:'重新上传公开版本'}));
    }
    if (entry?.visibility === 'private' && version.originalAvailable) actions.append(el('button', {class:'secondary',type:'button',
      text:'直接发布到广场', onclick:() => publishForm(id, version.revision, section).catch(error => message(error.message,true))}));
    section.append(actions);
    const form = el('form');
    for (const bank of version.banks) form.append(el('label', {class:'library-bank'},
      el('input', {type:'checkbox',name:'banks',value:bank.bankUuid,checked:true}),
      el('span', {text:`${bank.title}（${bank.questionCount} 道题）`})));
    const group = el('input', {name:'group',type:'text',maxlength:120,placeholder:'留空放入根目录'});
    const submit = el('button', {class:'primary',type:'submit',text:'导入练习空间',disabled:version.status !== 'approved' || !version.banks.length});
    form.append(el('label', {}, '练习分组（可选）', group), submit);
    form.addEventListener('submit', event => {
      event.preventDefault();
      action(submit, async () => {
        const banks = [...form.querySelectorAll('input[name=banks]:checked')].map(input => input.value);
        const result = await request(`/api/library/packs/${encodeURIComponent(id)}/practice`, 'POST', {revision:version.revision,banks,group:group.value});
        message(`已创建 ${result.collections.length} 本独立练习，可进入练习空间作答。`);
      });
    });
    section.append(form);
    holder.append(section);
  }
  const remove = el('button', {class:'secondary',type:'button',text:'删除此资源'});
  remove.addEventListener('click', () => action(remove, async () => {
    if (!confirm('删除此题库包资源？已导入的练习与历史仍会保留；只要练习实例还在，私人文件仍计入存储。')) return;
    await request(`/api/library/uploads/${encodeURIComponent(id)}`, 'DELETE');
    openId = null;
    holder.hidden = true;
    await load();
    message('资源已删除，已导入的练习保持可用。');
  }));
  holder.append(el('div', {class:'library-actions'},remove,el('a', {href:'/practice/',class:'secondary',text:'进入练习空间'})));
}

async function publishForm(id, revision, section) {
  const config = await request('/api/catalog/categories');
  section.querySelector('[data-publish-form]')?.remove();
  const form = el('form', {'data-publish-form':true});
  for (const [name, label, max] of [['title','标题',80],['summary','简介',240],['description','详细介绍',12000],['tags','标签（逗号分隔，可空）',100]]) {
    form.append(el('label', {},label,el(name === 'description' ? 'textarea' : 'input',{name, maxlength:max,required:name !== 'tags'})));
  }
  form.append(el('label', {},'分类',el('select',{name:'category'},...config.categories.map(text=>el('option',{value:text,text})))));
  form.append(el('label', {class:'library-bank'},el('input',{name:'rights',type:'checkbox',required:true}),'我确认有权分享此题库'));
  const submit = el('button',{type:'submit',class:'primary',text:'用原文件发布'});
  form.append(submit);
  form.addEventListener('submit',event => {
    event.preventDefault();
    action(submit, async () => {
      const values = Object.fromEntries(new FormData(form));
      const accepted = await request(`/api/library/packs/${encodeURIComponent(id)}/publish`, 'POST', {...values,revision,rights:values.rights === 'on'});
      await load();
      await showDetail(accepted.packId);
      message('原文件已提交公开发布，正在排队校验；公开版本仍需管理员审核。');
    });
  });
  section.append(form);
}

function pick(pack = null) {
  if (busy) return;
  retryPack = pack;
  $('#upload-file').value = '';
  $('#upload-file').click();
}

// XHR 仅用于字节上传进度，不在浏览器解包，不保存 Cookie 或登录令牌。
function upload(file, pack) {
  return new Promise((resolve,reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST','/api/library/uploads?filename=' + encodeURIComponent(file.name) + (pack ? '&packId=' + encodeURIComponent(pack) : ''));
    xhr.setRequestHeader('Content-Type','application/octet-stream');
    xhr.upload.onprogress = event => { $('#upload-progress').textContent = `正在上传 ${event.lengthComputable ? Math.floor(100*event.loaded/event.total)+'%' : bytes(event.loaded)}`; };
    xhr.onload = () => {
      let result;
      try { result = JSON.parse(xhr.responseText); } catch { reject(new Error('服务器响应无效')); return; }
      if (xhr.status === 401) location.assign('/?returnTo=' + encodeURIComponent('/library/'));
      if (xhr.status >= 200 && xhr.status < 300) resolve(result);
      else reject(new Error(result?.error?.message ?? '上传失败'));
    };
    xhr.onerror = xhr.onabort = () => reject(new Error('无法连接服务器，请刷新列表确认上传结果后再重试'));
    xhr.send(file);
  });
}

$('#upload').addEventListener('click',() => pick());
$('#upload-file').addEventListener('change',() => action($('#upload'), async () => {
  const file = $('#upload-file').files[0];
  if (!file) return;
  if (!file.name.toLowerCase().endsWith('.qbank')) throw new Error('请选择 .qbank 文件');
  $('#upload-progress').hidden = false;
  try {
    const accepted = await upload(file, retryPack);
    await load();
    await showDetail(accepted.packId);
    message('原文件已保存，待审核；无需等待后台校验完成。');
  } finally { $('#upload-progress').hidden = true; }
}));
$('#more').addEventListener('click',event => action(event.currentTarget,() => load(true)));

async function poll() {
  if (busy || refreshing || document.hidden) return;
  refreshing = true;
  try {
    let detailChanged = false;
    let changed = false;
    for (const entry of entries.values()) {
      if (entry.status !== 'pending') continue;
      const state = await request(`/api/library/uploads/${encodeURIComponent(entry.id)}/revisions/${entry.revision}`);
      if (state.status !== entry.status || state.reason !== entry.reason) {
        changed = true;
        entry.status = state.status;
        entry.reason = state.reason;
        detailChanged ||= openId === entry.id;
      }
    }
    if (changed) renderList();
    if (detailChanged && !$('#detail [data-publish-form]')) await showDetail(openId);
  } catch (error) { message(error.message, true); }
  finally { refreshing = false; }
}

load().catch(error => message(error.message,true));
const poller = setInterval(poll, 3000);
window.addEventListener('pagehide',() => clearInterval(poller));

let favoriteAfter=null;
async function loadFavorites(more=false) {
  const data=await request('/api/library/favorites'+(more&&favoriteAfter?'?after='+encodeURIComponent(favoriteAfter):''));
  if(!more)$('#favorites').replaceChildren();
  for(const pack of data.items) {
    const remove=el('button',{type:'button',text:'取消收藏'});
    remove.onclick=()=>action(remove,async()=>{await request(`/api/plaza/packs/${encodeURIComponent(pack.id)}/favorite`,'DELETE');await loadFavorites();});
    $('#favorites').append(el('article',{class:'library-card'},el('h3',{text:pack.title}),
      pack.status==='deleted'?el('p',{text:'作者已删除'}):el('div',{class:'library-actions'},
        el('a',{href:'/plaza/#'+pack.id,text:'查看详情 / 导入练习空间'}),el('a',{href:`/api/plaza/packs/${pack.id}/archive`,text:'下载到本地'})),remove));
  }
  if(!data.items.length&&!more)$('#favorites').append(el('p',{text:'还没有收藏，去题库广场看看吧。'}));
  favoriteAfter=data.next;$('#favorites-more').hidden=!favoriteAfter;
}
$('#favorites-more').onclick=e=>action(e.currentTarget,()=>loadFavorites(true));
loadFavorites().catch(error=>message(error.message,true));
