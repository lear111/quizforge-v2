# L1.8 存储配额与下载流量

依据：练习验收设计 §4/§7；旧 Hub `docs/archive/CLOUD_IMPLEMENTATION.md` 的默认额度与计入规则。两个产品边界已确认：每个私人题库实例独立计费；历史实例按可恢复大小补记，新导入按实际展开字节计量。

## 实现与文件

- `V6__storage_and_download_quota.sql`：站点默认值、用户账本、按所有者划分的存储条目、管理员调整审计；从 V5 补记已有实例与练习数据，不改旧迁移。
- `quota/application/QuotaService`、`quota/infrastructure/QuotaMapper`：统一替换条目大小、删除释放、每日下载扣减、覆盖额度与查询。所有记账写入要求调用方已有事务（`MANDATORY`）。
- `PracticeStorage`、`PracticeQuotaMapper`：测量本次变化的 JSON 载荷，供草稿、提交、重做、人工评分与再练一轮使用。
- `PrivateCollectionService`、`CollectionDeletionMapper`：按所有者删除实例的可变数据并释放全部关联条目。
- `QuotaController`、`PrivateCollectionController`：用户用量、管理员覆盖与题库删除 API，契约见设计 02 §8。
- `ImportService`：解压时记录文件字节数，在原业务事务内逐实例记账。
- `DraftService`、`SubmitService`、`PracticeLifecycleService`：成功写入后、事务提交前更新载荷占用。未改变乐观锁、先存草稿再事务外判分、回执与冲突后新事务重查顺序。
- `frontend/public/{index.html,auth.js,auth.css,quota.js}`：用户中心两张用量卡片、刷新/错误状态、手机布局；纯文本显示服务端数据。
- `frontend/practice/library/library-management.js`、`app.js`：删除按钮调用 DELETE 实例接口，确认提示与完成通知明确连带删除历史/笔记，刷新目录并关闭已删除页面。
- `frontend/practice/outline/outline-view.js`：删除后进入空练习页时，大纲首次渲染须先检查旧渲染记录存在；补充空页回归。自动切换下一题库也跳过审核中实例，避免删除已成功却在弹窗显示打开失败。
- 测试：`QuotaIntegrationTest`、`QuotaMigrationTest`、`PracticeLifecycleIntegrationTest` 的新增场景，前端用量和删除适配单元测试，以及 `quota.spec.mjs` 的真实浏览器验收。
- `ImportIntegrationTest` 中两项直接调用服务的旧用例改为创建真实账号，保留原并发/哈希断言；`MySqlTestBase` 仅在测试中将每个上下文连接池设为最多 4、最少空闲 0，避免越来越多的缓存上下文保留默认 10 个连接而耗尽共享 MySQL 的连接上限。
- `.dockerignore` 排除 `tools/**/local`，避免本机私有题库进入镜像构建上下文。

## 计量口径

私人新导入的每个实例 = 包格式标识原字节 + 该题库目录的所有文件原字节 + 该题库绑定拓展目录的所有文件原字节。绑定包含未被大纲引用的题及根级默认拓展；同包共享拓展在各实例重复计入。同一用户重导入、不同用户导入均独立计入，不因物理内容去重减少用量。未绑定于任何题库的附带拓展不产生实例占用。公共题库内容不计入，用户在公共题库产生的个人练习数据仍计入。

练习按数据库规范化 JSON 的 UTF-8 字节数计量：state 的 answer / answer_state / result / outline_states / projection，白板 draft，轮次 collection_snapshot / summary，单题快照 payload。修订号、时间戳、数据库索引、幂等回执及配额账本是平台开销，不计入。替换大小用差额记账；清空释放，历史保留时仍收费。现有上传资源按 user/hash 归属补记一次，后续上传与白板/历史删除功能须复用同一服务。

历史导入没有原 bank.json、原始展开大小、遗漏题数据和完整原绑定等信息。迁移使用可恢复元数据及题目 JSON、题库资源、题目引用拓展文件大小补记；因此不声称等同原包大小，不改写内容，不截断已有数据。新导入使用实际原字节消除该差异。

## 事务与并发

按 user_id 锁定 `quota_account`（初始化用直接获取排他锁的幂等 UPSERT，再 SELECT FOR UPDATE），持锁读取条目旧值、检查增量与更新用量。相同用户并发导入、下载、管理员修改在该行串行，不会各自基于旧余额通过；不同用户独立。配额与业务数据同事务失败回滚，拒绝增长后已用量不变。降低额度后允许缩减占用。

练习操作一次可能同时缩减 live state、增加轮次摘要或替换快照，因此用 `setStorageBatch` 检查全部条目的净增量；restart 删除白板的释放也合并进入同一次检查。不会因中间更新顺序误拒绝最终未超额的操作；调低额度后，最终用量仍高于新上限的净缩减也允许。拒绝后整批条目及业务数据回滚。

业务行锁先于账本锁；提交仍先保存草稿，事务外调用 rule-runner，再在最终事务锁存档/状态/轮次、写快照/回执并记账。最终提交超额只回滚结果/快照/回执，前阶段已提交的草稿保留。没有把网络判分包进数据库事务。删除先锁实例、按顺序锁存档/状态，最后释放账本；释放查询采用当前读，不使用过期一致性快照累计。

并发测试以 MyBatis 门控保证两次真实导入在两个不同 MySQL connection / 活跃事务中同时到达插入，再放行；额度只容一份，断言一份成功、一份配额拒绝且恰好一个实例。下载以 barrier 同时预约，只允许一笔通过。不用 sleep 判断并发状态。

下载账本保存北京日期；注入 Clock 决定日期及下次重置时刻，不依赖服务器本地时区。跨日读取显示零，下次扣减写入新日期；重启服务不会丢当日用量。

## 测试对应

| 范围 | 测试 |
| --- | --- |
| 默认值、null 跟随、降额、审计、权限 | `defaultsOverridesNullAndLoweringDoNotDeleteOrResetUsage`、`nullableOverridesFollowPersistedSiteDefault`、`quotaHttpIsPrivateAdminOnlyAndNormalReadsNeverConsumeTraffic` |
| 新导入精确计费、重复实例、共享拓展、跨用户去重 | `importChargesExactExpandedBytesAndDeletingReleasesOnlyThatInstance`、`groupWithSharedExtensionChargesAnExpandedCopyForEachBank`、`sharedPhysicalContentIsChargedIndependentlyForEachUser` |
| 公共内容不计费 | `publicContentReferenceDoesNotChargePrivateStorage` |
| 导入拒绝无业务残留、并发不超用、HTTP 错误 | `overQuotaImportRollsBackEveryBusinessRowAndLedger`、`concurrentImportsCannotExceedSingleInstanceLimit`、`importHttpReturnsQuotaErrorAndDeleteHttpIsOwnerScoped` |
| 同事务回滚、下载并发、跨日及服务重建 | `storageAndDownloadRollbackWithCallerFailureAndRequireTransaction`、`concurrentDownloadReservationsCannotExceedLimit`、`downloadLimitResetsAtBeijingMidnightAndSurvivesNewServiceCall` |
| 批量净变化、降额后的缩减、超额整批回滚 | `storageBatchChecksNetGrowthAndRollsBackAllItemsOnOverage`；真实 runner 练习记账用例另在额度用满并有大白板时执行 restart |
| 删除历史与白板、IDOR、释放 | `deletionRemovesHistoricalSnapshotsWhiteboardsAndReleasesAllOwnedItems`、上述 HTTP 删除测试 |
| 草稿超额保留旧答案 | `draftGrowthIsBilledInSameTransactionAndFailurePreservesOldAnswer` |
| 真实 runner 的提交/review/retry/restart、重放不重扣 | `practiceQuotaTracksSubmissionReviewRetryAndRestartWithoutChargingReplay` |
| 最终提交超额保留前阶段草稿，结果/轮次/回执回滚 | `quotaFailureDuringFinalSubmissionRollsBackResultRoundAndReceiptButKeepsPreviouslySavedDraft` |
| 旧 V5 升级补记 | `QuotaMigrationTest` |
| 网页两卡、零额度/降额、删除 API 适配 | `quota.test.mjs`、`library-delete.test.mjs` |
| 桌面/375px 真实注册→导入→查看卡片→网页删除→用量下降 | `quota.spec.mjs` |

## 验证与边界

测试数量及 CI 结果在 PR 描述更新。配额专项测试使用真实 MySQL；导入计费专项隔离规则调用，练习集成和浏览器另外使用真实 rule-runner，不将 mock 验证称为全链路验收。

浏览器首轮 CI 的新增手机用例存在初始化时序：其他用例已审核相同样例后，进入练习空间会自动打开题库并关闭侧栏。已等待题库激活完成后再打开侧栏，后续四项 CI 通过。本机完整套件另一次在旧 `read-slices.spec.mjs:115` 的刷新后点击题库遇到侧栏移出视口超时（该用例该次之前的配额测试已成功，CI 完整 12 项通过）；这是旧用例的同类就绪边界，本任务记录但未修改旧读题测试或生产侧栏行为。

目前没有题库导出/下载路由，`consumeDownload` 已完成持久化、跨日、并发与回滚测试，留给对应接口在准备文件后、授权响应前的事务内调用，按授权文件大小计入（网络中断不自动退还）。正常读题/读图路由没有接入扣减。上传图片/截图、白板写入、历史删除的未来接口须接入统一服务；当前已有的草稿与轮次写路径已接入，既有白板/上传资源已补记。尚无独立笔记表，现有快照及白板随题库删除。

超额导入已按 ADR-007 允许在解析阶段产生无引用 blob；业务事务回滚不删除这些物理文件，孤立文件清理由后续 GC 完成。共享题库/拓展/blob 本任务不物理删除，用户界面不展示是否发生全局去重。
