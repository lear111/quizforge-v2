// Selection uses world coordinates and whole strokes; saved draft format stays unchanged.
export function rectangle(from, to) {
  return { x: Math.min(from.x, to.x), y: Math.min(from.y, to.y), width: Math.abs(to.x - from.x), height: Math.abs(to.y - from.y) };
}
export function strokeBounds(strokes) {
  if (!strokes.length) return null;
  let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
  for (const stroke of strokes) for (const point of stroke.points) {
    const radius = stroke.width * (0.35 + 0.65 * point.pressure) / 2;
    left = Math.min(left, point.x - radius); top = Math.min(top, point.y - radius);
    right = Math.max(right, point.x + radius); bottom = Math.max(bottom, point.y + radius);
  }
  return { x: left, y: top, width: right - left, height: bottom - top };
}
export function containsPoint(rect, point) {
  return !!rect && point.x >= rect.x && point.y >= rect.y && point.x <= rect.x + rect.width && point.y <= rect.y + rect.height;
}
export function enclosedStrokes(strokes, rect) {
  return strokes.filter(stroke => {
    const bounds = strokeBounds([stroke]);
    return containsPoint(rect, bounds) && containsPoint(rect, { x: bounds.x + bounds.width, y: bounds.y + bounds.height });
  }).map(stroke => stroke.id);
}
export function translateStrokes(strokes, ids, dx, dy, newId = null) {
  const selected = new Set(ids);
  return strokes.filter(stroke => !newId || selected.has(stroke.id)).map(stroke => selected.has(stroke.id)
    ? { ...stroke, id: newId ? newId() : stroke.id, points: stroke.points.map(point => ({ ...point, x: Math.round((point.x + dx) * 1000) / 1000, y: Math.round((point.y + dy) * 1000) / 1000 })) }
    : stroke);
}
