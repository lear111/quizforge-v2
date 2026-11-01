import { query } from '../api.js';
import { el, time, shortId } from './format.js';

// 操作日志：只读，按时间倒序，键集分页。
const ACTIONS = {
  'authentication-policy-updated': '修改登录与注册设置',
  'authentication-login-recovery': '登录故障恢复（命令行）',
  'user-blocked': '封禁用户',
  'user-unblocked': '解封用户',
  'local-role-promote': '设为管理员（命令行）',
  'local-role-demote': '取消管理员（命令行）',
  'invitation-created': '生成邀请码',
  'invitation-revoked': '停用邀请码',
  'quota-adjusted': '调整存储额度',
};

export async function render(container, context) {
  const body = el('tbody');
  const more = el('button', { type: 'button', class: 'qa-button secondary', text: '加载更多', hidden: true });
  const status = el('p', { class: 'qa-muted', role: 'status' });
  let cursor = null;

  async function load() {
    more.disabled = true;
    try {
      const page = await context.request(query('/api/admin/audit', { cursor, limit: 50 }));
      for (const entry of page.items) {
        body.append(el('tr', {},
          el('td', { text: time(entry.createdAt) }),
          el('td', { text: entry.actor ? (entry.actor.name ?? shortId(entry.actor.id)) : '服务器命令行' }),
          el('td', { text: ACTIONS[entry.action] ?? entry.action }),
          el('td', { text: /^[0-9a-f-]{36}$/.test(entry.target) ? shortId(entry.target) : entry.target }),
          el('td', {}, el('code', { text: JSON.stringify(entry.detail) }))));
      }
      cursor = page.nextCursor;
      more.hidden = !cursor;
      status.textContent = body.children.length ? '' : '还没有操作记录';
    } catch (error) {
      status.textContent = error.message;
    } finally {
      more.disabled = false;
    }
  }

  more.addEventListener('click', () => void load());
  container.append(el('h1', { text: '操作日志' }), el('section', { class: 'qa-panel' },
    el('p', { class: 'qa-muted', text: '所有管理员写操作都记录在这里，只能查看，不能修改或删除。' }),
    el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['时间', '操作人', '操作', '对象', '详情'].map((text) => el('th', { scope: 'col', text })))),
      body)),
    status, more));
  await load();
}
