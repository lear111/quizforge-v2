// 在线活跃上报（与旧平台 activity.js 相同的行为，服务器只在内存中计数）：
// - 每天第一次可见时 visit；可见期间每 60 秒 heartbeat（页面隐藏时不发）；
// - 练习空间中切换题目、点击大纲时 practice，最多每分钟一次；
// - 未登录或会话失效（401）后停止上报。

const bindings = new WeakMap();
const PRACTICE_TARGETS = '#next,#previous,#outline-list button,#exam-start,#exam-submit';

function today() {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

export function bindActivity({ document: target = document, practice = false, endpoint = '/api/activity' } = {}) {
  if (bindings.has(target)) return bindings.get(target);
  let visitDay = '';
  let lastPractice = 0;
  let stopped = false;

  const send = (kind) => {
    if (stopped || target.visibilityState === 'hidden') return;
    fetch(endpoint, {
      method: 'POST', credentials: 'same-origin', keepalive: true,
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ kind }),
    }).then((response) => { if (response.status === 401) cleanup(); }).catch(() => {});
  };
  const visible = () => {
    if (target.visibilityState !== 'hidden' && visitDay !== today()) { visitDay = today(); send('visit'); }
  };
  const onPractice = (event) => {
    if (!event.isTrusted || !event.target.closest?.(PRACTICE_TARGETS) || Date.now() - lastPractice < 60000) return;
    lastPractice = Date.now();
    send('practice');
  };
  const interval = setInterval(() => send('heartbeat'), 60000);
  function cleanup() {
    stopped = true;
    clearInterval(interval);
    target.removeEventListener('visibilitychange', visible);
    target.removeEventListener('click', onPractice);
  }
  target.addEventListener('visibilitychange', visible);
  if (practice) target.addEventListener('click', onPractice);
  visible();
  const binding = { cleanup };
  bindings.set(target, binding);
  return binding;
}

// 练习空间直接加载本模块时自动绑定（带练习上报）。
if (typeof document !== 'undefined' && document.querySelector('script[data-qf-activity="practice"]')) {
  bindActivity({ practice: true });
}
