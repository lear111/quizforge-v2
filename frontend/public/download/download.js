// 客户端下载页：内容来自 Nginx 直接提供的 /download/manifest.json（作者用 tools/downloads/publish.py 生成），
// 安装包由 /download/files/<文件名> 直接下载。所有字段先校验再以 textContent 显示。

const PLATFORMS = { windows: { label: 'Windows', note: '适用于 Windows 10 / 11（64 位）' }, android: { label: 'Android', note: '适用于 Android 手机与平板' } };
const FILE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,120}$/;
const SHA256 = /^[a-f0-9]{64}$/;

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

export function size(bytes) {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** 只接受格式正确的条目；Windows 排在最前（主要分发 Windows 版）。 */
export function validItems(manifest) {
  const items = Array.isArray(manifest?.items) ? manifest.items : [];
  return items.filter((item) => PLATFORMS[item?.platform] && typeof item.file === 'string' && FILE.test(item.file)
      && Number.isSafeInteger(item.bytes) && item.bytes > 0 && typeof item.version === 'string'
      && typeof item.sha256 === 'string' && SHA256.test(item.sha256))
    .sort((a, b) => Object.keys(PLATFORMS).indexOf(a.platform) - Object.keys(PLATFORMS).indexOf(b.platform));
}

function card(item, primary) {
  const platform = PLATFORMS[item.platform];
  const hash = el('code', { class: 'hash', text: item.sha256 });
  const copy = el('button', { type: 'button', class: 'text-button', text: '复制' });
  copy.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(item.sha256); copy.textContent = '已复制'; } catch { copy.textContent = '请手动复制'; }
  });
  const updated = item.updated ? new Date(item.updated) : null;
  return el('article', { class: `download-card${primary ? ' primary' : ''}`, 'data-platform': item.platform },
    el('h2', {}, platform.label, el('span', { class: 'version', text: `版本 ${item.version}` }),
      item.label ? el('span', { class: 'label', text: item.label }) : null),
    el('p', { class: 'muted', text: platform.note }),
    el('a', { class: 'download-button', href: `/download/files/${encodeURIComponent(item.file)}`, download: item.file,
      text: `下载 ${platform.label} 版（${size(item.bytes)}）` }),
    el('dl', {},
      el('dt', { text: '文件名' }), el('dd', { text: item.file }),
      el('dt', { text: '大小' }), el('dd', { text: `${size(item.bytes)}（${item.bytes.toLocaleString('zh-CN')} 字节）` }),
      updated && !Number.isNaN(updated.getTime()) ? el('dt', { text: '发布时间' }) : null,
      updated && !Number.isNaN(updated.getTime()) ? el('dd', { text: updated.toLocaleString('zh-CN', { hour12: false }) }) : null,
      el('dt', { text: 'SHA-256' }), el('dd', { class: 'hash-row' }, hash, copy)),
    typeof item.notes === 'string' && item.notes.trim()
      ? el('section', { class: 'notes' }, el('h3', { text: '更新说明' }), el('p', { text: item.notes })) : null);
}

async function start() {
  const status = document.getElementById('download-status');
  const list = document.getElementById('download-list');
  try {
    const response = await fetch('/download/manifest.json', { cache: 'no-store' });
    if (response.status === 404) { status.textContent = '暂时没有可下载的安装包，请稍后再来。'; return; }
    if (!response.ok) throw new Error(String(response.status));
    const items = validItems(await response.json());
    list.replaceChildren(...items.map((item, index) => card(item, index === 0)));
    status.textContent = items.length ? '' : '暂时没有可下载的安装包，请稍后再来。';
    status.hidden = items.length > 0;
  } catch {
    status.textContent = '暂时无法读取安装包信息，请刷新重试。';
  }
}

if (typeof document !== 'undefined') start();
