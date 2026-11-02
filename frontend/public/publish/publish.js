// 发布题库与“我的发布”（题库广场 1/4）。两个入口：从本地上传 .qbank、从“我的题库”选择自己上传的题库。
// 发布者只能对整个题库包操作：上传整包新版本、删除整个包。所有用户数据都以 textContent 写入。

const view = document.getElementById('publish-view');
const feedback = document.getElementById('publish-feedback');
const notice = document.getElementById('publish-notice');
const STATUS = { pending: ['审核中', 'pending'], approved: ['已通过', 'approved'], rejected: ['未通过', 'rejected'],
  superseded: ['已由新版本代替', ''], withdrawn: ['已撤回', ''] };
let config = null;

class RequestError extends Error {
  constructor(code, message, status = 0) { super(message); this.code = code; this.status = status; }
}

export function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

async function request(path, method = 'GET', body) {
  let response;
  try {
    response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store',
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
  } catch { throw new RequestError('NETWORK_ERROR', '无法连接服务器'); }
  if (response.status === 204) return null;
  let data = null;
  try { data = await response.json(); } catch { /* 下面按状态处理 */ }
  if (!response.ok) throw new RequestError(data?.error?.code ?? 'UNKNOWN_ERROR', data?.error?.message ?? '请求未能完成，请稍后重试', response.status);
  return data;
}

// 上传使用 XMLHttpRequest：进度来自真实的传输事件；成功提示等待服务器保存确认。
function upload(path, form, progress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', path);
    xhr.withCredentials = true;
    xhr.upload.addEventListener('progress', (event) => { if (event.lengthComputable) progress.value = event.loaded / event.total; });
    xhr.addEventListener('load', () => {
      let data = null;
      try { data = JSON.parse(xhr.responseText); } catch { /* 下面按状态处理 */ }
      if (xhr.status >= 200 && xhr.status < 300) resolve(data);
      else reject(new RequestError(data?.error?.code ?? 'UNKNOWN_ERROR', data?.error?.message ?? '请求未能完成，请稍后重试', xhr.status));
    });
    // 网络中断不自动重试：结果可能已保存，请先查看“我的发布”。
    xhr.addEventListener('error', () => reject(new RequestError('NETWORK_ERROR', '网络中断，结果可能已经保存，请先在“我的发布”中确认，避免重复提交')));
    xhr.send(form);
  });
}

function showError(error) {
  notice.hidden = true;
  feedback.textContent = error.message;
  feedback.hidden = false;
  feedback.focus();
}

function showNotice(message) {
  feedback.hidden = true;
  notice.textContent = message;
  notice.hidden = false;
}

function clearMessages() { feedback.hidden = true; notice.hidden = true; }

// ------------------------------------------------------------------ 广场信息字段

export function infoFields(initial = {}) {
  const field = (label, control, hint) => el('div', { class: 'field' }, el('label', { for: control.id, text: label }), control,
    hint ? el('small', { text: hint }) : null);
  const title = el('input', { id: 'pack-title', name: 'title', maxlength: '80', required: true, value: initial.title ?? '' });
  const summary = el('input', { id: 'pack-summary', name: 'summary', maxlength: '240', required: true, value: initial.summary ?? '' });
  const description = el('textarea', { id: 'pack-description', name: 'description', maxlength: '12000', rows: '6', required: true });
  description.value = initial.description ?? '';
  const category = el('select', { id: 'pack-category', name: 'category' },
    ...config.categories.map((name) => el('option', { value: name, text: name, selected: name === (initial.category ?? '其他') })));
  const tags = el('input', { id: 'pack-tags', name: 'tags', maxlength: '120', value: (initial.tags ?? []).join('，'), placeholder: '用逗号分隔，最多 5 个' });
  return [
    field('名称', title, '最多 80 个字符'),
    field('简介', summary, '列表中显示的一句话，最多 240 个字符'),
    field('详细说明', description, '最多 12000 个字符'),
    field('分类', category),
    field('标签', tags, '最多 5 个，合计不超过 100 个字符'),
  ];
}

function screenshotsField() {
  const input = el('input', { id: 'pack-screenshots', name: 'screenshots', type: 'file', multiple: true, accept: 'image/png,image/jpeg,image/webp' });
  return el('div', { class: 'field' }, el('label', { for: 'pack-screenshots', text: '截图（可选）' }), input,
    el('small', { text: `PNG、JPEG 或 WebP，最多 ${config.maxScreenshots} 张，每张不超过 3 MiB` }));
}

function rightsField() {
  return el('label', { class: 'upload-rights' }, el('input', { type: 'checkbox', name: 'rights', value: 'yes', required: true }),
    '我确认有权分享这些题库及其中的图片、音频和拓展，并同意它们在审核通过后公开。');
}

function checkScreenshots(form, kept = 0) {
  const files = [...form.elements.namedItem('screenshots').files];
  if (files.length + kept > config.maxScreenshots) throw new RequestError('PACK_INFO_INVALID', `截图最多 ${config.maxScreenshots} 张`);
  for (const file of files) {
    if (file.size > config.maxScreenshotBytes) throw new RequestError('PACK_INFO_INVALID', `截图「${file.name}」超过 3 MiB`);
  }
}

// ------------------------------------------------------------------ 发布题库

async function renderNew() {
  const shareable = (await request('/api/catalog/shareable')).items;
  const archive = el('input', { id: 'pack-archive', name: 'archive', type: 'file', accept: '.qbank' });
  const uploadPane = el('div', { class: 'field' }, el('label', { for: 'pack-archive', text: '题库包（.qbank）' }),
    el('div', { class: 'dropzone' }, archive, el('small', { text: '作者上传的原文件会原样保存，以后下载时逐字节一致；不超过 128 MiB。' })));
  const libraryPane = el('div', { class: 'field', hidden: true }, el('span', { class: 'label', text: '从我的题库选择（只能分享自己上传的题库）' }),
    shareable.length ? el('div', { class: 'library-list' }, ...shareable.map((item) => el('label', {},
      el('input', { type: 'checkbox', name: 'collectionIds', value: item.id }),
      `${item.groupName ? `${item.groupName} / ` : ''}${item.title}（${item.questionCount} 题）`)))
      : el('p', { class: 'publish-muted', text: '你还没有自己上传的题库。' }));
  const source = el('div', { class: 'source-switch', role: 'radiogroup', 'aria-label': '题库来源' },
    el('label', {}, el('input', { type: 'radio', name: 'source', value: 'upload', checked: true }), '从本地上传 .qbank'),
    el('label', {}, el('input', { type: 'radio', name: 'source', value: 'library' }), '从我的题库选择'));
  const progress = el('progress', { class: 'upload-progress', max: '1', value: '0', hidden: true });
  const submit = el('button', { class: 'primary', type: 'submit', text: '提交审核' });
  const form = el('form', { id: 'publish-form', novalidate: true }, source, uploadPane, libraryPane, ...infoFields(),
    screenshotsField(), rightsField(), progress, submit);
  source.addEventListener('change', () => {
    const library = form.elements.namedItem('source').value === 'library';
    uploadPane.hidden = library; libraryPane.hidden = !library;
  });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearMessages();
    const library = form.elements.namedItem('source').value === 'library';
    try {
      const data = new FormData(form);
      data.delete('source');
      checkScreenshots(form);
      if (!form.elements.namedItem('rights').checked) throw new RequestError('PACK_INFO_INVALID', '请确认你有权分享这些题库');
      if (library) {
        data.delete('archive');
        if (!data.getAll('collectionIds').length) throw new RequestError('PACK_INFO_INVALID', '请选择要分享的题库');
      } else {
        data.delete('collectionIds');
        if (!archive.files.length) throw new RequestError('PACK_INFO_INVALID', '请选择 .qbank 题库包');
        if (archive.files[0].size > config.maxArchiveBytes) throw new RequestError('LIBRARY_ARCHIVE_LIMIT', '题库包超过 128 MiB 上传上限');
      }
      submit.disabled = true; progress.hidden = false; progress.value = 0;
      const result = await upload(library ? '/api/catalog/packs/from-library' : '/api/catalog/packs', data, progress);
      history.replaceState(null, '', '#mine');
      await route();
      showNotice(`已提交审核（第 ${result.revision} 版）。审核结果会通过站内通知告诉你。`);
    } catch (error) {
      showError(error);
    } finally {
      submit.disabled = false; progress.hidden = true;
    }
  });
  view.replaceChildren(el('h1', { text: '发布题库' }),
    el('p', { class: 'publish-muted', text: '提交后由管理员审核，通过后在题库广场公开。每个版本只审核一次；以后修改请上传整包新版本。' }), form);
}

// ------------------------------------------------------------------ 我的发布

function badge(status) {
  const [label, tone] = STATUS[status] ?? [status, ''];
  return el('span', { class: `badge ${tone}`.trim(), text: label });
}

function time(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';
}

function revisionForm(pack, latest) {
  const archive = el('input', { name: 'archive', type: 'file', accept: '.qbank', 'aria-label': '新版本题库包' });
  const notes = el('textarea', { name: 'releaseNotes', maxlength: '2000', rows: '3', placeholder: '更新说明：会随题库更新通知发送给已加入的用户' });
  const keep = latest.screenshots.length ? el('div', { class: 'field' }, el('span', { class: 'label', text: '沿用上一版本的截图' }),
    el('div', { class: 'shot-grid' }, ...latest.screenshots.map((shot) => el('figure', {},
      el('img', { src: `/api/catalog/files/${encodeURIComponent(shot.id)}`, alt: shot.name, loading: 'lazy' }),
      el('figcaption', {}, el('input', { type: 'checkbox', name: 'keepScreenshots', value: shot.id, checked: true }), shot.name))))) : null;
  const progress = el('progress', { class: 'upload-progress', max: '1', value: '0', hidden: true });
  const submit = el('button', { class: 'primary', type: 'submit', text: '提交新版本' });
  const form = el('form', { class: 'revision-form', novalidate: true, 'data-pack': pack.id },
    el('div', { class: 'field' }, el('label', { text: '新版本题库包（.qbank，整包）' }), el('div', { class: 'dropzone' }, archive,
      el('small', { text: '包内题库按 bank.json 的 UUID 对应：UUID 相同为更新，消失为移除，多出为新增。' }))),
    el('div', { class: 'field' }, el('label', { text: '更新说明' }), notes),
    ...infoFields(latest), keep, screenshotsField(), rightsField(), progress, submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    clearMessages();
    try {
      const kept = [...form.querySelectorAll('input[name="keepScreenshots"]:checked')].length;
      checkScreenshots(form, kept);
      if (!archive.files.length) throw new RequestError('PACK_INFO_INVALID', '请选择新版本的 .qbank 题库包');
      if (!form.elements.namedItem('rights').checked) throw new RequestError('PACK_INFO_INVALID', '请确认你有权分享这些题库');
      submit.disabled = true; progress.hidden = false;
      const result = await upload(`/api/catalog/packs/${encodeURIComponent(pack.id)}/revisions`, new FormData(form), progress);
      showNotice(`第 ${result.revision} 版已提交审核。审核期间题库广场继续公开上一个已审核版本。`);
      await renderMine();
    } catch (error) {
      showError(error);
      submit.disabled = false; progress.hidden = true;
    }
  });
  return form;
}

function packItem(pack) {
  const latest = pack.revisions[0];
  const deleted = pack.status === 'deleted';
  const header = el('h3', {}, latest.title, deleted ? el('span', { class: 'badge', text: '已删除' }) : null,
    !deleted && pack.approvedRevision ? el('span', { class: 'badge public', text: `广场公开第 ${pack.approvedRevision} 版` }) : null);
  const revisions = el('ol', { class: 'revision-list' }, ...pack.revisions.map((revision) => el('li', { 'data-revision': revision.revision },
    el('strong', { text: `第 ${revision.revision} 版` }), badge(revision.reviewStatus),
    ` ${revision.banks.length} 本题库 · ${revision.filename} · 提交于 ${time(revision.submittedAt)}`,
    revision.reviewedAt ? ` · 审核于 ${time(revision.reviewedAt)}` : '',
    revision.reviewStatus === 'rejected' && revision.reviewNote
      ? el('p', { class: 'reject-reason', text: `未通过原因：${revision.reviewNote}\n被拒绝的版本不能修改后重新提交，请修改后上传新版本。` }) : null)));
  const item = el('article', { class: 'pack-item', 'data-pack-id': pack.id }, header,
    el('p', { class: 'publish-muted', text: latest.summary }), revisions);
  if (!deleted) {
    const holder = el('div');
    const reviseButton = el('button', { class: 'secondary', type: 'button', text: '上传新版本' });
    reviseButton.addEventListener('click', () => {
      holder.replaceChildren(holder.childElementCount ? '' : revisionForm(pack, latest));
    });
    const deleteButton = el('button', { class: 'danger', type: 'button', text: '删除整个题库包' });
    deleteButton.addEventListener('click', async () => {
      if (!confirm(`删除题库包「${latest.title}」？\n删除后从题库广场下架，不能再加入；已经加入的用户保留题库。此操作不能撤销。`)) return;
      deleteButton.disabled = true;
      try {
        await request(`/api/catalog/packs/${encodeURIComponent(pack.id)}`, 'DELETE');
        showNotice('题库包已删除');
        await renderMine();
      } catch (error) { showError(error); deleteButton.disabled = false; }
    });
    item.append(el('div', { class: 'pack-actions' }, reviseButton, deleteButton), holder);
  }
  return item;
}

async function renderMine() {
  const packs = (await request('/api/catalog/my-packs')).items;
  view.replaceChildren(el('h1', { text: '我的发布' }),
    packs.length ? el('div', {}, ...packs.map(packItem))
      : el('p', { class: 'publish-muted' }, '你还没有发布题库。', el('a', { href: '#new', text: '发布第一个题库' })));
}

// ------------------------------------------------------------------ 路由

async function route() {
  const page = location.hash === '#mine' ? 'mine' : 'new';
  for (const link of document.querySelectorAll('.publish-nav a')) {
    if (link.dataset.view === page) link.setAttribute('aria-current', 'page'); else link.removeAttribute('aria-current');
  }
  document.title = `${page === 'mine' ? '我的发布' : '发布题库'} · QuizForge`;
  try {
    if (!config) config = await request('/api/catalog/categories');
    if (page === 'mine') await renderMine(); else await renderNew();
  } catch (error) {
    if (error.status === 401) { location.href = `/?returnTo=${encodeURIComponent('/publish/' + location.hash)}`; return; }
    view.replaceChildren(el('p', { class: 'publish-muted', text: error.message }));
  }
}

window.addEventListener('hashchange', () => { clearMessages(); void route(); });
void route();
