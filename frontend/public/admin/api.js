// 后台页面共用的请求工具：同源 Cookie 会话，统一 JSON 错误格式 {error:{code,message}}。

export class RequestError extends Error {
  constructor(code, message, status = 0) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export async function request(path, method = 'GET', body) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    let response;
    try {
      response = await fetch(path, {
        method,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
        signal: controller.signal,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch {
      throw new RequestError('NETWORK_ERROR', '无法连接服务器');
    }
    if (response.status === 204) return null;
    let data = null;
    try {
      data = await response.json();
    } catch {
      if (!response.ok) throw new RequestError('INVALID_RESPONSE', '服务器响应异常，请稍后重试', response.status);
    }
    if (!response.ok) {
      throw new RequestError(data?.error?.code ?? 'UNKNOWN_ERROR', data?.error?.message ?? '请求未能完成，请稍后重试',
        response.status);
    }
    return data;
  } finally {
    clearTimeout(timeout);
  }
}

/** 拼接查询参数，忽略空值。 */
export function query(path, params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) if (value !== undefined && value !== null && value !== '') search.set(key, value);
  const text = search.toString();
  return text ? `${path}?${text}` : path;
}
