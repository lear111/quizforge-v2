# M4 前端分叉验证截图

2026-10-29，来自隔离 Compose 项目 `qf-m4-frontend-fork` 的真实 Nginx / Java / MySQL 服务，地址 http://localhost:8098。注册测试账号后由 Cookie 登录；后端基线 main@f22e247 尚无目录/导入接口，因此截图展示友好降级，没有注入模拟题库。

- desktop.png：1440×960，“我的题库”和练习空间框架。
- mobile.png：375×812，打开左侧题库抽屉。
- mobile-shell.png：375×812，关闭抽屉的完整练习界面。

使用 frontend/test/browser/practice.spec.mjs 的真实会话测试生成，保存前等待抽屉退出视口；页面无横向滚动、无 pageerror。应用内浏览器在 Work 设置中禁用，因此使用 Playwright 的隔离 Chromium，未操作已有个人浏览器窗口。

复现：Compose 设置 QF_HTTP_PORT=8098、QF_PUBLIC_ORIGIN=http://localhost:8098；在 frontend/ 执行 npm ci、npm run build、npx playwright install --no-shell chromium，设置 QF_BROWSER_BASE_URL=http://localhost:8098、QF_SCREENSHOT_DIR=../docs/screenshots/m4-frontend-fork 后运行 npx playwright test。另一项模拟目录的测试只验证后续切片缺失的错误处理，不用于这些截图。
