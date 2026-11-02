import { el, time, bytes, badge, shortId } from './format.js';

// 题库审核：待审核版本列表与详情。每个版本只审核一次；只有版本用到的拓展全部批准后才能通过，
// 详情页可以直接批准未审核的拓展；拒绝必须填写原因。审核结果以站内通知发给发布者。

const CHANGES = { new: ['首次发布', ''], added: ['新增', 'accent'], updated: ['更新', 'accent'], removed: ['移除', 'danger'],
  unchanged: ['未变', ''] };
const EXTENSION_STATES = { pending: ['待审核', ''], approved: ['已批准', 'accent'], rejected: ['未通过', 'danger'],
  revoked: ['已撤销', 'danger'] };
const REVIEW_STATES = { pending: ['待审核', ''], approved: ['已通过', 'accent'], rejected: ['未通过', 'danger'],
  superseded: ['已由新版本代替', ''], withdrawn: ['已撤回', ''] };
const ACTIONS = { published: '发布', revised: '上传新版本', deleted: '删除整个包', approved: '审核通过', rejected: '审核拒绝' };

export async function render(container, context) {
  if (context.param) await renderDetail(container, context, context.param);
  else await renderList(container, context);
}

async function renderList(container, context) {
  const { items } = await context.request('/api/admin/catalog/reviews');
  const rows = items.map((item) => el('tr', { 'data-pack-id': item.packId },
    el('td', {}, el('a', { href: `#pack-reviews/${item.packId}~${item.revision}`, text: item.title })),
    el('td', { text: `第 ${item.revision} 版${item.approvedRevision ? `（公开中：第 ${item.approvedRevision} 版）` : '（首次发布）'}` }),
    el('td', { text: item.owner?.name ?? shortId(item.owner?.id) }),
    el('td', { text: bytes(item.size) }),
    el('td', { text: time(item.submittedAt) })));
  container.append(el('h1', { text: '题库审核' }), el('section', { class: 'qa-panel' },
    el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['题库包', '版本', '发布者', '大小', '提交时间'].map((text) => el('th', { scope: 'col', text })))),
      el('tbody', {}, ...rows))),
    rows.length ? null : el('p', { class: 'qa-muted', text: '没有待审核的版本' })));
}

async function renderDetail(container, context, param) {
  const [packId, revision] = param.split('~');
  container.append(el('p', {}, el('a', { href: '#pack-reviews', text: '← 返回待审核列表' })));
  const holder = el('div');
  container.append(holder);

  async function refresh() {
    const detail = await context.request(`/api/admin/catalog/packs/${encodeURIComponent(packId)}/revisions/${encodeURIComponent(revision)}`);
    const r = detail.revision;
    const [stateLabel, stateTone] = REVIEW_STATES[r.reviewStatus] ?? [r.reviewStatus, ''];
    const pending = r.reviewStatus === 'pending' && detail.status === 'active';
    holder.replaceChildren(
      el('h1', {}, r.title, badge(`第 ${r.revision} 版`, ''), badge(stateLabel, stateTone), detail.status === 'deleted' ? badge('发布者已删除', 'danger') : null),
      detail.identicalToApproved ? el('p', { class: 'qa-panel qa-warning', role: 'status',
        text: `内容与已通过的第 ${detail.approvedRevision} 版完全相同（题库与拓展都没有变化）。` }) : null,
      infoPanel(detail),
      banksPanel(detail),
      extensionsPanel(detail, context, refresh),
      audioPanel(detail),
      pending ? decisionPanel(detail, context, refresh) : null,
      historyPanel(detail));
  }
  await refresh();
}

function infoPanel(detail) {
  const r = detail.revision;
  const facts = [
    ['发布者', detail.owner?.name ?? shortId(detail.owner?.id)],
    ['简介', r.summary],
    ['分类', r.category],
    ['标签', r.tags.length ? r.tags.join('、') : '—'],
    ['更新说明', r.releaseNotes || '—'],
    ['来源', r.source === 'library' ? '从“我的题库”分享（发布时打包）' : '本地上传的原文件'],
    ['文件', `${r.filename}（${bytes(r.size)}）`],
    ['SHA-256', detail.sha256],
    ['提交时间', time(r.submittedAt)],
    ['当前公开', detail.approvedRevision ? `第 ${detail.approvedRevision} 版` : '尚未公开'],
  ];
  return el('section', { class: 'qa-panel' }, el('h2', { text: '广场信息' }),
    el('dl', { class: 'qa-facts' }, ...facts.flatMap(([label, value]) => [el('dt', { text: label }), el('dd', { text: value })])),
    el('h3', { text: '详细说明' }), el('p', { class: 'qa-prewrap', text: r.description }),
    r.screenshots.length ? el('div', { class: 'qa-shots' }, ...r.screenshots.map((shot) =>
      el('a', { href: `/api/catalog/files/${encodeURIComponent(shot.id)}`, target: '_blank', rel: 'noopener' },
        el('img', { src: `/api/catalog/files/${encodeURIComponent(shot.id)}`, alt: shot.name, loading: 'lazy' })))) : null,
    el('p', {}, el('a', { href: `/api/admin/catalog/packs/${encodeURIComponent(detail.packId)}/revisions/${r.revision}/archive`,
      text: '下载该版本的 .qbank（检查拓展代码）' })));
}

function banksPanel(detail) {
  const rows = detail.diff.map((item) => {
    const [label, tone] = CHANGES[item.change] ?? [item.change, ''];
    const count = item.change === 'removed' ? `${item.previousQuestionCount} 题`
      : item.previousQuestionCount != null && item.previousQuestionCount !== item.questionCount
        ? `${item.previousQuestionCount} → ${item.questionCount} 题` : `${item.questionCount} 题`;
    return el('tr', { 'data-bank-uuid': item.uuid }, el('td', { text: item.title }), el('td', {}, badge(label, tone)),
      el('td', { text: count }), el('td', {}, el('code', { text: item.uuid })));
  });
  return el('section', { class: 'qa-panel' }, el('h2', { text: '包内题库' }),
    el('p', { class: 'qa-muted', text: detail.approvedRevision && detail.approvedRevision !== detail.revision.revision
      ? `与已通过的第 ${detail.approvedRevision} 版比较（按 bank.json 的 UUID 对应）。不同题库包之间 UUID 相同不会自动检测，请人工判断是否为他人题库或重复发布。`
      : '首次发布。不同题库包之间 UUID 相同不会自动检测，请人工判断是否为他人题库或重复发布。' }),
    el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['题库', '变化', '题数', 'UUID'].map((text) => el('th', { scope: 'col', text })))),
      el('tbody', {}, ...rows))));
}

function extensionsPanel(detail, context, refresh) {
  const rows = detail.extensions.map((extension) => {
    const [label, tone] = EXTENSION_STATES[extension.status] ?? [extension.status, ''];
    const approve = extension.status !== 'approved' ? el('button', { type: 'button', class: 'qa-button small', text: '批准拓展' }) : null;
    approve?.addEventListener('click', async () => {
      if (!confirm(`批准拓展「${extension.name ?? extension.extensionId}」${extension.version}？\n批准后所有使用它的题库都可以打开。`)) return;
      approve.disabled = true;
      try {
        await context.request(`/api/admin/extensions/${extension.digest}/approve`, 'POST');
        context.toast('拓展已批准', 'success');
        await refresh();
      } catch (error) { context.toast(error.message, 'danger'); approve.disabled = false; }
    });
    return el('tr', { 'data-digest': extension.digest },
      el('td', { text: `${extension.name ?? '—'}（${extension.extensionId ?? '—'}）` }), el('td', { text: extension.version ?? '—' }),
      el('td', {}, badge(label, tone), extension.reason ? el('small', { text: ` ${extension.reason}` }) : null),
      el('td', {}, el('code', { text: extension.digest.slice(0, 16) })), el('td', {}, approve));
  });
  return el('section', { class: 'qa-panel' }, el('h2', { text: '所用拓展' }),
    el('p', { class: 'qa-muted', text: detail.allExtensionsApproved ? '全部拓展都已批准。' : '版本只能在全部拓展都已批准后通过。拓展按摘要审核，批准对所有使用它的题库生效。' }),
    el('div', { class: 'qa-table-wrap' }, el('table', { class: 'qa-table' },
      el('thead', {}, el('tr', {}, ...['拓展', '版本', '审核状态', '摘要', ''].map((text) => el('th', { scope: 'col', text })))),
      el('tbody', {}, ...rows))));
}

function audioPanel(detail) {
  return el('section', { class: 'qa-panel' }, el('h2', { text: '引用的外部音频域名' }),
    detail.audioHosts.length ? el('ul', {}, ...detail.audioHosts.map((host) => el('li', {}, el('code', { text: host }))))
      : el('p', { class: 'qa-muted', text: '没有引用网络音频' }));
}

function decisionPanel(detail, context, refresh) {
  const r = detail.revision;
  const base = `/api/admin/catalog/packs/${encodeURIComponent(detail.packId)}/revisions/${r.revision}`;
  const approve = el('button', { type: 'button', class: 'qa-button', text: '审核通过', disabled: !detail.allExtensionsApproved });
  approve.addEventListener('click', async () => {
    if (!confirm(`通过「${r.title}」第 ${r.revision} 版？通过后立即在题库广场公开。`)) return;
    approve.disabled = true;
    try {
      await context.request(`${base}/approve`, 'POST');
      context.toast('已通过，已通知发布者', 'success');
      await refresh();
    } catch (error) { context.toast(error.message, 'danger'); approve.disabled = false; }
  });
  const reason = el('textarea', { name: 'reason', rows: '3', maxlength: '500', placeholder: '拒绝原因（必填，最多 500 字），会发给发布者' });
  const reject = el('button', { type: 'submit', class: 'qa-button danger', text: '拒绝' });
  const form = el('form', { class: 'qa-form', 'data-action': 'reject-revision' }, el('label', {}, '拒绝原因', reason), reject);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!reason.value.trim()) { context.toast('请填写拒绝原因', 'danger'); reason.focus(); return; }
    if (!confirm(`拒绝「${r.title}」第 ${r.revision} 版？被拒绝的版本不能重新提交，发布者需要上传新版本。`)) return;
    reject.disabled = true;
    try {
      await context.request(`${base}/reject`, 'POST', { reason: reason.value.trim() });
      context.toast('已拒绝，已通知发布者', 'success');
      await refresh();
    } catch (error) { context.toast(error.message, 'danger'); reject.disabled = false; }
  });
  return el('section', { class: 'qa-panel', 'data-section': 'decision' }, el('h2', { text: '审核决定' }),
    el('div', { class: 'qa-actions' }, approve), form);
}

function historyPanel(detail) {
  return el('section', { class: 'qa-panel' }, el('h2', { text: '题库包记录' }),
    el('ol', { class: 'qa-history' }, ...detail.history.map((item) => el('li', {},
      `${time(item.createdAt)} · ${item.actor?.name ?? shortId(item.actor?.id)} · ${ACTIONS[item.action] ?? item.action}`,
      item.revision ? `（第 ${item.revision} 版）` : '',
      item.detail?.reason ? `：${item.detail.reason}` : ''))));
}
