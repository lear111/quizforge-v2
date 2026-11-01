// 页面共用的格式化与 DOM 小工具。所有用户数据都以 textContent 写入，不拼接 HTML。

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

export function time(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false });
}

export function bytes(value) {
  if (!Number.isFinite(value)) return '—';
  const units = ['B', 'KiB', 'MiB', 'GiB'];
  let amount = value;
  let unit = 0;
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
  return `${unit === 0 ? amount : amount.toFixed(2)} ${units[unit]}`;
}

export function badge(text, tone = '') {
  return el('span', { class: `qa-badge ${tone}`.trim(), text });
}

export function shortId(id) {
  return typeof id === 'string' ? id.slice(0, 8) : '—';
}
