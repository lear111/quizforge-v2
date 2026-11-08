# 题库广场界面复用旧 Hub

## 范围与来源

仅调整广场表现层和静态页面路由，不修改后端接口、权限、数据库、规则运行器或现有后端测试。

旧版来源为 `lear111/quizforge_hub@83d6dfdc5497013b7b4811f8a9c960eab6b503b6` 的 `java-hub/src/main/resources/static/`。参考副本放在仓库外，未修改旧仓库。新页面复用其紫色、浅灰背景、字体、卡片、圆角、搜索框、详情双栏、评论与响应式布局；V2 按本选择导入、收藏和不可用提示沿用同一套样式。没有恢复分类列表。

## 文件与理由

| 文件 | 改动 |
| --- | --- |
| `frontend/public/plaza/hub.css` | 从旧 Hub 分叉广场所需的基础、卡片、详情、目录及响应式样式 |
| `frontend/public/plaza/index.html` | Hub 导航、搜索、卡片列表、详情容器与截图对话框 |
| `frontend/public/plaza/view.js` | 安全 DOM 卡片及共用表现组件，所有用户文字使用 textContent |
| `frontend/public/plaza/plaza.css` | V2 按本导入、收藏、加载与错误状态、手机适配 |
| `frontend/public/plaza/plaza.js` | 将现有接口绑定到新布局；保留搜索期间导航、评论独立加载与原下载行为 |
| `frontend/public/plaza/feedback.js` | 评论、删除和举报使用 Hub 组件样式，接口与权限不变 |
| `frontend/public/404.html`、`404.css` | 不依赖脚本的中文 404 页面 |
| `deploy/nginx/qf/routes.conf` | 静态路径不存在时返回真正的 404，不再回退登录页；API 仍返回应用的错误响应 |
| `frontend/public/index.html`、`library/index.html`、`publish/index.html` | 页面资源改用站点绝对路径，不改页面风格 |
| `frontend/UPSTREAM.md` | 记录来源提交、分叉范围和差异 |
| `frontend/test/static-paths.test.mjs` | 检查所有 HTML 的脚本、样式和图片路径 |
| `frontend/test/browser/not-found.spec.mjs` | 桌面与手机的深层路径、真实状态码、404 样式与 API 边界 |
| `frontend/test/browser/plaza-appearance.spec.mjs` | 合成数据的新旧截图、加载、空状态、网络重试、截图放大、按本选择与已删除提示 |
| `.github/workflows/ci.yml` | 从固定旧提交获取只读静态源码到运行器临时目录，生成离线对比截图并随原有浏览器证据上传 |

## 验证对应

- `npm run build`：富文本与 SDK 原有构建。
- `npm test`：包括所有页面绝对资源路径检查。
- `not-found.spec.mjs`：访问 `/plaza/不存在`、`/library/abc`、`/unknown/deep/path` 和不存在的脚本均为带样式的 404；`/api/me` 保持 401 JSON。
- `plaza-appearance.spec.mjs`：1440px 桌面和 375px 手机列表、详情；题库与评论使用合成数据。旧版只执行纯视图函数，不执行 hub.js；所有旧页面网络请求均被拦截，无旧平台或外部图片访问。
- 原有 `plaza.spec.mjs`：真实上传队列、审核、搜索期间导航、详情、收藏、导入练习空间、原文件逐字节下载、打开题目。
- 原有 `plaza-feedback.spec.mjs`：真实评论、纯文本安全、删除、举报、管理员下架与通知；评论加载门控期间仍可收藏。

截图不提交仓库。CI 产物 `frontend-browser-evidence` 中的 `local/plaza-ui-hub/screenshots/` 包含桌面及手机的新旧列表和详情共八张截图；真实流程截图分别位于 `local/plaza/` 与 `local/plaza-feedback/`。

## 已知差异与未处理范围

1. 当前列表接口不返回截图、原文件大小或评论数量。卡片使用旧 Hub 的文件占位图，只显示实际提供的下载次数，不新增每卡详情请求或伪造统计；详情仍显示现有截图。
2. 固定提交的旧 Hub 没有深色模式，本次沿用其浅色主题。
3. 我的题库、账号、发布与管理后台不在本次视觉改造范围；这些页面只在需要时修正资源绝对路径。管理员复用的评论模块保留原权限与接口。
4. 没有引入旧站点的登录、分类、上传、重打包或服务器连接逻辑。

本机 `npm run build` 通过，`npm test` 为 174/174；桌面与手机的相关浏览器测试共 10 项。首次组合运行 9 项通过，桌面评论测试在注册准备阶段超时（第一次发码约 20 秒，注册约 7 秒），服务预热后原测试单独重跑通过，未修改超时、断言或加入固定等待。完整 CI 结果及链接见 PR 描述。

本地使用独立项目 `qf-plaza-ui-hub` 与端口 18796；验证结束仅停止自己的项目，不删除卷。未读取 SSH 配置、未连接旧平台或业务服务器。
