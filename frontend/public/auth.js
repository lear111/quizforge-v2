const byId = (id) => document.getElementById(id);
const loginForm = byId('login-form');
const registerForm = byId('register-form');
const feedback = byId('feedback');
let pending = false;
let mode = 'login';

class RequestError extends Error {
  constructor(code, message, status = 0) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

async function request(path, method = 'GET', body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    let response;
    try {
      response = await fetch(path, {
        method,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch {
      throw new RequestError('NETWORK_ERROR', '无法连接服务器');
    }
    if (response.status === 204) return null;
    let data;
    try {
      data = await response.json();
    } catch (error) {
      if (controller.signal.aborted || error.name !== 'SyntaxError') {
        throw new RequestError('NETWORK_ERROR', '无法连接服务器');
      }
      throw new RequestError('INVALID_RESPONSE', '服务器响应异常，请稍后重试', response.status);
    }
    if (!response.ok) {
      const code = typeof data?.error?.code === 'string' ? data.error.code : 'UNKNOWN_ERROR';
      const message = typeof data?.error?.message === 'string' ? data.error.message : '请求未能完成，请稍后重试';
      throw new RequestError(code, message, response.status);
    }
    return data;
  } finally {
    clearTimeout(timeout);
  }
}

function setBusy(busy) {
  pending = busy;
  document.querySelectorAll('button, fieldset').forEach((element) => { element.disabled = busy; });
  document.querySelector('.account-card').setAttribute('aria-busy', String(busy));
  loginForm.querySelector('[type="submit"]').textContent = busy ? '正在登录…' : '登录';
  registerForm.querySelector('[type="submit"]').textContent = busy ? '正在注册…' : '注册并登录';
  byId('logout').textContent = busy ? '正在退出…' : '退出登录';
}

function clearError() {
  feedback.hidden = true;
  feedback.textContent = '';
  document.querySelectorAll('[aria-invalid]').forEach((input) => input.removeAttribute('aria-invalid'));
}

function showError(error, form) {
  feedback.hidden = false;
  // 服务端 message 以纯文本展示；code 用于定位可修正的字段。
  feedback.textContent = error instanceof RequestError ? error.message : '请求未能完成，请稍后重试';
  const fieldName = { INVALID_CREDENTIALS: 'password', EMAIL_TAKEN: 'email', EMPTY_EMAIL: 'email',
    PASSWORD_LENGTH: 'password', PASSWORD_MISMATCH: 'confirm' }[error.code];
  const input = fieldName ? form?.elements.namedItem(fieldName) : null;
  if (input) {
    input.setAttribute('aria-invalid', 'true');
    input.focus();
  } else {
    feedback.focus();
  }
}

function showView(view) {
  byId('checking-view').hidden = view !== 'checking';
  byId('forms-view').hidden = view !== 'forms';
  byId('signed-in-view').hidden = view !== 'signed-in';
}

function setMode(nextMode, focusInput = false) {
  mode = nextMode;
  clearError();
  const registering = mode === 'register';
  byId('login-panel').hidden = registering;
  byId('register-panel').hidden = !registering;
  for (const name of ['login', 'register']) {
    const selected = name === mode;
    byId(`${name}-tab`).setAttribute('aria-selected', String(selected));
    byId(`${name}-tab`).tabIndex = selected ? 0 : -1;
  }
  byId('page-title').textContent = registering ? '创建你的账号' : '欢迎回来';
  byId('page-description').textContent = registering ? '注册 QuizForge，开启新的学习旅程。' : '登录你的 QuizForge 账号。';
  document.title = `${registering ? '注册' : '登录'} · QuizForge`;
  if (focusInput) byId(`${mode}-email`).focus();
}

function showAccount(data) {
  const user = data?.user;
  if (typeof user?.name !== 'string' || (user.email != null && typeof user.email !== 'string')) {
    throw new RequestError('INVALID_RESPONSE', '服务器响应异常，请稍后重试');
  }
  clearError();
  showView('signed-in');
  byId('page-title').textContent = '欢迎回来';
  byId('page-description').textContent = '你已成功登录 QuizForge。';
  byId('identity').textContent = `已登录：${user.name}（${user.email ?? '未绑定邮箱'}）`;
  document.title = '账号 · QuizForge';
  loginForm.reset();
  registerForm.reset();
}

async function checkSession() {
  if (pending) return;
  clearError();
  showView('checking');
  byId('retry').hidden = true;
  byId('checking-message').textContent = '正在检查登录状态…';
  document.querySelector('.spinner').hidden = false;
  setBusy(true);
  try {
    showAccount(await request('/api/me'));
  } catch (error) {
    if (error.status === 401) {
      showView('forms');
      setMode('login');
    } else {
      byId('checking-message').textContent = '暂时无法读取登录状态';
      document.querySelector('.spinner').hidden = true;
      byId('retry').hidden = false;
      showError(error);
    }
  } finally {
    setBusy(false);
  }
}

async function submit(event) {
  event.preventDefault();
  if (pending) return;
  const form = event.currentTarget;
  const registering = form === registerForm;
  clearError();
  const email = form.elements.namedItem('email').value.trim();
  const password = form.elements.namedItem('password').value;
  try {
    if (!email) throw new RequestError('EMPTY_EMAIL', '请填写邮箱');
    if (registering) {
      const length = Array.from(password).length;
      if (length < 8 || length > 128) throw new RequestError('PASSWORD_LENGTH', '注册密码需为 8–128 个字符');
      if (password !== form.elements.namedItem('confirm').value) throw new RequestError('PASSWORD_MISMATCH', '两次输入的密码不一致');
    }
    setBusy(true);
    const body = registering ? { email, name: form.elements.namedItem('name').value.trim(), password } : { email, password };
    await request(registering ? '/api/auth/register' : '/api/auth/login', 'POST', body);
    // 会话是否生效以 /api/me 为准，不从注册/登录响应推断浏览器状态。
    showAccount(await request('/api/me'));
    byId('page-title').focus();
  } catch (error) {
    setBusy(false);
    showError(error, form);
  } finally {
    setBusy(false);
  }
}

loginForm.addEventListener('submit', submit);
registerForm.addEventListener('submit', submit);
for (const name of ['login', 'register']) {
  byId(`${name}-tab`).addEventListener('click', () => { if (!pending) setMode(name); });
  byId(`${name}-tab`).addEventListener('keydown', (event) => {
    if (pending || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    setMode(event.key === 'Home' ? 'login' : event.key === 'End' ? 'register' : mode === 'login' ? 'register' : 'login');
    byId(`${mode}-tab`).focus();
  });
}
byId('to-register').addEventListener('click', () => { if (!pending) setMode('register', true); });
byId('to-login').addEventListener('click', () => { if (!pending) setMode('login', true); });
byId('retry').addEventListener('click', checkSession);
byId('logout').addEventListener('click', async () => {
  if (pending) return;
  clearError();
  setBusy(true);
  try {
    await request('/api/auth/logout', 'POST');
    showView('forms');
    setMode('login');
  } catch (error) {
    setBusy(false);
    showError(error);
  } finally {
    setBusy(false);
  }
});
checkSession();
