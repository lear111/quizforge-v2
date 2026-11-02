// 账号页的 AI 余额：余额（为负显示“欠费”）、键集分页的流水与兑换入口。
// 金额以 0.0001 元为单位的整数传输。

const KINDS = { default_credit: '默认额度', redeem: '兑换码', ai_call: 'AI 调用', admin: '管理员调整',
  migration: '迁移', refund: '退款' };

export function formatMoney(units) {
  if (!Number.isSafeInteger(units)) throw new Error('金额无效');
  const sign = units < 0 ? '-' : '';
  const abs = Math.abs(units);
  const fraction = String(abs % 10000).padStart(4, '0').replace(/0{1,2}$/, '');
  return `${sign}¥${Math.floor(abs / 10000)}.${fraction}`;
}

export function ledgerItem(entry) {
  const title = entry.kind === 'admin' && entry.reason ? `${KINDS.admin}：${entry.reason}` : (KINDS[entry.kind] ?? entry.kind);
  return { title, delta: `${entry.delta > 0 ? '+' : ''}${formatMoney(entry.delta)}`, negative: entry.delta < 0,
    caption: `${new Date(entry.createdAt).toLocaleString('zh-CN', { hour12: false })} · 余额 ${formatMoney(entry.balanceAfter)}` };
}

export function bindAiBalance(request) {
  const byId = (id) => document.getElementById(id);
  const list = byId('ai-ledger');
  const more = byId('ai-ledger-more');
  const status = byId('ai-status');
  const form = byId('redeem-form');
  let before = null;
  let generation = 0;

  async function loadBalance() {
    const balance = await request('/api/account/ai');
    byId('ai-balance').textContent = formatMoney(balance.balance);
    byId('ai-overdrawn').hidden = !balance.overdrawn;
  }

  async function loadLedger(reset) {
    const current = reset ? ++generation : generation;
    if (reset) { before = null; list.replaceChildren(); }
    more.disabled = true;
    try {
      const page = await request(`/api/account/ai/ledger${before ? `?before=${before}` : ''}`);
      if (current !== generation) return;
      for (const entry of page.items) {
        const item = ledgerItem(entry);
        const row = document.createElement('li');
        const title = document.createElement('span'); title.textContent = item.title;
        const delta = document.createElement('span'); delta.className = `delta${item.negative ? ' negative' : ''}`; delta.textContent = item.delta;
        const caption = document.createElement('small'); caption.textContent = item.caption;
        row.append(title, delta, caption); list.append(row);
      }
      before = page.nextBefore;
      more.hidden = !before;
    } finally {
      more.disabled = false;
    }
  }

  async function refresh() {
    status.textContent = '';
    try {
      await Promise.all([loadBalance(), loadLedger(true)]);
    } catch (error) {
      status.textContent = error?.message || '暂时无法读取 AI 余额，请重试。';
    }
  }

  more.addEventListener('click', () => { void loadLedger(false).catch((error) => { status.textContent = error.message; }); });
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = form.elements.namedItem('code');
    const button = form.querySelector('button');
    if (!input.value.trim()) { status.textContent = '请输入兑换码'; input.focus(); return; }
    button.disabled = true;
    try {
      const result = await request('/api/account/ai/redeem', 'POST', { code: input.value });
      form.reset();
      await refresh();
      status.textContent = `兑换成功，已增加 ${formatMoney(result.added)}。`;
    } catch (error) {
      status.textContent = error?.message || '兑换失败，请重试。';
    } finally {
      button.disabled = false;
    }
  });
  return refresh;
}
