# API 接口设计

> **文档版本：** v0.1（初稿，待评审）
> **日期：** 2026-10-27
> **依据：** [遗留契约 C-05、F-01](../legacy/01-contracts.md)，[数据库设计](01-database.md)，[ADR-005](../adr/ADR-005-session-auth.md)、[ADR-006](../adr/ADR-006-frontend-reuse.md)、[ADR-007](../adr/ADR-007-file-storage.md)

本文定义 V2 后端对外提供的 HTTP 接口：通用约定、接口清单、每个接口由哪个模块实现、读写哪些表，以及与旧接口的差异。字段级的请求与响应结构，以本文引用的旧契约为准；实现阶段再整理成 OpenAPI 文件（`docs/api/openapi.yaml`），并在 CI 中校验实现与文档一致。

---

## 1. 通用约定

### 1.1 三类接口

| 类别 | 调用方 | 路径 | 认证 | 兼容要求 |
| --- | --- | --- | --- | --- |
| **浏览器接口** | 复用的练习前端、Hub 页面 | `/api/...` | Cookie 会话（ADR-005） | 沿用旧接口的路径与语义（F-01），只做必要调整 |
| **设备接口** | Windows / Android 客户端 | `/api/connect/...`、`/api/ai/v1/...`、`/api/packs/{id}/local-import` 等 | `Authorization: Bearer qfd_...` | **路径、参数、响应、错误码完全不变**（C-05） |
| **内部接口** | Java ↔ rule-runner | 内网 | 不对外暴露 | 见《rule-runner 调用协议》（下一份设计文档） |

浏览器接口与前端同源，前后端同步发布，因此**不在路径中加版本号**（例如 `/api/v1`）。设备接口面对已发布、无法强制升级的客户端，保持旧路径不变；以后若需要不兼容的修改，新增带版本的路径，旧路径继续保留。

### 1.2 请求与响应

- 请求和响应均为 JSON（`Content-Type: application/json; charset=utf-8`），上传 `.qbank` 和读取文件除外。
- 时间使用 ISO 8601 UTC 字符串，例如 `2026-10-27T09:00:00.000Z`。
- 动态接口统一返回 `Cache-Control: no-store`；文件类响应的缓存规则见 ADR-007。
- 请求体大小上限：普通写请求 8 MiB（与旧系统一致，作答可能包含内嵌图片），导入 `.qbank` 64 MiB。由 Nginx 和 Spring 双重限制。

### 1.3 错误格式

沿用旧格式，前端无需修改错误处理：

```json
{ "error": { "code": "CONFLICT", "message": "作答已在其他页面更新，请刷新后重试" } }
```

- `code`：稳定的机器可读错误码，前端据此分支处理。
- `message`：可直接展示给用户的中文说明。**不包含**堆栈、SQL 或内部路径。
- 500 错误统一返回 `INTERNAL_ERROR`，并在响应头 `X-Request-Id` 中给出请求编号，日志中用同一编号记录详情，便于排查。

常用状态码：

| 状态码 | 含义 |
| --- | --- |
| 400 | 请求格式错误、字段校验失败 |
| 401 | 未登录或会话失效 |
| 403 | 已登录但无权操作（包括来源校验失败） |
| 404 | 资源不存在，**或属于其他用户**（不泄露其存在性） |
| 409 | 并发冲突或状态不允许（见第 4 节错误码表） |
| 413 | 请求体过大 |
| 422 | 题库、拓展或规则校验失败 |
| 429 | 频率限制 |
| 503 | 依赖暂不可用（例如 rule-runner），可稍后重试 |

### 1.4 并发与幂等（沿用 F-01）

所有改变作答状态的写请求都在**请求体**中携带：

| 字段 | 作用 | 服务端处理 |
| --- | --- | --- |
| `requestId` | 幂等键，每次用户操作生成一个，重试时不变 | 查 `practice_request_receipt`：相同请求返回原结果；相同 ID、不同内容返回 400 `REQUEST_ID_REUSED` |
| `revision` | 客户端看到的作答版本 | 条件更新，不匹配返回 409 `CONFLICT` |
| `contentVersion` | 客户端看到的题目内容版本 | 不匹配返回 409 `CONTENT_CONFLICT`。在线版题库内容不可变，正常情况下不会发生，保留用于兼容 |

读题支持条件请求：请求头 `X-QuizForge-Question-Stamp` 中的版本戳未变化时，返回 `{unchanged:true, stamp}`，跳过投影计算。

### 1.5 安全

- **认证：** 浏览器接口从 Cookie 读取会话；设备接口从 `Authorization` 读取设备令牌；两者互不通用（ADR-005）。
- **来源校验：** 浏览器接口的所有写请求（POST / PUT / DELETE）必须带 `Origin` 头，且等于站点来源，否则返回 403。
- **归属校验：** 路径中的每个 ID（题库实例、考试、历史记录）都在 SQL 中与当前 `user_id` 一起查询；查不到一律返回 404。
- **频率限制：** 沿用旧系统的分组限制（例如练习接口每用户每分钟 240 次，导入每分钟 6 次），超出返回 429。第一阶段为单实例进程内计数；以后多实例部署时再改为共享计数。
- **不返回原始题目数据：** 前端拿到的永远是 `project` 投影后的数据。`content_question.data` 中的标准答案只在服务端使用。

### 1.6 集合标识

旧前端的接口以 `/api/collections/{kind}/{id}` 定位一个“集合”：

| `kind` | `id` | V2 对应 |
| --- | --- | --- |
| `bank` | 题库实例 ID | `practice_collection.id`（迁移数据沿用旧实例 ID 生成的 UUID，旧路由 `g.<id>` 记录在 `legacy_route_id` 中） |
| `extension` | 拓展示例 | **在线版不提供**，返回 404。示例题库保留在拓展包文件中，随导出的 `.qbank` 一起带走，在本地调试 |
| `exam` | 考试 ID | `exam_exam.id` |

---

## 2. 浏览器接口清单

“模块”列表示由哪个后端模块实现（ADR-001）；“差异”列说明与旧接口相比的变化，留空表示行为一致。

### 2.1 账号与会话（identity）

| 方法与路径 | 说明 | 差异 |
| --- | --- | --- |
| `POST /api/auth/register` | 注册（邮箱 / 手机验证码、邀请码、人机验证按策略启用） | 来自旧 Java Hub |
| `POST /api/auth/login` | 密码或验证码登录，成功后写入会话 Cookie | 旧本地版的“远程访问密码登录”不再提供 |
| `POST /api/auth/email-code`、`/phone-code`、`/login-code` | 发送验证码 | |
| `POST /api/auth/logout` | 删除当前会话 | |
| `GET /api/me` | 当前用户信息 | |
| `POST /api/account/contact-code`、`/api/account/contact` | 绑定或更换联系方式 | |

### 2.2 题库与练习（practice / content）

| 方法与路径 | 说明 | 主要读写的表 | 差异 |
| --- | --- | --- | --- |
| `GET /api/catalog` | 我的题库列表 | `practice_collection`、`content_bank` | 只返回当前用户的实例；`extensions` 返回空列表，不列出拓展示例 |
| `GET /api/library/index` | 题库分组视图 | 同上 | |
| `POST /api/library/import` | 上传 `.qbank` 导入（`Content-Type: application/vnd.quizforge.qbank`） | `content_*`、`practice_collection` | 包内拓展须在审核白名单中（C-04），否则返回 422 并提示提交审核 |
| `POST /api/library/manage` | 改名、移动分组、删除题库实例 | `practice_collection` | 删除为软删除，不进入回收站流程 |
| `POST /api/library/export` | 导出为 `.qbank` | `content_*`、`quota_traffic` | 计流量（沿用旧云端规则） |
| `GET /api/collections/{kind}/{id}` | 集合概览：题目列表、各题状态 | `content_question`、`practice_question_state` | |
| `GET /api/collections/{kind}/{id}/questions/{qid}` | 读题（投影 + 状态 + 白板 + 版本戳） | `content_question`、`practice_question_state`、`practice_whiteboard` | 投影走缓存（数据库设计 4.2 节） |
| `GET .../questions/{qid}/stamp` | 只读版本戳 | `practice_question_state` | |
| `POST .../questions/{qid}/actions` | `draft` / `submit` / `review` / `retry` / `whiteboard` | `practice_question_state`、`practice_whiteboard`、`practice_round*`、`practice_request_receipt` | 流程见数据库设计 5.1、5.2 节 |
| `GET /api/collections/{kind}/{id}/summary` | 本轮汇总 | `practice_question_state`、`practice_context` | |
| `POST .../finish` | 完成本轮，冻结历史 | `practice_round`、`practice_context` | |
| `POST .../restart` | 再练一次 | 同上 | |
| `GET .../history`、`GET .../history/{historyId}` | 历史列表与快照 | `practice_round`、`practice_round_question` | 冻结页面通过拓展摘要取回，不再读取复制的页面文件 |
| `DELETE .../history/{historyId}` | 删除历史记录 | `practice_round` | 软删除 |

### 2.3 考试（exam）

路径前缀：`B=/api/collections/bank/{id}`，`E=/api/collections/exam/{examId}`（遗留契约 V-3，参照 `EXAM_MODE.md`）。

| 方法与路径 | 说明 |
| --- | --- |
| `GET B/exams` | 列出该题库的考试 |
| `POST B/exams` | 开始考试 `{requestId, durationMinutes}`，幂等创建 |
| `GET E` | 考试大纲、状态、截止时间，以及服务器当前时间（用于校准倒计时） |
| `GET E/exam`、`POST E/exam` | 查询状态 / 交卷（幂等）；已锁定的考试只能重试评分 |
| `DELETE E/exam` | 删除未完成的考试 |
| `GET E/questions/{qid}`、`POST E/questions/{qid}/actions` | 考试中的读题与保存：只允许 `draft` 和 `whiteboard`，不允许单题提交与重做 |
| `GET E/questions/{qid}/page` | 本场考试冻结的题目页面 |
| `GET E/resources/{sha256}`、`POST E/resources` | 考试中的图片读取与上传，交卷后禁止上传 |

服务端在每个考试请求中都检查截止时间（数据库设计 5.6 节），不依赖前端倒计时。

### 2.4 资源与页面（resource / content）

| 方法与路径 | 说明 | 差异 |
| --- | --- | --- |
| `POST /api/resources` | 上传作答图片 `{mime, data(base64)}`，4 MiB 上限 | |
| `GET /api/resources/{sha256}` | 读取图片：鉴权后由 Nginx 发送（`X-Accel-Redirect`） | 发送方式改变，对前端透明 |
| `GET /api/collections/{kind}/{id}/listening/{materialId}` | 听力音频：鉴权后由 Nginx 发送，支持 Range | **前端改为直接 `<audio src>`**（ADR-007） |
| `GET /api/extensions/{id}/{version}/page` | 拓展页面资源 `{apiVersion, html, script, style, dependencies}` | 由拓展包摘要定位文件；结果按摘要长期缓存 |
| `GET /api/sdk/{id}/{version}` | SDK 与富文本组件 | 静态版本化资源，可由 Nginx 直接提供 |

### 2.5 AI 辅导（ai，新增，FR-08）

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/collections/{kind}/{id}/questions/{qid}/tutor` | 对当前题目提问 `{requestId, conversationId?, message}`；以流式响应（Server-Sent Events）返回模型回答 |
| `GET /api/collections/{kind}/{id}/questions/{qid}/tutor/{conversationId}` | 读取当前题目的对话（第一阶段只保留短期记录） |
| `GET /api/account/ai`、`POST /api/account/ai/redeem` | AI 余额与兑换（来自旧 Java Hub） |

要点（细节在 AI 模块详细设计中确定）：

- 前端**只发送问题本身**，题目内容、作答状态、重做次数、评分结果都由服务端从数据库读取并组装上下文。前端提供的状态不可信，也不能借此套取答案。
- 未提交时，组装的上下文中不包含标准答案（FR-08）。
- 独立线程池、超时和每用户并发上限，与练习接口隔离。

### 2.6 题库广场（catalog，沿用旧 Java Hub 接口）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/packs` | 搜索、分页、热门排序、我的分享 |
| `GET /api/packs/{id}`、`GET /api/packs/{id}/catalog` | 详情、包内目录 |
| `POST /api/packs`、`PUT /api/packs/{id}`、`DELETE /api/packs/{id}` | 上传、编辑（带 `revision` 冲突检查）、删除 |
| `GET /api/packs/{id}/download`、`POST /api/packs/{id}/download-selection`、`GET .../download-status` | 整包 / 部分下载、下载预检 |
| `POST /api/packs/{id}/comments`、`DELETE /api/comments/{id}`、`POST /api/packs/{id}/report` | 评论与举报 |
| `POST /api/packs/{id}/practice` | **新增**：把公开题库直接导入到“我的题库”（取代旧系统中跨站跳转到练习服务的导入流程） |

### 2.7 管理（admin，沿用旧 Java Hub 接口）

`/api/admin/...` 下的审核、拓展白名单、账号封禁、邀请码、AI 配置、统计、审计等接口，路径与行为沿用旧 Java Hub。所有管理接口都要求管理员身份，并对写操作复查身份和来源。字段级定义在管理模块的详细设计中整理。

### 2.8 本地专用、V2 不提供的接口

`/api/settings/network*`、`/api/settings/development`、`/api/development/*`、`/api/settings/ai*`（本机 AI 配置）、`/api/editor-drafts/*` 与题目编辑接口（`.../editor`、`.../edit`）、`/quizforge-lan-ca.crt`。前端中对应的入口一并删除（ADR-006）。

---

## 3. 设备接口（C-05，保持不变）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /connect/authorize` | 设备授权确认页（浏览器） |
| `POST /api/connect/authorize` | 用户确认授权（浏览器会话） |
| `POST /api/connect/token` | 授权码 + PKCE verifier 换取设备令牌 |
| `GET /api/connect/me` | 设备对应的用户 |
| `GET /api/connect/devices`、`PUT/DELETE /api/connect/devices/{id}` | 设备列表、改名、撤销 |
| `POST /api/connect/logout`、`POST /api/connect/redeem` | 设备退出、兑换 AI 额度 |
| `POST /api/ai/v1/chat/completions` | AI 网关（OpenAI 兼容），只接受设备令牌 |
| `GET /api/packs/{id}/local-import`、`GET /api/packs/{id}/download?revision=&sha256=` | 固定版本导入清单与下载 |
| `GET /download`、`GET /api/downloads`、`GET\|HEAD /download/files/{name}` | 软件下载 |

实现前须从旧源码逐个整理请求与响应字段（遗留契约 V-1），并用**录制的旧接口响应**作为契约测试的期望值：同一请求发给 V2，响应结构必须一致。

---

## 4. 错误码表（主要）

| 错误码 | 状态码 | 场景 |
| --- | --- | --- |
| `UNAUTHENTICATED` | 401 | 未登录、会话过期 |
| `FORBIDDEN` / `ORIGIN_REJECTED` | 403 | 无权限 / 写请求来源不合法 |
| `NOT_FOUND` | 404 | 不存在或不属于当前用户 |
| `VALIDATION_FAILED` | 400 | 字段校验失败 |
| `REQUEST_ID_REUSED` | 400 | 相同 `requestId` 搭配不同的请求内容 |
| `CONFLICT` | 409 | `revision` 不匹配 |
| `CONTENT_CONFLICT` | 409 | `contentVersion` 不匹配 |
| `SUMMARY_CONFLICT` | 409 | `summaryVersion` 不匹配 |
| `PENDING_REVIEW` | 409 | 有待评分的提交，不能完成本轮 |
| `PRACTICE_NOT_FINISHED` | 409 | 本轮未完成，不能再练一次 |
| `EXAM_COMPLETED` / `EXAM_DELETED` / `EXAM_DEADLINE_PASSED` | 409 | 考试状态不允许该操作 |
| `UNSUPPORTED_LIBRARY_ARCHIVE` | 422 | 题库包不是格式 3 |
| `CLOUD_EXTENSION_REVIEW_REQUIRED` | 422 | 拓展未审核或审核已撤销 |
| `RULE_REJECTED` / `RULE_TIMEOUT` | 422 | 拓展规则拒绝或超时 |
| `RULE_UNAVAILABLE` | 503 | rule-runner 不可用 |
| `PAYLOAD_TOO_LARGE` | 413 | 请求体过大 |
| `RATE_LIMITED` | 429 | 频率限制 |
| `INTERNAL_ERROR` | 500 | 未预期错误，附 `X-Request-Id` |

旧前端已经在使用的错误码（`CONFLICT`、`CONTENT_CONFLICT`、`PENDING_REVIEW` 等）**名称保持不变**，前端的处理逻辑无需修改。

---

## 5. 后端代码结构约定

每个模块的接口层遵循相同的分层，便于审查和测试：

```text
practice/
├── api/            Controller：参数绑定、校验、调用 Service、转换为响应 DTO；不写业务逻辑
├── application/    Service：事务边界（@Transactional）、业务流程、调用其他模块的公开接口
├── domain/         领域对象与规则：状态流转（unanswered → draft → submitted）、版本号检查
├── infrastructure/ MyBatis Mapper、rule-runner 客户端等外部依赖
└── PracticeFacade  对其他模块公开的接口（ADR-001）
```

- 请求与响应使用专门的 DTO（Java `record`），**不直接把数据库实体返回给前端**，避免意外暴露字段（例如题目原始数据）。
- 参数校验使用 Bean Validation（`@Valid`、`@NotNull`、`@Size` 等）。
- 统一的 `@RestControllerAdvice` 把异常转换为第 1.3 节的错误格式。

---

## 6. 测试要求

| 类型 | 内容 |
| --- | --- |
| 接口集成测试 | 每个接口至少覆盖：成功、未登录（401）、访问他人数据（404）、校验失败（400）；写接口另覆盖冲突（409）与幂等重放 |
| 契约测试 | 设备接口：与录制的旧响应比对；浏览器接口：Playwright 端到端测试验证前端可正常工作（ADR-006） |
| 文档一致性 | 实现后生成 OpenAPI 文档，CI 中检查与 `docs/api/openapi.yaml` 一致 |

---

## 7. 待定事项

| 编号 | 事项 |
| --- | --- |
| API-1 | AI 辅导的流式协议（SSE 还是普通请求轮询）、对话保留时长与上下文组装规则 —— AI 模块详细设计 |
| API-2 | 旧练习站链接（`practice.treenewbee.cloud/cloud/practice/<id>/`）在切换后如何跳转到新地址 |
| API-3 | 设备接口与管理接口的字段级定义（遗留契约 V-1） |
| ~~API-4~~ | ~~拓展示例是否保留~~ **已决定**：示例文件保留在拓展包中（审核与导出需要），在线版不展示、不可练习；前端隐藏示例入口 |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
