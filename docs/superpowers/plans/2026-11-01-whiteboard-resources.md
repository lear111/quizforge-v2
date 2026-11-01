# Whiteboard and answer resources Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 让真实练习界面支持持久化两层白板、私有答题图片与提交截图。

**Architecture:** 图片复用 BlobStore 与 Nginx internal 传输，Java 维护用户归属。白板采用独立 revision 和幂等回执，只处理当前白板行。提交截图作为本次 answer 的资源引用冻结，保持原有提交并发语义。

**Tech Stack:** Java / Spring Boot / MyBatis / MySQL、原生 ES modules、现有答题区组件、Testcontainers、Playwright。

**Spec:** `docs/superpowers/specs/2026-11-01-whiteboard-resources-design.md`（已确认）；执行方式：本会话直接实现。

## Global Constraints

- 图片最大 4 MiB；当前单题作答最多 20 个不同图片引用；每用户每分钟最多 30 次上传。
- 两层白板合计最大 2 MiB，笔记只追加，整轮完成后草稿锁定。
- 图片、截图、白板、草稿、历史不计配额；私人导入计费不变。
- 不改变 SubmitService / DraftService / IdempotentDraftService 已有并发语义。
- 白板保存不扫描整库、不投影、不写答案行；资源字节由 Nginx 发送。

## Review Focus

- 相同答案 stamp、不同白板版本必须重新读到白板（任务 3）。
- 白板保存响应不能冲掉未保存答案（任务 3）。
- 截图失败不得发送无图提交，旧截图不得冒充新提交（任务 4）。
- 资源归属与引用均须验证，哈希不可成为越权通道（任务 2）。
- 取消旧计费后用量不为负且重复迁移不会改变导入账目（任务 1）。

### Task 1: 配额口径与白板表迁移

Files: 新建 V7 白板 notes/revision 迁移、V8 非导入计费释放迁移；修改 PracticeStorage 及现有记账调用与测试；更新验收清单第 7 节。
Interfaces: 白板行 `(contextId,qid,userId,draft,notes,revision,sizeBytes,updatedAt)`；仅 import category 继续记账。

- [x] 增加释放既有 state/round/snapshot/resource/whiteboard 账目、保留 import 的真实 MySQL 测试。
- [x] 删除练习保存中的记账查询/写入，保留业务事务和锁顺序。
- [x] 运行配额和练习原有测试并更新产品口径断言。
- [x] 提交本任务改动。

### Task 2: 私有图片上传和引用校验

Files: 新建 practice/resource 下的 ImageUploads、UploadLimiter、UserResourceMapper/Row、ResourceController、AnswerResources；修改 ContentPages 的资源鉴权；增加有界请求体读取。
Interfaces: `ImageUploads.upload(userId,JsonNode,transientFlag)` → `{id,mime,size}`；`AnswerResources.validate(userId,answer)`；`ContentPages.resource(userId,sha)` → FileResponse。

- [x] 写图片头、base64、4 MiB、限流窗口及并发测试，先运行确认红灯。
- [x] 实现文件头识别、BlobStore 写入与归属幂等登记，GET 使用 X-Accel-Redirect。
- [x] 实现当前作答 20 个引用限制及归属校验，接入 HTTP 草稿/提交适配。
- [x] 真实 MySQL / HTTP 验证去重、类型伪装、超限、IDOR、零配额仍可上传。
- [x] 提交本任务改动。

### Task 3: 两层白板与前端版本适配

Files: 新建 WhiteboardDocument/Policy/Service、WhiteboardMapper；修改 PracticeActionController、PracticeReadService/Views、shared/write-queue.js、app.js、draft/whiteboard.js 与模型、测试。
Interfaces: `WhiteboardService.save(user,collection,qid,requestId,revision,data,contentVersion)`；白板视图 `{draft,notes,revision,draftLocked}`，响应仅包含白板字段。

- [x] 先写追加/锁定策略、独立版本和写队列隔离测试。
- [x] 实现仅当前行的白板事务、回执和新事务重查；同 ID 并发首次/后续保存、不同 ID 同版本竞争均测试。
- [x] 返回两层，启用 features.whiteboard；白板 revision 加入条件读取校验。
- [x] 前端分层切换，旧内容只读显示，写队列分离版本并只合并白板响应。
- [x] spy 验证不调用整库 questions、rule-runner、配额，零配额仍可保存。
- [x] 提交本任务改动。

### Task 4: 截图提交与冻结

Files: 修改 bridge/api-v1.js 与答题区组件的宿主适配、SubmitGrader/ReviewGrader、RoundSnapshots；新建截图资源关联辅助类与测试。
Interfaces: answer 内答题区 `snapshot:{type:"image",attrs:{assetId}}`；投影保留本次截图资源元数据，review 保留它；RoundSnapshots 读取当前白板两层。

- [x] 测试上传成功先于 submit、截图失败保留输入不提交。
- [x] 实现模型绘制截图、上传、为本次 answer 副本加入引用，禁止回写旧答案。
- [x] 真实 runner 测试提交/刷新/重放、不同次截图关联、review 保留、事务内冻结当前白板。
- [x] 提交本任务改动。

### Task 5: 验收、性能与交付

Files: 新增前端 browser 测试与 docs/reports/whiteboard-resources.md；更新 API map。

- [x] `mvn -B -ntp -f backend/pom.xml verify`，要求零失败/错误；`npm test` 全绿。
- [x] 独立 Compose 实测打字、插图、手写、截图可见和刷新持久化，截图放忽略目录。
- [x] 在 300 题题库测单次白板保存耗时及 SQL，报告测量条件。
- [ ] 检查全部资源引用来源与剩余边界，更新报告、推送并开 PR，CI 四项全绿。
- [ ] 独立 Compose down（不删卷）、工作目录 detach；不合并 PR。
