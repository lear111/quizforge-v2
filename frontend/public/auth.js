import {quotaCards} from './quota.js';

function practiceReturn() {
  const raw=new URLSearchParams(location.search).get('returnTo');
  if(!raw)return null;
  try{const target=new URL(raw,location.origin);return target.origin===location.origin&&target.pathname.startsWith('/practice/')?target.pathname+target.search+target.hash:null;}catch{return null;}
}
const byId = (id) => document.getElementById(id);
const loginForm = byId('login-form');
const registerForm = byId('register-form');
const resetForm = byId('reset-form');
const contactForm = byId('contact-form');
const feedback = byId('feedback');
const notice = byId('notice');
const FAKE_CAPTCHA_TOKEN = 'fake-captcha-pass';
const TURNSTILE_SCRIPT = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
let pending = false;
let mode = 'login';
let quotaGeneration = 0;
// 登录与注册要求以服务器 /api/config 为准；服务器在最终提交时还会重新核对。
let config = null;
let configTimer = null;

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

// ------------------------------------------------------------------ 账号格式（与服务器规则一致）

function channelOf(raw) {
  const value = raw.trim();
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return 'email';
  const digits = value.replace(/[\s-]/g, '').replace(/^(?:\+86|86)(?=1\d{10}$)/, '');
  return /^1[3-9]\d{9}$/.test(digits) ? 'phone' : null;
}

function requireIdentifier(form) {
  const value = form.elements.namedItem('identifier').value.trim();
  if (!value) throw new RequestError('EMPTY_IDENTIFIER', '请填写邮箱或手机号');
  const channel = channelOf(value);
  if (!channel) throw new RequestError('INVALID_IDENTIFIER', '请填写正确的邮箱或中国大陆手机号');
  return { identifier: value, channel };
}

function requirePassword(form, field = 'password') {
  const password = form.elements.namedItem(field).value;
  const length = Array.from(password).length;
  if (length < 8 || length > 128) throw new RequestError('PASSWORD_LENGTH', '密码需为 8–128 个字符');
  if (password !== form.elements.namedItem('confirm').value) throw new RequestError('PASSWORD_MISMATCH', '两次输入的密码不一致');
  return password;
}

// ------------------------------------------------------------------ 登录与注册要求

function needs(form, requirement) {
  if (requirement === 'always-captcha') return true;
  const policy = config?.authPolicy ?? {};
  if (form === loginForm) {
    return requirement === 'code' ? policy.loginCodeRequired === true
      : requirement === 'captcha' ? policy.loginCaptchaRequired === true && policy.loginCodeRequired !== true
        : false;
  }
  if (form === registerForm) {
    return requirement === 'code' ? policy.registrationCodeRequired === true
      : requirement === 'captcha' ? policy.registrationCaptchaRequired === true
        : requirement === 'invitation' ? policy.invitationRequired === true : false;
  }
  return false;
}

// 需要验证码时，人机验证在获取验证码时完成；登录的人机验证组件因此也要显示。
function captchaNeeded(form) {
  if (form === loginForm) return config?.authPolicy?.loginCaptchaRequired === true;
  if (form === registerForm) return config?.authPolicy?.registrationCaptchaRequired === true;
  return true;
}

function applyConfig() {
  for (const form of [loginForm, registerForm]) {
    for (const field of form.querySelectorAll('[data-requires]')) {
      const requirement = field.dataset.requires;
      field.hidden = requirement === 'captcha' ? !captchaNeeded(form) : !needs(form, requirement);
    }
  }
  byId('register-closed').hidden = config?.registrationReady !== false;
  for (const form of [loginForm, registerForm, resetForm, contactForm]) syncCaptcha(form);
}

async function refreshConfig() {
  try {
    config = await request('/api/config');
    applyConfig();
  } catch {
    // 读取失败时保留上一次的要求；最终以服务器核对为准。
  }
}

function watchConfig() {
  clearInterval(configTimer);
  configTimer = setInterval(() => {
    if (document.visibilityState === 'visible' && !byId('forms-view').hidden && !pending) void refreshConfig();
  }, 10000);
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && !byId('forms-view').hidden) void refreshConfig();
});

// ------------------------------------------------------------------ 人机验证组件

const captchas = new Map();
let turnstileLoading = null;

function captchaAction(form) {
  if (form === resetForm) return 'reset_password';
  if (form === contactForm) return 'bind_contact';
  const channel = channelOf(form.elements.namedItem('identifier').value) ?? 'email';
  return `${form === loginForm ? 'login' : 'register'}_${channel}`;
}

function loadTurnstile() {
  if (globalThis.turnstile) return Promise.resolve(globalThis.turnstile);
  turnstileLoading ??= new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = TURNSTILE_SCRIPT;
    script.async = true;
    script.onload = () => resolve(globalThis.turnstile);
    script.onerror = () => { turnstileLoading = null; reject(new Error('turnstile')); };
    document.head.append(script);
  });
  return turnstileLoading;
}

function syncCaptcha(form) {
  const container = form.querySelector('[data-captcha]');
  if (!container || container.closest('[hidden]')) return;
  const provider = config?.captchaProvider ?? '';
  const action = captchaAction(form);
  const state = captchas.get(form);
  if (state && state.provider === provider && (provider !== 'turnstile' || state.action === action)) return;
  if (state?.widget != null && globalThis.turnstile) globalThis.turnstile.remove(state.widget);
  const next = { provider, action, token: '', widget: null };
  captchas.set(form, next);
  container.replaceChildren();
  if (provider === 'fake') {
    const label = document.createElement('label');
    label.className = 'fake-captcha';
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.addEventListener('change', () => { next.token = box.checked ? FAKE_CAPTCHA_TOKEN : ''; });
    label.append(box, document.createTextNode(' 我不是机器人（开发环境模拟）'));
    container.append(label);
  } else if (provider === 'turnstile') {
    loadTurnstile().then((turnstile) => {
      if (captchas.get(form) !== next) return;
      next.widget = turnstile.render(container, {
        sitekey: config.turnstileSiteKey,
        action,
        callback: (token) => { next.token = token; },
        'expired-callback': () => { next.token = ''; },
      });
    }).catch(() => {
      container.textContent = '人机验证组件加载失败，请检查网络后刷新页面。';
    });
  } else {
    container.textContent = '人机验证服务暂未配置。';
  }
}

function captchaToken(form) {
  const token = captchas.get(form)?.token ?? '';
  if (!token) throw new RequestError('CAPTCHA_REQUIRED', '请先完成人机验证');
  return token;
}

// 令牌只能使用一次：每次提交后重置组件。
function resetCaptcha(form) {
  const state = captchas.get(form);
  if (!state) return;
  state.token = '';
  if (state.widget != null && globalThis.turnstile) globalThis.turnstile.reset(state.widget);
  const box = form.querySelector('.fake-captcha input');
  if (box) box.checked = false;
}

for (const form of [loginForm, registerForm]) {
  form.elements.namedItem('identifier').addEventListener('change', () => syncCaptcha(form));
}

// ------------------------------------------------------------------ 获取验证码

const countdowns = new Map();

function startCountdown(button, seconds) {
  const until = Date.now() + seconds * 1000;
  countdowns.set(button, until);
  const tick = () => {
    const left = Math.ceil((countdowns.get(button) - Date.now()) / 1000);
    if (left <= 0) {
      countdowns.delete(button);
      button.textContent = '获取验证码';
      button.disabled = pending;
      return;
    }
    button.textContent = `${left} 秒后重发`;
    button.disabled = true;
    setTimeout(tick, 1000);
  };
  tick();
}

async function sendCode(form, button) {
  if (pending || countdowns.has(button)) return;
  clearMessages();
  try {
    const { identifier, channel } = requireIdentifier(form);
    setBusy(true);
    await refreshConfig();
    let result;
    if (form === loginForm) {
      const password = form.elements.namedItem('password').value;
      if (!password) throw new RequestError('EMPTY_PASSWORD', '请先填写密码');
      const body = { identifier, password };
      if (captchaNeeded(form)) body.captchaToken = captchaToken(form);
      result = await request('/api/auth/login-code', 'POST', body);
    } else if (form === registerForm) {
      const body = { identifier };
      if (needs(form, 'invitation')) body.invitationCode = form.elements.namedItem('invitationCode').value.trim();
      if (captchaNeeded(form)) body.captchaToken = captchaToken(form);
      result = await request(channel === 'phone' ? '/api/auth/phone-code' : '/api/auth/email-code', 'POST', body);
    } else if (form === resetForm) {
      result = await request('/api/auth/password-reset-code', 'POST', { identifier, captchaToken: captchaToken(form) });
    } else {
      const password = form.elements.namedItem('password').value;
      if (!password) throw new RequestError('EMPTY_PASSWORD', '请填写当前密码');
      result = await request('/api/account/contact-code', 'POST', { identifier, password, captchaToken: captchaToken(form) });
    }
    resetCaptcha(form);
    setBusy(false);
    showNotice(result?.message ?? '验证码已发送，请查收。');
    startCountdown(button, Number(result?.retryAfter) > 0 ? Number(result.retryAfter) : 60);
    form.elements.namedItem('code').focus();
  } catch (error) {
    setBusy(false);
    if (error.code !== 'CAPTCHA_REQUIRED') resetCaptcha(form);
    showError(error, form);
  }
}

for (const form of [loginForm, registerForm, resetForm, contactForm]) {
  const button = form.querySelector('[data-send-code]');
  button.addEventListener('click', () => void sendCode(form, button));
}

// ------------------------------------------------------------------ 页面状态

function setBusy(busy) {
  pending = busy;
  document.querySelectorAll('button, fieldset').forEach((element) => {
    element.disabled = busy || countdowns.has(element);
  });
  document.querySelector('.account-card').setAttribute('aria-busy', String(busy));
  loginForm.querySelector('[type="submit"]').textContent = busy ? '正在登录…' : '登录';
  registerForm.querySelector('[type="submit"]').textContent = busy ? '正在注册…' : '注册并登录';
  resetForm.querySelector('[type="submit"]').textContent = busy ? '正在重置…' : '重置密码';
  contactForm.querySelector('[type="submit"]').textContent = busy ? '正在绑定…' : '确认绑定';
  byId('logout').textContent = busy ? '正在退出…' : '退出登录';
}

function clearMessages() {
  feedback.hidden = true;
  feedback.textContent = '';
  notice.hidden = true;
  notice.textContent = '';
  document.querySelectorAll('[aria-invalid]').forEach((input) => input.removeAttribute('aria-invalid'));
}

function showNotice(message) {
  notice.hidden = false;
  notice.textContent = message;
}

function showError(error, form) {
  feedback.hidden = false;
  // 服务端 message 以纯文本展示；code 用于定位可修正的字段。
  feedback.textContent = error instanceof RequestError ? error.message : '请求未能完成，请稍后重试';
  const fieldName = {
    INVALID_CREDENTIALS: 'password', WRONG_PASSWORD: 'password', EMPTY_PASSWORD: 'password',
    EMAIL_TAKEN: 'identifier', PHONE_TAKEN: 'identifier', CONTACT_TAKEN: 'identifier', CONTACT_ALREADY_BOUND: 'identifier',
    EMPTY_IDENTIFIER: 'identifier', INVALID_IDENTIFIER: 'identifier',
    PASSWORD_LENGTH: 'password', PASSWORD_MISMATCH: 'confirm',
    VERIFICATION_CODE_INVALID: 'code', VERIFICATION_CODE_MISMATCH: 'code', EMPTY_CODE: 'code',
    INVITATION_INVALID: 'invitationCode',
  }[error.code];
  const input = fieldName ? form?.elements.namedItem(fieldName) : null;
  if (input && !input.closest('[hidden]')) {
    input.setAttribute('aria-invalid', 'true');
    input.focus();
  } else {
    feedback.focus();
  }
}

function showView(view) {
  if (view !== 'signed-in') quotaGeneration++;
  byId('checking-view').hidden = view !== 'checking';
  byId('forms-view').hidden = view !== 'forms';
  byId('signed-in-view').hidden = view !== 'signed-in';
  if (view === 'forms') {
    void refreshConfig();
    watchConfig();
  } else {
    clearInterval(configTimer);
  }
}

function setMode(nextMode, focusInput = false) {
  mode = nextMode;
  clearMessages();
  const registering = mode === 'register';
  const resetting = mode === 'reset';
  byId('tabs').hidden = resetting;
  byId('login-panel').hidden = mode !== 'login';
  byId('register-panel').hidden = !registering;
  byId('reset-panel').hidden = !resetting;
  for (const name of ['login', 'register']) {
    const selected = name === mode;
    byId(`${name}-tab`).setAttribute('aria-selected', String(selected));
    byId(`${name}-tab`).tabIndex = selected ? 0 : -1;
  }
  byId('page-title').textContent = resetting ? '找回密码' : registering ? '创建你的账号' : '欢迎回来';
  byId('page-description').textContent = resetting ? '通过邮箱或短信验证码设置新密码。'
    : registering ? '注册 QuizForge，开启新的学习旅程。' : '登录你的 QuizForge 账号。';
  document.title = `${resetting ? '找回密码' : registering ? '注册' : '登录'} · QuizForge`;
  applyConfig();
  if (focusInput) byId(resetting ? 'reset-identifier' : `${mode}-email`).focus();
}

function showAccount(data) {
  const user = data?.user;
  if (typeof user?.name !== 'string' || (user.email != null && typeof user.email !== 'string')
      || (user.phone != null && typeof user.phone !== 'string')) {
    throw new RequestError('INVALID_RESPONSE', '服务器响应异常，请稍后重试');
  }
  clearMessages();
  showView('signed-in');
  byId('page-title').textContent = '欢迎回来';
  byId('page-description').textContent = '你已成功登录 QuizForge。';
  const contacts = [user.email, user.phone].filter(Boolean).join('，');
  byId('identity').textContent = `已登录：${user.name}（${contacts || '未绑定联系方式'}）`;
  const unverified = Array.isArray(user.unverifiedContacts) ? user.unverifiedContacts : [];
  byId('unverified').hidden = unverified.length === 0;
  byId('unverified').textContent = unverified.length
    ? `${unverified.map((channel) => channel === 'phone' ? '手机号' : '邮箱').join('、')}尚未验证，下次用验证码登录或重新绑定时会完成验证。` : '';
  // 只能补绑缺少的那一种联系方式；已绑定的不能更换（与旧版一致）。
  const missing = user.email == null ? '邮箱' : user.phone == null ? '手机号' : null;
  byId('contact-section').hidden = missing === null;
  byId('contact-summary').textContent = missing ? `绑定${missing}` : '绑定联系方式';
  contactForm.elements.namedItem('identifier').placeholder = missing === '邮箱' ? '要绑定的邮箱' : '要绑定的中国大陆手机号';
  byId('admin-link').hidden = user.admin !== true;
  document.title = '账号 · QuizForge';
  for (const form of [loginForm, registerForm, resetForm, contactForm]) form.reset();
  void loadQuota();
}

async function loadQuota() {
  const generation = ++quotaGeneration;
  const status = byId('quota-status'); status.textContent = '正在读取资源用量…';
  byId('quota-cards').replaceChildren();
  try {
    const cards = quotaCards(await request('/api/account/quota'));
    if (generation !== quotaGeneration) return;
    for (const card of cards) {
      const section = document.createElement('section'); section.className = 'quota-card';
      const heading = document.createElement('h3'); heading.textContent = card.title;
      const amount = document.createElement('div'); amount.className = 'quota-number'; amount.textContent = card.text;
      const progress = document.createElement('progress'); progress.max = card.max; progress.value = card.value;
      progress.setAttribute('aria-label', `${card.title}使用量`);
      const caption = document.createElement('p'); caption.textContent = card.caption;
      section.append(heading, amount, progress, caption); byId('quota-cards').append(section);
    }
    status.textContent = '正常练习、读题读图和页面资源不扣下载流量。';
  } catch (error) {
    if (generation === quotaGeneration) status.textContent = error instanceof RequestError ? error.message : '暂时无法读取资源用量，请重试。';
  }
}

byId('quota-refresh').addEventListener('click', () => { if (!pending) void loadQuota(); });
byId('contact-section').addEventListener('toggle', () => {
  if (byId('contact-section').open) {
    void refreshConfig().then(() => syncCaptcha(contactForm));
  }
});

async function checkSession() {
  if (pending) return;
  clearMessages();
  showView('checking');
  byId('retry').hidden = true;
  byId('checking-message').textContent = '正在检查登录状态…';
  document.querySelector('.spinner').hidden = false;
  setBusy(true);
  try {
    showAccount(await request('/api/me'));
    if(practiceReturn())location.replace(practiceReturn());
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

// ------------------------------------------------------------------ 提交

function loginBody(form) {
  const { identifier } = requireIdentifier(form);
  const body = { identifier, password: form.elements.namedItem('password').value };
  if (!body.password) throw new RequestError('EMPTY_PASSWORD', '请填写密码');
  if (needs(form, 'code')) {
    body.code = form.elements.namedItem('code').value.trim();
    if (!body.code) throw new RequestError('EMPTY_CODE', '请先获取并填写验证码');
  } else if (captchaNeeded(form)) {
    body.captchaToken = captchaToken(form);
  }
  return body;
}

function registerBody(form) {
  const { identifier } = requireIdentifier(form);
  const password = requirePassword(form);
  const body = { identifier, name: form.elements.namedItem('name').value.trim(), password };
  if (needs(form, 'invitation')) body.invitationCode = form.elements.namedItem('invitationCode').value.trim();
  if (needs(form, 'code')) {
    body.code = form.elements.namedItem('code').value.trim();
    if (!body.code) throw new RequestError('EMPTY_CODE', '请先获取并填写验证码');
  } else if (captchaNeeded(form)) {
    body.captchaToken = captchaToken(form);
  }
  return body;
}

async function submit(event) {
  event.preventDefault();
  if (pending) return;
  const form = event.currentTarget;
  const registering = form === registerForm;
  clearMessages();
  try {
    setBusy(true);
    await refreshConfig();
    const body = registering ? registerBody(form) : loginBody(form);
    await request(registering ? '/api/auth/register' : '/api/auth/login', 'POST', body);
    // 会话是否生效以 /api/me 为准，不从注册/登录响应推断浏览器状态。
    showAccount(await request('/api/me'));
    location.assign(practiceReturn()||'/practice/');
    byId('page-title').focus();
  } catch (error) {
    setBusy(false);
    resetCaptcha(form);
    showError(error, form);
  } finally {
    setBusy(false);
  }
}

resetForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (pending) return;
  clearMessages();
  try {
    const { identifier } = requireIdentifier(resetForm);
    const code = resetForm.elements.namedItem('code').value.trim();
    if (!code) throw new RequestError('EMPTY_CODE', '请先获取并填写验证码');
    const password = requirePassword(resetForm);
    setBusy(true);
    await request('/api/auth/password-reset', 'POST', { identifier, code, password });
    setBusy(false);
    resetForm.reset();
    setMode('login');
    loginForm.elements.namedItem('identifier').value = identifier;
    showNotice('密码已重置，所有设备上的登录已退出，请用新密码登录。');
    loginForm.elements.namedItem('password').focus();
  } catch (error) {
    setBusy(false);
    showError(error, resetForm);
  } finally {
    setBusy(false);
  }
});

contactForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (pending) return;
  clearMessages();
  try {
    const { identifier } = requireIdentifier(contactForm);
    const password = contactForm.elements.namedItem('password').value;
    if (!password) throw new RequestError('EMPTY_PASSWORD', '请填写当前密码');
    const code = contactForm.elements.namedItem('code').value.trim();
    if (!code) throw new RequestError('EMPTY_CODE', '请先获取并填写验证码');
    setBusy(true);
    showAccount(await request('/api/account/contact', 'POST', { identifier, password, code }));
    byId('contact-section').open = false;
    showNotice('联系方式已绑定。');
  } catch (error) {
    setBusy(false);
    showError(error, contactForm);
  } finally {
    setBusy(false);
  }
});

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
byId('to-reset').addEventListener('click', () => { if (!pending) setMode('reset', true); });
byId('reset-back').addEventListener('click', () => { if (!pending) setMode('login', true); });
byId('retry').addEventListener('click', checkSession);
byId('logout').addEventListener('click', async () => {
  if (pending) return;
  clearMessages();
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
