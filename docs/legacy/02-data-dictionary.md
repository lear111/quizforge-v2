# 旧数据字典

> **文档版本：** v0.1（初稿，待评审）
> **日期：** 2026-10-27
> **依据：** [需求 FR-09 旧数据迁移](../requirements/01-requirements.md)
> **源码版本：** `quizforge_web` @ `4522bb8`，`quizforge_hub` @ `83d6dfd`

本文列出旧系统中**需要迁移的数据在哪里、是什么格式**，作为 ADR-008（迁移策略）和新数据模型设计的输入。

旧系统共有三处数据：

```text
① Java Hub 数据目录（HUB_JAVA_DATA_DIR，例如 data-java/）
   ├── hub.sqlite          账号、题库广场、审核、AI
   └── files/、downloads/  题库包、截图等附件
② Node 练习服务数据目录（DATA_DIR）
   ├── hub.sqlite          配额、导入记录、拓展审核白名单（与 ① 是两个不同的库）
   └── cloud-content/
       ├── users/<userId>/<workspaceId>/   每个用户的练习工作区（JSON 文件）
       ├── backups/、staging/、trash/      检查点备份、导入暂存、回收站
       └── host.lock
③ 生产环境中可能仍在运行的旧 Node Hub 数据（README：生产站尚未切换到 Java）
```

> **重要：** 根据 `quizforge_hub/README.md`，生产环境仍运行旧版本，尚未切换到 Java Hub。**迁移的真正源头是生产数据的实际格式**，在写迁移工具之前，必须先取得一份生产数据副本，核对它属于哪一代格式。旧 Node Hub → Java Hub 的迁移做法可以作为参考：先用 `scripts/snapshot-hub-for-java.mjs` 在停写状态下制作数据库副本，再由 `HubDatabase.java` 做增量结构升级。

---

## ① Java Hub 数据库（`hub.sqlite`）

**出处：** `java-hub/src/main/resources/schema.sql`、`account-schema.sql`、`admin-schema.sql`、`ai-schema.sql`

迁移建议的含义：**迁移** 表示数据迁入 V2；**不迁移** 表示丢弃或在 V2 中重新生成；**待定** 表示需要讨论。

### 账号

| 表 | 字段 | 迁移建议 |
| --- | --- | --- |
| `users` | id, email, name, password（scrypt）, admin, created | 迁移；保留原 `id`，密码哈希原样迁入（C-06） |
| `user_phones` | user_id, phone, verified | 迁移 |
| `unverified_contacts` | user_id, channel, contact | 迁移 |
| `user_blocks` | user_id, actor_id, reason, created | 迁移 |
| `sessions` | token（哈希）, user_id, expires | **不迁移**，用户重新登录 |
| `email_codes`、`email_sends`、`contact_codes`、`contact_sends` | 验证码及发送记录 | 不迁移（临时数据） |
| `registration_policy`、`authentication_policy` | 注册与登录策略 | 迁移（配置） |
| `invitations`、`invitation_audit` | 邀请码（存摘要）及审计 | 迁移 |
| `connect_devices` | id, user_id, token（SHA-256）, name, client, created, last_used, revoked | 迁移（C-05，用户无需重新授权） |
| `connect_codes` | 一次性授权码 | 不迁移（10 分钟有效） |

### 题库广场

| 表 | 字段 | 迁移建议 |
| --- | --- | --- |
| `packs` | id, user_id, title, summary, description, category, tags, version, downloads, created, updated, status, revision, review_note, reviewed_by, reviewed_at | 迁移 |
| `files` | id, pack_id, kind, name, mime, size, sha256 | 迁移；对应的文件复制到 V2 文件存储，并按 sha256 校验 |
| `comments`、`reports` | 评论、举报 | 迁移 |
| `pack_reviews`、`pack_admin_reviews`、`pack_cloud_approvals` | 审核记录、不可变审核包、云端许可（含拓展摘要 `digests`） | 迁移 |
| `traffic`、`activity_transfers` | 流量统计 | 迁移当月数据（配额计算需要），其余可归档 |
| `activity_daily`、`activity_online` | 活跃统计 | 待定（统计数据，可归档） |

### 管理与 AI

| 表 | 字段 | 迁移建议 |
| --- | --- | --- |
| `admin_contact`、`admin_meta`、`admin_audit` | 联系方式、元数据、管理审计 | 迁移 |
| `review_mail_state`、`review_mail_attempts` | 审核邮件队列状态 | 不迁移（V2 重新生成） |
| `official_ai_config` | 官方 AI 配置（`key_cipher` 为 AES-256-GCM 密文） | 迁移；V2 须使用**同一主密钥** `OFFICIAL_AI_MASTER_KEY` 才能解密 |
| `ai_upstreams` | 上游模型列表、价格、用量（密钥为密文） | 迁移，同上 |
| `ai_points`、`ai_points_ledger`、`ai_signup_bonus` | 用户 AI 余额（10000 单位 = 1 元）、流水、注册赠送 | 迁移；**迁移后余额必须与流水总和一致** |
| `ai_redeem_codes` | 兑换码（摘要 + 密文） | 迁移 |
| `official_ai_usage` | 每日用量 | 迁移当月数据 |

---

## ② Node 练习服务数据库（另一个 `hub.sqlite`）

**出处：** `quizforge_hub/practice/server.mjs`、`cloud/accounts.mjs`、`cloud/library.mjs`、`cloud/practice.mjs`、`cloud/extension-review.mjs`

| 表 | 内容 | 迁移建议 |
| --- | --- | --- |
| `users`、`sessions` | 从 Java Hub 同步过来的身份副本 | 不迁移（以 ① 为准） |
| `cloud_defaults`、`cloud_accounts` | 默认配额、每个用户的存储上限与已用量 | 迁移配额设置；已用量在 V2 中重新计算 |
| `cloud_storage_reservations` | 存储预留 | 不迁移（临时） |
| `cloud_traffic` | 用户流量统计 | 迁移当月数据 |
| `cloud_audit` | 管理员操作审计（如私人题库交付） | 迁移 |
| `cloud_imports` | 用户的云端题库导入记录：id, user_id, actor_id, status, reservation_id, title, bytes, catalog, source, created | 迁移；**它是用户工作区与题库实例之间的索引** |
| `cloud_workspace_members` | 旧独立导入合并进统一工作区后的映射 | 迁移（旧链接需要它定位到新题库） |
| `cloud_practice_writes` | 检查点恢复记录 | 不迁移；**迁移前必须为空**，否则说明有未完成的写入 |
| `cloud_extension_reviews` | 拓展审核白名单：digest, extension_id, version, inventory, status, reviewer, reviewed, reason | **迁移**（C-04） |
| `extension_review_names`、`extension_submissions` | 审核名称、用户提交的待审拓展 | 迁移 |
| `traffic`、联系方式相关表 | 站点流量、联系方式 | 以 ① 为准，核对后决定 |

---

## ③ 用户练习工作区（JSON 文件）

**位置：** `<DATA_DIR>/cloud-content/users/<userId>/<workspaceId>/`
**出处：** `quizforge_web/core/server/host/practice/state-store.js`、`history/history-rounds.js`、`LIBRARY_PACKAGES.md`、`API.md`

这是迁移中**最复杂**的部分：旧系统的作答、历史全部在这里，而且是本地版的文件格式。

### 目录结构

```text
<workspace>/
├── question-banks/<组>/<题库>/
│   ├── bank.json             题库内容（C-01）
│   ├── assets/               图片
│   └── .instance.json        宿主生成的本地保存身份 {schemaVersion:1, id}
├── extensions/<组>/<拓展>/    拓展包（含 .bundle.json）
└── .state/
    ├── <sha256(stateKey)>.json   每个集合一个文件：当前作答 + 历史轮次 + 请求回执
    ├── bank-locations.json       题库改名、移动的路由记录
    ├── exams/                    考试状态
    ├── resources/                上传的图片（按内容哈希）
    ├── ai/、ai-grading/          AI 评分任务
    ├── editor-drafts/            题目编辑草稿
    ├── sdk/<id>/<version>/       固定版本的 SDK
    ├── .edit-journal.json        编辑恢复日志
    └── host.lock、validation-cache/   运行时文件，不迁移
```

### 集合状态文件 `.state/<sha256(stateKey)>.json`

- 文件名是“集合键”的 SHA-256。集合键的形式如下：
  - 题库：`bank:<路由ID>:mixed`，路由 ID 为 `g.<.instance.json 中的 id>`；
  - 拓展示例：`extension:<拓展ID>:path:<路径>`。
- 顶层字段：`schemaVersion:1`、`collection`（集合键）、`extensionFingerprint`、`questions`（以题目 ID 为键）、`historyRounds`（历史轮次，`schemaVersion:2`）、旧版 `history`（兼容字段）、请求回执 `receipts`。
- 每道题的状态字段：`status`（`unanswered` / `draft` / `submitted`）、`answer`、`result`、`revision`、`answerState`、`draft`（白板）、`submissionGeneration`、`gradingGeneration`、AI 任务字段。
- 每个历史轮次包含 `id`、`createdAt`、`updatedAt`、`status`、`collectionTitle`、`fingerprints`、冻结的题目快照与页面资源、`summary` 等。
- 单个集合文件上限 32 MiB。

**待核实：** 每个字段的完整结构与取值。需要对照 `state-store.js`、`history-rounds.js` 的校验函数（`validateRound` 等）逐项整理，并用一份真实（脱敏）工作区样本核对。

### 迁移难点

1. **冻结历史包含页面资源快照**（`questions:[{payload,page?}]`、共享 `page:{html,script,style}`），数据量可能较大。须决定这些快照存入数据库还是文件存储，以及是否按内容去重。
2. **作答身份依赖集合键**：新系统需要把“工作区 + 集合键 + 题目 ID”映射到新的“用户题库实例 + 题目”。
3. **白板草稿**体积可能较大，按 ADR-002 不放入数据库正文，存入文件存储。
4. **无法识别的旧格式**（例如更早的 `history` 字段）不得静默丢弃（FR-09），要在迁移报告中列出。
5. 迁移前，旧系统必须**停写**，并确认 `cloud_practice_writes` 为空、`backups/` 中没有未完成的检查点。

---

## 文件附件

| 位置 | 内容 | 迁移建议 |
| --- | --- | --- |
| Java Hub `files/` | 题库包、截图等，由 `files` 表索引 | 迁移；按 `sha256` 校验 |
| Java Hub `downloads/` | 软件安装包 | 迁移（或重新上传） |
| 工作区 `question-banks/*/assets/` | 题库图片 | 迁移；按内容哈希去重存储 |
| 工作区 `.state/resources/` | 用户上传的图片 | 迁移 |
| `backups/`、`staging/`、`trash/` | 检查点、暂存、回收站 | 不迁移（回收站待定：用户可能期望能恢复已删除的题库） |

---

## 待核实与待决定事项

| 编号 | 事项 |
| --- | --- |
| D-1 | 取得生产数据副本，确认其格式代际（旧 Node Hub 还是 Java Hub） |
| D-2 | 集合状态文件与历史轮次的完整字段结构（对照校验函数和脱敏样本） |
| D-3 | 冻结历史中的页面快照存放位置与去重方式 |
| D-4 | 回收站中的题库是否迁移 |
| D-5 | 统计类数据（活跃度、流量）保留多久 |
| D-6 | 生产数据规模：用户数、工作区总大小、历史轮次数量（影响迁移耗时和停机窗口） |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
