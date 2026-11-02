# L1.6 小题草稿状态：按 PR #100 审查修订

## 当前行为

保存草稿不调用 rule-runner，也不为了构造保存响应加载投影。声明 answer-state 的拓展直接使用页面随答案上报的进度，只保留本题已知大纲 ID 与 unanswered/draft；其他拓展或未上报时按答案非空生成整题状态，小题未知。进度不参与计分、权限或完成条件判断。

白名单过滤为纯本地计算；额外字段不会存储或返回，非法状态/ID/重复 ID 按进度未知处理，答案仍保存。仅上报整题状态时不推测各小题。outlineStates=null 表示未知，未保存的空题仍显示未作答。

## 写入与并发

- IdempotentDraftService 增加一个同事务的完成回调重载，原七参数 save 的行为与返回保持不变。乐观锁、先 INSERT 后 UPDATE 的选择、原请求哈希和冲突后新事务重查回执均未改动。
- 新回调在原草稿条件写入后，通过 DraftOutlineMapper 对同一用户、同一题、同一 revision、status=draft 写入进度，再把进度随 revision 一起插入原回执。没有第二次事务、没有事后修改回执。
- 回调、进度 SQL 或回执失败时，草稿一起回滚。同 requestId 重放不执行回调，也不把旧进度应用到新版本。乱序到达的旧 revision 返回 409。
- 未修改 DraftService、QuestionStateMapper、SubmitService；增加的 IdempotentDraftService 重载仅提供本地 SQL 扩展点，原事务与冲突处理由同一段代码执行。

## 保存响应与前端

PracticeReadService.prepareDraftSave 只读取归属、审核、本题指纹/大纲/manifest，不查询题目原文、题库列表、白板、轮次或投影缓存。新增的 SQL 同样带 user_id 和精确题目 ID 条件。

HTTP 返回 {revision, state, stamp} 增量。前端通过集中 mergeDraftResponse 合并状态与版本，沿用已经加载的安全题目投影、白板和能力；缓存已淘汰时不凭空制造题目缓存条目。读题接口仍按原规则生成安全投影，正式提交仍校验并判分。

## 测试对应

| 要求 | 测试 |
| --- | --- |
| 页面进度持久化、回执重放 | reportedProgressPersistsWithDraftAndReplayNeverRewritesIt |
| 草稿零规则调用、零投影、零原文查询 | noRunnerOrProjectionIsUsedEvenForColdQuestionAndUnavailableRunner |
| 非空回退、小题未知 | unreportedProgressFallsBackToAnswerOnlyAndLeavesChildrenUnknown；DraftOutlineServiceTest |
| 旧请求乱序不覆盖新状态、过期派生 UPDATE 0 行 | delayedOldRequestCannotOverwriteNewerAnswerAndProgress |
| 同事务回滚 | progressFailureRollsBackDraftAndReceiptInTheSameTransaction；原 failingReceiptInsertRollsBackTheDraftInTheSameTransaction |
| 同事务可见性 | answerProgressAndReceiptAreInvisibleUntilTheSingleTransactionCommits |
| 白名单、安全 | declaredAnswerStateUsesOnlyWhitelistedDraftStatusesAndIds；reportedProgressOnlyContainsKnownIdsAndDraftStatuses |
| 幂等并发、409、IDOR | 原 DraftActionIntegrationTest 与 DraftServiceTest 保留 |
| 原安全投影不被草稿增量替换、不能生成伪缓存 | draft-response.test.mjs |
| 实时大纲与刷新保持 | outline.test.mjs；central-outline.spec.mjs 桌面/手机 |

并发与事务测试使用 CountDownLatch / 现有 DraftSqlGate，不用固定 sleep。旧的 runner 超时、校验乱序测试已由上述零 RPC、事务原子性与请求乱序测试替代，不再保留无效的 deadline 配置。

## P95 对比方法

审查前实现曾测得三组 P95 中位数 103.5ms → 204.3ms（+97.4%）；该结果仅说明被替换方案的开销，不代表本次修订。

本次使用同一个 loadtest/probes/draft-outline.py，基线改为最新 main@74b399f 的 git archive 构建。独立项目 qf-draft-outline-review、回环端口 8106；app 1.5 核/3GiB、MySQL 1 核/2GiB、runner 1.25 核/2GiB、nginx 0.25 核/1GiB，合计 4 核/8GiB，runner limit=8。仅公开 300 题合成样例，4 并发各保存不同题，每题预热 10 次，再测三组，每组 200 次，计时客户端在 runner 容器中直连 app:8080，不含 nginx 与 Windows 发布端口。

命令：python loadtest/probes/draft-outline.py --project qf-draft-outline-review --base-url http://127.0.0.1:8106 --output .artifacts/local/draft-review/<main-or-candidate>.json。实际结果见下表与 PR；不输出账号、Cookie 或题目内容，不访问远程业务环境。

## 实测结果

| 版本与遍次 | 三组 P95（ms） | 三组 P95 中位数（ms） | 请求数 / 错误数 |
| --- | --- | --- | --- |
| 基线，首遍 | 429.9 / 154.6 / 100.9 | 154.6 | 600 / 0 |
| 修订版，首遍 | 247.9 / 235.7 / 181.3 | 235.7 | 600 / 0 |
| 基线，第二遍 | 212.7 / 156.3 / 101.0 | 156.3 | 600 / 0 |
| 修订版，第二遍 | 131.7 / 108.0 / 102.1 | 108.0 | 600 / 0 |

两版均完整执行两遍：首遍用于观察预热过程，第二遍用于对比；每遍仍含每题 10 次预热与 600 次计时请求。第二遍三组 P95 中位数由 156.3ms 降至 108.0ms（-30.9%）；末组分别为 101.0ms 与 102.1ms（+1.1%），本次结果满足增幅约 10% 以内的目标。首遍修订版中位数 235.7ms 高于基线 154.6ms，不能忽略启动/预热差异；故四遍结果全部列出，不只保留有利样本。共 2400 次计时请求，0 错误。此为 Windows Docker Desktop 下的本机小探针，有明显预热与组间波动，不作容量或生产延迟结论。

本地后端 `mvn verify -Dtest=DraftActionIntegrationTest,DraftOutlineServiceTest,DraftServiceTest`：68 项，0 失败、0 错误、0 跳过（其中 HTTP 集成 34、进度单元 16、原草稿并发测试 18）。前端 build 与单元测试：78 项通过。全量后端与四个 CI job 的最终结果见 PR 检查。

本地真实浏览器 `central-outline.spec.mjs` 桌面与 375px 手机宽度：2 项通过。步骤为注册合成账号 → 导入集中大纲样例并仅在本机批准其拓展 → 打开简答题并输入 → 确认草稿 200 与整题状态 → 使用公开 SDK 上报指定小题 → 确认大纲更新 → 刷新后进度仍在，页面无 JS 异常且无横向滚动。截图仅保留在 git 忽略的 `.artifacts/screenshots/`。

## 范围与说明

旧基础简答题拓展只上报整题进度，因此其集中大纲小题仍显示未知；支持 targetId 上报的拓展可显示精确状态。浏览器测试先验证真实输入，再通过公开 SDK 上报小题并验证刷新持久化，不修改拓展。

文档已统一更新协议 03 §5.2、ADR-003 对应旧说明、接口映射与草稿增量响应说明。没有新增依赖或迁移；下载、历史、正式提交规则与投影缓存实现不变。

## 文件清单

- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/DraftActionService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/DraftOutlineService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/IdempotentDraftService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeReadService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeViews.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/DraftOutlineMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeReadMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeReadRows.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/web/PracticeActionController.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/DraftActionIntegrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/DraftOutlineServiceTest.java`
- `docs/adr/ADR-003-extension-rule-execution.md`
- `docs/design/02-api.md`
- `docs/design/03-rule-runner-protocol.md`
- `docs/design/07-frontend-api-map.md`
- `docs/reports/subquestion-draft-states.md`
- `frontend/practice/app.js`
- `frontend/practice/outline/outline-view.js`
- `frontend/practice/practice/draft-response.js`
- `frontend/test/browser/central-outline.spec.mjs`
- `frontend/test/draft-response.test.mjs`
- `frontend/test/outline.test.mjs`
- `loadtest/probes/draft-outline.py`
