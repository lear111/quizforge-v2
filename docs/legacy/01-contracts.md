# 遗留契约清单

> **文档版本：** v0.3（补全 C-05 / V-1，记录作者决定）
> **日期：** 2026-10-27
> **依据：** [需求 1.3 节 C-01～C-06](../requirements/01-requirements.md)，[ADR-006](../adr/ADR-006-frontend-reuse.md)
> **源码版本：** `quizforge_web` @ `4522bb8`，`quizforge_hub` @ `83d6dfd`

本文整理 V2 必须兼容的对外契约，以及复用前端所依赖的 HTTP 接口。目的是在设计新系统之前，弄清楚**哪些东西不能改**。

- 每条事实都注明出处。
- 标记“**待核实**”的条目尚未逐行确认，详细设计前须补齐。
- 契约的完整字段级说明以旧仓库文档为准，本文只摘录对 V2 设计有影响的部分，不复制全文。

---

## C-01 题库包与题库格式

> **2026-10-28（需求 v1.3）：** V2 不再兼容本节描述的格式 3，改用重新定义的新格式（详细设计 06）。本节保留为旧格式参考，拓展接口（C-02、C-03）不受影响。

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

## C-05 Windows / Android 客户端调用的 Hub 接口（V-1，2026-11-05 补全）

**出处（以代码为准）：** 服务端 `quizforge_hub/java-hub/.../account/DeviceController.java`、`DeviceService.java`、`AccountHttp.java`、`ai/AiController.java`、`ai/AiGateway.java`、`ai/AiPoints.java`、`ai/AiUpstreams.java`、`catalog/LocalImportService.java`、`catalog/PublicHubController.java`、`delivery/RequestLimit.java`、`delivery/TrafficService.java`；协议文档 `docs/HUB_CONNECT_AI.md`、`docs/HUB_ANDROID_IMPORT.md`。客户端 `quizforge_web/core/server/host/ai/hub-connection.js`、`ai-provider.js`、`http/admin-contact.js`、`core/desktop/windows/HubImportRequest.cs`、`HubPackageImport.cs`；`quizforge_android/.../HubConnection.java`、`HubImportClient.java`、`AiGateway.java`。

未录制旧服务的真实响应（任务要求不连接旧平台）：结构来自上述源码与它们的测试（`AccountHttpTest`、`AiHttpTest`、`LocalImportHttpTest`、`hub-connection.test.mjs`）。

### 总览：已发布客户端实际调用的接口

客户端的服务器地址**写死**为 `https://quizforge.treenewbee.cloud`（Windows 的 `HubPackageImport.Origin`、`hub-connection.js` 的 `HUB_ORIGIN`、Android 的 `HubImportClient.ORIGIN`），不读配置；发布版没有覆盖入口。

| 接口 | 认证 | Web/Windows | Android |
| --- | --- | --- | --- |
| `GET /connect/authorize`（系统浏览器打开） | 浏览器会话 | ✓ | ✓ |
| `POST /api/connect/token` | 授权码 + PKCE | ✓ | ✓ |
| `GET /api/connect/me` | 设备令牌 | ✓ | ✓ |
| `POST /api/connect/redeem` | 设备令牌 | ✓ | ✓ |
| `POST /api/connect/logout` | 设备令牌 | ✓ | ✓ |
| `POST /api/ai/v1/chat/completions` | 设备令牌 | ✓ | ✓ |
| `GET /api/packs/{id}/local-import?revision=` | 匿名 | ✓ | ✓ |
| `GET /api/packs/{id}/download?revision=&sha256=` | 匿名 | ✓ | ✓ |
| `GET /api/admin-contact` | 匿名 | ✓（设置页联系方式） | — |
| 链接 `/manage/#library`（题库广场入口，浏览器打开） | — | ✓ | ✓ |

**只有 Hub 自己的网页调用、已发布客户端不调用：** `POST /api/connect/authorize`（授权页）、`GET/PUT/DELETE /api/connect/devices*`（账号页）、`GET /api/config`、`GET /api/java/capabilities`（Hub 前端 `hub.js`）、`GET /api/downloads`、`/download/files/*`。

### 通用规则

- **错误格式有两种：** 设备与 AI 接口为 `{"error":{"code":"…","message":"…"}}`；固定版本导入为 `{"error":"文字","code":"LOCAL_IMPORT_*"}`（`error` 是字符串，客户端读顶层 `code`）。
- **限频：** 每分钟固定窗口，内存计数（`RequestLimit`），键为“分组 + 用户 ID”或“分组 + IP”；超限 429，文字“操作太频繁，请稍后再试”。计数表满 10000 项时 503。
- **JSON 请求体（`AccountHttp.body`）：** 必须是 `application/json`（否则 415）、最大 24000 字节（否则 413）、重复键或语法错误 400、非对象 400。这些错误在设备接口中的错误码是 `CONNECT_REQUEST_REJECTED`。
- **同源校验：** 只用于浏览器调用的 `POST /api/connect/authorize` 与 `PUT/DELETE /api/connect/devices/{id}`；设备接口不校验 Origin（客户端不发送）。
- 令牌无效的统一响应：401 `DEVICE_TOKEN_INVALID`“设备登录已失效，请在 AI 设置中重新登录”。客户端收到 401（不看错误码）就清除本地令牌。

### 1. `GET /connect/authorize`（浏览器页面）

| 项目 | 内容 |
| --- | --- |
| 参数 | **恰好**以下 7 个、各一次：`v=1`；`client=web\|android`；`redirect_uri`；`state`（`[A-Za-z0-9._~-]{22,256}`）；`code_challenge`（`[A-Za-z0-9_-]{43}`）；`code_challenge_method=S256`；`device_name`（去控制字符后 1–40 个码点）。或者只有 `request=<64 位十六进制>`（登录后回到页面） |
| `redirect_uri` | `web`：`^http://(127\.0\.0\.1\|localhost):<1024–65535>/hub/connect/callback$`；`android`：恰好 `quizforge://hub/connect` |
| 限频 | `connect-authorize` 30/分钟（IP） |
| 未登录 | 303 到登录页，登录后回到 `?request=<key>` |
| 已登录 | 200 HTML，确认页带 `data-request="<key>"`；请求 10 分钟有效，最多 5000 个（超出 503） |
| 错误 | HTML 错误页（不重定向）：400“连接请求无效，请回到 QuizForge 重新点击登录”；`request` 过期 400；404 `OFFICIAL_AI_DISABLED`（未开放，也是 HTML） |

### 2. `POST /api/connect/authorize`（确认页，浏览器会话 + 同源）

- 请求 `{"request":"<key>","decision":"allow"|"deny"}`；限频 `connect-decision` 20/分钟（用户）。
- 成功 200 `{"redirect":"<redirect_uri>?code=<43 位 base64url>&state=<state>"}` 或 `{"redirect":"<redirect_uri>?error=access_denied&state=<state>"}`，由页面脚本跳转（**不是** 302，与 `HUB_CONNECT_AI.md` 3.2 不同）。
- 授权码 32 字节随机、120 秒、一次性，绑定用户、`client`、`redirect_uri`、`code_challenge`、设备名；库中只存 SHA-256。
- 错误（`CONNECT_REQUEST_REJECTED`）：401 请先登录；403 来源不受信任；403 账号已停用；410 请求已过期；400 请选择允许或拒绝。

### 3. `POST /api/connect/token`

- 请求体**恰好** `{"v":1,"client":"web|android","code":"…","code_verifier":"…","redirect_uri":"…"}`，`Content-Type: application/json`；限频 `connect-token` 20/分钟（IP）。
- 校验：`code` 为 `[A-Za-z0-9_-]{43}`；`code_verifier` 为 `[A-Za-z0-9._~-]{43,128}`；未过期；`client`、`redirect_uri` 与签发时一致；`base64url(SHA-256(verifier)) == challenge`；账号未封禁。
- 成功 200：`{"token":"qfd_<43 位 base64url>","device":{"id":"…","name":"…","client":"web","created":"2026-11-05T01:02:03.000Z"},"user":{"name":"…"}}`。
- 失败 400 `INVALID_GRANT`。**只要授权码存在就被删除**（失败也作废）；并发兑换只有一个成功。
- 同一账号最多 10 个有效设备，按 `last_used DESC, created DESC` 保留前 10 个，其余撤销。
- **客户端依赖：** `token` 必须匹配 `^qfd_[A-Za-z0-9_-]{43}$`；`user.name`、`device.name` 必须是字符串（否则视为无效）；`device.id` 任意（转字符串保存）。

### 4. 设备令牌

- 请求头必须匹配 `Authorization: Bearer qfd_[A-Za-z0-9_-]{43}`；库中只存 SHA-256；Cookie 无效。
- 已撤销、连续 180 天未使用、账号被封禁都返回 401；距上次超过 60 秒才更新 `last_used`。

### 5. `GET /api/connect/me`

200：`{"user":{"name":"…"},"device":{"id":"…","name":"…","client":"web","created":"…"},"ai":{"enabled":true,"model":"QuizForge 官方模型","vision":true,"balance":9876}}`。

- `ai.enabled` = 总开关打开且有可用 Key；`balance` 单位 0.0001 元。不含邮箱、手机号。
- **客户端依赖：** 401 视为已退出；缺 `ai` 或非 2xx 显示“暂不可用”；只读 `ai.enabled`（必须严格为 `true`）、`ai.model`（截到 80 字）、`ai.balance`（非负安全整数，否则按 0）。

### 6. `POST /api/connect/redeem`

- 请求 `{"code":"…"}`；限频 `ai-redeem` 10/分钟（用户，超限 429 `CONNECT_REQUEST_REJECTED`）。
- 成功 200 `{"added":50000,"balance":92000}`。
- 400 `REDEEM_CODE_INVALID`“兑换码无效、已使用或已过期”；429 `REDEEM_LOCKED`（1 小时内失败 10 次）；401 见上。缺少 `code` 或不是字符串也按无效计一次失败。
- **客户端依赖：** 只认这两个错误码（文字用客户端自己的）；其他情况要求 `balance` 是整数，否则“兑换没有完成”。

### 7. `POST /api/connect/logout`

成功 204，无响应体，撤销当前设备。令牌无效 401。客户端不等待结果，无论成败都删除本地令牌。

### 8. 设备列表（账号页，只接受浏览器会话）

- `GET /api/connect/devices` → `{"devices":[{"id","name","client","created","lastUsed"}]}`，只列未撤销且 180 天内用过的，按最后使用倒序。
- `PUT /api/connect/devices/{id}` 请求 `{"name":"…"}` → `{"device":{…}}`；`DELETE` → `{"ok":true}`。限频 `connect-devices` 30/分钟（用户）。不存在或不属于本人 404（`CONNECT_REQUEST_REJECTED`）；未登录 401。

### 9. `POST /api/ai/v1/chat/completions`

| 项目 | 内容 |
| --- | --- |
| 认证 | 设备令牌（Cookie 无效） |
| 请求头 | `application/json`，否则 415 `AI_REQUEST_UNSUPPORTED` |
| 请求体 | 上限 13 MiB（超过 413 `AI_REQUEST_TOO_LARGE`）；重复键或非法 JSON 400 |
| 允许的字段 | `model`（必须 `quizforge-official`）、`messages`（1–50 条，每条只有 `role`∈system/user/assistant 与 `content`）、`max_tokens`（1–8192 整数，缺省 8192）、`temperature`（0–2）、`response_format`（`type`∈json_object/json_schema/text）、`stream`（**只能是 `false`**）；其他字段 400 `AI_REQUEST_UNSUPPORTED` |
| 内容 | 字符串，或部件数组：`{"type":"text","text":…}`、`{"type":"image_url","image_url":{"url":"data:image/(png\|jpeg\|webp);base64,…","detail"?:…}}`；不接受外部 URL |
| 图片 | 单张 ≤ 4 MiB、合计 ≤ 12 MiB（解码后），超过 413 |
| 并发 | 每用户 2，全站 `AI_SITE_CONCURRENCY`（默认 2，最大 8）；超出 429 `AI_BUSY`，不排队 |
| 余额 | 小于 1 个单位 402 `AI_BALANCE_EXHAUSTED`，不调用上游 |
| 上游 | 最多试 3 个 Key；每个 90 秒；全部被限流 429 `AI_BUSY`；其他失败 502 `AI_UPSTREAM_FAILED`；都不扣费。没有可用 Key 503 `AI_DISABLED`；总开关关闭 404 `OFFICIAL_AI_DISABLED` |

成功 200：

```json
{"id":"qf-<uuid>","object":"chat.completion","model":"quizforge-official",
 "choices":[{"index":0,"message":{"role":"assistant","content":"…"},"finish_reason":"stop"}],
 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2},
 "quizforge":{"cost":60,"balance":41940}}
```

`choices` 与 `usage` 取自上游，其他标识去掉。扣费 = `ceil((输入 × 输入单价 + 输出 × 输出单价) / 1000000)`，至少 1；旧平台余额扣到 0 为止，`cost` 是实际扣到的数。

**客户端依赖（Web `ai-provider.js`、Android `AiGateway.java`）：**

- 只发 `stream:false`（官方额度 `outputMode=json`，即 `response_format:{type:"json_object"}`）。**旧网关和旧客户端都没有流式。**
- 读 `choices[0].message.content`（字符串或文本部件数组，非空、≤ 24000 字符）；`finish_reason` 为空或 `stop`；`content_filter` 或 `message.refusal` 视为拒答；响应最大 2 MiB。
- 读 `usage.*` 中的非负整数；`id` 须匹配 `[A-Za-z0-9._:-]{0,200}`，否则忽略。不读 `quizforge`。
- 错误分类：401 或 `DEVICE_TOKEN_INVALID` → 需要登录（清令牌）；402 或 `AI_BALANCE_EXHAUSTED` → 余额；`AI_BUDGET_EXHAUSTED` → 预算（旧服务已不再返回）；`AI_BUSY` 或 429 → 稍后重试；`AI_UPSTREAM_FAILED` 或 502 → 上游；413 或 `AI_REQUEST_TOO_LARGE` → 超限；404、503 → 不可用；其他 → 配置错误。

### 10. `GET /api/packs/{id}/local-import?revision=<n>`

- 匿名；限频 `local-import-manifest` 60/分钟（IP）；不扣流量。其他方法 405。
- 200（`application/json`，≤ 32 KiB）：`{"schemaVersion":1,"pack":{"id":"<小写 UUID>","revision":1,"title":"…"},"archive":{"name":"xxx.qbank","size":123,"sha256":"<64 位小写十六进制>"}}`。

### 11. `GET /api/packs/{id}/download?revision=<n>&sha256=<hex>`

- 匿名；限频 `download` 30/分钟（IP）；预算：站点每月 150 GB、每 IP 每天 1 GiB（超出 429 `LOCAL_IMPORT_TRAFFIC_LIMIT`）；成功时下载数 +1。
- 200：`Content-Type: application/octet-stream`、`Content-Length` 必须等于 `size`、**不得有** `Content-Encoding`、`Cache-Control: private, no-store`、`Content-Disposition: attachment; filename="question-bank.qbank"; filename*=UTF-8''…`；响应体为原始文件。

### 12. 固定版本导入的公共规则

| HTTP | `code` | 条件 |
| --- | --- | --- |
| 404 | `LOCAL_IMPORT_DISABLED` | 未开启 |
| 400 | `LOCAL_IMPORT_REQUEST_INVALID` | `id` 不是小写 UUID；参数集合不恰好是 `revision`（下载再加 `sha256`）；参数重复；查询串含 `[a-z0-9=&-]` 以外的字符（因此拒绝百分号编码）；`revision` 不是 `[1-9][0-9]{0,15}` 或超过 2^53−1；`sha256` 不是 64 位小写十六进制 |
| 405 | `LOCAL_IMPORT_REQUEST_INVALID` | 非 GET |
| 429 | `LOCAL_IMPORT_RATE_LIMITED` | 限频 |
| 404 | `LOCAL_IMPORT_UNAVAILABLE` | 不存在、未公开、没有附件（对作者也一样） |
| 409 | `LOCAL_IMPORT_REVISION_CHANGED` | 版本或 SHA-256 与当前不符（不自动换版本） |
| 422 | `LOCAL_IMPORT_FORMAT_UNSUPPORTED` | 文件名不以 `.qbank` 结尾 |
| 413 | `LOCAL_IMPORT_TOO_LARGE` | 超过 64 MiB |
| 503 | `LOCAL_IMPORT_SERVER_ERROR` | 附件信息无效 |

**客户端依赖：** 不跟随重定向（3xx 直接失败，所以不能用 302 下载）；JSON 响应须为 `application/json`，下载须为 `application/octet-stream` 且不压缩；`pack.id`、`pack.revision` 必须与链接一致；`pack.title` 是字符串；`archive.name` 以 `.qbank` 结尾（Android 还要求 ≤ 255 字符、不含路径分隔符和控制字符）；`schemaVersion`、`revision`、`size` 必须是 JSON 整数；下载后校验长度和 SHA-256；全程 180 秒。匿名请求，不带 Cookie 和令牌。

### 13. `quizforge://hub/import` 链接

固定为 `quizforge://hub/import?v=1&pack=<小写 UUID>&revision=<正整数>`；客户端要求整串匹配 `[a-z0-9:/?=&-]+`、恰好 3 个参数、长度 ≤ 1024。网页不加入任何其他内容，点击后只提示已请求打开。

### 14. `GET /api/admin-contact`（V2 不实现，见作者决定 3）

匿名，200 `{"content":"…","revision":0}`（`content` ≤ 4000 字符，`revision` 非负安全整数，≤ 32 KiB）。其他情况客户端显示“暂时无法读取联系方式”。

### 15. `GET /api/config`、`GET /api/java/capabilities`（已发布客户端不调用）

- `/api/config`：`maxArchiveMB`、`registrationReady`、`registrationMethods`、`verificationMethods`、`captchaReady`、`authPolicy`、`invitationRequired`、`turnstileSiteKey`、`cloudEnabled`、`practiceOrigin`、`adminEnabled`、`localImport:{enabled,protocolVersion:1,scheme:"quizforge"}`、`officialAi:{enabled,protocolVersion:1}`。
- `/api/java/capabilities`：`phase`、`practiceOrigin`、`legacyOrigin`、`localImportEnabled`、`native`（已实现能力的字符串数组）、`pending`。

### 旧文档、旧服务与旧客户端不一致的地方（以代码为准）

| 项目 | 文档 | 代码 |
| --- | --- | --- |
| `stream` 字段 | `HUB_CONNECT_AI.md` 第 6 节：拒绝 | 接受 `stream:false`，拒绝其他值；客户端都发送 `stream:false`（**不接受就全部失败**） |
| 确认授权的结果 | 3.2：302 重定向 | 200 JSON `{redirect}`，由页面跳转 |
| 全站并发 | 第 6 节：8 | 配置 `AI_SITE_CONCURRENCY`，默认 2、最大 8 |
| 请求大小 | 第 6 节：12 MiB | 13 MiB（图片单独按 12 MiB 限制；base64 编码后图片合计最多约 9.7 MiB 就会先触发请求体上限，客户端自己把请求限制在 12 MiB 以内） |
| `ai` 字段 | 第 5 节含 `pricePerMillion`、`model:"通义千问 qwen-vl-plus"` | 没有 `pricePerMillion`；`model` 固定为“QuizForge 官方模型” |
| 402 响应 | 第 6.1 节 `error` 含 `balance` | 只有 `code`、`message` |
| 计费公式 | 6.1：`total_tokens × 单价` | 按输入、输出分别计价（只有总量时取较高单价） |
| 设备接口范围 | 本文 v0.1：设备令牌可调用设备列表 | 设备列表只接受浏览器会话 |
| `/api/connect/*` 关闭时 | 第 6 节：`OFFICIAL_AI_DISABLED` | `me`、`logout`、`redeem` 经 `device()` 检查 `requireEnabled`，错误相同；`GET /connect/authorize` 返回 HTML |
| 授权页文字 | 3.2：显示权限说明和余额 | 只显示设备、账号与“授权后这台设备将以你的账号登录” |

### 对 V2 的影响

- 路径、参数、响应结构、错误码按上面实现；V2 的差异（余额可为负、撤销是永久的等）列在详细设计 02 第 3 节。
- **设备令牌不迁移**（需求 FR-09 v1.3，覆盖本文 v0.1 “可以直接迁移”的说法）：V2 上线后客户端收到 401，提示重新登录一次。
- **题库包格式（需求 C-01 v1.3）：** V2 的题库包是格式 4；已发布客户端的导入器只接受格式 3（`library-package.js`），协议能走通，但导入会被客户端拒绝。各客户端的处理见下面的作者决定。

### 作者决定（2026-11-05，PR #120）

| # | 事项 | 决定 |
| --- | --- | --- |
| 1 | 已发布客户端与格式 4 | **Android 客户端不更新：** 旧 Android 在 V2 上可以连接设备、使用 AI 网关与兑换，但不能导入格式 4 题库（返回不支持的格式）。**Windows 已由作者适配格式 4：** 上线后旧 Windows 用户需升级到新版（下载页提供）。 |
| 2 | 客户端服务器地址写死 | V2 上线时接管同一域名 `quizforge.treenewbee.cloud` 并使用有效证书（上线前检查清单）。 |
| 3 | `/api/admin-contact`（第 14 节） | **不实现。** 联系方式以置顶通知提供；旧 Windows 设置页显示“暂时无法读取联系方式”，可以接受。 |
| 4 | 流式输出 | 客户端 AI 网关按旧契约只支持非流式（`stream:false`），不新增流式。 |
| 5 | 契约测试的期望值 | 来自旧源码（不录制旧服务的响应）。V2 部署到正式域名后，用旧 Windows 客户端做一次真机验收（上线前检查清单）。 |

上线前检查清单见[功能盘点 H 节](../plan/03-feature-inventory.md)。

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
| `POST /api/library/import` | 旧同步导入 `.qbank` | V2 已退役；改用上传、后台队列、审核后创建实例（见设计 02） |
| `POST /api/library/manage` | 改名、移动、删除 | 保留 |
| `POST /api/library/export` | 导出 `.qbank` | 保留（旧云端版计流量） |
| 编辑器：`.../editor`、`.../edit`、`/api/editor-drafts/...` | 在线编辑题目 | **不提供**（2026-10-27 决定，需求第 5 节） |

### 本地专用，V2 不提供

`/api/auth/login`（本地远程访问密码）、`/api/settings/network*`、`/api/settings/development`、`/api/development/*`、`/api/settings/ai*`（本机 AI 配置）、`/quizforge-lan-ca.crt`。

---

## 待核实事项汇总

| 编号 | 事项 | 影响 |
| --- | --- | --- |
| ~~V-1~~ | ~~C-05 各接口的完整请求、响应字段与错误码~~ | **已补全**（见 C-05，2026-11-05） |
| V-2 | scrypt salt 的生成规则 | 密码迁移 |
| V-3 | 考试相关接口的完整列表与状态机 | 考试模块设计 |
| ~~V-4~~ | ~~在线版是否开放题目编辑~~ | **已决定：不提供**。前端删除编辑器入口，后端不实现编辑接口 |
| V-5 | 听力、AI 评分（`ai-grading`）相关接口 | 资源模块、AI 模块设计 |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
| v0.2 | 2026-11-05 | 按旧 Hub 与旧客户端源码补全 C-05（V-1）；更正“设备令牌可以直接迁移”“设备令牌可调用设备列表” |
| v0.3 | 2026-11-05 | 记录作者对 PR #120 待决事项的决定 |
