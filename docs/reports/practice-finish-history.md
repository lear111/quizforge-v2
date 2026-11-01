# L1.3 完成练习与 L1.4 历史记录验收报告

## 规则与实现

依据 `docs/plan/02-practice-acceptance.md`。作者确认：开始完成任务即锁定作答与重做，失败恢复编辑；pending/null 允许完成，未人工改分时按 0 汇总，宿主不改写拓展评分语义。

- `POST C/finish`、`POST C/restart` 返回 202 和持久化 jobId；`GET C/jobs/{jobId}` 返回进度、结果或安全错误。请求带 requestId、roundId、summaryVersion，阶段变更与回执同事务；重放返回原任务，失败用新 requestId 重试。
- 完成任务每次只处理一道已保存的非空答案，判分在事务外。context 锁与状态乐观锁保证完成期间不能写入新答案；部分失败保留已成功提交和剩余草稿。pending/null 不阻止完成；未作答不调用 submit。
- 每个 app 一个后台线程，逐题重新入队，120 秒租约及 owner 条件更新阻止旧工作线程提交。后台规则请求标记 background，共享现有舱壁，默认 8 个名额时保留 4 个给前台；后台单次占用最多 1 个。每题后间隔 250ms，前台名额不足时让出并稍后重试。没有改 runner 的池配置和调度规则。
- 单题提交仍先保存草稿、再事务外判分、最后 revision CAS 与回执重查。仅移除不需要的未提交投影和历史快照计算；首次提交只建轻量轮次。草稿只增加所属 context 的共享阶段锁，不处理整本题库。
- 只有完成后的 restart 接受时计算一次 collection_snapshot；后台逐题冻结最后投影、作答、评分、小题状态、两层白板、尝试信号与拓展摘要，包含未作答题。全部成功后，同事务发布历史并重置作答/白板、递增版本、清空 current_round_id。中途失败不清空当前状态，部分历史不可见。
- 历史列表 SQL 只选摘要；详情才读冻结结构与 payload，不运行规则、不读取当前题目。历史笔记使用独立 revision，锁所属 round，只追加，复用 WhiteboardDocuments 的 2 MiB 上限。删除历史后取消历史资源授权；删除题库连带清理历史、笔记、资源授权索引与后台任务。
- 白板锁定沿用 WhiteboardService / WhiteboardDocuments 的 completed 判断；归档期间额外阻止修改。练习和历史路径不访问配额。

## 迁移与兼容

新增 `V11__practice_completion_history.sql`，未修改 main 已有迁移。实施期间 main 先后合入账号补全 V9 与默认认证策略 V10；已合并 main 56b7668，本 PR 尚未发布的迁移最终顺延为 V11，保留 main 两个迁移。

新历史为 snapshotVersion=1。旧历史为 legacy/version 0，只显示当时已有的题，不能恢复以前没有保存的未作答题；旧 submission_generation 同时累计重做，无法精确恢复尝试次数时记 null，不伪造数据。新增独立 MySQL 升级测试验证当前轮次、既有历史和旧信号的补记。

## 测试对应关系

`PracticeCompletionHistoryIntegrationTest` 使用真实 MySQL、真实 rule-runner、五种基础题型，通过真实 HTTP 调用。后台由 tick 门控，避免多个缓存 Spring 上下文消费彼此的测试任务；真实定时调度另外由 Compose 浏览器与性能探针覆盖。

| 规则 | 测试方法 |
| --- | --- |
| 五种题型批量完成、pending 允许完成、重放、一次归档 | `finishGradesSavedAnswersAndArchiveFreezesOnlyOnRestart`（五组参数） |
| 全题历史、未作答和空答案草稿、题序、晚追加笔记 | `historyContainsUnansweredQuestionsFrozenOrderAndLateNotes`（两组参数） |
| 失败保留草稿并恢复编辑 | `failureKeepsDraftsUnlocksAndCanBeRetriedWithoutDuplicateGrading` |
| 部分成功后失败，重试只处理剩余题 | `partialFinishFailureKeepsSuccessfulGradesAndRetryOnlyGradesRemainingDrafts` |
| 事务外判分，其他题库前台提交可用 | `gradingIsOutsideTransactionAndOtherCollectionSubmissionRemainsAvailable` |
| 未完成不能再练、汇总版本冲突 | `unfinishedRestartAndStaleFinishAreRejected` |
| 同一 requestId 真并发只建一个任务 | `sameFinishRequestConcurrentlyCreatesOneTask` |
| 历史笔记追加、版本、重放、禁止修改既有笔迹 | `annotationsAreAppendOnlyVersionedIdempotentAndDoNotChangeFrozenAnswer` |
| 同请求/不同请求并发历史笔记 | `concurrentHistoryNotesRespectRevisionAndIdenticalRequestReplay`（两组参数） |
| 冻结不依赖当前题库/runner，历史/任务 IDOR 与删除 | `archivedHistoryDoesNotUseCurrentBankOrRunnerAndEnforcesOwnership` |
| 被移除拓展的替代页保留作答与评分 | `removedExtensionPageKeepsHistoryAndRegistersReadablePlaceholder` |
| 归档失败不重置，重新请求可重试 | `archiveFailureDoesNotResetAnswersAndNewRequestCanResume` |
| 过期租约恢复、旧工作线程被围栏拦截 | `expiredWorkerLeaseCanBeRecovered`、`reclaimedLeaseFencesOldWorkerBeforeAnyGradeCommit` |
| 最后尝试、首次对错、人工评分来源 | `lastAttemptSignalsAndManualSourceAreFrozenWithoutEarlierAttempts` |
| 完成后未作答题的缓存 stamp 不污染新轮能力 | `completedUnansweredStampCannotReuseReadonlyCapabilitiesAfterRestart` |
| 列表不读取 payload，历史批注 IDOR | `historyListDoesNotLoadPayloadAndAnnotationIdorCannotWrite` |
| 历史资源引用持续授权，删除后取消 | `historyAssetsRemainAuthorizedAfterBankChangesUntilHistoryIsDeleted` |
| 旧部分历史明确标识 legacy | `oldPartialHistoryIsLabeledLegacyInsteadOfInventingMissingQuestions` |
| 删除题库清理全部关联记录 | `deletingCollectionRemovesHistoryNotesJobsAndResourceReferencesTogether` |
| V10 → V11 升级补记 | `PracticeCompletionMigrationTest.upgradePreservesOldAttemptsAndDistinguishesCurrentRoundFromFrozenHistory` |

另外保留并更新原 `PracticeLifecycleIntegrationTest`、`SubmitActionIntegrationTest`、`DraftActionIntegrationTest`，继续覆盖幂等、同题并发、不同题首次轮次去重、判分期间草稿不阻塞、判分不可用时保留答案。`RuleRunnerClientTest` 新增后台名额预留、退出作用域后恢复前台优先级、单名额配置与许可不泄漏。前端新增轮询成功/失败/超时、pending 完成、冻结能力与历史笔记冲突恢复测试。

## 浏览器验收

本机独立 Compose、公开 samples，不访问远程服务或真实用户数据。桌面 1440×960 与手机 375×812 均覆盖：提交 → 重做 → 再提交 → 人工评分 → 汇总 → 完成练习 → 再练一次 → 历史查看 → 追加笔记 → 刷新 → 删除。另回归 L1.5 答题区打字、插图、手写、上传提交截图、刷新后两层白板与图片可读（含 nginx Range 读取）。

截图只存本机 git 忽略目录 `.artifacts/local/finish-history-screenshots/`，不提交。首次与完整 Maven 并行时桌面出现题卡 10 秒加载超时；隔离串行运行最终镜像后 4/4 通过，未放宽生产超时或断言。

手动复验：打开练习空间，在简答题内作答/提交，再重做、提交并拖动人工评分至满分；进入汇总点击完成，等待完成后点击再练一次；打开历史确认原题序、成绩和作答，切换白板笔记层追加笔迹，刷新确认保留；新轮作答应为空，历史不可重做/改分。

## P95 对比

### 最终生产代码版本复测（15d4d31）

同步 main 56b7668 后，先跑一组兼容性复验，再在同一最终版本、相同配置下完整复测三组。每阶段每组 90 次，前台 4 VU；每组后台处理 300 题，测量窗口内始终运行且实际推进。四组共 1080 次计时提交，全部 200、graded、满分，错误 0；四个后台任务全部完成。结果如下，未删除不利组，也未为复测调整参数：

| 组 | 无任务 P95 ms | 完成期间 P95 ms | 完成后 P95 ms | 测量窗口内后台进度 |
| --- | ---: | ---: | ---: | --- |
| 首次兼容性复验 | 896.865 | 1093.511 | 506.540 | 1 → 20 |
| 后续复测 1 | 364.783 | 318.592 | 413.108 | 1 → 11 |
| 后续复测 2 | 512.384 | 498.274 | 925.906 | 1 → 14 |
| 后续复测 3 | 389.517 | 475.832 | 589.871 | 1 → 16 |
| 后续三组合并原始样本 | 398.210 | 436.957 | 617.429 | 三组均推进 |
| 最终版本全部四组合并原始样本 | 623.419 | 686.797 | 599.259 | 四组均推进 |

后续三组合并 during 上升约 9.7%，最终版本全部四组合并上升约 10.2%；首次复验与后续第 3 组各上升约 22%。其他两组没有上升，无任务阶段本身也有明显波动。因此**不能宣称性能约束已完全验收通过**：后台并发与频率已受限，但本次本机小样本没有证明对前台 P95 零影响，也没有排除约 10% 的总体影响。正式容量对照仍需复核；不以早期版本较有利的数值代替最终版本证据，不据此作容量结论。

原始数据位于忽略目录 `.artifacts/finish-submit-latency-latest.json` 与 `.artifacts/finish-submit-latency-final-three.json`。复测沿用下面列出的隔离 Compose、探针和资源限制；默认验证码开启时的真实注册、导入、草稿、提交和异步完成链路也均通过。下面保留较早版本的测量与调参过程，供追溯，不作为最终版本的性能结论。

### 较早版本与调参过程

测量版本：19a1ddd（基于 main 70bf289）。随后合入 main 56b7668 的默认验证码、认证限频与密码哈希排队；该变更不处理题目 actions，额外复验其兼容性，未据此改写下述原始结果。

环境：Windows Docker Desktop，本地 Docker Engine 6 vCPU / 15.33 GiB；被测 app 1.5 核/3 GiB、MySQL 1 核/2 GiB、runner 1.25 核/2 GiB、nginx 0.25 核/1 GiB，合计 4 核/8 GiB。runner limit=8、Java 舱壁=8，后台最多 1 个、预留前台 4 个。未更改 runner 的每包 2 worker、LRU、500 请求回收等规则。探针客户端为额外 0.25 核/128 MiB 的 Node 22 容器，通过本项目 Docker 网络请求 nginx；同源 Origin、Cookie 与真实 HTTP，复用连接，计时包含响应读取与解析。

公开 `loadtest-300.qbank`，单选/多选/判断混合，按 schema 生成正确答案。先预热 300 次提交；每组三阶段各 90 次，前台并发 4，每组另预热 30 次。前台/后台使用两个独立用户与不同实例；后台每次从 300 份已保存草稿开始。三组每阶段合计 270 次、全部 HTTP 200/graded/满分，错误 0；后台 3×300 题均完成。程序断言每个 during 窗口结束时任务仍在执行且进度确实增长，避免“只排队但没在判分”的假对照。

| 组 | 无任务 P95 ms | 完成期间 P95 ms | 完成后 P95 ms | 测量窗口内后台进度 |
| --- | ---: | ---: | ---: | --- |
| 1 | 584.640 | 409.513 | 445.053 | 1 → 7 |
| 2 | 455.659 | 528.437 | 1208.111 | 1 → 16 |
| 3 | 1065.144 | 371.251 | 465.371 | 1 → 12 |
| 合并原始样本 | 584.640 | 477.500 | 601.855 | 三组均推进 |

合并后完成期间比基线低约 18.3%，本次没有观察到总体 P95 恶化；不能据此声称后台任务提升性能。第 2 组 during 比该组 baseline 高约 16.0%，但无任务的 after 波动更大，说明小样本/共享本机存在明显波动。原始值已全部列出，不隐藏不利组；此结果只覆盖上述 4 VU、本机公开样例，不等价于正式服 100 VU 的性能保证，仍需正式容量压测复核。

初次探索（25ms 调度、宿主机逐请求建连接、预热较少）合并 P95 为 558.534/740.034/420.864ms；因此降低后台频率为 250ms，并改用预热后 Docker 内的复用连接对照。宿主机复测也出现完成后 1.2s 的高延迟，不能把所有波动归因于后台任务，最终采用上述完整三阶段结果，不做单因素因果结论。没有为了测量而停止作者的其他本地环境。

复现配置与命令（只允许本机与独立项目）：

```powershell
docker compose -p qf-practice-finish-local -f deploy/docker-compose.yml -f loadtest/probes/finish-compose.yml up -d --build
python -B loadtest/probes/finish-submit.py --project qf-practice-finish-local --runs 3 --vus 4
docker compose -p qf-practice-finish-local -f deploy/docker-compose.yml -f loadtest/probes/finish-compose.yml down
```

原始延迟只保存在 git 忽略的 `.artifacts/finish-submit-latency-container.json`；不包含账号密码或 Cookie，不提交、不上传。探针验证本机 Docker、项目名前缀、项目的回环端口；用户凭据仅在进程内，通过真实接口注册和导入；默认注册验证码使用独立环境的 fake 实现，脚本按唯一测试邮箱只提取自己的验证码，不输出日志、验证码或 Cookie。只在独立数据库批准公开样例拓展。


## 最终验证

最终生产代码提交 15d4d31 的 [CI 四项全部通过](https://github.com/lear111/quizforge-v2/actions/runs/41337561920)：`mvn verify` 共 **3203 项，失败 0、错误 0、跳过 3**；前端构建与 68 项测试通过，rule-runner 128 项通过，Compose 冒烟与 14 项真实浏览器测试通过，并确认共享 blob 的 runner 挂载只读。[桌面/手机浏览器截图与 trace](https://github.com/lear111/quizforge-v2/actions/runs/41337561920/artifacts/13096560079)。后续提交只整理本报告，最新 Checks 同样须全绿后转待审。

CI（提交 ae612ed）：`mvn -B -ntp verify` 共 3189 项，失败 0、错误 0、跳过 3（既有 archive 分层/差异测试）；前端构建与 68 项测试通过，rule-runner 128 项通过，Compose 冒烟与 14 项真实浏览器测试通过。新增完成/历史集成测试 26 项，迁移测试 1 项；RuleRunnerClient 24 项中的 3 项为后台预留与优先级新增覆盖。本地对应提交另跑 `clean verify -Dtest=PracticeCompletionHistoryIntegrationTest,PracticeCompletionMigrationTest,RuleRunnerClientTest`：51 项、失败 0、错误 0。收尾同步 main 56b7668（V10 默认注册验证码）后，迁移顺延 V11，测试注册复用 main 的 fake 辅助方法；最终提交的 CI 结果见本 PR 最新 Checks / 描述。

## 发现的问题与边界

- 按产品决定修正旧前端“有 pending 即禁止完成”及“未完成也可 restart”的行为；普通简答题 graded/0 与答题区 pending/null 均保持拓展语义。
- 新旧轮次能力不同，即使未作答题 revision=0，也不能复用 completed 的只读响应；stamp 已加入阶段并回归验证。
- 旧快照缺失的题和尝试数据无法补造，按上述 legacy/null 明确返回。此限制不影响新历史。
- 管理员强制删除与 GC 尚属后续功能。现有历史拓展缺失时使用可注册的替代页，保留冻结作答/评分；资源文件被物理移除时底层 nginx 仍会返回 404，统一图片占位体验尚未新增。
- GC 必须保留：未删除历史的 round_question.extension_digest、practice_history_asset、payload 中 answer/提交截图/白板的用户资源，以及 practice_history_annotation.notes 追加引用。删除历史/题库取消相应授权引用；本 PR 不实现物理文件清理。
- 每 app 一个后台调用；多 app 部署时后台总并发会随实例数增加，需按部署容量一起限制。进度轮询为 500ms，页面等待最多 10 分钟，超时仅提示刷新，不取消持久化任务。


## 文件清单

共 52 个文件；生产实现、迁移、回归测试、前端、设计和本机复验工具如下。

- `backend/src/main/java/cloud/treenewbee/quizforge/content/infrastructure/ContentAccessMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/DraftService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeHistoryService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeLifecycleJobs.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeLifecycleService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeReadService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeSummaryService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeWrites.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/QuestionStamps.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/SubmitService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/LifecycleJobMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeContextMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeContextRow.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeHistoryMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeLifecycleMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeReadMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/QuestionStateMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/web/PracticeHistoryController.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/web/PracticeLifecycleController.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/whiteboard/WhiteboardService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/quota/application/PrivateCollectionService.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/quota/infrastructure/CollectionDeletionMapper.java`
- `backend/src/main/java/cloud/treenewbee/quizforge/rules/RuleRunnerClient.java`
- `backend/src/main/resources/db/migration/V11__practice_completion_history.sql`
- `backend/src/test/java/cloud/treenewbee/quizforge/MySqlTestBase.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/DraftActionIntegrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/PracticeCompletionHistoryIntegrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/PracticeLifecycleIntegrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/application/SubmitActionIntegrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/infrastructure/PracticeCompletionMigrationTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/practice/infrastructure/SubmitInfrastructureMapperTest.java`
- `backend/src/test/java/cloud/treenewbee/quizforge/rules/RuleRunnerClientTest.java`
- `docs/design/01-database.md`
- `docs/design/02-api.md`
- `docs/design/07-frontend-api-map.md`
- `docs/plan/02-practice-acceptance.md`
- `docs/reports/practice-finish-history-plan.md`
- `docs/reports/practice-finish-history.md`
- `frontend/practice/app.js`
- `frontend/practice/bridge/extension-pages.js`
- `frontend/practice/listening/listening-player.js`
- `frontend/practice/practice/lifecycle-jobs.js`
- `frontend/practice/practice/practice-context.js`
- `frontend/practice/practice/practice-summary.js`
- `frontend/practice/practice/save-recovery.js`
- `frontend/test/browser/practice-lifecycle.spec.mjs`
- `frontend/test/browser/read-slices.spec.mjs`
- `frontend/test/lifecycle-jobs.test.mjs`
- `frontend/test/practice-lifecycle.test.mjs`
- `frontend/test/save-recovery.test.mjs`
- `loadtest/probes/finish-compose.yml`
- `loadtest/probes/finish-submit.py`
