import { query } from '../api.js';
import { el, time, bytes, badge, shortId, money, parseMoney } from './format.js';

const PAGE_SIZE = 20;
const MIB = 1024 * 1024;

export async function render(container, context) {
  if (context.param) await renderDetail(container, context, context.param);
  else await renderList(container, context);
}

// ------------------------------------------------------------------ 列表（键集分页）

async function renderList(container, context) {
  const search = el('input', { type: 'search', name: 'q', maxlength: '100', placeholder: '搜索邮箱、手机号或昵称',
    'aria-label': '搜索用户' });
  const body = el('tbody');
  const more = el('button', { type: 'button', class: 'qa-button secondary', text: '加载更多', hidden: true });
  const status = el('p', { class: 'qa-muted', role: 'status' });
  let cursor = null;
  let generation = 0;

  async function load(reset) {
    const current = ++generation;
    if (reset) { cursor = null; body.replaceChildren(); }
    more.disabled = true;
    status.textContent = '正在加载…';
    try {
      const page = await context.request(query('/api/admin/users', { q: search.value.trim(), cursor, limit: PAGE_SIZE }));
      if (current !== generation) return;
      for (const user of page.items) body.append(row(user));
      cursor = page.nextCursor;
      more.hidden = !cursor;
      status.textContent = body.children.length ? '' : '没有找到用户';
    } catch (error) {
      if (current === generation) status.textContent = error.message;
    } finally {
      more.disabled = false;
    }
  }

  function row(user) {
    const name = el('a', { href: `#users/${user.id}`, text: user.name });
    return el('tr', { 'data-user-id': user.id },
      el('td', {}, name, user.admin ? badge('管理员', 'accent') : null, user.blocked ? badge('已封禁', 'danger') : null),
      el('td', { text: user.email ?? '—' }),
      el('td', { text: user.phone ?? '—' }),
      el('td', { text: time(user.createdAt) }));
  }

  const form = el('form', { class: 'qa-toolbar', role: 'search', onsubmit: (event) => { event.preventDefault(); void load(true); } },
    search, el('button', { type: 'submit', class: 'qa-button', text: '搜索' }));
  more.addEventListener('click', () => void load(false));
  container.append(
    el('h1', { text: '用户管理' }),
    el('section', { class: 'qa-panel' }, form,
      el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
        el('thead', {}, el('tr', {}, ...['昵称', '邮箱', '手机号', '注册时间'].map((text) => el('th', { scope: 'col', text })))),
        body)),
      status, more));
  await load(true);
}

// ------------------------------------------------------------------ 详情与操作

async function renderDetail(container, context, id) {
  container.append(el('p', {}, el('a', { href: '#users', text: '← 返回用户列表' })));
  const holder = el('div');
  container.append(holder);

  async function refresh() {
    const { user, storage } = await context.request(`/api/admin/users/${encodeURIComponent(id)}`);
    holder.replaceChildren(
      el('h1', {}, user.name, user.admin ? badge('管理员', 'accent') : null, user.block ? badge('已封禁', 'danger') : null),
      info(user, storage),
      el('p', {}, el('a', { href: `#notifications/${user.id}`, text: '给该用户发通知' })),
      // 不能封禁自己（服务器同样拒绝），自己的详情页不显示封禁表单。
      user.id === context.me?.id ? null : blockForm(user, context, refresh),
      quotaForm(user, storage, context, refresh),
      await aiSection(user, context, refresh));
  }
  await refresh();
}

function contact(state) {
  if (!state) return '未绑定';
  const verified = state.verified ? `已验证${state.verifiedAt ? `（${time(state.verifiedAt)}）` : ''}` : '未验证';
  return `${state.value}　${verified}`;
}

function info(user, storage) {
  const rows = [
    ['用户 ID', user.id],
    ['邮箱', contact(user.email)],
    ['手机号', contact(user.phone)],
    ['注册时间', time(user.createdAt)],
    ['管理员', user.admin ? '是（通过服务器命令行设置）' : '否'],
    ['封禁状态', user.block ? `已封禁：${user.block.reason || '（未填写原因）'}，${user.block.actorName ?? shortId(user.block.actorId)} 于 ${time(user.block.createdAt)}` : '正常'],
    ['存储空间', `${bytes(storage.usedBytes)} / ${bytes(storage.limitBytes)}${storage.overrideBytes == null ? '（默认额度）' : '（单独设置）'}`],
  ];
  return el('section', { class: 'qa-panel' }, el('dl', { class: 'qa-facts' },
    ...rows.flatMap(([label, value]) => [el('dt', { text: label }), el('dd', { text: value })])));
}

function blockForm(user, context, refresh) {
  const blocking = !user.block;
  const reason = el('textarea', { name: 'reason', maxlength: '200', rows: '2', placeholder: blocking ? '封禁原因（必填，最多 200 字）' : '解封说明（可选）' });
  const submit = el('button', { type: 'submit', class: `qa-button ${blocking ? 'danger' : ''}`.trim(), text: blocking ? '封禁' : '解封' });
  const form = el('form', { class: 'qa-panel qa-form', 'data-action': 'block' },
    el('h2', { text: blocking ? '封禁用户' : '解除封禁' }),
    el('p', { class: 'qa-muted', text: blocking ? '封禁后该用户立即退出所有设备，不能登录。' : '解封后用户可以重新登录。' }),
    el('label', {}, blocking ? '原因' : '说明', reason), submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (blocking && !reason.value.trim()) { context.toast('请填写封禁原因', 'danger'); reason.focus(); return; }
    if (blocking && !confirm(`确定封禁“${user.name}”？`)) return;
    submit.disabled = true;
    try {
      await context.request(`/api/admin/users/${encodeURIComponent(user.id)}/block`, 'PUT',
        { blocked: blocking, reason: reason.value.trim() });
      context.toast(blocking ? '已封禁' : '已解封', 'success');
      await refresh();
    } catch (error) {
      context.toast(error.message, 'danger');
      submit.disabled = false;
    }
  });
  return form;
}

function quotaForm(user, storage, context, refresh) {
  const input = el('input', { type: 'number', name: 'storageMiB', min: '0', step: '1', inputmode: 'numeric',
    value: storage.overrideBytes == null ? '' : String(Math.round(storage.overrideBytes / MIB)),
    placeholder: `留空使用默认额度（当前 ${bytes(storage.limitBytes)}）` });
  const submit = el('button', { type: 'submit', class: 'qa-button', text: '保存额度' });
  const form = el('form', { class: 'qa-panel qa-form', 'data-action': 'quota' },
    el('h2', { text: '存储额度' }),
    el('p', { class: 'qa-muted', text: `已用 ${bytes(storage.usedBytes)}。只统计私人上传的题库；单位 MiB。` }),
    el('label', {}, '额度（MiB）', input), submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const raw = input.value.trim();
    const value = raw === '' ? null : Number(raw);
    if (value !== null && (!Number.isInteger(value) || value < 0)) { context.toast('额度须为非负整数', 'danger'); return; }
    submit.disabled = true;
    try {
      // 接口同时设置两项额度：每日下载额度原样回传，不被改动。
      await context.request(`/api/admin/users/${encodeURIComponent(user.id)}/quota`, 'PUT',
        { storageBytes: value === null ? null : value * MIB, dailyDownloadBytes: storage.dailyDownloadOverrideBytes ?? null });
      context.toast('存储额度已更新', 'success');
      await refresh();
    } catch (error) {
      context.toast(error.message, 'danger');
      submit.disabled = false;
    }
  });
  return form;
}

const LEDGER_KINDS = { default_credit: '默认额度', redeem: '兑换码', ai_call: 'AI 调用', admin: '管理员调整',
  migration: '迁移', refund: '退款' };

// AI 余额：显示余额与最近流水，增减都必须填写原因（写 admin 流水与操作日志）。
async function aiSection(user, context, refresh) {
  const { balance, ledger } = await context.request(`/api/admin/users/${encodeURIComponent(user.id)}/ai`);
  const amount = el('input', { type: 'text', name: 'delta', inputmode: 'decimal', placeholder: '例如 5 或 -1.5',
    autocomplete: 'off' });
  const reason = el('textarea', { name: 'reason', maxlength: '200', rows: '2', placeholder: '原因（必填，最多 200 字）' });
  const submit = el('button', { type: 'submit', class: 'qa-button', text: '调整余额' });
  const form = el('form', { class: 'qa-form', 'data-action': 'ai-balance' },
    el('label', {}, '增减金额（元，负数为扣减）', amount), el('label', {}, '原因', reason), submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const delta = parseMoney(amount.value);
    if (!delta) { context.toast('请输入非零金额，最多四位小数', 'danger'); amount.focus(); return; }
    if (!reason.value.trim()) { context.toast('请填写调整原因', 'danger'); reason.focus(); return; }
    if (!confirm(`确定给“${user.name}”${delta > 0 ? '增加' : '扣减'} ${money(Math.abs(delta))}？`)) return;
    submit.disabled = true;
    try {
      await context.request(`/api/admin/users/${encodeURIComponent(user.id)}/ai-balance`, 'POST',
        { delta, reason: reason.value.trim() });
      context.toast('余额已调整', 'success');
      await refresh();
    } catch (error) {
      context.toast(error.message, 'danger');
      submit.disabled = false;
    }
  });
  const rows = ledger.items.map((entry) => el('tr', {},
    el('td', { text: time(entry.createdAt) }),
    el('td', { text: LEDGER_KINDS[entry.kind] ?? entry.kind }),
    el('td', { class: entry.delta < 0 ? 'qa-negative' : '', text: `${entry.delta > 0 ? '+' : ''}${money(entry.delta)}` }),
    el('td', { text: money(entry.balanceAfter) }),
    el('td', { text: entry.actor ? `${entry.actor.name ?? shortId(entry.actor.id)}：${entry.reason ?? ''}` : (entry.reason ?? '—') })));
  return el('section', { class: 'qa-panel', 'data-section': 'ai' },
    el('h2', { text: 'AI 余额' }),
    el('p', { 'data-ai-balance': String(balance.balance) }, `当前余额 ${money(balance.balance)}`,
      balance.overdrawn ? badge('欠费', 'danger') : null),
    el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['时间', '类型', '变动', '变动后余额', '说明'].map((text) => el('th', { scope: 'col', text })))),
      el('tbody', {}, ...rows))),
    rows.length ? (ledger.nextBefore ? el('p', { class: 'qa-muted', text: '只显示最近 20 条流水。' }) : null)
      : el('p', { class: 'qa-muted', text: '还没有流水' }),
    el('h3', { text: '调整余额' }), form);
}
