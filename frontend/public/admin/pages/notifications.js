import { query } from '../api.js';
import { el, time, badge, shortId } from './format.js';
import { textWithLinks } from '/practice/shell/notifications.js';

// 站内通知：新建、查看、删除、置顶；不能修改（要改就删除重发）。管理员联系方式发一条置顶通知即可。
const AUDIENCE = { all: '所有人', user: '单个用户', bank: '题库用户' };
const SOURCE = { admin: '管理员', system: '系统', publisher: '题库发布者' };

export async function render(container, context) {
  const title = el('input', { name: 'title', maxlength: '100', required: true, placeholder: '标题（最多 100 字）' });
  const body = el('textarea', { name: 'body', rows: '6', maxlength: '5000', required: true,
    placeholder: '正文为纯文本，换行会保留，http(s) 链接可点击（最多 5000 字）' });
  const audience = el('select', { name: 'audience' },
    el('option', { value: 'all', text: '所有人' }), el('option', { value: 'user', text: '单个用户' }));
  const userId = el('input', { name: 'userId', maxlength: '36', placeholder: '用户 ID（在用户管理的详情中查看）' });
  const userField = el('label', { hidden: true }, '用户 ID', userId);
  const pinned = el('input', { type: 'checkbox', name: 'pinned' });
  const submit = el('button', { type: 'submit', class: 'qa-button', text: '发布' });
  audience.addEventListener('change', () => { userField.hidden = audience.value !== 'user'; });
  if (context.param) { audience.value = 'user'; userId.value = context.param; userField.hidden = false; }

  const form = el('form', { class: 'qa-panel qa-form', 'data-action': 'publish-notification' },
    el('h2', { text: '发布通知' }),
    el('p', { class: 'qa-muted', text: '发布后不能修改，只能删除重发。管理员联系方式请发一条置顶通知。' }),
    el('label', {}, '标题', title), el('label', {}, '正文', body),
    el('label', {}, '接收者', audience), userField,
    el('label', { class: 'qa-check' }, pinned, '置顶'), submit);

  const list = el('section', { class: 'qa-panel' });
  const more = el('button', { type: 'button', class: 'qa-button secondary', text: '加载更多', hidden: true });
  let cursor = null;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!title.value.trim() || !body.value.trim()) { context.toast('请填写标题和正文', 'danger'); return; }
    submit.disabled = true;
    try {
      await context.request('/api/admin/notifications', 'POST', {
        title: title.value.trim(), body: body.value, audience: audience.value,
        userId: audience.value === 'user' ? userId.value.trim() : undefined, pinned: pinned.checked,
      });
      context.toast('通知已发布', 'success');
      form.reset();
      userField.hidden = true;
      await load(true);
    } catch (error) {
      context.toast(error.message, 'danger');
    } finally {
      submit.disabled = false;
    }
  });

  function entry(item) {
    const pin = el('button', { type: 'button', class: 'qa-button small secondary', text: item.pinned ? '取消置顶' : '置顶' });
    const remove = el('button', { type: 'button', class: 'qa-button small danger', text: '删除' });
    pin.addEventListener('click', async () => {
      try {
        await context.request(`/api/admin/notifications/${encodeURIComponent(item.id)}/pin`, 'PUT', { pinned: !item.pinned });
        await load(true);
      } catch (error) {
        context.toast(error.message, 'danger');
      }
    });
    remove.addEventListener('click', async () => {
      if (!confirm(`删除通知“${item.title}”？所有用户将不再看到它。`)) return;
      try {
        await context.request(`/api/admin/notifications/${encodeURIComponent(item.id)}`, 'DELETE');
        context.toast('已删除', 'success');
        await load(true);
      } catch (error) {
        context.toast(error.message, 'danger');
      }
    });
    const target = item.audience === 'user' ? `：${shortId(item.targetId)}` : item.audience === 'bank' ? `：${item.targetId}` : '';
    return el('article', { class: 'qa-notice', 'data-notification-id': item.id },
      el('h3', {}, item.title, item.pinned ? badge('置顶', 'accent') : null),
      el('p', { class: 'qa-muted', text: `${time(item.createdAt)} · ${SOURCE[item.source] ?? item.source}`
        + `${item.createdBy ? `（${item.createdBy.name ?? shortId(item.createdBy.id)}）` : ''} · 发给${AUDIENCE[item.audience] ?? item.audience}${target}` }),
      el('div', { class: 'qa-notice-body' }, textWithLinks(item.body)),
      el('div', { class: 'qa-actions' }, pin, remove));
  }

  async function load(reset) {
    if (reset) { cursor = null; list.replaceChildren(); }
    more.disabled = true;
    try {
      const page = await context.request(query('/api/admin/notifications', { cursor, limit: 20 }));
      list.append(...page.items.map(entry));
      cursor = page.nextCursor;
      more.hidden = !cursor;
      if (!list.children.length) list.append(el('p', { class: 'qa-muted', text: '还没有通知' }));
    } catch (error) {
      context.toast(error.message, 'danger');
    } finally {
      more.disabled = false;
    }
  }

  more.addEventListener('click', () => void load(false));
  container.append(el('h1', { text: '站内通知' }), form, el('h2', { text: '已发布' }), list, more);
  await load(true);
}
