// 账号页“已连接设备”：列表、改名、撤销（遗留契约 C-05 第 8 节）。
const CLIENTS = { web: '电脑', android: 'Android' };

export function deviceCaption(device) {
  const time = (value) => new Date(value).toLocaleString('zh-CN', { hour12: false });
  return `${CLIENTS[device.client] ?? device.client} · 连接于 ${time(device.created)} · 最后使用 ${time(device.lastUsed)}`;
}

export function bindDevices(request) {
  const byId = (id) => document.getElementById(id);
  const list = byId('device-list');
  const status = byId('device-status');
  let generation = 0;

  function deviceIcon(client) {
    const holder = document.createElement('span'); holder.className = 'dev-icon';
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'icon'); svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', client === 'android' ? '#i-phone' : '#i-monitor');
    svg.append(use); holder.append(svg);
    return holder;
  }

  function row(device) {
    const item = document.createElement('li'); item.className = 'device';
    const name = document.createElement('input');
    name.type = 'text'; name.className = 'dev-name'; name.maxLength = 40; name.value = device.name; name.setAttribute('aria-label', '设备名称');
    const caption = document.createElement('small'); caption.textContent = deviceCaption(device);
    const info = document.createElement('div'); info.className = 'dev-info'; info.append(name, caption);
    const save = document.createElement('button');
    save.type = 'button'; save.className = 'btn-outline'; save.textContent = '保存名称';
    const revoke = document.createElement('button');
    revoke.type = 'button'; revoke.className = 'btn-danger'; revoke.textContent = '撤销';
    const buttons = document.createElement('div'); buttons.className = 'device-actions'; buttons.append(save, revoke);
    const act = async (button, action, done) => {
      button.disabled = true; status.textContent = '';
      try { await action(); await refresh(); status.textContent = done; }
      catch (error) { status.textContent = error?.message || '操作没有完成，请重试。'; }
      finally { button.disabled = false; }
    };
    save.addEventListener('click', () => void act(save,
      () => request(`/api/connect/devices/${encodeURIComponent(device.id)}`, 'PUT', { name: name.value }), '名称已保存。'));
    revoke.addEventListener('click', () => {
      if (!confirm(`确定撤销“${device.name}”的连接吗？这台设备需要重新登录才能继续使用 AI 额度。`)) return;
      void act(revoke, () => request(`/api/connect/devices/${encodeURIComponent(device.id)}`, 'DELETE'), '已撤销。');
    });
    item.append(deviceIcon(device.client), info, buttons);
    return item;
  }

  async function refresh() {
    const current = ++generation;
    try {
      const { devices } = await request('/api/connect/devices');
      if (current !== generation) return;
      list.replaceChildren(...devices.map(row));
      byId('device-empty').hidden = devices.length > 0;
    } catch (error) {
      if (current === generation) status.textContent = error?.message || '暂时无法读取已连接设备，请重试。';
    }
  }
  return refresh;
}
