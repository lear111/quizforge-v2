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

账号首页与我的题库的外观按旧 Hub（`lear111/quizforge_hub@83d6dfd`）改版：`public/hub.css` 是其 `hub.css` 的分叉，只保留这两类页面用到的规则；页面专属样式在 `public/account.css` 与 `public/library/mine.css`。发布页仍使用 `public/auth.css`，改版前不要删除。

## 后续同步

上游新功能按 ADR-006：记录新提交、人工移植、回归测试、更新本清单。禁止重新引入宿主运行时替换，也不直接覆盖整个分叉目录。

## 2026-10-30：切片 2、3

- page-assets.js 集中加载受鉴权的答题区 SDK；冻结页面已有组件时不重复加载。scripts/build-sdk.mjs 按 4522bb8 的 answer-area-component.js 组装器生成 JSON，并打包富文本静态/编辑组件；生成物仍不提交。
- practice-context.js 遵守服务端 canSave/canSubmit/canRetry/canWhiteboard=false；app.js 显示只读阶段提示，自动打开时跳过审核中的题库；library-view.js 显示 availability。Bridge 沙箱和题型页面不改。
- Nginx SDK 文件移到 internal 目录，API 鉴权后发送；公开 richtext bundle 路径关闭。宿主共用源码仍保留在 shared/。

## 2026-10-31：格式 4 v0.7 集中大纲

- `practice/outline/outline-groups.js` 改用服务端的 outlineGroupIndex / outlineGroupName，不再按拓展题型分组；相邻同名组不合并。
- `practice/outline/outline-view.js` 只在作者提供分组名时显示标题，状态、高亮和跳转逻辑保留。
- `practice/app.js` 展示导入响应 warnings 中未引用题的数量与 ID。

## 2026-11-01：L1.1 / L1.2 练习闭环

- `practice/app.js` 更新练习能力提示，再练一轮前确认清空当前作答；继续沿用原 Bridge、写队列的 retry / review 字段。
- `practice/practice/practice-summary.js` 与 CSS 支持 V2 的 canFinish / canRestart：批量完成尚未开放时禁用该按钮，进行中轮次可使用独立的“再练一轮”。待评分数量与按 0 汇总的提示按 FR-14 更新，历史只读行为保留。
- 新增纯前端卡片/能力测试和真实 iframe 闭环浏览器测试；拓展源码、SDK 和依赖均未修改。

## 2026-11-01：L1.5 白板与答题资源

- 宿主 bridge 增加答题区模型绘制、截图上传和本次提交的 snapshot 绑定，沿用现有 1.4.1 组件源码，未修改拓展或 SDK。
- app / write-queue 使用独立白板 revision；两层合成显示、分层保存，笔记不进入正式作答。白板模型保护已存笔迹，笔记模式不允许改变纸面。
- 用户中心文案改为只有私人题库计入存储，并按 main #78 移除每日下载流量卡片。新增资源/白板单元与真实浏览器测试。

## 2026-11-02：HTTPS 网络听力材料

- 宿主 `practice/listening/network-audio.js` 独立完成地址复核、匿名下载、64 MiB 流式计数与取消；不复用附带登录信息的 API request，也不把网络地址传给拓展 iframe。
- `listening-player.js` 在完整下载后使用 Blob URL，切换/离开时取消并释放旧资源，历史查看提示网络内容可能变化。本地文件继续由 nginx 传输。
- 合成音频 HTTPS 测试服务仅通过 `deploy/docker-compose.audio-test.yml` 启动；测试域名由 Chromium 映射到本机，测试证书不进入源码，正式页面不忽略证书错误。

## 2026-11-03：我的题库资源与练习实例分离

- 移除练习空间的上传按钮、文件选择器和 `library-transfer.js`，保留原目录管理对话框；新增“从‘我的题库’导入”的主页链接。
- 主页 `/library/` 使用 Hub 风格展示原文件资源、提交记录与审核状态，独立创建练习实例。原题型页面、答题区和 SDK 无改动。
- 浏览器验收从主页上传并导入；仍验证五种基础题型的真实渲染、手机目录管理和 Cookie 会话。

- 广场 2/4：新增 Hub 主题 `/plaza/`、我的题库收藏分区与主页入口；原练习/拓展页面保持不变。

## 2026-11-03：ADR-011 浏览器判分

- 宿主新增 `practice/grading/`：经过审核的原始规则在不透明来源 iframe 的 Worker 中运行，宿主页面只交换 JSON，不改变拓展、SDK 或 Windows 协议。
- `app.js` 的提交写队列在锁定后本地判分并上报；重新打开等待题可补判；完成练习与 `exam-mode.js` 的交卷复用批量上报。执行失败保留已提交答案，由服务器后台补判。
- 每个钩子 650 ms，输出 2 MiB；`getScore` 使用全新上下文。网络由 CSP 与不可修改的禁用 API 双重限制。

## 2026-11-04：L5.4 AI 评分

- `practice/ai/ai-client.js` 保留旧 QF.ai 路径与任务视图，宿主显示排队、采用、重试、余额不足及花费；已采用任务刷新本题，不在浏览器准备或发送评分材料。
- 账号页增加默认关闭、服务器持久化的自动 AI 评分设置。拓展源码、SDK 和答题区组件保持不变。
- 本机 / CI 假模型只在独立 Compose 的 app 回环地址监听；桌面与手机验收使用合成题库与假 Key。
