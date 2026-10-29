# 练习空间源码分叉

- 来源仓库：[lear111/quizforge_web](https://github.com/lear111/quizforge_web)
- 固定提交：`4522bb81c2126a699fcf72ce61d074dbc1264161`（4522bb8）
- 分叉日期：2026-10-29
- 方式：ADR-006 方案 C，直接修改副本，不在服务端或页面加载时做字符串替换。
- 许可：上游 MIT 许可保留于 LICENSE.upstream；原文件中的作者/第三方声明保留。

## 复制范围

`core/web/` → `practice/`；`core/shared/` → `shared/`。固定提交有 85 个 web 文件、36 个 shared 文件（包含已发布组件产物）。保留所有 ink、answer-area 和两版 richtext 源码、CSS、README、service.json；不复制旧 Node 宿主、数据、题库、用户设置或令牌。

`core/scripts/build/build-richtext.mjs`、`build-richtext-advanced.mjs` → `scripts/build/`，脚本本身不改。npm 只保留富文本/截图构建需要的依赖，沿用上游 lock 中的依赖版本；Playwright 是本分叉新增的开发测试依赖。

## 对上游文件的改动

下列 web 文件以外的留存文件保持上游内容（忽略 CRLF/LF）：

| 文件 | 原因 |
| --- | --- |
| practice/app.js | 移除本机认证/设置/作者编辑/开发监听接线；目录适配、账号退出、外观对话框；保留练习/草稿/历史/考试的流程与写队列 |
| practice/index.html | 静态路径加 /practice/ 前缀；删除本机和作者编辑入口/对话框；增加账号/退出和独立外观入口 |
| practice/manifest.webmanifest | 启动路径/作用域改为 /practice/，删除本机存储描述 |
| practice/shared/api.js | 同源 Cookie、集中 401/404/501、FormData/204；readResource 复用 request，避免保留第二套认证传输 |
| practice/library/library-transfer.js | multipart file 与 128 MiB，匹配 V2 导入契约 |
| practice/library/library-view.js | 只展示我的题库；目录缺失/真实空库区分，不再提示去本机导入 |
| practice/listening/listening-player.js | HEAD 预检后直接 audio.src，去掉下载整个 Blob 及旧 8 MiB 客户端音频限制 |
| practice/practice/practice-context.js | canEditCollection 固定 false，防止 Bridge 宣告在线版不提供的作者编辑能力 |
| practice/bridge/frame.js | 仅调整共用页面内联器的 import 路径；srcdoc/sandbox/消息通道不变 |
| practice/shell/appearance.js | 原外观组件接独立对话框，移除本机设置面板切换依赖 |
| practice/exam/exam-mode.js | 删除接口的旧 404 幂等分支不吞掉 FEATURE_NOT_AVAILABLE，避免把尚未实现误报成删除成功 |
| practice/shell/themes.css | 内置背景 URL 加 /practice/ 前缀 |

新增 practice/shared/online.js 为小型目录/缺失切片适配边界；practice/shell/online.css 保留公共题卡候选/错误样式并补独立导航、外观对话框样式。

## 删除与保留的边界

- 删除 editor/ 三文件；development/ 下设置、发布、监听和专用 CSS。原 development-preview.js 的页面资源内联器被**原样移动**为 bridge/page-html.js：普通题卡也依赖它，不能随开发入口删除。
- 删除 ai/ai-settings.js、ai-settings.css、hub-login.js、user-center.js；平台 AI 评分客户端 ai-client.js 保留。
- 删除 shell/network-settings.js、network-settings.css，以及原用户中心直接依赖的 admin-contact.js、admin-contact.css。
- 删除本地启动器传递导入提示的 library/native-import.js；浏览器导入成功后刷新真实目录。
- 删除 HTML 内的拓展/开发模式切换、本机连接密码/Hub 设备登录/模型配置/网络连接/HTTPS 设置/作者编辑对话框。题库实例管理和作答富文本编辑保留。
- 纯函数中的历史数据兼容分支未为删入口而重写；在线目录适配始终排除开发项和拓展示例。Bridge 协议、iframe allow-scripts 沙箱与底层墨迹/答题区协议保留。

## 生成产物与部署

不提交 richtext 各版本的 richtext.js、richtext-editor.js、生成的 THIRD_PARTY_NOTICES.md，以及第三方 html2canvas.min.js。scripts/build.mjs 只清理这些已忽略文件，再执行两份上游发布脚本（1.0.0/1.1.3），并从固定 npm 版本复制 html2canvas；源文件/CSS不清理。生成的第三方声明随镜像分发。

```
cd frontend
npm ci --ignore-scripts --no-audit --no-fund
npm run build
npm test
```

Dockerfile 的 Node 22 构建阶段生成产物，Nginx 阶段只复制 public/、practice/、shared/。Compose 不再用源码目录覆盖构建结果。practice/ 和登录页使用 no-cache + ETag/Last-Modified 重验证；shared/ 带固定版本的组件长期 immutable 缓存，service.json 单独重验证。

认证页 public/auth.js / index.html / auth.css 增加安全 returnTo 和练习入口。deploy 的 Nginx、Compose 与 CI 配置也有相应修改；不修改后端业务代码。完整接口与切片映射见 [设计 07](../docs/design/07-frontend-api-map.md)。

## 后续同步

上游新功能按 ADR-006：记录新提交、人工移植、回归测试、更新本清单。禁止重新引入宿主运行时替换，也不直接覆盖整个分叉目录。
