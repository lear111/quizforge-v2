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
- 请求体大小上限：普通写请求 8 MiB（与旧系统一致，作答可能包含内嵌图片）；导入 multipart 的 `file` 上限 128 MiB，请求总量上限 129 MiB（含 multipart 封装）。由 Nginx 和 Spring 双重限制；应用复制上传流时也计数。

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
- **频率限制：** 练习接口每用户每分钟 240 次；导入每用户滑动 60 秒最多 5 次（失败的已接纳请求也计数），超出返回 429 `RATE_LIMITED`。第一阶段为单实例进程内计数，以后多实例部署时再改为共享计数。导入全局并发默认 2，满时立即返回 503 `IMPORT_BUSY`，不等待接收并解析 multipart。
- **不返回原始题目数据：** 前端拿到的永远是 `project` 投影后的数据。`content_question.data` 中的标准答案只在服务端使用。

### 1.6 集合标识

旧前端的接口以 `/api/collections/{kind}/{id}` 定位一个“集合”：

| `kind` | `id` | V2 对应 |
| --- | --- | --- |
| `bank` | 题库实例 ID | `practice_collection.id`（迁移数据沿用旧实例 ID 生成的 UUID，旧路由 `g.<id>` 记录在 `legacy_route_id` 中） |
| `extension` | 拓展示例 | **在线版不提供**，返回 404。示例题库保留在拓展包文件中，随导出的 `.qbank` 一起带走，在本地调试 |
| `exam` | 考试 ID | `exam_exam.id` |

---

### 原文件接收与后台校验（FR-11 v1.16）

- `POST /api/library/uploads?filename=题库.qbank`：正文为 `application/octet-stream` 原始字节，成功立即返回 `202 {packId,revision}`。登录、次数及声明长度检查先于读取正文；实际流也逐块计数。可用 `packId` 在未通过的私人条目内提交新版本。
- `GET /api/library/uploads/{id}/revisions/{revision}`：仅所有者可读，返回 `{packId,revision,status,reason}`。状态为 `pending / approved / rejected`；排队、校验中、基础设施重试及处理失败均对用户显示待审核。白名单撤销在下一个请求生效。
- `DELETE /api/library/uploads/{id}`：仅所有者可删；取消任务、使旧代次失效，释放尚未关联练习实例的原文件配额。
- `POST /api/catalog/packs` 与 `POST /api/catalog/packs/{id}/revisions` 保留既有 multipart 字段与 `201 {packId,revision}` 响应，接收完成后立即入队。普通公开版本仍需管理员审核；管理员上传通过校验后可自动批准，但绝不恢复已经拒绝或撤销的拓展。
- `GET /api/admin/import-jobs` 返回待处理、正在处理及处理失败的任务；`POST /api/admin/import-jobs/{id}/retry` 重试仍属于最新有效版本的失败任务。只允许未封禁管理员。
- 后台规则调用携带低优先级并保留前台舱壁名额；本实例检测到前台规则调用时，及其结束后 250 ms 内，暂缓领取/推进导入。规则批次之间与业务落库前再次检查，让行不消耗基础设施重试次数。持续的前台负载可能延长排队时间。
- 默认单包 128 MiB、每用户每日接收尝试 20 次；日期按北京时间计算。中断尝试计次数，不建立条目、不占配额；超过次数返回 `429 UPLOAD_LIMIT`，提示“请稍后再试”。私人包按原文件大小预留个人配额，公开包不计个人配额（作者确认，2026-11-02）；配额与版本、任务在同一短事务内写入，超额全部回滚。
- 主页 `/library/` 为“我的题库”，练习空间删除上传入口，提示从资源库导入。旧同步 `POST /api/library/import` 已退役；脚本与测试同样通过原文件上传（202）→后台队列→审核通过→创建练习实例，不再提供兼容入口。

### 我的题库与导入练习空间（第二切片）

| 方法与路径 | 行为 |
| --- | --- |
| `GET /api/library/packs?after=<id>` | 当前用户未删除的私人包与公开发布；按 ID 倒序，每页 50 条，返回 `{items}`；不列出他人的私人包 |
| `GET /api/library/packs/{id}` | 所有提交版本、每版审核结果/原因与题库目录；`originalAvailable=false` 显示“原文件不可用” |
| `GET /api/library/packs/{id}/revisions/{revision}/archive` | 所有者下载原文件，Java 只授权，Nginx `X-Accel-Redirect` 传输；无原文件为 `409 ORIGINAL_UNAVAILABLE`；不重新打包、不计下载流量 |
| `POST /api/library/packs/{id}/practice` | `{revision,banks:[bank_uuid…],group?}`；仅已审核通过的资源；每次都创建独立实例，返回 `{collections:[{id,title,bankId}]}`；同分组重名自动加后缀 |
| `POST /api/library/packs/{id}/publish` | `{revision,rights,title,summary,description,category,tags}`；私人资源直接引用原文件并创建公开包入队；不再上传/解包；公开版本仍需审核（管理员通过校验可自动批准） |

- 公开详情使用的 `POST /api/plaza/packs/{id}/imports` 同样改为每次独立创建，使用当前 `approved_revision`，不再返回已有实例。来源包、版本和 UUID 继续用于公开更新；私人资源实例不参与公开版本更新。
- 创建实例只查询包版本、已存的题库目录和拓展白名单；不读题目、不读取文件、不调用 rule-runner。目录锁保证并发重名处理；整个创建过程在一个短事务中。
- 私人版本的配额只保留一份：`quota_private_revision` 的资源标志和实例计数在用户 `quota_account` 锁内更新，资源或任一实例仍存在时继续计费；最后一个引用删除时释放。不同私人上传版本仍各自计费；公开包免费。
- V24 对既有私人实例逐本补建资源条目，转移既有逻辑用量，保留作答；没有原始文件的版本使用 `source=legacy`、`blob_sha256=NULL`，不能下载或直接发布，但可从已存内容创建新实例。
- 后台队列接口增加全量 `{counts:{queued,running,failed}}`，`items` 仍有 200 条上限；概况增加 `uploads:{today,topUsers:[{userId,name,uploads}]}`，以北京时间统计接收尝试（含中断），只向管理员开放。

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
| `POST /api/library/manage` | 改名、移动分组、删除题库实例 | `practice_collection` | 实例软删除并清除作答、历史、笔记和后台任务，释放私人导入配额；不进入回收站流程 |
| `GET /api/collections/{kind}/{id}` | 集合概览：题目列表、各题状态 | `content_question`、`practice_question_state` | 增加 `update` 字段；没有进行中的作答时先切换到新版本（FR-10 v1.6） |
| `GET /api/collections/{kind}/{id}/questions/{qid}` | 读题（投影 + 状态 + 白板 + 版本戳） | `content_question`、`practice_question_state`、`practice_whiteboard` | 投影走缓存（数据库设计 4.2 节） |
| `GET .../questions/{qid}/stamp` | 只读版本戳 | `practice_question_state` | |
| `POST .../questions/{qid}/actions` | `draft` / `submit` / `review` / `retry` / `whiteboard` | `practice_question_state`、`practice_whiteboard`、`practice_round*`、`practice_request_receipt` | 流程见数据库设计 5.1、5.2 节 |
| `GET /api/collections/{kind}/{id}/summary` | 本轮汇总 | `practice_question_state`、`practice_context` | |
| `POST .../finish` | 异步完成本轮，不生成历史 | `practice_round`、`practice_context` | |
| `POST .../restart` | 完成后异步冻结历史并再练一次 | 同上 | |
| `GET .../history`、`GET .../history/{historyId}` | 历史列表与快照 | `practice_round`、`practice_round_question` | 冻结页面通过拓展摘要取回，不再读取复制的页面文件 |
| `DELETE .../history/{historyId}` | 删除历史记录 | `practice_round` | 软删除 |

草稿 `action=draft` 成功返回 `{revision, state, stamp}` 增量；前端合并到已加载的题目，不依赖保存接口重取题目投影。声明 `answer-state` 的拓展通过 `data.answerState` 上报显示进度，宿主只按已知小题 ID 与 `unanswered/draft` 白名单处理，随答案和回执同事务保存；其他拓展按答案是否为空回退，小题状态未知。保存草稿不调用 rule-runner，正式提交仍由拓展校验与判分。

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

个人模拟考试（L2）使用 `practice_context.kind=exam`，开考时冻结 `bank_id` 与显示标题，普通练习与各场考试的作答、白板完全独立。`features.exam=false` 返回 403 `FEATURE_DISABLED`。时长为整数 1～1440，省略或 null 为不限时。

- 同一用户的同一题库实例至多有一场 `in_progress` 考试；创建时锁定已有且归属匹配的实例行，在锁内重查 requestId 与进行中考试。不同 requestId 并发创建只有一个成功，其余返回 409 `EXAM_IN_PROGRESS`（中文提示）；同一 requestId 仍幂等重放。评分中考试不阻止开始新场。
- 考试元数据使用 `status: in-progress / grading / completed`，包含 `id`、`bankKind:bank`、`bankId`（来源实例 ID）、`startedAt`、`deadline`（可空）、`serverTime`、`durationMinutes`、`version`、`historyId`、后台任务进度与 `gradingErrors`。概览在 `exam` 中附带同一结构；列表 `{records:[...], defaultDurationMinutes}` 只列进行中和待处理评分的考试；默认时长取本题库最近一次考试（包括已完成或已删除），`null` 表示不限时，没有考试记录时为 60 分钟。
- `POST E/exam {requestId}` 只登记后台任务，规则判分不占用请求线程或数据库事务。用户交卷与到期扫描共用 context 锁和带 status/version 的条件更新；重复交卷不创建第二个任务或轮次。评分失败保留固定答案，使用新 requestId 重试；重放旧 requestId 返回原回执。
- 作答中仅允许 draft/whiteboard；单题 submit/retry/review/AI 返回 409 `EXAM_ACTION_FORBIDDEN`。已交卷后草稿、白板、上传返回 409 `EXAM_LOCKED`；截止后写入返回 409 `EXAM_DEADLINE_PASSED`。归属及截止检查均在短事务锁定 context 后复查，不能通过普通 bank 路由传考试 ID 绕过限制。
- 交卷后可人工评分；任务正在修改状态时返回现有 `PRACTICE_BUSY`，待任务结束后可重试。**已完成考试也可人工改分**，与题目状态、冻结单题快照及历史汇总在同一事务更新（作者确认，覆盖旧版已完成考试全部只读的规则）。历史详情附 `examId`，页面提供“核对考试评分”入口。AI 能力保持关闭，预留操作返回 501。
- 全部判分完成后自动按 position 键集分页冻结完整历史，`mode=exam`、`snapshotVersion=1`；未作答按 0 计入总分，主观题 pending/null 不阻止完成。历史列表只返回摘要，详情才加载快照。
- 删除进行中或待评分考试返回 `{deleted:examId}`，重复删除相同响应；保留删除标记，丢弃在途评分。已完成返回 409 `EXAM_COMPLETED`，应从历史删除；重放已删除考试的创建请求返回 409 `EXAM_DELETED`。


### 2.4 资源与页面（resource / content）

| 方法与路径 | 说明 | 差异 |
| --- | --- | --- |
| `POST /api/resources` | 上传作答图片 `{mime, data(base64)}`，4 MiB 上限 | |
| `GET /api/resources/{sha256}` | 读取图片：鉴权后由 Nginx 发送（`X-Accel-Redirect`） | 发送方式改变，对前端透明 |
| `GET /api/collections/{kind}/{id}/listening/{materialId}` | 本地听力音频：鉴权后由 Nginx 发送，支持 Range | 本地 file 使用 `<audio src>`（ADR-007）；网络 url 由宿主匿名下载为 Blob（格式 4 v0.8 §4.2.1），该路径返回 422 AUDIO_NETWORK_SOURCE，不做代理 |
| `GET /api/extensions/{id}/{version}/page` | 拓展页面资源 `{apiVersion, html, script, style, dependencies}` | 由拓展包摘要定位文件；结果按摘要长期缓存 |
| `GET /api/sdk/{id}/{version}` | SDK 与富文本组件 | 静态版本化资源，可由 Nginx 直接提供 |

### 2.5 AI 辅导（ai，FR-08；详细设计 08 第 3 节，2026-11-03 修订）

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/ai/tutor/sessions` | 新建会话 `{title?}` → `{id, title, createdAt}` |
| `GET /api/ai/tutor/sessions?cursor=` | 我的会话（按更新时间倒序，键集分页） |
| `PATCH /api/ai/tutor/sessions/{id}` | 改名 `{title}` |
| `DELETE /api/ai/tutor/sessions/{id}` | 删除会话（软删除） |
| `GET /api/ai/tutor/sessions/{id}/messages?before=` | 历史消息（按 seq 倒序，键集分页；不返回 tool 消息的原文） |
| `POST /api/ai/tutor/sessions/{id}/messages` | 发送 `{requestId, contextId?, questionId?, text}`，响应为 SSE |
| `GET /api/account/ai`、`POST /api/account/ai/redeem` | AI 余额与兑换（L5.1） |

SSE 事件：`message`（`{userMessageId}`，请求已接受）→ 若干 `delta`（`{text}`）→ 可选 `tool`（`{name, status}`，只告诉前端“正在查询答案”等，不含工具结果）→ `done`（`{assistantMessageId, inputTokens, outputTokens, cost, balanceAfter}`）；出错时 `error`（`{code, message}`）后结束。客户端断开时服务器继续读完上游并计费（L5.2）。

要点：前端**只发送问题文字与当前题目的标识**，题目内容、作答状态、评分结果都由服务端读取；未提交时上下文和工具都拿不到答案；同一 requestId 重放不重复调用模型。原待定项 API-1 已确定。

### 2.6 题库广场（catalog，沿用旧 Java Hub 接口）

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/packs` | 搜索、分页、热门排序、我的分享 |
| `GET /api/packs/{id}`、`GET /api/packs/{id}/catalog` | 详情、包内目录 |
| `POST /api/packs`、`PUT /api/packs/{id}`、`DELETE /api/packs/{id}` | 上传、编辑（带 `revision` 冲突检查）、删除 |
| `GET /api/packs/{id}/download`、`POST /api/packs/{id}/download-selection`、`GET .../download-status` | 整包 / 部分下载、下载预检 |
| `GET /api/plaza/packs/{id}/comments` | 评论列表，匿名可读；`cursor` 键集分页，返回 `{items:[{id,userId,author,body,createdAt,canDelete}],nextCursor}` |
| `POST /api/plaza/packs/{id}/comments` | 登录后评论 `{body}`；去首尾空白后 1～2000 字，每用户每分钟 10 次；201 `{comment}` |
| `DELETE /api/plaza/comments/{id}` | 评论作者本人或管理员删除，204；题库发布者不能删除他人评论 |
| `POST /api/plaza/packs/{id}/report` | 举报 `{reason}`，去首尾空白后 1～1000 字，每用户每分钟 5 次；201 `{ok:true,id}`；同用户同包只保留一条待处理举报 |
| `POST /api/plaza/packs/{id}/imports` | 从公开详情或收藏创建独立练习实例 `{banks:[bank_uuid…]}`，不产生他人资源条目 |

### 2.7 管理（admin，沿用旧 Java Hub 接口）

`/api/admin/...` 下的审核、拓展白名单、账号封禁、邀请码、AI 配置、统计、审计等接口，路径与行为沿用旧 Java Hub。所有管理接口都要求管理员身份，并对写操作复查身份和来源。字段级定义在管理模块的详细设计中整理。

举报处理（题库广场 4/4）：

- `GET /api/admin/catalog/reports?cursor=…`：未处理优先，再按创建时间和 ID 倒序；每页 20 条，返回 `{items,nextCursor}`。
- `GET /api/admin/catalog/reports/{id}`：举报原因、题库与举报人、处理记录；仅管理员可读。
- `POST /api/admin/catalog/reports/{id}/resolve`：`{decision:"removed"|"dismissed"|"handled",note}`；处理说明去首尾空白后 1～1000 字。下架复用删除原资源的逻辑，不影响已导入练习实例；同时写审计并向举报人发站内通知。相同处理重放不重复通知，不同处理返回 409 `REPORT_ALREADY_HANDLED`。
- `DELETE /api/admin/catalog/comments/{id}`：管理员删除任意评论并写审计，204。
- `GET /api/admin/catalog/comments?cursor=…`：全部评论的时间倒序键集列表，含未被举报和已下架包的评论；每页 20 条，返回 `{items,nextCursor}`。

评论和举报仅面向已审核且未删除的公开包，封禁用户禁止写入。限频返回 429 `FEEDBACK_RATE_LIMIT`；长度或空内容返回 400 `VALIDATION_FAILED`；不存在或不可见的评论/包返回 404。列表使用纯文本，不解释 HTML。

### 2.8 本地专用、V2 不提供的接口

`/api/settings/network*`、`/api/settings/development`、`/api/development/*`、`/api/settings/ai*`（本机 AI 配置）、`/api/editor-drafts/*` 与题目编辑接口（`.../editor`、`.../edit`）、`/quizforge-lan-ca.crt`。前端中对应的入口一并删除（ADR-006）。

---


### 练习空间目录管理（L1.7）

依据 FR-11 v1.14 / 设计 04 §6.0，主页“我的题库”是资源库，练习空间只管理练习实例。练习空间不提供导出；资源下载应由“我的题库”返回上传的原始 `.qbank`，资源库与原文件下载见上文“我的题库与导入练习空间”。练习空间已移除上传入口；旧同步接口仅保留脚本兼容。

目录管理代码位于 `practice.management`；V20 仅新增 `practice_directory_revision` 与 `practice_group`，不保存供重新打包使用的作者文档。现有 `POST /api/library/manage` 路径为前端兼容保留，不代表新增 library 资源模块。

- `GET /api/catalog` 返回 `managementRevision`（当前用户的目录版本）；每个 bank 的 `path` 为稳定的实例 ID，不随显示名称或分组变化。
- `POST /api/library/manage` 请求：`{kind:"bank",target:"item"|"group",action:"create"|"update"|"delete",path,name,group,revision}`。`revision` 必须为打开管理对话框时的 `managementRevision`。分组支持新建空文件夹；实例不提供 create，单本删除仍走 `DELETE /api/collections/bank/{id}`。
- 修改实例时 `path` 是实例 ID，`name` 是显示名称（trim 后 1～200 个字符），`group` 为目标分组名，空字符串或 null 为根目录。分组不存在时创建。只修改显示元数据，不改变作者 UUID、题目、作答或历史。
- 修改分组时 `path` 为原分组名、`name` 为新分组名；新建分组只传 `name`。分组名沿用旧宿主 `library-management.js:folderName`：最长 120 个 UTF-16 单元，非空，不能以点开头、空格或点结尾；禁用 `\ / : * ? " < > |`、U+0000～001F、U+007F～009F；禁用 Windows 设备名（含后缀）和 `bank.json`、`manifest.json`、`development.json`（忽略大小写）。根目录不是可改名/删除的分组。
- 用户自己的题库空间允许同一 UUID 的多个实例（同组、跨组均可），不同内容版本也不会互相覆盖。相同内容仍复用内容行；同组显示名称重名时导入自动加后缀。资源与实例共存时的配额释放按设计 04 §6.0，由资源库接入时衔接；本切片的过渡上传与删除沿用现有记账服务。实例目录管理不承担资源库或题库广场的 UUID 校验。
- 同一分组内实例名称忽略大小写比较（保留重音差别），重名返回 409 `NAME_TAKEN`；分组已存在返回 409 `GROUP_EXISTS`；目录版本过期返回 409 `LIBRARY_CONFLICT`，前端提示并刷新目录。
- 删除分组返回 `{deleted:true,deletedCount,remaining,partial,message}`。每本题库独立事务，部分失败返回 HTTP 200 且 `partial:true`、`remaining>0`；客户端刷新目录版本后可继续删除。已完成的删除不回滚。进行中的完成/归档任务沿用单本删除规则：锁住对应上下文后删除任务及其关联数据，过期工作线程无法重新写入。

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

请求与响应字段见[遗留契约 C-05](../legacy/01-contracts.md)（V-1，2026-11-05 补全）。契约测试 `ClientContractIntegrationTest`（接口）与 `client-compat.spec.mjs`（经过 Nginx）以它为准；没有录制旧服务的响应（不连接旧平台），期望值来自旧 Hub 与旧客户端的源码和测试。

实现说明（L6，模块 `client`）：

- **认证：** `/api/connect/{token,me,redeem,logout}`、AI 网关只看 `Authorization` 头，Cookie 无效，不做 Origin 校验；确认授权与设备列表只认浏览器会话并校验 Origin；其他网页接口不识别设备令牌。
- **授权请求**保存在 `auth_connect_request`（旧平台在内存中），重启后仍然有效。
- **AI 网关**复用 `AiGateway`：L5.2 的错误码映射回旧契约（`AI_UNAVAILABLE`、`AI_KEY_REJECTED` → 503 `AI_DISABLED`；`AI_SITE_BUSY`、`AI_UPSTREAM_BUSY` → 429 `AI_BUSY`；`AI_UPSTREAM_REJECTED` → 502 `AI_UPSTREAM_FAILED`；`AI_REQUEST_INVALID` → 400 `AI_REQUEST_UNSUPPORTED`）。
- **固定版本下载**走 `X-Accel-Redirect`，客户端看到的仍是 200 与原始字节；匿名流量预算记在 `client_download_traffic`。

与旧平台的差异（已知且不影响已发布客户端的判断逻辑）：

| 项目 | 旧平台 | V2 |
| --- | --- | --- |
| 余额 | 扣到 0 为止，`cost` 是实际扣的数 | 按用量如实扣费，余额可为负（设计 08 第 1.2 节）；客户端接口把负余额显示为 0，`cost` 是实际费用 |
| 上游超时 | 每个 Key 90 秒 | `qf.ai.call-timeout`（默认 40 秒） |
| `response_format: json_schema` | 原样转发 | 按 JSON 输出处理，不把 schema 发给上游（官方额度的客户端只用 `json_object`） |
| 响应 `choices` | 上游原样 | 由模型回复重新组装（`index`、`message`、`finish_reason`） |
| 图片 | 任何角色的消息都转发 | 只接受 user 消息中的图片（已发布客户端只这样发送） |
| 封禁 | 封禁期间令牌失效，解封后恢复 | 封禁时撤销（L4），解封后需要重新授权 |
| 未登录的授权页 | 303 到 `/manage/#connect-login/<key>` | 303 到 `/?returnTo=…`（由浏览器跟随，客户端看不到） |
| 浏览器写接口的来源错误 | 403 `CONNECT_REQUEST_REJECTED` | 403 `ORIGIN_REJECTED`（只有本站页面调用） |

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
| `PRACTICE_COMPLETED` | 409 | 本轮已完成，不能单题重做；仍允许人工评分 |
| `REVIEW_UNAVAILABLE` | 409 | 当前轮次没有该题提交快照，或题型不支持人工评分 |
| `EXAM_COMPLETED` / `EXAM_DELETED` / `EXAM_DEADLINE_PASSED` | 409 | 考试状态不允许该操作 |
| `UNSUPPORTED_LIBRARY_ARCHIVE` | 422 | 题库包不是格式 3 |
| `CLOUD_EXTENSION_REVIEW_REQUIRED` | 422 | 拓展未审核或审核已撤销 |
| `RULE_REJECTED` / `RULE_TIMEOUT` | 422 | 拓展规则拒绝或超时 |
| `RULE_UNAVAILABLE` | 503 | rule-runner 不可用 |
| `PAYLOAD_TOO_LARGE` | 413 | 请求体过大 |
| `RATE_LIMITED` | 429 | 频率限制 |
| `INTERNAL_ERROR` | 500 | 未预期错误，附 `X-Request-Id` |

旧前端已经在使用的错误码（`CONFLICT`、`CONTENT_CONFLICT`、`PENDING_REVIEW` 等）**名称保持不变**，前端的处理逻辑无需修改。

L1.3/L1.4 按验收清单恢复顺序限制：先 finish 完成，再 restart 归档并重置；未完成返回 409 PRACTICE_NOT_FINISHED。两者均匹配 roundId / summaryVersion。L1.1 / L1.2 的请求和响应字段详见[前端接口映射](07-frontend-api-map.md#l11--l12重做人工评分汇总与再练一轮)。

---


### L1.3 / L1.4 异步完成与历史（2026-11-01）

`C=/api/collections/bank/{id}`，身份只取 Cookie 会话。

| 请求 | 成功响应 | 行为 |
| --- | --- | --- |
| POST C/finish | 202 `{jobId,status,total,completed}` | body `{requestId,roundId,summaryVersion}`；立即锁作答与重做，事务外逐题提交已保存答案；未作答不判分 |
| POST C/restart | 202，同上 | 同形 body；仅 completed 允许；冻结全库最后状态（含未作答与两层白板）后，原子发布历史并重置 |
| GET C/jobs/{jobId} | 200 `{jobId,kind,status,total,completed,result?,error?}` | 状态 queued/running/completed/failed；只读一条所属用户的任务记录；客户端可刷新恢复进度 |
| GET C/history | 200 `{records:[摘要]}` | 不含完整题目或快照；仅已归档且未删除的历史 |
| GET C/history/{historyId} | 200 冻结 collection / questions / pages / summary | snapshotVersion=1；历史模式；不重新执行规则或读当前题目 |
| POST C/history/{historyId}/questions/{qid}/annotations | 200 `{whiteboard}` | `{requestId,revision,notes}`；只追加，独立版本，合计 2 MiB，冲突 409 |
| GET/HEAD C/history/{historyId}/listening/{materialId} | Nginx 本地音频 | 来源为冻结配置与历史资源授权，不依赖当前题库；网络 url 原样冻结，由浏览器读取，此路径返回 422 AUDIO_NETWORK_SOURCE |
| DELETE C/history/{historyId} | 204 | 所属用户软删除历史、删除追加笔记，资源授权随之失效 |

完成允许 pending/null（按 0 计总分并单列待评分数），之后可人工评分；宿主保留拓展的 score/gradingStatus。失败恢复可编辑，已经成功的提交保留，新的 finish 请求只处理剩余草稿。归档失败恢复 completed，当前状态与白板不清空，未完成的历史不可见。相同 requestId 重放同一 202 与 jobId；已失败任务使用新 requestId 重试。网络断开不取消任务。

写入阶段若正在 finishing/archiving，返回 409 PRACTICE_LOCKED/PRACTICE_BUSY。历史不可改答案、重做或人工评分；只允许追加宿主笔记。历史中被管理员移除的拓展显示替代提示，其余冻结信息仍可读取。


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
| ~~API-1~~ | ~~AI 辅导的流式协议~~ **已确定**：SSE，见 2.5 与设计 08 第 3 节 |
| API-2 | 旧练习站链接（`practice.treenewbee.cloud/cloud/practice/<id>/`）在切换后如何跳转到新地址 |
| API-3 | 设备接口与管理接口的字段级定义（遗留契约 V-1） |
| ~~API-4~~ | ~~拓展示例是否保留~~ **已决定**：示例文件保留在拓展包中（审核与导出需要），在线版不展示、不可练习；前端隐藏示例入口 |

## 8. L1.8 用户配额与删除题库

### GET /api/account/quota

浏览器会话认证；只返回当前用户，响应 `Cache-Control: no-store`：

```json
{
  "storage": {"usedBytes": 1024, "limitBytes": 268435456, "overrideBytes": null},
  "dailyDownload": {"usedBytes": 0, "limitBytes": 268435456, "overrideBytes": null},
  "day": "2026-11-01",
  "resetsAt": "2026-11-01T16:00:00Z"
}
```

字节使用二进制单位。`day` 是北京时间日期；下载量按该日期读取，跨日为零。普通练习、读题、读图和静态资源不扣下载流量。

### PUT /api/admin/users/{id}/quota

会话认证、Origin 校验，并从数据库检查当前操作者的管理员身份。请求：

```json
{"storageBytes": 268435456, "dailyDownloadBytes": null}
```

两个值为非负整数或 `null`（跟随持久化的站点默认值，初始均为 256 MiB）；这是完整替换，省略等同 `null`。返回该用户上述配额结构。调低额度不删除数据、不重置已用量；允许读取、删除及缩减占用。记录操作者、目标用户、前后覆盖值和时间。普通用户 403 `FORBIDDEN`，目标不存在 404 `NOT_FOUND`，负数 400 `VALIDATION_FAILED`。

### DELETE /api/collections/bank/{id}

会话认证、Origin 校验，只允许删除自己的实例。一个事务内删除该实例的练习状态、白板、轮次与题目快照、存档，软删除实例并释放账本占用。共享不可变内容保留待引用感知 GC。成功 204；他人、不存在或已删除的实例均 404 `NOT_FOUND`。

存储增长超额：413 `STORAGE_QUOTA_EXCEEDED`；每日题库文件下载超额：429 `DOWNLOAD_QUOTA_EXCEEDED`。后者由统一记账服务提供，接入以后的题库导出/下载接口；当前没有这两类后端路由。

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
| v0.2 | 2026-10-27 | 题库按本加入、版本更新相关接口 |

### 题库广场浏览（广场 2/4）

- `GET /api/plaza/packs?q=&sort=latest|downloads&cursor=`：公开且未删除的已审核版本；每页 20 条，返回 `{items,next}`，键集游标包含排序值与 ID。最新按包创建时间倒序；热门按下载次数倒序；同值按 ID 倒序。名称、简介、标签按字面子串搜索，不把 `%` / `_` 当通配符。
- `GET /api/plaza/packs/{id}`：`{pack,description,releaseNotes,banks,screenshots,favorite}`；只返回当前已审核版本。
- `GET /api/plaza/packs/{id}/archive`：原始文件，Java 授权后 Nginx 直接传输；授权成功的 GET 原子增加一次下载次数（包括客户端随后中止；失败授权不计）。不计个人下载配额。
- `POST /api/plaza/packs/{id}/imports`：登录，`{banks:[uuid…],group?}`，使用当前已审核版本，复用快速创建实例，返回 `{collections:[{id,title,bankId}]}`；公开包免费、重复调用独立。
- `PUT /api/plaza/packs/{id}/favorite` / `DELETE …/favorite`：会话用户收藏/取消，幂等。`GET /api/library/favorites?after=` 返回当前用户书签 `{items,next}`，删除包保留标题、状态 `deleted`，不再能下载/导入。
- 收藏列表的每项另有 `seenRevision`（收藏者最后确认过的已审核版本）与当前版本的 `releaseNotes`；`revision > seenRevision` 时显示“有新版本”。`PUT /api/plaza/packs/{id}/favorite/seen`：确认已看到当前版本（只改自己的书签，未收藏返回 404）。
- 版本自动切换（设计 04 第 3.5 节）：`GET /api/collections/bank/{id}` 与 `GET /api/library/index` 的 `banks[]` 增加 `update:{status,revision?}`。`status` 为 `current`、`pending`（有新版本，“再练一次”时使用；`revision` 为新版本号）、`removed`（作者已移除）、`deleted`（作者已删除）；私人题库为 `null`。概览请求中能切换时在返回前完成切换，列表只读。
- 匿名浏览、详情、公开截图及下载沿用旧 Hub `PublicHubController.list/detail/archive` 与 `FileDelivery.archive` 的可匿名行为；写操作仍需会话和 Origin。
- 删除旧临时 `POST /api/catalog/packs/{id}/practice`，不保留别名。
- 热门排序的计数会随下载变化，键集分页不提供跨请求数据库快照；不使用 OFFSET。

### 浏览器判分（ADR-011）

默认 `qf.grading.mode=browser`；回退 `server` 保持原同步提交协议。普通练习 `action=submit` 保存并锁定后返回原读题结构（`state.status=submitted`、`state.result.gradingStatus=awaiting_browser`），另有 `browserGrading`：`{questionId,generation,data,answer,package,source}`。只发放当前用户已锁定的本题和已批准的规则，不提供任意原文查询。

- `GET /api/collections/{bank|exam}/{id}/grading/pending?questionId=`：当前用户待浏览器判分的题，`{items:[…]}`，最多 20 道；单题查询必须已锁定且仍等待判分。
- `POST /api/collections/{bank|exam}/{id}/grading/reports`：`{items:[{questionId,generation,outcome:{result,projected,score:{score,maxScore,gradingStatus,outlineStates?}}}]}`。每批 1～20 道、请求 8 MiB；每题输出 2 MiB。返回 `{items:[读题响应…]}`。单题短事务，部分成功后可重放。
- `generation` 同时包含提交代次与评分代次。相同代次相同内容重放不增加 revision；不同内容、重做 / 再练 / 人工改分后的旧代次返回 409 `GRADING_GENERATION_CONFLICT`。无效格式 400 `INVALID_GRADING_REPORT`，批次超限 413 `GRADING_REPORT_TOO_LARGE`。
- 只验证格式、非负数且得分不超过满分、DECIMAL 范围、小题 ID 白名单及大小；分数统一 HALF_UP 四位小数，不验证正确性。未评分保持 null。
- `qf.grading.browser-timeout=PT2M` 后使用现有租约后台任务补判，评分来源分别为 `browser` / `server`。手动考试交卷等待浏览器；关闭页面后的到期扫描走服务器。未作答题归档允许 `project` 缓存未命中，判分计数不包含安全投影。
