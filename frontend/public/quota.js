export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) throw new Error('资源用量无效');
  if (bytes < 1024) return `${bytes} B`;
  const unit = bytes < 1024 * 1024 ? 'KiB' : 'MiB';
  return `${(bytes / (unit === 'KiB' ? 1024 : 1024 * 1024)).toFixed(2)} ${unit}`;
}

export function quotaCards(usage) {
  return [
    ['storage', '存储空间', '仅私人上传的题库；作答图片、白板与练习记录不计入'],
  ].map(([key, title, caption]) => {
    const {usedBytes, limitBytes} = usage?.[key] || {};
    return {title, text: `${formatBytes(usedBytes)} / ${formatBytes(limitBytes)}`,
      value: Math.min(usedBytes, limitBytes), max: Math.max(1, limitBytes), caption};
  });
}
