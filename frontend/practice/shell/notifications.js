// 站内通知铃铛（功能盘点 I 节），练习空间与管理后台共用。
//
// - 页面打开、回到前台时读取 /api/notifications/status：有比“上次打开通知界面”更新的通知时显示红点；
// - 打开通知界面即记录打开时间（POST /api/notifications/seen），红点消失，即使没有逐条查看；
// - 列表每条显示标题与发布时间，未查看的条目各自带红点，点开查看后消失；置顶排最前；
// - 正文按纯文本显示：保留换行，http(s) 链接可点击，不渲染 HTML。
//
// 用法：页面中放一个 [data-qf-notifications] 占位元素并加载本模块；或调用 mountNotifications(element)。

const URL_PATTERN = /https?:\/\/[^\s<>"'，。；！？）】」]+/g;

async function call(path, method = 'GET') {
  const response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error(String(response.status));
  return response.status === 204 ? null : response.json();
}

function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else node.setAttribute(key, value === true ? '' : value);
  }
  node.append(...children.filter((child) => child !== null && child !== undefined));
  return node;
}

/** 纯文本正文：按链接切分为文本节点与 <a>，换行由 CSS 的 pre-wrap 保留。 */
export function textWithLinks(text) {
  const fragment = document.createDocumentFragment();
  let last = 0;
  for (const match of text.matchAll(URL_PATTERN)) {
    if (match.index > last) fragment.append(document.createTextNode(text.slice(last, match.index)));
    fragment.append(el('a', { href: match[0], target: '_blank', rel: 'noopener noreferrer', text: match[0] }));
    last = match.index + match[0].length;
  }
  if (last < text.length) fragment.append(document.createTextNode(text.slice(last)));
  return fragment;
}

function time(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

export function mountNotifications(slot) {
  if (!slot || slot.dataset.qfNotificationsMounted) return;
  slot.dataset.qfNotificationsMounted = 'true';
  const dot = el('span', { class: 'qf-bell-dot', hidden: true });
  const button = el('button', { type: 'button', class: 'qf-bell', 'aria-label': '通知', title: '通知', 'aria-expanded': 'false', 'aria-haspopup': 'dialog' });
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('viewBox', '0 0 24 24');
  icon.setAttribute('aria-hidden', 'true');
  for (const d of ['M6 9a6 6 0 0 1 12 0c0 6 2 7 2 7H4s2-1 2-7', 'M10 20a2 2 0 0 0 4 0']) {
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', d);
    icon.append(path);
  }
  button.append(icon, dot);
  const list = el('ol', { class: 'qf-notice-list' });
  const status = el('p', { class: 'qf-notice-status', role: 'status' });
  const close = el('button', { type: 'button', class: 'qf-notice-close', 'aria-label': '关闭通知', text: '×' });
  const panel = el('section', { class: 'qf-notice-panel', role: 'dialog', 'aria-label': '通知', hidden: true },
    el('header', {}, el('h2', { text: '通知' }), close), status, list);
  slot.append(button, panel);

  async function refreshDot() {
    try {
      dot.hidden = !(await call('/api/notifications/status')).unseen;
      button.setAttribute('aria-label', dot.hidden ? '通知' : '通知（有新通知）');
    } catch {
      dot.hidden = true;
    }
  }

  function item(entry) {
    const unread = el('span', { class: 'qf-notice-unread', 'aria-label': '未查看', hidden: entry.read });
    const body = el('div', { class: 'qf-notice-body', hidden: true });
    const head = el('button', { type: 'button', class: 'qf-notice-head', 'aria-expanded': 'false' },
      unread,
      el('span', { class: 'qf-notice-title', text: entry.title }),
      entry.pinned ? el('span', { class: 'qf-notice-pin', text: '置顶' }) : null,
      el('time', { class: 'qf-notice-time', datetime: entry.createdAt, text: time(entry.createdAt) }));
    head.addEventListener('click', async () => {
      const opening = body.hidden;
      head.setAttribute('aria-expanded', String(opening));
      body.hidden = !opening;
      if (!opening || body.childNodes.length) return;
      body.textContent = '正在加载…';
      try {
        const detail = await call(`/api/notifications/${encodeURIComponent(entry.id)}`);
        body.replaceChildren(textWithLinks(detail.body));
        if (!entry.read) {
          await call(`/api/notifications/${encodeURIComponent(entry.id)}/read`, 'POST');
          entry.read = true;
          unread.hidden = true;
        }
      } catch {
        body.textContent = '这条通知已不存在或暂时无法读取。';
      }
    });
    return el('li', { class: 'qf-notice', 'data-notification-id': entry.id }, head, body);
  }

  async function open() {
    panel.hidden = false;
    button.setAttribute('aria-expanded', 'true');
    status.textContent = '正在加载…';
    list.replaceChildren();
    try {
      // 打开通知界面即视为已看过：红点消失。
      await call('/api/notifications/seen', 'POST');
      dot.hidden = true;
      const { items } = await call('/api/notifications');
      list.replaceChildren(...items.map(item));
      status.textContent = items.length ? '' : '暂时没有通知';
    } catch {
      status.textContent = '暂时无法读取通知，请稍后重试。';
    }
  }

  function hide() {
    panel.hidden = true;
    button.setAttribute('aria-expanded', 'false');
  }

  button.addEventListener('click', () => (panel.hidden ? void open() : hide()));
  close.addEventListener('click', () => { hide(); button.focus(); });
  document.addEventListener('keydown', (event) => { if (event.key === 'Escape' && !panel.hidden) { hide(); button.focus(); } });
  document.addEventListener('pointerdown', (event) => { if (!panel.hidden && !slot.contains(event.target)) hide(); });
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') void refreshDot(); });
  void refreshDot();
}

for (const slot of document.querySelectorAll('[data-qf-notifications]')) mountNotifications(slot);
