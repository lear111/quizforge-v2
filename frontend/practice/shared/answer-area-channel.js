// The viewport owns draft tools; frames only receive a bounded, scoped copy.
export function publishAnswerTools(container, value) {
  const next = {tool:value.tool,color:value.color,width:value.width,penOnly:value.penOnly};
  if (JSON.stringify(container.qfAnswerAreaTools) === JSON.stringify(next)) return;
  container.qfAnswerAreaTools = next;
  const Event = container.ownerDocument?.defaultView?.CustomEvent;
  if (Event) container.dispatchEvent(new Event('qf-draft-tools-change',{detail:next}));
}
export function activeAnswerFrame(container) {
  return [...container.querySelectorAll('iframe')].find(frame=>frame.qfAnswerAreaFocus?.writable===true) || null;
}
export function sendAnswerCommand(container, action) {
  const frame = activeAnswerFrame(container), Event = container.ownerDocument?.defaultView?.CustomEvent;
  if (!frame || !Event) return false;
  frame.dispatchEvent(new Event('qf-answer-area-command',{detail:{action,id:frame.qfAnswerAreaFocus.id}}));
  return true;
}
export function clearAnswerFocus(container) {
  const frame = activeAnswerFrame(container);
  if (!frame) return;
  sendAnswerCommand(container,'deactivate');
  frame.qfAnswerAreaFocus = null;
}
