# 题库共享与版本更新

> **文档版本：** v0.1（初稿，待评审）
> **日期：** 2026-10-27
> **依据：** [需求 FR-10](../requirements/01-requirements.md)，[数据库设计](01-database.md)，[rule-runner 协议](03-rule-runner-protocol.md)

本文说明三件事：题库内容如何在用户之间共享；作者增、删、改题库后，用户的题库如何变化；自动更新的具体算法与并发处理。

---

## 1. 概念回顾

```text
题库包（catalog_pack）            作者上传的一个“组”，包含多本题库
 └─ 版本（catalog_pack_revision）  作者每次更新产生一个新版本，需审核
     └─ 包内题库（catalog_pack_bank） 某版本中的一本题库 → 指向一份共享内容（content_bank）

用户的题库实例（practice_collection）  指向某个包内题库的某个版本的内容
 └─ 作答、草稿、历史、考试             用户私有
```

- **共享内容不可变**：每个版本的每本题库对应一份 `content_bank`；内容相同则复用同一份（`content_sha256` 唯一）。
- **包内题库的身份**：`bank_key` = 题库在包内的路径 `question-banks/<组>/<题库>/`，与旧 Hub 的识别方式一致。新旧版本中 `bank_key` 相同的题库视为“同一本题库的不同版本”。

## 2. 作者操作与用户效果

| 作者操作 | 平台处理 | 已加入的用户 |
| --- | --- | --- |
| **增**：上传新的题库包 | 新建 `catalog_pack` 与第 1 个版本，审核通过后公开 | 无影响 |
| **改**：上传新版本 | 新建版本，审核中时**继续公开上一个已审核版本** | 新版本审核通过后自动更新（第 3 节） |
| 新版本中去掉某本题库 | 该 `bank_key` 在新版本中不存在 | 保留该题库，停在最后一个版本，标记“作者已移除，不再更新” |
| 新版本中题库改名 | 视为删除旧题库 + 新增一本 | 旧题库同上保留；新题库需要用户自己加入 |
| **删**：删除整个题库包 | 题库包下架，不再出现在广场中 | 全部保留，标记“作者已移除，不再更新” |
| **查**：查看题库包信息 | 只读 | 无影响 |

> 与旧 Hub 的差异：旧 Hub 中作者编辑后，整个题库包暂停公开，直到重新审核通过。V2 改为新版本审核期间继续提供上一个已审核版本，用户不会因为作者正在修改而看不到题库。

**共享内容不会因作者删除而丢失：** 内容只有在**没有任何用户实例、历史记录、考试和题库包版本引用**时，才会被后台垃圾回收删除（ADR-007）。

## 3. 自动更新规则

### 3.1 何时更新

| 用户题库的状态 | 处理 |
| --- | --- |
| 没有进行中的练习轮次，也没有进行中的考试 | **立即更新** |
| 本轮已提交至少一题但未点“完成”，或有进行中 / 评分中的考试 | **暂缓**，显示“有新版本，完成本轮后自动更新”，并提供“立即更新”按钮 |
| 暂缓中，用户完成本轮或考试结束 | 在“完成”的同一事务中更新 |
| 暂缓中，用户点击“立即更新” | 当前进行中的轮次封存为“因题库更新而中断”（已提交的结果保留在历史中），然后更新 |

“只保存了草稿、还没提交任何题”不算正在练习，直接更新。

### 3.2 何时检查

**懒更新：不在作者发布时批量修改所有用户的数据，而是在用户打开题库时检查。**

- 用户请求题库概览（`GET /api/collections/bank/{id}`）时，比较 `practice_collection.source_revision` 与该题库包最新的**已审核**版本。
- 有新版本且可以立即更新 → 在本次请求中完成更新，再返回新版本的概览。
- 有新版本但需暂缓 → 返回旧版本概览，附带 `update:{status:"pending", revision}`，前端显示提示。

为什么不在发布时批量更新：

1. 批量修改几百个用户的数据是一个耗时的大事务，容易和用户正在进行的保存冲突；
2. 很多用户可能很久都不会再打开这本题库，提前更新没有意义；
3. 懒更新让每个用户的更新都是一个独立的小事务，失败只影响这一个人，下次打开时重试即可。

### 3.3 逐题合并算法

新旧版本按题目 ID 对应，按题目内容哈希（`content_question.content_sha256`）判断是否改变：

| 情况 | 当前作答（`practice_question_state`） | 白板 |
| --- | --- | --- |
| 题目 ID 相同、内容哈希相同 | **保留**作答、评分、分数与投影 | 保留 |
| 题目 ID 相同、内容哈希不同 | 重置为 `unanswered`，清空作答与结果，`revision + 1`，标记“本题已更新” | 清空 |
| 旧版有、新版没有 | 删除该题的当前作答 | 删除 |
| 旧版没有、新版有 | 无记录，显示为未作答 | — |
| 题目使用的拓展包换了版本（摘要不同） | 视同内容改变，重置 | 清空 |

其他规则：

- **冻结历史永不改变**：`practice_round` 与 `practice_round_question` 保存的是当时的快照，并引用当时的拓展包摘要，不受更新影响。
- **重置用更新而不是删除**，`revision + 1`，与“再练一次”相同的原因：防止版本号回到初始值，导致前端缓存的旧版本号误判为有效（数据库设计 5.5 节）。
- 分组名、用户自定义的标题**保留**，不被作者的新标题覆盖。
- **考试**引用的是开考时的 `bank_id`，本身就是冻结的；考试结束后才轮到所属题库的更新。

### 3.4 事务与并发

一次更新在**一个事务**中完成：

```text
BEGIN
  SELECT ... FROM practice_collection WHERE id = ? AND user_id = ? FOR UPDATE
      —— 锁住题库实例：防止同一用户两个页面同时触发更新
  再次确认：source_revision 仍是旧值（已被另一个请求更新则直接结束）
  SELECT ... FROM practice_context WHERE collection_id = ? AND kind = 'practice' FOR UPDATE
      —— 锁住练习上下文：与提交、完成本轮互斥（加锁顺序：collection → context → state）
  再次确认可以更新（没有进行中的轮次，或是“立即更新”请求）
  按 3.3 节逐题更新 practice_question_state、practice_whiteboard
  practice_collection.bank_id = 新内容，source_revision = 新版本，update_status = 'current'
  practice_context.progress_version + 1
COMMIT
```

- 更新只涉及数据库，不需要调用 rule-runner：新版本每题的满分已在导入时算好（rule-runner 协议第 6 节），未改变的题的分数和投影直接沿用。
- 更新与保存草稿并发时：保存草稿的条件更新带 `revision`，如果这道题刚好被更新重置，保存会得到 409，前端刷新后看到“本题已更新”。不会出现旧作答写进新题目的情况。

## 4. 数据库变更（数据库设计 v0.4）

```sql
CREATE TABLE catalog_pack_bank (                   -- 某个版本中的一本题库
  pack_id    CHAR(36)      NOT NULL,
  revision   INT           NOT NULL,
  bank_key   VARCHAR(255)  NOT NULL,               -- question-banks/<组>/<题库>/
  bank_id    CHAR(36)      NOT NULL,               -- 共享内容 content_bank.id
  title      VARCHAR(300)  NOT NULL,
  PRIMARY KEY (pack_id, revision, bank_key),
  KEY idx_pack_bank_content (bank_id)
);
```

`practice_collection` 增加来源与更新状态字段：

```sql
  source_pack_id   CHAR(36)      NULL,             -- 来自题库广场时填写；用户自己上传的 .qbank 为 NULL（不参与更新）
  source_bank_key  VARCHAR(255)  NULL,
  source_revision  INT           NULL,             -- 当前使用的版本
  update_status    ENUM('current','pending','removed') NOT NULL DEFAULT 'current',
      -- pending：有新版本但暂缓；removed：作者已移除，不再更新
  KEY idx_collection_source (source_pack_id, source_bank_key)
```

`catalog_pack` 增加 `approved_revision`（最新的已审核版本），审核通过时更新；用户打开题库时与 `source_revision` 比较。

`practice_round.status` 增加取值 `interrupted`（因“立即更新”而封存的轮次）。

## 5. 接口变更（API 设计 v0.2）

| 接口 | 变更 |
| --- | --- |
| `GET /api/collections/bank/{id}` | 响应增加 `update:{status, revision?, removed?}`；可以立即更新时，在返回前完成更新 |
| `POST /api/collections/bank/{id}/update` | **新增**：“立即更新” `{requestId}`；进行中的轮次被封存为 `interrupted` |
| `GET /api/collections/bank/{id}/questions/{qid}` | 被更新重置的题目带 `updatedByAuthor:true`，前端提示“本题已更新” |
| `POST /api/packs/{id}/practice` | 加入“我的题库” `{banks:[bank_key…]}`，按本选择；同一用户重复加入同一本时，打开已有的实例（与旧系统“重复的 Hub 版本打开已有内容”一致） |

## 6. 用户自己上传的 `.qbank`

- 内容同样共享存储（相同内容只存一份），但不关联任何题库包，**不参与自动更新**。
- 用户上传同一题库的新文件，会生成一个新的题库实例（与旧系统“每次导入都是独立副本”一致，C-01）。

## 7. 测试要点

1. 未在练习的用户打开题库：已是新版本，未变的题作答保留，变了的题显示“本题已更新”。
2. 正在练习的用户：看到提示，继续练习不受影响；点“完成”后自动更新；历史记录内容不变。
3. “立即更新”：进行中的轮次在历史中显示为“中断”，已提交的结果仍可查看。
4. 作者删除题库包：用户的题库仍能正常练习，显示“作者已移除”。
5. 并发：同一用户两个页面同时打开题库，只更新一次；更新与保存草稿并发时，没有旧作答写入新题目。
6. 新版本审核期间，用户和广场看到的都是上一个已审核版本。

## 8. 待定事项

| 编号 | 事项 |
| --- | --- |
| UP-1 | 存储配额：建议从题库广场加入的公开题库不计入个人配额；用户自己上传的 `.qbank` 按大小计入上传者配额；作答图片等个人数据照常计入（待确认） |
| UP-2 | 是否需要告诉用户“新版本改了什么”（作者填写更新说明，在提示中显示） |
| UP-3 | 迁移：旧系统中从 Hub 导入的题库，能否根据 `cloud_imports.source` 关联回题库包，以便迁移后也能自动更新 |

## 变更记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v0.1 | 2026-10-27 | 初稿 |
