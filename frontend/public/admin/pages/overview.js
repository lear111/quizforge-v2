import { el, bytes } from './format.js';

// 概况：用户总数、近 7 天新注册、当前在线、存储总用量，以及近 7 天的注册与活跃趋势。
// 题库相关的统计等题库广场完成后再加（下方留出位置）。

export async function render(container, context) {
  const data = await context.request('/api/admin/overview');
  const cards = [
    ['用户总数', String(data.users.total)],
    ['近 7 天新注册', String(data.users.newLast7Days)],
    ['当前在线', String(data.online), '5 分钟内有活动的用户（单实例统计）'],
    ['存储总用量', bytes(data.storageUsedBytes), '私人上传的题库'],
  ];
  const max = Math.max(1, ...data.trend.map((day) => Math.max(day.registered, day.active)));
  const bar = (value, tone) => el('span', { class: `qa-bar ${tone}`, style: `width:${(value / max) * 100}%` });
  container.append(
    el('h1', { text: '概况' }),
    el('section', { class: 'qa-cards' }, ...cards.map(([label, value, note]) => el('article', { class: 'qa-panel qa-card', 'data-metric': label },
      el('span', { class: 'qa-muted', text: label }), el('strong', { text: value }), note ? el('small', { class: 'qa-muted', text: note }) : null))),
    el('section', { class: 'qa-panel' },
      el('h2', { text: '近 7 天' }),
      el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table qa-trend' },
        el('thead', {}, el('tr', {}, ...['日期', '新注册', '活跃用户', '练习用户', ''].map((text) => el('th', { scope: 'col', text })))),
        el('tbody', {}, ...data.trend.map((day) => el('tr', {},
          el('td', { text: day.day }), el('td', { text: String(day.registered) }), el('td', { text: String(day.active) }),
          el('td', { text: String(day.practiced) }),
          el('td', { class: 'qa-bars', 'aria-hidden': 'true' }, bar(day.registered, 'registered'), bar(day.active, 'active'))))))),
      el('p', { class: 'qa-muted', text: '活跃用户：当天打开过网站的登录用户；练习用户：当天切换过题目的用户。数据每分钟写入一次。' })),
    el('section', { class: 'qa-panel', 'data-placeholder': 'banks' },
      el('h2', { text: '题库' }),
      el('p', { class: 'qa-muted', text: '题库数量、待审核与下载等统计将在题库广场完成后加入。' })));
}
