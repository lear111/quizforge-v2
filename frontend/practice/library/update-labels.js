// 广场题库的版本状态（设计 04 第 3 节）：服务器在题库列表与概览中返回 update:{status, revision}。
// current 不显示；pending 有新版本，再练一次时使用；removed 作者在新版本中去掉了这本题库；deleted 作者删除了整个题库包。
// 后两种情况练习照常可用，只是不再更新。

const LABELS = Object.freeze({
  pending: { badge: '有新版本', notice: revision => `有新版本${revision ? `（第 ${revision} 版）` : ''}，“再练一次”时使用。当前这一轮仍使用原来的题目。` },
  removed: { badge: '作者已移除', notice: () => '作者已在新版本中移除这本题库，它会停在当前版本，不再更新；练习照常可用。' },
  deleted: { badge: '作者已删除', notice: () => '作者已删除这个题库包，这本题库停在当前版本，不再更新；练习照常可用。' },
});

export function updateBadge(update) {
  return LABELS[update?.status]?.badge ?? null;
}

export function updateNotice(update) {
  const label = LABELS[update?.status];
  return label ? label.notice(update.revision) : null;
}
