# 练习后台任务后续改进（PR 1）

## 范围与行为

基于 main c43eaed，分支 `fix/lifecycle-jobs-followup`。只处理 #89 后台任务审查意见；小题草稿状态是后续 PR 2，须等作者合并本 PR 后再从最新 main 开始。

- 单工作线程改为可唤醒的单次调度。连续空轮询等待 500 / 1000 / 2000 / 4000 / 5000ms，之后封顶 5 秒；领取到工作后恢复默认 250ms。有新 finish/restart 任务时，只在事务提交后立即唤醒本实例；回滚和幂等重放不会多唤醒。执行期间收到唤醒则在本次 tick 结束后立即继续，仍然串行。
- 取消排程使用代次校验，避免“取消成功前回调已进入执行”的竞态造成两个调度链。异常后仍会安排下一次轮询，关闭后不再安排。
- completed/failed 任务默认保留 7 天，每小时每种终态各删除最多 200 行；每条 DELETE 自身提交，不循环追删、不把清理包进领取事务。SQL 同时限定终态，不能误删 queued/running。
- 新增 V13（main V12 已用于管理审计），不修改任何已应用迁移：生成列 `available_at` 与 `(available_at,id)` 索引用于领取；`(status,updated_at,id)` 用于清理；`archive_position` 保存归档游标。
- 归档改为 `position > lastPosition ORDER BY position LIMIT 1`。首次游标 NULL 无下界；快照、完成数量和 position 同事务提交，心跳不推进游标。升级时从执行中任务已提交的单题快照恢复最大 position，进程重启和租约接管后继续。
- 保留原租约 owner 围栏、SKIP LOCKED、逐题事务外判分、背景名额限制与 250ms 工作节奏。只格式化本 PR 修改到的 Java 文件。

## 配置与保留边界

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `qf.practice.jobs.interval-ms` | 250 | 有工作时的间隔，沿用已有配置；空闲最多 5 秒 |
| `QF_PRACTICE_JOBS_RETENTION` / `qf.practice.jobs.retention` | P7D | 终态 updated_at 严格早于保留边界才删除 |
| `QF_PRACTICE_JOBS_CLEANUP_INTERVAL` / `qf.practice.jobs.cleanup-interval` | PT1H | 两轮清理之间的间隔 |
| `QF_PRACTICE_JOBS_CLEANUP_BATCH_SIZE` / `qf.practice.jobs.cleanup-batch-size` | 200 | 每种终态每轮最多删除的行数，允许 1..1000 |

时长使用 ISO-8601（例如 P7D、PT1H），必须为正。默认每小时最多删除 400 行；如长期终态任务产生速度更高，可缩短清理间隔或提高有界批量，不在单次 tick 中删除到空。其他实例不通过本进程通知，最长 5 秒自行发现新任务。

清理只移除任务行，保留历史、作答、context 与幂等回执；清理后的 jobId 查询返回原有 NOT_FOUND。旧 requestId 仍重放原回执，不会重新执行已结束的任务；若其 jobId 已过保留期，则应从当前练习/历史状态查看结果。

## 领取查询与 EXPLAIN

复现方法：`LifecycleJobSchedulingMigrationTest.upgradeBackfillsSparseArchiveCursorAndClaimUsesOrderedRangeInsteadOfFilesort`。独立 Testcontainers MySQL 8.4，先运行 Flyway 至 V11，写入 10000 条 completed/failed 合成任务、100 条 queued 和 1 条过期 running。ANALYZE TABLE 后对旧 SQL 做 EXPLAIN；迁移至最新版本，对 Mapper 注解中的真实新 SQL 做 EXPLAIN，再把终态历史增至 20000 条复验。固定查询时间为 UTC `2026-11-01 12:00:00`。

旧 SQL（SELECT 为原有会话任务字段）：

```sql
SELECT id,user_id,collection_id,context_id,round_id,kind,status,total,completed,
       lease_owner,lease_until,result,error_code,error_message
FROM practice_lifecycle_job
WHERE status='queued' OR (status='running' AND lease_until < ?)
ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED;
```

新 SQL（实际字段另外返回 archive_position）：

```sql
SELECT id,user_id,collection_id,context_id,round_id,kind,status,total,completed,
       lease_owner,lease_until,result,error_code,error_message,archive_position
FROM practice_lifecycle_job
WHERE available_at <= ? AND (status='queued' OR lease_until < ?)
ORDER BY available_at,id LIMIT 1 FOR UPDATE SKIP LOCKED;
```

生成列为 queued 的 updated_at、running 的 lease_until，终态为 NULL。保留 `lease_until < now` 的严格过期边界；新排序按可领取时间和 id，以便直接按索引找到首个候选，不需要收集全部候选再排序。

本机真实输出摘要（两次执行结果一致，耗时仅作记录）：

| 条件 | type | key | key_len | rows（估算） | Extra |
| --- | --- | --- | ---: | ---: | --- |
| 旧查询，10000 终态 | range | idx_lifecycle_queue | 9 | 101 | Using index condition; Using filesort |
| 新查询，10000 终态 | range | idx_lifecycle_available | 8 | 101 | Using index condition; Using where |
| 新查询，20000 终态 | range | idx_lifecycle_available | 8 | 101 | Using index condition; Using where |

新查询 EXPLAIN ANALYZE（20000 终态）核心输出：

```text
Index range scan on practice_lifecycle_job using idx_lifecycle_available
over (NULL < available_at <= '2026-11-01 12:00:00.000')
(cost=45.7 rows=101) (actual time=0.0244..0.0244 rows=1 loops=1)
```

旧索引已经能按 status 过滤终态，实测没有全表扫描，不能声称旧代码必然随历史任务数线性退化。本次去掉的是 OR 条件下与排序不匹配导致的 filesort；新查询终态 NULL 不在时间范围中，历史翻倍不增加候选范围，LIMIT 实际读一行即可停止。索引定位仍有正常的 B-tree 对数成本，不承诺与任意数据量完全无关。

## 测试对应

新增 18 项，无固定 sleep：

| 规则 | 测试 |
| --- | --- |
| 空闲退避、5 秒封顶、恢复 250ms | `LifecycleJobPollerTest.emptyPollsBackOffToFiveSecondsAndWorkResetsTo250ms` |
| 立即唤醒、取消竞态 | `wakeCancelsIdleWaitAndStaleCallbackCannotExecuteOrScheduleAgain` |
| 执行时唤醒、不并发执行 | `wakeDuringTickRunsImmediatelyAfterItWithoutConcurrentTicks` |
| 异常继续、关闭、重复 start | `errorStillSchedulesAnotherPoll`、`closeCancelsPendingTickAndWakeCannotRestartIt`、`repeatedStartAndWakeBeforeStartDoNotCreateExtraCallbacks` |
| 保留时长/间隔/批量配置、清理不在领取事务中 | `LifecycleJobMaintenanceTest.cleanupIsBoundedOutsideClaimTransactionAndUsesConfigurableRetentionAndInterval` |
| 禁用测试调度不创建线程 | `disabledSchedulerDoesNotPollOrCreateThreads` |
| finish/archive 提交后唤醒、幂等不多唤醒 | `LifecycleJobFollowupIntegrationTest.newJobWakesOnlyAfterCommitAndReplayDoesNotScheduleAnotherWake`（2 组） |
| finish/archive 回滚不唤醒、无残留 | `rolledBackCreationDoesNotWakeAndLeavesNoJobOrReceipt`（2 组） |
| 仅清理严格过期终态、边界与批量 | `cleanupDeletesOnlyStrictlyExpiredTerminalJobsAndRespectsBatchSize` |
| 租约边界、终态排除、yield 重新排队 | `nextIgnoresEndedAndUnexpiredLeasesAndRequeuesAfterYield` |
| 真事务竞争时跳过已锁行 | `nextSkipsRowLockedByAnotherWorker` |
| 稀疏/非零/负 position、租约恢复续跑 | `archiveUsesPositionKeysetWithGapsAndResumesAfterLeaseRecovery` |
| 快照与游标原子回滚、旧 owner 不能推进 | `archiveCursorAndSnapshotRollbackTogetherAndOldOwnerCannotAdvanceIt` |
| 旧归档游标升级与前后 EXPLAIN | `LifecycleJobSchedulingMigrationTest.upgradeBackfillsSparseArchiveCursorAndClaimUsesOrderedRangeInsteadOfFilesort` |

本机新增测试 `mvn verify -Dtest=LifecycleJobFollowupIntegrationTest,LifecycleJobSchedulingMigrationTest,LifecycleJobPollerTest,LifecycleJobMaintenanceTest`：18 项、失败 0、错误 0、跳过 0。另一次 clean verify 中原有 `PracticeCompletionHistoryIntegrationTest` 26 项和 `PracticeCompletionMigrationTest` 1 项通过。

首轮分支手动 CI：后端 mvn verify 3228 项、失败 0、错误 0、跳过 3 项既有 archive 测试；账号迁移工具 17 项通过。最终四个 job 与浏览器数量以 PR 描述的最终提交验证记录为准。

## 文件清单

- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/LifecycleJobPoller.java`：可控、可唤醒的单次调度。
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/application/PracticeLifecycleJobs.java`：提交后唤醒、定期有界清理、持久化键集游标；局部文件格式整理。
- `backend/src/main/java/cloud/treenewbee/quizforge/practice/infrastructure/LifecycleJobMapper.java`：领取、清理与游标 SQL；局部文件格式整理。
- `backend/src/main/resources/db/migration/V13__lifecycle_job_scheduling.sql`：新索引、游标与升级补记。
- `backend/src/main/resources/application.yml`：清理配置入口。
- 上述四个新增测试类。
- `.github/workflows/ci.yml`：增加 workflow_dispatch，允许分支先跑同一套 CI，四项通过后再开非草稿 PR。
- `docs/superpowers/specs/2026-11-01-lifecycle-jobs-followup-design.md`、本报告：设计选择与实测证据。

## 发现的问题与边界

- 本机曾因 target/classes 留有旧分支迁移误报重复 V10；clean 后消失，未修改既有迁移规避。
- 新夹具最初混用 JDBC Timestamp 与显式 UTC 的 Instant Mapper，在非 UTC 本机时区导致租约边界夹具偏移。现夹具以 UTC LocalDateTime 写入；生产 Instant 处理器保持不变。
- V12 已随管理后台合并，已 rebase main c43eaed 并顺延为 V13。没有改管理后台代码。
- 本 PR 不改后台名额与节流频率，不对 #89 的前台 P95 限制做新的性能结论；PR 2 将另做草稿保存 P95 对照。
- PR 2 依赖本 PR 合并，未提前开分支或绕过依赖。
