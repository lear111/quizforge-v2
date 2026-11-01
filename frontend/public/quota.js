export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) throw new Error('资源用量无效');
  if (bytes < 1024) return `${bytes} B`;
  const unit = bytes < 1024 * 1024 ? 'KiB' : 'MiB';
  return `${(bytes / (unit === 'KiB' ? 1024 : 1024 * 1024)).toFixed(2)} ${unit}`;
}

export function quotaCards(usage) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(usage?.day)) throw new Error('统计日无效');
  return [
    ['storage', '存储空间', '私人题库、答题图片、草稿、白板与历史'],
    ['dailyDownload', '今日下载流量', `统计日：${usage.day}（北京时间）`],
  ].map(([key, title, caption]) => {
    const {usedBytes, limitBytes} = usage[key] || {};
    return {title, text: `${formatBytes(usedBytes)} / ${formatBytes(limitBytes)}`,
      value: Math.min(usedBytes, limitBytes), max: Math.max(1, limitBytes), caption};
  });
}
