import { request, RequestError } from './api.js';
import { pages, defaultPage } from './pages/registry.js';
import { el } from './pages/format.js';
import { mountNotifications } from '/practice/shell/notifications.js';

const main = document.getElementById('admin-main');
const nav = document.getElementById('admin-nav');
const toastBox = document.getElementById('admin-toast');
let toastTimer = null;
let renderGeneration = 0;
let currentUser = null;

function toast(message, tone = '') {
  toastBox.textContent = message;
  toastBox.className = `qa-toast ${tone}`.trim();
  toastBox.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { toastBox.hidden = true; }, 4000);
}

// 非管理员或未登录：只显示说明，不渲染导航与后台内容。
function blocked(title, message, link) {
  nav.hidden = true;
  nav.replaceChildren();
  main.replaceChildren(el('section', { class: 'qa-panel qa-blocked' },
    el('h1', { text: title }), el('p', { text: message }),
    link ? el('a', { class: 'qa-button', href: link.href, text: link.label }) : null));
}

function route() {
  const hash = decodeURIComponent(location.hash.replace(/^#/, ''));
  const [id, ...rest] = hash.split('/');
  const page = pages.find((item) => item.id === id) ?? pages.find((item) => item.id === defaultPage);
  return { page, param: rest.join('/') || null };
}

function renderNav(activeId) {
  const groups = new Map();
  for (const page of pages) {
    if (!groups.has(page.group)) groups.set(page.group, []);
    groups.get(page.group).push(page);
  }
  nav.replaceChildren(...[...groups].map(([group, items]) => el('div', { class: 'qa-nav-group' },
    el('small', { text: group }),
    ...items.map((page) => el('a', {
      class: 'qa-nav', href: `#${page.id}`, 'data-page': page.id,
      'aria-current': page.id === activeId ? 'page' : undefined, text: page.label,
    })))));
  nav.hidden = false;
}

async function render() {
  const generation = ++renderGeneration;
  const { page, param } = route();
  renderNav(page.id);
  document.title = `${page.label} · 管理后台 · QuizForge`;
  main.replaceChildren(el('p', { class: 'qa-muted', text: '正在加载…' }));
  try {
    const module = await page.load();
    if (generation !== renderGeneration) return;
    const container = el('div', { class: 'qa-page', 'data-page': page.id });
    main.replaceChildren(container);
    await module.render(container, {
      param,
      me: currentUser,
      toast,
      navigate: (hash) => { location.hash = hash; },
      // 页面请求统一经过这里：会话失效或失去管理员身份时切换到说明页。
      request: async (...args) => {
        try {
          return await request(...args);
        } catch (error) {
          if (error.status === 401) blocked('需要登录', '登录已失效，请重新登录。', { href: '/', label: '去登录' });
          else if (error.status === 403 && error.code === 'FORBIDDEN') blocked('无权访问', '仅管理员可以使用管理后台。');
          throw error;
        }
      },
    });
  } catch (error) {
    if (generation !== renderGeneration || nav.hidden) return;
    main.replaceChildren(el('section', { class: 'qa-panel' },
      el('p', { class: 'qa-error', text: error instanceof RequestError ? error.message : '页面加载失败，请刷新重试' })));
  }
}

async function start() {
  try {
    currentUser = (await request('/api/me')).user;
  } catch (error) {
    if (error.status === 401) blocked('需要登录', '请先登录管理员账号。', { href: '/', label: '去登录' });
    else blocked('无法连接', error instanceof RequestError ? error.message : '暂时无法读取登录状态');
    return;
  }
  if (!currentUser?.admin) {
    blocked('无权访问', '仅管理员可以使用管理后台。', { href: '/practice/', label: '返回练习空间' });
    return;
  }
  document.getElementById('admin-account').textContent = `管理员：${currentUser.name}`;
  const bell = document.getElementById('admin-bell');
  bell.hidden = false;
  mountNotifications(bell);
  window.addEventListener('hashchange', render);
  await render();
}

start();
