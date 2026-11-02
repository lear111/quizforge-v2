import { query } from '../api.js';
import { el, time, badge, money, parseMoney } from './format.js';

const STATES = { available: ['可用', 'accent'], redeemed: ['已兑换', ''], revoked: ['已停用', 'danger'], expired: ['已过期', ''] };

// AI 兑换码：批量生成、列表（键集分页）、停用。完整码只在生成时返回一次，可导出为文本文件。
export async function render(container, context) {
  container.append(el('h1', { text: 'AI 兑换码' }));
  const batch = el('section', { class: 'qa-panel', hidden: true });
  const count = el('input', { type: 'number', name: 'count', min: '1', max: '200', value: '10', required: true });
  const amount = el('input', { type: 'text', name: 'amount', inputmode: 'decimal', value: '10', required: true });
  const days = el('input', { type: 'number', name: 'days', min: '1', max: '3650', value: '365', required: true });
  const note = el('input', { type: 'text', name: 'note', maxlength: '60', placeholder: '可选，最多 60 字' });
  const create = el('button', { type: 'submit', class: 'qa-button', text: '生成' });
  const form = el('form', { class: 'qa-panel qa-form qa-inline', 'data-action': 'create-ai-codes' },
    el('label', {}, '数量（1–200）', count), el('label', {}, '面额（元，0.01–10000）', amount),
    el('label', {}, '有效天数（1–3650）', days), el('label', {}, '备注', note), create);

  const body = el('tbody');
  const more = el('button', { type: 'button', class: 'qa-button secondary', text: '加载更多', hidden: true });
  const status = el('p', { class: 'qa-muted', role: 'status' });
  const list = el('section', { class: 'qa-panel' },
    el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['序号', '兑换码', '面额', '状态', '备注', '生成时间', '有效期至', '兑换记录', '']
        .map((text) => el('th', { scope: 'col', text })))),
      body)),
    status, more);
  container.append(form, batch, list);
  let before = null;

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const points = parseMoney(amount.value);
    if (!points || points < 100 || points > 100_000_000) { context.toast('面额须在 0.01 到 10000 元之间', 'danger'); return; }
    create.disabled = true;
    try {
      const { items } = await context.request('/api/admin/ai-codes', 'POST',
        { count: Number(count.value), points, days: Number(days.value), note: note.value.trim() });
      showBatch(items);
      await load(true);
    } catch (error) {
      context.toast(error.message, 'danger');
    } finally {
      create.disabled = false;
    }
  });

  function showBatch(items) {
    const text = items.map((item) => `${item.code}\t${money(item.points)}\t${item.expiresAt}`).join('\n');
    const exportButton = el('button', { type: 'button', class: 'qa-button secondary', text: '导出本批次' });
    exportButton.addEventListener('click', () => {
      const link = el('a', { href: URL.createObjectURL(new Blob([`兑换码\t面额\t有效期至\n${text}\n`], { type: 'text/plain' })),
        download: `quizforge-ai-codes-${new Date().toISOString().slice(0, 10)}.txt` });
      link.click();
      setTimeout(() => URL.revokeObjectURL(link.href), 1000);
    });
    batch.replaceChildren(el('h2', { text: `本次生成的 ${items.length} 个兑换码` }),
      el('textarea', { readonly: true, rows: String(Math.min(items.length, 10)), 'aria-label': '本次兑换码', class: 'qa-codes',
        text: items.map((item) => item.code).join('\n') }),
      el('p', { class: 'qa-muted', text: '离开或刷新页面后不再显示完整兑换码，请及时导出或复制。' }), exportButton);
    batch.hidden = false;
  }

  function row(item) {
    const [label, tone] = STATES[item.state] ?? [item.state, ''];
    const revoke = item.state === 'available'
      ? el('button', { type: 'button', class: 'qa-button small secondary', text: '停用' }) : null;
    revoke?.addEventListener('click', async () => {
      if (!confirm(`停用兑换码 ${item.prefix}…？`)) return;
      try {
        await context.request(`/api/admin/ai-codes/${encodeURIComponent(item.id)}/revoke`, 'POST');
        context.toast('已停用', 'success');
        await load(true);
      } catch (error) {
        context.toast(error.message, 'danger');
      }
    });
    return el('tr', { 'data-code-id': item.id },
      el('td', { text: String(item.seq) }), el('td', { text: `${item.prefix}…` }), el('td', { text: money(item.points) }),
      el('td', {}, badge(label, tone)), el('td', { text: item.note || '—' }),
      el('td', { text: time(item.createdAt) }), el('td', { text: time(item.expiresAt) }),
      el('td', { text: item.redeemedBy ? `${item.redeemedBy.name ?? '—'} ${time(item.redeemedAt)}` : '—' }),
      el('td', {}, revoke));
  }

  async function load(reset) {
    if (reset) { before = null; body.replaceChildren(); }
    more.disabled = true;
    try {
      const page = await context.request(query('/api/admin/ai-codes', { before, limit: 50 }));
      for (const item of page.items) body.append(row(item));
      before = page.nextBefore;
      more.hidden = !before;
      status.textContent = body.children.length ? '' : '还没有兑换码';
    } catch (error) {
      status.textContent = error.message;
    } finally {
      more.disabled = false;
    }
  }
  more.addEventListener('click', () => void load(false));
  await load(true);
}
