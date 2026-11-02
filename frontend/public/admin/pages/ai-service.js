import { el, time, badge, money, parseMoney } from './format.js';

// AI 服务：上游配置（由环境变量提供，只读）与 Key 池。Key 只写不读，列表只显示末 4 位。
// 单价以“元 / 百万 token”输入，保存为 0.0001 元的整数。

const STATES = { ok: ['可用', 'accent'], unavailable: ['不可用', 'danger'] };

export async function render(container, context) {
  container.append(el('h1', { text: 'AI 服务' }));
  const config = el('section', { class: 'qa-panel' });
  const list = el('section', { class: 'qa-panel' });
  container.append(config, addForm(context, () => load()), list);

  async function load() {
    const status = await context.request('/api/admin/ai-service');
    const c = status.config;
    config.replaceChildren(el('h2', { text: '上游配置' }), el('dl', { class: 'qa-facts' },
      ...[['服务地址', c.host], ['模型', c.model], ['单次调用超时', `${c.callTimeoutSeconds} 秒（每次最多尝试 ${c.maxKeyAttempts} 个 Key）`],
        ['流式回复最长', `${c.streamTimeoutSeconds} 秒`], ['单次最多输出', `${c.maxOutputTokens} token`],
        ['并发上限', `每用户 ${c.perUserConcurrency} 个，全站 ${c.siteConcurrency} 个`]]
        .flatMap(([label, value]) => [el('dt', { text: label }), el('dd', { text: value })])),
      el('p', { class: 'qa-muted', text: '服务地址与模型由服务器环境变量 QF_AI_BASE_URL、QF_AI_MODEL 设置，修改后重启生效。' }));
    renderKeys(status.keys, c.maxKeys);
  }

  function renderKeys(keys, maxKeys) {
    const ids = keys.map((key) => key.id);
    const rows = keys.map((key, index) => {
      const [label, tone] = STATES[key.state] ?? [key.state, ''];
      const actions = el('div', { class: 'qa-actions' });
      const button = (text, handler, extra = '') => {
        const node = el('button', { type: 'button', class: `qa-button small secondary ${extra}`.trim(), text });
        node.addEventListener('click', async () => {
          node.disabled = true;
          try { await handler(); } catch (error) { context.toast(error.message, 'danger'); } finally { node.disabled = false; }
        });
        return node;
      };
      const move = (offset) => async () => {
        const order = [...ids];
        [order[index], order[index + offset]] = [order[index + offset], order[index]];
        await context.request('/api/admin/ai-keys/order', 'PUT', { ids: order });
        await load();
      };
      if (index > 0) actions.append(button('上移', move(-1)));
      if (index < keys.length - 1) actions.append(button('下移', move(1)));
      actions.append(button(key.enabled ? '停用' : '启用', async () => {
        if (key.enabled && !confirm(`停用 Key #${key.seq}？`)) return;
        await context.request(`/api/admin/ai-keys/${encodeURIComponent(key.id)}/enabled`, 'PUT', { enabled: !key.enabled });
        context.toast(key.enabled ? '已停用' : '已启用', 'success');
        await load();
      }));
      actions.append(button('修改单价', async () => {
        const input = prompt('输入单价（元 / 百万 token）', price(key.inputPrice));
        if (input === null) return;
        const output = prompt('输出单价（元 / 百万 token）', price(key.outputPrice));
        if (output === null) return;
        const inputPrice = parseMoney(input);
        const outputPrice = parseMoney(output);
        if (inputPrice === null || outputPrice === null || inputPrice < 0 || outputPrice < 0) throw new Error('单价格式不正确');
        await context.request(`/api/admin/ai-keys/${encodeURIComponent(key.id)}/prices`, 'PUT', { inputPrice, outputPrice });
        context.toast('单价已更新', 'success');
        await load();
      }));
      actions.append(button('测试连接', async () => {
        const result = await context.request(`/api/admin/ai-keys/${encodeURIComponent(key.id)}/test`, 'POST');
        context.toast(result.ok ? `测试通过（${result.ms} 毫秒，费用 ${money(result.cost)} 计入你的 AI 余额）` : `测试失败：${result.reason}`,
          result.ok ? 'success' : 'danger');
        await load();
      }));
      actions.append(button('删除', async () => {
        if (!confirm(`删除 Key #${key.seq}（末 4 位 ${key.keyHint}）？删除后不能恢复。`)) return;
        await context.request(`/api/admin/ai-keys/${encodeURIComponent(key.id)}`, 'DELETE');
        context.toast('已删除', 'success');
        await load();
      }, 'danger'));
      return el('tr', { 'data-key-id': key.id },
        el('td', { text: String(index + 1) }),
        el('td', {}, `#${key.seq} ${key.note || ''}`.trim(), el('br'), el('code', { text: `…${key.keyHint}` })),
        el('td', {}, badge(label, tone), key.enabled ? null : badge('已停用', '')),
        el('td', { text: key.expiresAt ? time(key.expiresAt) : '不过期' }),
        el('td', { text: `输入 ${price(key.inputPrice)} / 输出 ${price(key.outputPrice)}` }),
        el('td', { text: `${key.calls} 次，${money(key.spent)}` }),
        el('td', { text: key.lastError ? `${key.lastError}（${time(key.lastErrorAt)}）` : '—' }),
        el('td', {}, actions));
    });
    list.replaceChildren(
      el('h2', { text: `API Key（${keys.length} / ${maxKeys}）` }),
      el('p', { class: 'qa-muted', text: '每次调用按顺序最多尝试 3 个可用的 Key。上游拒绝 Key 本身（401、403、欠费）时自动标为不可用，处理后点“启用”或“测试连接”恢复。单价单位：元 / 百万 token。' }),
      el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
        el('thead', {}, el('tr', {}, ...['顺序', 'Key', '状态', '到期', '单价', '用量', '最近错误', ''].map((text) => el('th', { scope: 'col', text })))),
        el('tbody', {}, ...rows))),
      rows.length ? null : el('p', { class: 'qa-muted', text: '还没有 Key：AI 功能暂不可用' }));
  }

  await load();
}

function price(units) {
  return money(units).replace('¥', '');
}

function addForm(context, reload) {
  const apiKey = el('input', { type: 'password', name: 'apiKey', autocomplete: 'off', maxlength: '200', placeholder: '保存后不能再查看' });
  const note = el('input', { type: 'text', name: 'note', maxlength: '60', placeholder: '可选' });
  const input = el('input', { type: 'text', name: 'inputPrice', inputmode: 'decimal', value: '3' });
  const output = el('input', { type: 'text', name: 'outputPrice', inputmode: 'decimal', value: '9' });
  const expires = el('input', { type: 'date', name: 'expiresAt' });
  const submit = el('button', { type: 'submit', class: 'qa-button', text: '添加' });
  const form = el('form', { class: 'qa-panel qa-form qa-inline', 'data-action': 'add-ai-key' },
    el('label', {}, 'API Key', apiKey), el('label', {}, '备注', note),
    el('label', {}, '输入单价（元 / 百万 token）', input), el('label', {}, '输出单价（元 / 百万 token）', output),
    el('label', {}, '到期日（可选）', expires), submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const inputPrice = parseMoney(input.value);
    const outputPrice = parseMoney(output.value);
    if (!apiKey.value.trim()) { context.toast('请输入 API Key', 'danger'); apiKey.focus(); return; }
    if (inputPrice === null || outputPrice === null || inputPrice < 0 || outputPrice < 0) { context.toast('单价格式不正确', 'danger'); return; }
    submit.disabled = true;
    try {
      await context.request('/api/admin/ai-keys', 'POST', { apiKey: apiKey.value.trim(), note: note.value.trim(), inputPrice, outputPrice,
        expiresAt: expires.value ? new Date(`${expires.value}T23:59:59`).toISOString() : null });
      form.reset();
      context.toast('Key 已保存', 'success');
      await reload();
    } catch (error) {
      context.toast(error.message, 'danger');
    } finally {
      submit.disabled = false;
    }
  });
  return form;
}
