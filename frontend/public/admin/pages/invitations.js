import { el, time, badge } from './format.js';

const STATES = { available: ['可用', 'accent'], used: ['已使用', ''], revoked: ['已停用', 'danger'], expired: ['已过期', ''] };

export async function render(container, context) {
  container.append(el('h1', { text: '邀请码' }));
  const batch = el('section', { class: 'qa-panel', hidden: true });
  const list = el('section', { class: 'qa-panel' });
  const count = el('input', { type: 'number', name: 'count', min: '1', max: '100', value: '1', required: true });
  const days = el('input', { type: 'number', name: 'days', min: '1', max: '365', value: '30', required: true });
  const create = el('button', { type: 'submit', class: 'qa-button', text: '生成' });
  const form = el('form', { class: 'qa-panel qa-form qa-inline', 'data-action': 'create-invitations' },
    el('label', {}, '数量（1–100）', count), el('label', {}, '有效天数（1–365）', days), create);
  container.append(form, batch, list);

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    create.disabled = true;
    try {
      const { items } = await context.request('/api/admin/invitations', 'POST', { count: Number(count.value), days: Number(days.value) });
      showBatch(items);
      await load();
    } catch (error) {
      context.toast(error.message, 'danger');
    } finally {
      create.disabled = false;
    }
  });

  // 完整邀请码只在生成时返回一次：在页面上展示并可导出为文本文件。
  function showBatch(items) {
    const text = items.map((item) => item.code).join('\n');
    const exportButton = el('button', { type: 'button', class: 'qa-button secondary', text: '导出本批次' });
    exportButton.addEventListener('click', () => {
      const link = el('a', { href: URL.createObjectURL(new Blob([`${text}\n`], { type: 'text/plain' })),
        download: `quizforge-invitations-${new Date().toISOString().slice(0, 10)}.txt` });
      link.click();
      setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    });
    batch.replaceChildren(el('h2', { text: `本次生成的 ${items.length} 个邀请码` }),
      el('textarea', { readonly: true, rows: String(Math.min(items.length, 10)), 'aria-label': '本次邀请码', class: 'qa-codes', text }),
      el('p', { class: 'qa-muted', text: '离开或刷新页面后不再显示完整邀请码，请及时导出或复制。' }), exportButton);
    batch.hidden = false;
  }

  async function load() {
    const { items, invitationRequired } = await context.request('/api/admin/invitations');
    const rows = items.map((item) => {
      const [label, tone] = STATES[item.state] ?? [item.state, ''];
      const revoke = item.state === 'available'
        ? el('button', { type: 'button', class: 'qa-button small secondary', text: '停用' }) : null;
      revoke?.addEventListener('click', async () => {
        if (!confirm(`停用邀请码 ${item.prefix}…？`)) return;
        try {
          await context.request(`/api/admin/invitations/${encodeURIComponent(item.id)}/revoke`, 'POST');
          context.toast('已停用', 'success');
          await load();
        } catch (error) {
          context.toast(error.message, 'danger');
        }
      });
      return el('tr', {},
        el('td', { text: `${item.prefix}…` }), el('td', {}, badge(label, tone)),
        el('td', { text: time(item.createdAt) }), el('td', { text: time(item.expiresAt) }),
        el('td', { text: item.usedBy ? `${item.usedBy.name}（${item.usedBy.contact ?? '—'}）${time(item.usedAt)}` : '—' }),
        el('td', {}, revoke));
    });
    list.replaceChildren(
      el('p', { class: 'qa-muted' }, `注册${invitationRequired ? '需要' : '不需要'}邀请码。开关在`,
        el('a', { href: '#authentication', text: '登录与注册设置' }), '中修改；关闭不会删除已生成的邀请码。'),
      el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
        el('thead', {}, el('tr', {}, ...['邀请码', '状态', '生成时间', '有效期至', '兑换记录', ''].map((text) => el('th', { scope: 'col', text })))),
        el('tbody', {}, ...rows))),
      rows.length ? null : el('p', { class: 'qa-muted', text: '还没有邀请码' }));
  }
  await load();
}
