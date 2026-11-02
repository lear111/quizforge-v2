import { el } from './format.js';

// 登录与注册设置（AUTH_POLICY 规则）：保存时携带 revision，另一位管理员已修改时服务器返回 409。
const FIELDS = [
  ['registrationCodeRequired', '注册需要邮箱 / 短信验证码'],
  ['registrationCaptchaRequired', '注册需要人机验证'],
  ['invitationRequired', '注册需要邀请码'],
  ['loginCodeRequired', '登录需要邮箱 / 短信验证码'],
  ['loginCaptchaRequired', '登录需要人机验证'],
];

export async function render(container, context) {
  container.append(el('h1', { text: '登录与注册设置' }));
  const holder = el('div');
  container.append(holder);

  async function load() {
    const { policy, capabilities } = await context.request('/api/admin/authentication');
    const boxes = new Map(FIELDS.map(([key]) => [key, el('input', { type: 'checkbox', name: key, checked: policy[key] })]));
    const submit = el('button', { type: 'submit', class: 'qa-button', text: '保存并生效' });
    const form = el('form', { class: 'qa-panel qa-form', 'data-revision': String(policy.revision) },
      el('p', { class: 'qa-muted', text: '登录固定需要账号和密码；注册固定需要昵称、账号和密码。以下开关独立生效。' }),
      ...FIELDS.map(([key, label]) => el('label', { class: 'qa-check' }, boxes.get(key), label)),
      submit);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const body = { revision: policy.revision };
      for (const [key, box] of boxes) body[key] = box.checked;
      submit.disabled = true;
      try {
        await context.request('/api/admin/authentication', 'PUT', body);
        context.toast('已保存并立即生效。', 'success');
        await load();
      } catch (error) {
        submit.disabled = false;
        if (error.code === 'POLICY_CONFLICT') {
          context.toast('设置已被其他管理员更新，已重新读取，请确认后再保存。', 'danger');
          await load();
        } else {
          context.toast(error.message, 'danger');
        }
      }
    });
    const service = (ok) => (ok ? '可用' : '未配置');
    holder.replaceChildren(form, el('section', { class: 'qa-panel' },
      el('h2', { text: '服务状态' }),
      el('dl', { class: 'qa-facts' },
        el('dt', { text: '邮件验证码' }), el('dd', { text: service(capabilities.emailCodeAvailable) }),
        el('dt', { text: '短信验证码' }), el('dd', { text: service(capabilities.phoneCodeAvailable) }),
        el('dt', { text: '人机验证' }), el('dd', { text: service(capabilities.captchaAvailable) }),
        el('dt', { text: '可开启登录验证码' }), el('dd', { text: capabilities.loginCodeAvailable ? '是' : '否（已有账号的某种联系方式收不到验证码）' })),
      el('p', { class: 'qa-muted', text: '启用尚未配置的服务会被拒绝。外部服务故障导致无法登录时，可在服务器上执行故障恢复命令 maintenance auth-login-recovery。' })));
  }
  await load();
}
