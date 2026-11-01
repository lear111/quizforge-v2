# L1.5 白板与答题资源

## 实现

以作者 2026-11-01 确认的验收规则和已批准的设计为准。图片、截图、白板、作答状态和轮次历史均不计配额；只有私人题库导入继续记账。

- 图片：`POST /api/resources` 沿用 `{mime,data}` base64 请求，返回 `{id,mime,size}`。实际 MIME 由 PNG/JPEG/WebP 文件头确定。请求体先限长，再解码。CAS 文件写入后，同一事务登记用户归属；同用户同内容幂等，跨用户分别登记。
- 读取：Java 校验本人上传或本人可用题库资源，仅检查文件存在，返回 `X-Accel-Redirect`；文件内容、Range 和 HEAD 由已有 Nginx internal 路由处理。返回 private/no-store、nosniff；不能通过猜中 hash 访问他人图片。
- 白板：`data.layer=draft/notes`，`data.draft` 是所选层。两层共用白板独立 revision，答案 revision 不变。笔记保留既有笔迹、纸面与布局，只能追加；草稿在整轮 completed 后锁定，单题 submitted 不锁定。前端显示两层并保护旧笔迹；笔记不会裁入正式作答。
- 白板事务：锁当前 context → 锁当前白板 → 查回执 → 检查独立版本/轮次 → 插入或条件更新当前白板 → 同事务写回执。冲突后新事务重查回执。只写白板与回执，不枚举整库、不投影、不写答案或配额。
- 再练一轮：清空白板两层并保留递增的版本空行，避免删除后版本复用，让旧标签页覆盖新轮白板。原 context→state 锁顺序与事务位置不变；历史 payload 不变。
- 截图：等待答题区 flush → 以组件模型绘制文字/图片/手写 → 上传 → 给本次 answer 副本添加 snapshot → submit。失败则停止提交、保留输入。截图哈希也被冻结在投影 `_qfSubmission.screenshots`、状态 answer 和单题轮次 payload 中；人工评分和回执重放保留本次关联。宿主不改变拓展返回的 score/gradingStatus。
- `RoundSnapshots` 在提交事务中读取当前白板，覆盖判分期间新增白板的情况；不改提交先保存、事务外判分、乐观锁和回执重查语义。

## 限制取值

| 限制 | 取值与原因 |
| --- | --- |
| 单张持久化图片 | 4 MiB；沿用旧 host/resources/resources.js 的 MAX_IMAGE_BYTES 与现有 bridge 上传上限。答题区可接收较大原图，但先压缩到 4 MiB 再上传 |
| 单题当前作答 | 最多 20 个不同 assetId，包含本次提交截图；历史旧图片不占当前名额。上传协议本身无题目字段，故在保存/提交时按引用限制，不能把上传记录数冒充单题数 |
| 上传频率 | 每用户每 60 秒 30 次通过类型/大小校验的上传尝试；去重上传也计次。内存限流、注入 Clock，并发不能突破窗口额度 |
| 白板 | 两层 UTF-8 JSON 合计 2 MiB，沿用现有笔迹数量/坐标约束，额外校验重复笔迹 ID 和有限数值 |
| transient 标记 | 缺省或 true；旧版 true 表示无历史场景的临时资源。V2 强制保留历史，因此兼容接受但持久化，不绕过限制 |

## 配额迁移

V7 增加白板 notes/revision，旧 draft 数据保留。V8 删除 state/round/snapshot/resource/whiteboard/annotation 分类的旧账项，再按剩余账本重算 storage_used，保留 import、用户覆盖额度与下载流量；不删作答/历史/资源业务数据。移除 PracticeStorage/PracticeQuotaMapper 及练习服务中的计费调用，导入和删除私人题库的原事务记账不变。另已合入 main #78 的简化规则，用户中心只显示存储，不再显示每日下载卡片；旧下载计数/管理字段暂保留兼容，本任务没有新增任何下载扣减入口。迁移测试覆盖从 V5→V6 旧计费→最新迁移、再次 migrate 不变，以及业务数据保留。

## 验证

- `mvn -B -ntp -f backend/pom.xml verify`：3096 项，失败 0、错误 0、跳过 3（均为已有 QbankArchiveReader 缺陷复现，未新增跳过）。耗时 11:45。
- `npm test`：48/48 通过；Docker 构建沿用 npm build。
- 真实 Chromium 浏览器：桌面和 375px 完整套件 14/14 通过（3.4 分钟）；CI 链接见 PR。
- 独立审查发现并修复笔记裁入答案、restart 白板版本复用两项；不改变既有提交/草稿并发流。

新增或扩展的主要测试：

| 范围 | 测试 |
| --- | --- |
| 文件头、解码、大小、限流窗口/并发 | ImageUploadsTest（4 项） |
| 草稿锁定、笔记追加、格式边界 | WhiteboardDocumentsTest（3 项） |
| HTTP/MySQL、去重、伪装、超限、图片 IDOR、外来引用、20 张边界、白板版本/回执/回滚/并发、特性开关、条件读取、300 题性能 | WhiteboardResourceIntegrationTest（15 项） |
| 截图→提交投影/轮次/review/replay、判分期间白板冻结、restart 版本不复用 | PracticeLifecycleIntegrationTest 新增 submissionScreenshotIsBoundToThisAnswerProjectionSnapshotReplayAndReview、submissionFreezesBoardWrittenWhileGradingWasInFlight、oldWhiteboardVersionCannotOverwriteTheNextRoundAfterRestart |
| 旧练习账目释放、零存储额度仍可保存/提交 | QuotaMigrationTest、QuotaIntegrationTest、PracticeLifecycleIntegrationTest |
| 白板响应不覆盖答案、两层选择、保护笔迹、独立版本、笔记不进入答案、截图顺序与失败 | frontend/test/whiteboard-resources.test.mjs（7 项） |
| 真浏览器文字/插图/手写→两层白板→提交截图→刷新恢复；Nginx Range | frontend/test/browser/whiteboard-resources.spec.mjs（桌面与 375px） |

并发测试通过 CountDownLatch / MyBatis SQL 门控让请求在实际事务中竞争，不以 sleep 判断状态。回执故障通过 ReceiptMapper 注入异常，验证同一事务的白板写入回滚。

### 本地浏览器复查步骤

独立项目 `qf-whiteboard-resources`，入口 `http://localhost:8105`；未操作用户已有的 8088/8098 环境。注册测试用户 → 导入 samples/basic-types.qbank → 仅在该测试项目写审核夹具 → 打开“简答题（答题区）” → 输入文字与插图 → 切到草稿并手写 → 追加笔记 → 返回作答并提交 → 可见完整截图 → 刷新确认截图和两层白板仍在。测试不会修改 samples 或拓展源码。

截图位于 git 忽略的 `.artifacts/screenshots/whiteboard-resources-desktop.png`、`whiteboard-resources-mobile.png`；CI 的 frontend-browser-evidence 工件保存同类截图与失败追踪。

### 300 题单次保存

真实 Testcontainers MySQL，当前题 q1，额外插入到 300 题；真实随机端口 HTTP 请求，计时包含认证、网络、SQL 与回执。本分支单次 HTTP 总耗时 **37.351 ms**（37,351 μs），MyBatis 拦截器记录 **10 个 SELECT + 2 个 INSERT**：

```text
SessionMapper.findActive
PracticeReadMapper.collection
BankMapper.isAvailable
QuestionMapper.findById
PracticeReadMapper.context
PracticeContextMapper.lockForUpdate
WhiteboardMapper.lock
ReceiptMapper.find
PracticeReadMapper.roundStatus
WhiteboardMapper.insert    # 唯一白板行写入
ReceiptMapper.insert       # 同事务幂等回执
ReceiptMapper.find
```

无 PracticeReadMapper.questions / collections、rule-runner、配额或答案状态写入；条件更新已有白板时则为一个 UPDATE 和一个回执 INSERT。计时来自真实 MySQL + HTTP + spy 仪器下的一次本地测量，不作容量结论。

## GC 引用来源（本次不实现 GC）

1. `practice_question_state.answer` / `answer_state` 中富文本 image.attrs.assetId、答题区 images[].attrs.assetId、snapshot.attrs.assetId。
2. `practice_question_state.projection._qfSubmission.screenshots[].assetId`，以及 projection/result 中拓展允许的嵌套资源引用。
3. `practice_round_question.payload` 冻结的 state.answer、question.data 投影/截图、whiteboard 两层；轮次 collection_snapshot/summary 中未来可能的嵌套引用。
4. `practice_request_receipt.response`：历史回执可重放，不能只检查当前状态就回收旧截图。
5. 当前 `practice_whiteboard.draft/notes` 为纯笔迹结构，不使用图片；仍需保留将来可能增加的资源节点。`practice_history_annotation` 是以后追加笔记的引用来源。
6. `content_bank_asset`、`content_extension_file` 与公共/私人题库其他资源引用；`practice_user_resource` 表示用户归属，同一 blob 可以有多个用户或题库引用。

GC 必须统一解析并保护全部引用；不能因当前 answer 没有该 hash 或文件由 transient 请求上传就删除。本次不回收孤立上传文件。

## 剩余边界

- 整轮完成接口（L1.3）、历史浏览/追加历史笔记（L1.4）和 AI 评分（L5）仍不在本任务中。completed 锁定规则已用真实数据库状态测试，供完成练习接口接入。
- 限流为单 app 内存窗口，多 app 需要共享状态；重启会重置窗口。资源去重不等于回收策略，GC 另行实现。
- 文件头识别不执行完整图像解码/重编码；浏览器负责上传前解码，服务端只接受受限 MIME 并 nosniff。
- 完成后追加的笔记保存于当前白板，冻结的历史单题 payload 不会被高频白板写反复重写；L1.4 读取历史时需要结合追加笔记通路。

## 实现取舍

- 只删除练习计费调用及已无调用方的适配类，保留其外围业务事务；若遗漏调用会重新收费，因此用零额度保存/提交/重启和迁移测试约束。
- 增加独立白板版本和顶层 whiteboard 视图，同时保留旧 draft 字段与历史回执构造器。代价是客户端必须按 action 选择版本，否则会得到冲突；写队列有专门测试。
- 截图关联使用宿主 `_qfSubmission` 元数据，不重写拓展的评分与状态。当前带截图的投影要求对象；非对象投影的自定义题型需要后续约定宿主元数据通路，当前会拒绝保存该结果而不是丢图。
- restart 保留空白板版本行，清除两层内容；空间代价是每个用过白板的题保留一条小空行，避免旧轮次版本覆盖新轮。

## 审查回归证据

- 笔记裁入答案：最小复现原先派发 note 笔迹；排除 protected 后不再进入 answer，正式前端回归测试通过。
- 白板版本 ABA：临时把 resetWhiteboards 恢复为 DELETE，oldWhiteboardVersionCannotOverwriteTheNextRoundAfterRestart 稳定失败（预期 409，实际 200）；恢复 UPDATE 保留版本后 clean test 1/1 通过。变异已恢复，源码与已提交修复一致。
- 完整浏览器回归暴露原 read-slices 手机端最后一次刷新只等 loading 初始隐藏，抽屉会被异步开库再次关闭；复用已有页面就绪等待，并区分已提交题不再期待单选控件 enabled。保留原来的提交状态与冲突断言。

## 文件清单

```text
M	backend/src/main/java/cloud/treenewbee/quizforge/content/application/ContentPages.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/DraftActionService.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/DraftService.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeLifecycleService.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeReadService.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeViews.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/ReviewGrader.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/RoundSnapshots.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/SubmitActionService.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/SubmitGrader.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/application/SubmitService.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeLifecycleMapper.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/AnswerResources.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/ImageUploads.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/ResourceController.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/SubmissionImages.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/UploadLimiter.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/UserResourceMapper.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/resources/UserResourceRow.java
M	backend/src/main/java/cloud/treenewbee/quizforge/practice/web/PracticeActionController.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardDocuments.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardMapper.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardService.java
A	backend/src/main/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardViews.java
D	backend/src/main/java/cloud/treenewbee/quizforge/quota/application/PracticeStorage.java
D	backend/src/main/java/cloud/treenewbee/quizforge/quota/infrastructure/PracticeQuotaMapper.java
A	backend/src/main/resources/db/migration/V7__whiteboard_layers.sql
A	backend/src/main/resources/db/migration/V8__private_import_only_quota.sql
M	backend/src/test/java/cloud/treenewbee/quizforge/practice/application/DraftActionIntegrationTest.java
M	backend/src/test/java/cloud/treenewbee/quizforge/practice/application/PracticeCollectionSnapshotTest.java
M	backend/src/test/java/cloud/treenewbee/quizforge/practice/application/PracticeLifecycleIntegrationTest.java
A	backend/src/test/java/cloud/treenewbee/quizforge/practice/resources/ImageUploadsTest.java
A	backend/src/test/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardDocumentsTest.java
A	backend/src/test/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardResourceIntegrationTest.java
M	backend/src/test/java/cloud/treenewbee/quizforge/quota/QuotaIntegrationTest.java
M	backend/src/test/java/cloud/treenewbee/quizforge/quota/QuotaMigrationTest.java
M	docs/design/01-database.md
M	docs/design/07-frontend-api-map.md
M	docs/plan/02-practice-acceptance.md
A	docs/superpowers/plans/2026-11-01-whiteboard-resources.md
A	docs/superpowers/specs/2026-11-01-whiteboard-resources-design.md
M	frontend/UPSTREAM.md
M	frontend/practice/app.js
A	frontend/practice/bridge/answer-snapshots.js
M	frontend/practice/bridge/api-v1.js
M	frontend/practice/bridge/frame.js
M	frontend/practice/draft/answer-ink.js
A	frontend/practice/draft/whiteboard-layers.js
M	frontend/practice/draft/whiteboard-model.js
M	frontend/practice/draft/whiteboard.css
M	frontend/practice/draft/whiteboard.js
M	frontend/practice/shared/write-queue.js
M	frontend/public/auth.css
M	frontend/public/quota.js
M	frontend/test/browser/quota.spec.mjs
M	frontend/test/browser/read-slices.spec.mjs
A	frontend/test/browser/whiteboard-resources.spec.mjs
M	frontend/test/quota.test.mjs
A	frontend/test/whiteboard-resources.test.mjs
A	docs/reports/whiteboard-resources.md
```
