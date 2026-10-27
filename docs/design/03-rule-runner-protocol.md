# rule-runner 调用协议

> **文档版本：** v0.1（初稿，待评审）
> **日期：** 2026-10-27
> **依据：** [ADR-003](../adr/ADR-003-extension-rule-execution.md)，[遗留契约 C-03、C-04](../legacy/01-contracts.md)，[数据库设计](01-database.md)
> **参照实现：** `quizforge_web/core/server/rules-runner.cjs`、`server/host/extensions/rule-engine.js` @ `4522bb8`（只作行为参照，不复用代码）

本文定义 Java 后端与规则执行服务（rule-runner）之间的通信协议：怎么调用、传什么、返回什么、出错怎么办，以及 Java 在哪些业务流程中调用哪个操作。

---

## 1. 定位与原则

- rule-runner 只做一件事：**在隔离环境中执行拓展的规则脚本**。它不访问数据库，不知道用户是谁，不保存任何状态。
- 协议是内部协议，Java 和 rule-runner 同仓库、同步发布；但仍带版本号，便于以后独立升级。
- **语义与旧运行器保持一致**（C-03）：同样的输入必须得到同样的输出。为了减少调用次数，V2 可以在一次请求中合并多个步骤，但每个步骤的语义不变。

---

## 2. 部署与传输

| 项目 | 约定 |
| --- | --- |
| 传输方式 | HTTP/1.1 + JSON，Java 端使用 JDK 自带的 `HttpClient`，保持长连接 |
| 网络位置 | Docker Compose 内部网络，只有 `app` 容器能访问；该网络**不能访问外网** |
| 接口 | `POST /v1/execute` 执行规则；`GET /v1/health` 健康检查；`GET /v1/metrics` 运行指标 |
| 内部认证 | 请求头 `X-Internal-Token` 携带共享密钥（通过环境变量配置），作为网络隔离之外的第二道防线 |
| 拓展文件 | rule-runner 以**只读**方式挂载文件存储的 `blobs/` 目录（ADR-007），按 SHA-256 读取规则与 Schema 文件 |

为什么用 HTTP 而不是更高效的 gRPC：调用量很小（每秒几次到几十次），HTTP + JSON 调试最方便，用 `curl` 就能直接测试，不需要额外的代码生成工具。若压测显示序列化开销明显，再评估。

---

## 3. 请求与响应格式

### 3.1 请求

```json
{
  "protocolVersion": 1,
  "requestId": "0192f0c4-…",
  "op": "submit",
  "deadlineMs": 8000,
  "package": {
    "digest": "<审核摘要，十六进制>",
    "apiVersion": { "major": 1, "minor": 5 },
    "outlineItemsDeclared": false,
    "answerStateDeclared": true,
    "rules":          { "sha256": "<规则文件哈希>" },
    "questionSchema": { "sha256": "<题目 Schema 哈希>" },
    "answerSchema":   { "sha256": "<作答 Schema 哈希>" }
  },
  "input": { "data": { }, "answer": { } }
}
```

| 字段 | 说明 |
| --- | --- |
| `protocolVersion` | 协议版本，当前为 1；rule-runner 不支持时返回 `UNSUPPORTED_PROTOCOL` |
| `requestId` | 本次调用的编号，用于日志关联（取自上游 HTTP 请求的 `X-Request-Id`） |
| `op` | 操作名，见第 4 节 |
| `deadlineMs` | 本次调用的总时限，由 Java 指定，rule-runner 按此终止执行 |
| `package` | 拓展包信息。文件**按内容哈希引用**，不按路径引用：rule-runner 读取 `blobs/sha256/..` 后会重新计算哈希，不一致则拒绝执行 |
| `input` | 该操作的输入，结构见第 4 节 |

**为什么文件按哈希引用：** 文件存储是内容寻址的（ADR-007），哈希就是文件的地址。rule-runner 不需要知道拓展的目录结构，也不需要访问数据库。读取后再校验一次哈希，即使存储被篡改也能发现。

**审核白名单由 Java 检查：** Java 在发出请求前确认 `package.digest` 在 `admin_extension_review` 中且状态为 `approved`（C-04）。rule-runner 只负责校验文件哈希，不判断审核状态。

### 3.2 响应

成功（HTTP 200）：

```json
{ "ok": true, "data": { "result": { }, "projected": { } }, "elapsedMs": 12 }
```

规则拒绝或失败（HTTP 200，`ok:false`）：

```json
{ "ok": false, "code": "RULE_REJECTED", "message": "Answer schema validation failed" }
```

- “规则拒绝了输入”是正常的业务结果，所以仍返回 HTTP 200，用 `ok:false` 表示。只有 rule-runner **自身**出了问题时（过载、内部错误）才使用非 200 状态码。这样 Java 能清楚区分“拓展说这个作答不合法”和“服务坏了”。
- `message` 只用于日志，**不直接展示给用户**，也不包含堆栈和文件路径（与旧运行器一致）。

---

## 4. 操作清单

旧运行器的全部操作都保留，语义不变。下表同时说明 V2 中由哪个流程调用。

| `op` | 输入 `input` | 输出 `data` | V2 中谁调用、何时调用 |
| --- | --- | --- | --- |
| `capabilities` | — | `{canAiGrade, canOutlineItems}` | 导入拓展包时调用一次，结果存入 `content_extension_package` |
| `validateBank` | `{questions:[data…], contextResetOffsets?}` | `{valid:true}` | 导入题库时，对使用该拓展的全部题目校验 |
| `outlineBatch` | `{questions:[data…], contextResetOffsets?}` | `{outlineItems:[[{id,label}]…]}` | 导入时，为没有显式 `outline` 的题目生成小题目录（API ≥ 1.1 且声明了 `outline-items`） |
| `scoreBatch` | `{questions:[{data, state}…]}` | `{scores:[{score,maxScore,gradingStatus,outlineStates?}…]}` | **导入时**以“未作答”状态计算每题满分，存入 `content_question.max_score`（见第 6 节） |
| `project` | `{data, state:{submitted:false,result:null}}` | `{projected}` | 读取未提交的题目，**缓存未命中时**才调用 |
| `projectBatch` | `{questions:[{data, state}…]}` | `{projected:[…]}` | 批量预热投影缓存（可选） |
| `validateAnswer` | `{data, answer, outlineAfterValidation?, outlineRevision?}` | `{valid:true, outlineScore?}` | **保存草稿**：校验作答，并计算小题进度（见第 5.2 节） |
| `submit` | `{data, answer, withScore?:true}` | `{result, projected, score?}` | **正式提交**：校验作答 → 评分 → 生成提交后的投影；`withScore` 为 V2 新增，见第 6 节 |
| `review` | `{data, answer, review:{score,feedback?}, state}` | `{result, projected}` | 人工评分（以及 AI 评分结果的采用） |
| `prepareAiGrading` | `{data, answer, state, targetId?, automatic?}` | `{gradingInput}` | AI 评分前生成送给模型的输入（AI 模块） |

`state` 与各字段的含义沿用旧运行器（遗留契约 C-03）。

**V2 新增的唯一变化：** `submit` 增加可选参数 `withScore:true`。它让 rule-runner 在评分后接着执行一次 `getScore`，并在同一次调用中返回 `score:{score,maxScore,gradingStatus,outlineStates?}`。这等价于旧系统中“`submit` 之后再调用一次 `scoreBatch`”，只是少一次往返。`getScore` 必须在**全新的 VM 上下文**中执行，以保持与旧系统一致的隔离语义。

---

## 5. 各业务流程中的调用

### 5.1 读题

```text
未提交的题目：查投影缓存（键 = 题目内容哈希 + 拓展摘要）
              └─ 未命中 → project → 写入缓存
已提交的题目：直接返回 practice_question_state.projection（提交时已存），不调用 rule-runner
```

在线版题库内容不可变，投影缓存永远不会过期，只会因为内存不足被淘汰。

### 5.2 保存草稿

```text
1. validateAnswer(data, answer, outlineAfterValidation = !answerStateDeclared)
2. 校验通过 → 事务内条件更新作答状态，同时写入 outlineScore 中的小题进度
3. ok:false → 400 VALIDATION_FAILED（作答不合法），前端保留输入
```

> **更正 ADR-003：** ADR-003 原写“rule-runner 不可用时保存草稿不受影响”。核对旧运行器后发现，旧系统保存草稿时**同样要执行作答校验**（`validateAnswer`），以保证存入的草稿能被拓展正确恢复。V2 保持这一语义：rule-runner 不可用时，保存草稿返回 503 `RULE_UNAVAILABLE`，**前端保留输入并自动重试**（旧前端已有“当前输入已保留”的处理）。因此 rule-runner 的可用性直接影响保存体验，需要健康检查、自动重启和监控告警（第 8 节）。ADR-003 已同步修改。

### 5.3 正式提交

```text
1. （事务外）submit(data, answer, withScore = true)
       → result、projected、score
2. （事务内）条件更新作答状态：status=submitted、result、projection、score、outline_states
   创建 / 更新练习轮次，写入冻结快照（payload 使用 projected）
```

rule-runner 调用在事务之外（数据库设计 5.2 节）。

### 5.4 导入题库

```text
对包内每个拓展：
  capabilities
对每本题库，按拓展分组：
  validateBank（全部题目）
  outlineBatch（需要时）
  scoreBatch（全部题目，state = 未作答）→ 每题满分
全部通过后，在一个事务中写入 content_* 与 practice_collection
```

导入是低频操作，可以花几秒钟；单次请求超过输入上限（8 MB）时，按题目分批调用。

### 5.5 得分汇总、概览、历史

**不调用 rule-runner。** 每道题的分数、满分、小题状态都已在写入时算好并存入数据库（第 6 节），汇总只需一条 SQL 聚合。

---

## 6. 写时计算：减少 rule-runner 调用

旧系统每次显示得分汇总，都要把整本题库的全部题目和作答送去 `scoreBatch` 重新计算。V2 改为**在数据变化时计算一次并保存**：

| 何时 | 计算什么 | 存到哪里 |
| --- | --- | --- |
| 导入题库 | 每题满分（`scoreBatch`，未作答状态） | `content_question.max_score` |
| 保存草稿 | 小题进度（`validateAnswer` 的 `outlineScore`） | `practice_question_state.outline_states` |
| 提交 / 人工评分 | 得分、小题状态、提交后的投影 | `practice_question_state.score`、`outline_states`、`projection` |

**为什么正确：** `getScore` 与 `project` 都是纯函数，结果只取决于题目数据和作答状态。题目数据不可变，作答状态只在这几个写操作中改变，所以在写入时算出的结果，在下一次写入之前一直有效。

**效果：** 读题（已提交）、概览、汇总、历史都不再需要 rule-runner。rule-runner 只在导入、读未提交题目（且缓存未命中）、保存和提交时被调用。

数据库设计据此新增以下列（`01-database.md` v0.3）：

- `content_question.max_score`
- `practice_question_state.score`、`max_score`、`outline_states`、`projection`

**分数精度：** 拓展返回的分数是 JavaScript 数字（双精度浮点）。为了避免汇总时出现 `0.1 + 0.2 ≠ 0.3` 这类问题，数据库中以 `DECIMAL(12,4)` 保存，汇总用 SQL 的 `SUM` 计算。旧系统用十进制方式求和（`decimalSum`），结果一致。超过 4 位小数的分数如何处理，列为待定项 RR-3。

---

## 7. 隔离、超时与容量

### 7.1 进程模型

沿用旧运行器已验证的模型（ADR-003）：

```text
rule-runner 主进程（HTTP 服务，不执行任何拓展代码）
 └─ 工作进程池，按拓展包分组
     ├─ 包 A：工作进程 ×1～2
     ├─ 包 B：工作进程 ×1～2
     └─ …   全局上限 WORKER_LIMIT（默认 = CPU 核数 × 2）
```

- 每个工作进程以 `--permission` 启动，只允许读取**该拓展包**的规则与 Schema 文件；Node 权限是在进程启动时确定的，所以工作进程按拓展包分组。
- 启动参数：`--max-old-space-size=96`、`--disable-proto=throw`；环境变量清空。
- **每个请求在全新的 `vm` 上下文中执行**（空全局对象，禁止 `eval` 和 WebAssembly）。
- 工作进程空闲 60 秒或处理 500 个请求后回收；超时、崩溃或输出超限时立即终止，下一个请求启动新进程。
- 主进程与工作进程之间使用标准输入输出逐行传递 JSON（与旧运行器相同）。

### 7.2 时限

| 层级 | 时限 | 超时后 |
| --- | --- | --- |
| 单次钩子调用（`grade`、`project` 等） | 650 ms（`vm` 超时） | 该请求返回 `RULE_TIMEOUT` |
| 单个请求 | `deadlineMs`，默认 8000 ms | 终止工作进程，返回 `RULE_TIMEOUT` |
| Java 等待 HTTP 响应 | `deadlineMs + 1000 ms` | Java 视为 `RULE_UNAVAILABLE` |

### 7.3 大小限制

输入 8 MB，单个规则文件 1 MB，输出 2 MB，均与旧运行器一致。

### 7.4 过载保护

- rule-runner 主进程维护一个有上限的等待队列（默认 200）。队列满时立即返回 HTTP 503 `OVERLOADED`，而不是无限排队。旧系统的问题之一就是排队没有上限、所有人一起变慢。
- Java 端对 rule-runner 的并发调用数设上限（信号量，默认等于工作进程上限），超出时快速失败并返回 503。这样 rule-runner 变慢时，不会拖住 Java 处理其他请求的线程（ADR-003）。
- **前台请求优先：** 导入等批量操作标记为后台，排在读题、保存、提交之后（旧运行器同样区分前台和后台）。

---

## 8. 错误码与 Java 的处理

| rule-runner 返回 | Java 对外返回（API 设计第 4 节） | 说明 |
| --- | --- | --- |
| `ok:false`，`RULE_REJECTED` | 草稿 / 提交：400 `VALIDATION_FAILED`；导入：422 `RULE_REJECTED` | 作答或题目不合法 |
| `ok:false`，`RULE_TIMEOUT` | 422 `RULE_TIMEOUT` | 拓展规则太慢，通常是拓展本身的问题 |
| `ok:false`，`SCORE_UNAVAILABLE`、`INVALID_OUTLINE_ITEMS`、`INVALID_OUTLINE_STATES`、`UNSUPPORTED_API_VERSION` | 422，错误码原样透传 | 拓展实现有缺陷，前端有对应提示 |
| HTTP 503 `OVERLOADED` | 503 `RULE_UNAVAILABLE` | 前端稍后重试 |
| 连接失败、超时、5xx | 503 `RULE_UNAVAILABLE` | 同上；Java 记录错误日志并计入监控指标 |

**重试策略：** rule-runner 的调用本身没有副作用（不写任何数据），所以 Java **可以安全地重试一次**连接失败的调用（例如 rule-runner 刚好重启）。只重试一次，避免故障时放大请求量；规则返回的 `ok:false` 是确定的结果，不重试。

### 可观测性

- rule-runner 每个请求输出一行结构化日志：`requestId`、`op`、拓展摘要前 12 位、耗时、结果码。不记录题目和作答内容。
- `GET /v1/metrics` 提供：各操作的调用次数与耗时分布、超时次数、工作进程数、队列长度。阶段六压测时用来判断 rule-runner 是否成为瓶颈。
- Java 记录调用 rule-runner 的耗时，并计算它在保存、提交 P95 中所占的比例（ADR-003 验证项 4）。

---

## 9. 测试

| 类型 | 内容 |
| --- | --- |
| **一致性测试** | 测试中**以旧 `rules-runner.cjs` 作为参照**：收集基础题型与已发布题库的拓展，构造“题目 + 作答”样本，对每个操作分别调用新旧运行器，输出逐字段比较。旧运行器只在测试中使用，不进入生产代码（需求第 5 节允许旧代码作为参照） |
| **隔离测试** | 恶意规则样本：死循环、无限分配内存、访问 `require` / `process`、原型污染、超大输出、在 `getScore` 中依赖上一次调用残留的全局变量。每种都必须被拒绝或终止，且服务持续可用 |
| **协议测试** | 不支持的协议版本、文件哈希不匹配、缺少字段、超过大小限制 |
| **故障测试** | 压测中重启 rule-runner：保存与提交返回 503，恢复后自动继续；Java 的线程池不被占满 |
| **性能测试** | 单独压测 `project`、`validateAnswer`、`submit` 的吞吐和 P95 |

---

## 10. 待定事项

| 编号 | 事项 |
| --- | --- |
| RR-1 | 工作进程池按拓展包分组时，拓展种类很多会导致进程数增加。在线版拓展数量预计不多，先沿用；若压测显示频繁启动进程，再评估按权限集合并 |
| RR-2 | `X-Internal-Token` 的轮换方式 |
| RR-3 | 分数超过 4 位小数时的处理（截断、四舍五入，还是拒绝） |
| RR-4 | 投影缓存的容量上限与淘汰策略，需按真实题库大小估算 |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
