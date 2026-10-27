# 遗留契约清单

> **文档版本：** v0.1（初稿，待评审）
> **日期：** 2026-10-27
> **依据：** [需求 1.3 节 C-01～C-06](../requirements/01-requirements.md)，[ADR-006](../adr/ADR-006-frontend-reuse.md)
> **源码版本：** `quizforge_web` @ `4522bb8`，`quizforge_hub` @ `83d6dfd`

本文整理 V2 必须兼容的对外契约，以及复用前端所依赖的 HTTP 接口。目的是在设计新系统之前，弄清楚**哪些东西不能改**。

- 每条事实都注明出处。
- 标记“**待核实**”的条目尚未逐行确认，详细设计前须补齐。
- 契约的完整字段级说明以旧仓库文档为准，本文只摘录对 V2 设计有影响的部分，不复制全文。

---

## C-01 题库包与题库格式

**出处：** `quizforge_web/core/docs/reference/LIBRARY_PACKAGES.md`、`QUESTION_BANK_FORMAT.md`

### `.qbank` 包（格式 3）

- ZIP 容器，内部结构如下：

  ```text
  quizforge-package.json
  question-banks/<组名>/<题库名>/bank.json
  question-banks/<组名>/<题库名>/assets/
  extensions/<组名>/<拓展名>/manifest.json
  extensions/<组名>/<拓展名>/...
  ```

- 清单文件：`{"format":"quizforge-bank-group","formatVersion":3,"group":"..."}`；单题库包额外带 `"scope":"bank"`。
- **只接受格式 3**；格式 1、2 返回 `422 UNSUPPORTED_LIBRARY_ARCHIVE`。
- 大小限制：上传 64 MiB、解压后 128 MiB、单文件 8 MiB、最多 10000 个条目、图片资源 4 MiB。
- **每次导入都安装一份全新副本，不去重、不合并、不覆盖**；新副本拥有独立的保存身份，不继承旧副本的进度。
- 包内**不含**作答、草稿、历史、设置或密钥；也不接受包内携带宿主生成的 `.instance.json`、`.bundle.json`。

### 题库 `bank.json`（格式 1）

- UTF-8 JSON，单文件不超过 8 MiB，题目 1～10000 道。
- 题目 ID 须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$`，同一题库内唯一。
- 拓展绑定：`"extension":{"path":"extensions/<组>/<拓展>"}`。可在题库根上设默认，也可逐题覆盖。
- `questions[].data` 的结构**由拓展的 Schema 决定**，宿主不解析其内部字段。
- 可选 `features`：`editing`、`whiteboard`、`history`、`exam` 四个开关，省略时默认为 `true`。
  - **拓展示例（`manifest.examples`）：** 在线版保留文件但不展示、不可练习，开发者导出后在本地调试（2026-10-27 决定）。
  - **在线版的处理（2026-10-27 决定）：** `whiteboard`、`exam` 照常生效；`editing` 无意义（在线版不提供编辑）；`history:false` 被忽略，在线版**统一保存历史**。题库文件本身不改，Windows / 本地版仍按原语义处理。
- 图片以 SHA-256 引用，文件名为 `assets/<sha256>.<扩展名>`，支持 PNG / JPEG / WebP / GIF。

### 对 V2 的影响

- 导入时要做完整校验（格式、路径、大小、Schema、规则校验），这一步需要调用 rule-runner（`validateBank`）。
- “每次导入都是独立副本”意味着：题库内容可以按内容哈希共享存储，但**用户的题库实例和进度必须独立**。
- `question.data` 适合存为 `JSON` 列（ADR-002）。

---

## C-02 拓展页面与 Bridge 协议

**出处：** `quizforge_web/core/docs/reference/API.md`（Browser extension SDK 一节）、`core/web/bridge/frame.js`、`api-v1.js`

### 隔离方式（已由源码证实）

- 拓展页面通过 `iframe` 的 `srcdoc` 注入，属性为 `sandbox="allow-scripts"`，**不含 `allow-same-origin`**。因此拓展运行在**不透明源**（opaque origin）中，读不到宿主的 Cookie、存储和页面。
- iframe 内的 CSP 为 `default-src 'none'; script-src 'nonce-…'; connect-src 'none'; ...`，**拓展不能直接发起任何网络请求**，只能通过 Bridge 请求宿主代劳。
- 拓展的 HTML、JS、CSS 资源由宿主读取后内联进 `srcdoc`（`inlinePageAssets`）。

> 这意味着 V2 **不需要**为拓展另设子域名，现有机制已经提供了更强的隔离。ADR-005、ADR-006 和总体架构中“独立源 / 子域名”的说法据此更正。

### 消息格式

- 拓展 → 宿主：`postMessage({channel:'quizforge-extension', session, kind, ...})`
- 宿主 → 拓展：`postMessage({channel:'quizforge-host', session, kind, ...})`
- `session` 是每次挂载 iframe 时生成的随机 ID；宿主同时校验 `event.source` 是否为该 iframe，两项都匹配才处理消息。
- 主要 `kind`：`registered`、`context`、`request`、`reply`、`flush` / `flushed`、`dispose`、`resize`、`input-dirty`、`outline-navigate`、`appearance`、`error`，以及编辑器、答题区（`answer-area-*`）相关消息。
- `kind:'request'` 的 `method`：`save`、`action`、`answer-state`、`resource-get`、`resource-put`、`sdk-editor`、`editor-layout`、`answer-area-sync`。

### SDK（拓展作者使用的接口）

| 接口 | 作用 |
| --- | --- |
| `QF.page.register({onLoad,onFlush?,onDispose?})` | 注册页面；`onLoad(context)` 收到 `{mode,question:{id,title,data},answer,result,status,capabilities}` |
| `QF.save({purpose:'draft'|'submit', data:{answer}})` | 保存草稿或正式提交；`purpose:'review'` 用于人工评分 |
| `QF.requestAction({action:'retry'|'previous'|'next'|'goToQuestion'})` | 重做、切题 |
| `QF.page.reportAnswerState(...)` | 报告小题作答进度（API 1.3） |
| `QF.ui.*`、`QF.editor.*`、`QF.answerAreas` | 尺寸、外观、编辑器、手写答题区（API 1.4 / 1.5） |

- `mode` 取值：`practice`、`example`、`exam`、`history`。在 `history` 模式下，宿主会独立拦截所有写请求，不依赖拓展自觉遵守。

### 版本

- 当前支持 Extension API 1.0～1.5。拓展在 `manifest.json` 中通过 `requiresApi:{major:1,minMinor,capabilities?}` 声明需要的版本与能力；不支持的声明在执行规则前就会被拒绝。
- 答题区组件已发布的版本：1.0.0、1.1.0、1.2.0、1.3.0～1.3.2、1.4.0、1.4.1，宿主须继续提供。

### 对 V2 的影响

- 因为复用了前端（ADR-006），Bridge 由同一套前端代码实现，契约自然保持不变。
- 后端需要提供拓展页面资源（`/api/extensions/{id}/{version}/page`）和 SDK 资源（`/api/sdk/...`），并按 `packageVersion` 做缓存。

---

## C-03 拓展规则钩子

**出处：** `quizforge_web/core/server/rules-runner.cjs`、`server/host/extensions/rule-engine.js`、`API.md`

- 规则脚本调用 `QF.defineType({...})` 注册。**必须提供** `project` 与 `grade`；可选 `validateQuestion`、`validateAnswer`、`getScore`、`review`、`prepareAiGrading`、`getOutlineItems`。
- 运行器支持的操作（`request.op`）：`capabilities`、`validateBank`、`outlineBatch`、`scoreBatch`、`projectBatch`、`project`、`submit`（调用 `grade`）、`validateAnswer`、`review`、`prepareAiGrading`。
- `grade(data, answer)` 返回 `{score,maxScore,correct,feedback}`，可标记为 `gradingStatus:"graded"`；或返回 `{gradingStatus:"pending",score:null,...}`，表示需要人工或 AI 评分。
- 执行环境：每个请求新建 `vm` 上下文，全局对象为空，禁止 `eval` 和 WebAssembly；每次钩子调用限时 650 ms；外层子进程限时 8 s、堆内存 96 MB、启用 Node 权限模型。
- 题目和作答先按拓展的 `questionSchema`、`answerSchema` 用 AJV 校验（`strict:true`，`validateFormats:false`）。
- 输入上限 8 MB，规则文件上限 1 MB，输出上限 2 MB（开发版投影）。

### 对 V2 的影响

- rule-runner（ADR-003）的接口直接按以上操作列表设计。
- **一致性测试的参照物**：同样的输入交给旧 `rules-runner.cjs` 和新 rule-runner，输出必须一致。

---

## C-04 拓展审核摘要

**出处：** `quizforge_hub/cloud/extension-review.mjs`（`extensionDigest`）

摘要的计算方法如下（V2 必须**逐字节保持一致**，否则旧的审核记录全部失效）：

1. 按文件名排序，递归遍历拓展目录。遇到符号链接或特殊文件直接拒绝。限制：单文件 8 MiB、总计 64 MiB、最多 2000 个文件。
2. 对每个文件计算 SHA-256。有两类文件先做规范化再计算：
   - `manifest.json`：将 `name` 替换为 `"@display-name"`，缺省的 `id` / `version` 分别补为 `"extension"` / `"0.0.0"`，然后**按键名递归排序**后序列化。
   - `manifest.examples` 指向的示例文件：将所有只含 `path` 的 `extension` 绑定替换为 `{"path":"@package-extension"}`，然后按键名递归排序后序列化。
3. 最终摘要为 `SHA-256(JSON.stringify(["qfh-extension-review-v1", files]))`，其中 `files` 是 `[{path,size,sha256}, ...]`。

- 云端要求 `manifest.examples` 必须是 `examples/` 下的 JSON 文件。
- 审核记录保存在旧练习服务数据库的 `cloud_extension_reviews` 表中，状态为 `approved` 或 `revoked`。

### 对 V2 的影响

- V2（Java）需要重新实现同样的摘要算法，并用旧系统算出的摘要做对照测试。
- 审核白名单需要整表迁移（见数据字典）。

---

## C-05 Windows / Android 客户端调用的 Hub 接口

**出处：** `quizforge_hub/java-hub/.../account/DeviceService.java`、`DeviceController.java`、`catalog/LocalImportService.java`、`ai/AiController.java`；`docs/JAVA_ADMIN_AI_PRACTICE.md`

### 设备授权（类似 OAuth 2.0 授权码 + PKCE）

1. 客户端在浏览器中打开 `GET /connect/authorize`，参数包括 `v=1`、`client`、`redirect_uri`、`state`、`code_challenge`、`code_challenge_method=S256`、`device_name`。
   - `redirect_uri` 只允许 `http://127.0.0.1:<端口>/hub/connect/callback` 或 `http://localhost:<端口>/hub/connect/callback`，端口 ≥ 1024。
   - 授权请求 10 分钟内有效。
2. 用户登录并确认后（`POST /api/connect/authorize`），浏览器带着一次性授权码跳回本机回调地址。
3. 客户端调用 `POST /api/connect/token`，用授权码和 `code_verifier` 换取设备令牌。
4. 设备令牌格式为 `qfd_<随机串>`；**数据库中只存 SHA-256 哈希**（`connect_devices.token`）。
5. 规则：每个账号最多 10 台设备（超出时撤销最久未用的）；180 天未使用自动失效；账号被封禁后立即失效。

### 设备令牌可调用的接口

| 接口 | 用途 |
| --- | --- |
| `GET /api/connect/me` | 当前设备对应的用户信息 |
| `GET /api/connect/devices`、`PUT/DELETE /api/connect/devices/{id}` | 设备列表、改名、撤销 |
| `POST /api/connect/logout` | 设备退出 |
| `POST /api/connect/redeem` | 兑换 AI 额度 |
| `POST /api/ai/v1/chat/completions` | AI 网关（OpenAI 兼容格式），**只接受设备 Bearer**，浏览器 Cookie 不可用 |

### 固定版本导入

- `GET /api/packs/{id}/local-import?revision=...`：返回固定版本清单，不扣流量；版本变化时返回 409。
- `GET /api/packs/{id}/download?revision=...&sha256=...`：匿名下载已审核公开的 `.qbank`，按站点 / IP 预算扣流量，上限 64 MiB，并校验实际字节。
- 错误码使用 `LOCAL_IMPORT_*` 前缀。
- 由开关 `LOCAL_IMPORT_ENABLED` 控制。

### 软件下载

- `GET /download` 页面、`GET /api/downloads` 清单、`GET|HEAD /download/files/{name}` 安装包下载。

### 对 V2 的影响

- 以上路径、参数、响应格式和错误码都要保持不变。**待核实：** 各接口的完整响应字段，需在详细设计前从源码逐个整理。
- **设备令牌只存哈希，可以直接迁移**，用户迁移后无需在客户端重新授权（ADR-005 的待查问题已解决）。

---

## C-06 账号凭据

**出处：** `quizforge_hub/java-hub/.../account/AccountCrypto.java`、`SessionIdentity.java`、`docs/JAVA_ACCOUNTS.md`

- 密码哈希：scrypt，参数 `N=16384, r=8, p=1, dkLen=64`。存储格式为 `<salt>:<hash>`，其中 salt 为 32 个字符（按 UTF-8 字节参与计算），hash 为 128 位十六进制。**待核实：** salt 的生成方式与字符集。
- 浏览器会话：数据库中只存令牌的 SHA-256。V2 **不迁移会话**（FR-09），用户迁移后重新登录一次即可。
- 验证码、邀请码在数据库中存摘要，不存明文。

### 对 V2 的影响

- V2 登录时必须支持校验旧的 scrypt 格式。新密码是否换用其他算法（例如 Argon2），在详细设计中决定；如果更换，可以在用户下次成功登录时自动升级哈希。

---

## F-01 复用前端依赖的 HTTP 接口

**出处：** `quizforge_web/core/docs/reference/API.md`（HTTP contract 一节）、`core/web/shared/api.js`

这不是对外契约（前后端现在都由我们维护，ADR-006），但它是 V2 后端接口设计的**起点**。

### 旧接口已经具备的并发设计（重要）

旧接口在协议层面已经考虑了并发和重试，问题只出在底层用文件存储：

| 机制 | 旧接口中的做法 | V2 的对应实现 |
| --- | --- | --- |
| 乐观锁 | 每个写请求带 `revision`，不匹配返回 `409` | 数据库 `version` 列 + 条件更新（ADR-002） |
| 内容版本 | 带 `contentVersion`，题目内容已变化时返回 `409 CONTENT_CONFLICT` | 题目内容哈希 |
| 幂等 | 每个写请求带 `requestId`；服务端保存有限数量的**请求回执**，重放相同请求返回原结果（重启后也有效），同 ID 不同内容则报错 | 回执表，`requestId` 唯一约束 |
| 读缓存 | 题目响应带 `stamp:{revision,contentVersion,packageVersion}`；前端用 `X-QuizForge-Question-Stamp` 做条件请求，未变化时返回 `{unchanged:true}` | 保留，可直接减少 rule-runner 投影调用 |

> 因此 V2 **沿用请求体中的 `requestId` 与 `revision`**，不另外引入 `Idempotency-Key` 请求头。ADR-002、ADR-006 和总体架构中的相关说法据此更正。

### 练习与历史

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/catalog` | 题库与拓展列表 |
| `GET /api/collections/{kind}/{id}` | 集合（题库 / 拓展示例 / 考试）概览与题目列表 |
| `GET /api/collections/{kind}/{id}/questions/{qid}` | 读题：投影后的题目、当前状态、草稿、`stamp` |
| `GET .../questions/{qid}/stamp` | 只读版本戳，不做投影 |
| `POST .../questions/{qid}/actions` | 写操作：`draft`、`submit`、`review`、`retry`、`whiteboard`；请求体 `{requestId,revision,contentVersion?,action,data}` |
| `GET /api/collections/{kind}/{id}/summary` | 本轮得分汇总 |
| `POST .../finish`、`POST .../restart` | 完成本轮（冻结历史）、再练一次 |
| `GET .../history`、`GET .../history/{historyId}` | 历史列表与冻结快照 |
| `GET .../listening/{materialId}` | 听力材料 |
| 考试相关：`.../exams`、`collections/exam/{id}/exam`、`.../questions/{qid}/page` | 开始考试、考试状态、考试中的题目页面。**待核实**，见 `EXAM_MODE.md` |

### 资源与拓展页面

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/resources`、`GET /api/resources/{sha256}` | 上传 / 读取图片（按内容哈希寻址，4 MiB 上限） |
| `GET /api/extensions/{id}/{version}/page` | 拓展页面资源 |
| `GET /api/sdk/{id}/{version}` | SDK 与富文本组件 |

### 题库管理

| 方法与路径 | 用途 | V2 处理 |
| --- | --- | --- |
| `GET /api/library/index` | 个人题库列表 | 保留 |
| `POST /api/library/import` | 导入 `.qbank` | 保留，并接入 Hub 导入流程 |
| `POST /api/library/manage` | 改名、移动、删除 | 保留 |
| `POST /api/library/export` | 导出 `.qbank` | 保留（旧云端版计流量） |
| 编辑器：`.../editor`、`.../edit`、`/api/editor-drafts/...` | 在线编辑题目 | **不提供**（2026-10-27 决定，需求第 5 节） |

### 本地专用，V2 不提供

`/api/auth/login`（本地远程访问密码）、`/api/settings/network*`、`/api/settings/development`、`/api/development/*`、`/api/settings/ai*`（本机 AI 配置）、`/quizforge-lan-ca.crt`。

---

## 待核实事项汇总

| 编号 | 事项 | 影响 |
| --- | --- | --- |
| V-1 | C-05 各接口的完整请求、响应字段与错误码 | 客户端兼容 |
| V-2 | scrypt salt 的生成规则 | 密码迁移 |
| V-3 | 考试相关接口的完整列表与状态机 | 考试模块设计 |
| ~~V-4~~ | ~~在线版是否开放题目编辑~~ | **已决定：不提供**。前端删除编辑器入口，后端不实现编辑接口 |
| V-5 | 听力、AI 评分（`ai-grading`）相关接口 | 资源模块、AI 模块设计 |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
