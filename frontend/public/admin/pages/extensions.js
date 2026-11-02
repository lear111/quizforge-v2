import { query } from '../api.js';
import { el, time, badge, shortId } from './format.js';

// 拓展审核（白名单，C-04）：导入时未知的拓展自动登记为待审核。批准后使用它的题库可以打开；
// 待审核时拒绝、已批准时撤销，都必须填写原因；撤销前显示会影响哪些题库。

const FILTERS = [['pending', '待审核'], ['approved', '已批准'], ['revoked', '已撤销'], ['rejected', '未通过']];
const STATES = { pending: ['待审核', ''], approved: ['已批准', 'accent'], rejected: ['未通过', 'danger'], revoked: ['已撤销', 'danger'] };

export async function render(container, context) {
  let status = 'pending';
  const tabs = el('div', { class: 'qa-toolbar', role: 'tablist', 'aria-label': '审核状态' });
  const body = el('div');
  container.append(el('h1', { text: '拓展审核' }),
    el('p', { class: 'qa-muted', text: '拓展的 JS 规则在服务器上执行。审核按拓展摘要记录，一次决定对所有使用它的题库生效；审核不代替隔离。' }),
    tabs, body);

  async function load() {
    tabs.replaceChildren(...FILTERS.map(([value, label]) => {
      const button = el('button', { type: 'button', role: 'tab', 'aria-selected': String(value === status),
        class: `qa-button small ${value === status ? '' : 'secondary'}`.trim(), text: label });
      button.addEventListener('click', () => { status = value; void load(); });
      return button;
    }));
    const { items } = await context.request(query('/api/admin/extensions', { status }));
    const rows = items.map((item) => row(item));
    body.replaceChildren(el('section', { class: 'qa-panel' }, el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['拓展', '版本', '状态', '摘要', '登记时间', '审核', ''].map((text) => el('th', { scope: 'col', text })))),
      el('tbody', {}, ...rows))),
      rows.length ? null : el('p', { class: 'qa-muted', text: '没有记录' })));
  }

  function row(item) {
    const [label, tone] = STATES[item.status] ?? [item.status, ''];
    const actions = el('div', { class: 'qa-actions' });
    const panel = el('div', { hidden: true });
    if (item.status !== 'approved') {
      const approve = el('button', { type: 'button', class: 'qa-button small', text: '批准' });
      approve.addEventListener('click', async () => {
        if (!confirm(`批准拓展「${item.name ?? item.extensionId}」${item.version}？`)) return;
        approve.disabled = true;
        try {
          await context.request(`/api/admin/extensions/${item.digest}/approve`, 'POST');
          context.toast('拓展已批准', 'success');
          await load();
        } catch (error) { context.toast(error.message, 'danger'); approve.disabled = false; }
      });
      actions.append(approve);
    }
    if (item.status === 'pending' || item.status === 'approved') {
      const revoke = el('button', { type: 'button', class: 'qa-button small secondary', text: item.status === 'approved' ? '撤销' : '拒绝' });
      revoke.addEventListener('click', async () => {
        if (!panel.hidden) { panel.hidden = true; return; }
        revoke.disabled = true;
        try { panel.replaceChildren(await revokeForm(item)); panel.hidden = false; }
        catch (error) { context.toast(error.message, 'danger'); }
        finally { revoke.disabled = false; }
      });
      actions.append(revoke);
    }
    return el('tr', { 'data-digest': item.digest },
      el('td', { text: `${item.name ?? '—'}（${item.extensionId}）` }), el('td', { text: item.version }),
      el('td', {}, badge(label, tone), item.reason ? el('small', { text: ` ${item.reason}` }) : null),
      el('td', {}, el('code', { text: item.digest.slice(0, 16) })), el('td', { text: time(item.createdAt) }),
      el('td', { text: item.reviewer ? `${item.reviewer.name ?? shortId(item.reviewer.id)} ${time(item.reviewedAt)}` : '—' }),
      el('td', {}, actions, panel));
  }

  async function revokeForm(item) {
    const { impact, siblings } = await context.request(`/api/admin/extensions/${item.digest}/impact`);
    const revoking = item.status === 'approved';
    const reason = el('textarea', { name: 'reason', rows: '2', maxlength: '500', placeholder: '原因（必填）' });
    const submit = el('button', { type: 'submit', class: 'qa-button small danger', text: revoking ? '确认撤销' : '确认拒绝' });
    const form = el('form', { class: 'qa-form', 'data-action': 'revoke-extension' },
      el('p', { class: 'qa-warning', text: `${revoking ? '撤销后' : '拒绝后'}以下题库变为不可用：私人题库 ${impact.privateCollections} 个；题库广场 ${impact.packs.length} 个题库包。` }),
      impact.packs.length ? el('ul', {}, ...impact.packs.map((pack) => el('li', {},
        el('a', { href: `#pack-reviews/${pack.packId}~${pack.revision}`, text: pack.title }),
        ` 第 ${pack.revision} 版${pack.current ? '（公开中）' : '（待审核）'}`))) : null,
      siblings.length ? el('p', { class: 'qa-muted', text: `同一拓展 ID 的其他版本：${siblings.map((s) => `${s.version}（${STATES[s.status]?.[0] ?? s.status}）`).join('、')}` }) : null,
      el('label', {}, '原因', reason), submit);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      if (!reason.value.trim()) { context.toast('请填写原因', 'danger'); reason.focus(); return; }
      submit.disabled = true;
      try {
        await context.request(`/api/admin/extensions/${item.digest}/revoke`, 'POST', { reason: reason.value.trim() });
        context.toast(revoking ? '拓展已撤销' : '拓展已拒绝', 'success');
        await load();
      } catch (error) { context.toast(error.message, 'danger'); submit.disabled = false; }
    });
    return form;
  }

  await load();
}
