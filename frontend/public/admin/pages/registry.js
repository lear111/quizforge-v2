// 后台栏目注册表：左侧导航由这里生成，按数组顺序显示，同一 group 的栏目归在一起。
//
// 接入新栏目（例如题库审核）只需：
//   1. 在本目录新建页面模块，导出 render(container, context)；
//   2. 在下面的数组中增加一项 {id, label, group, load}。
// id 同时是地址中的 #id（#id/参数 时参数通过 context.param 传入），必须唯一，只用小写字母、数字和连字符。
// 页面只调用 /api/admin/** 等服务器接口；管理员身份由服务器检查，页面不做权限判断。

export const pages = Object.freeze([
  { id: 'users', label: '用户管理', group: '用户与账号', load: () => import('./users.js') },
  { id: 'authentication', label: '登录与注册设置', group: '用户与账号', load: () => import('./authentication.js') },
  { id: 'invitations', label: '邀请码', group: '用户与账号', load: () => import('./invitations.js') },
  { id: 'notifications', label: '站内通知', group: '运营', load: () => import('./notifications.js') },
  { id: 'audit', label: '操作日志', group: '系统', load: () => import('./audit.js') },
]);

export const defaultPage = 'users';
