# M3 切片 5 准备：提交判分基础设施

基于 `main@143a57b`，分支 `feat/m3-submit-infra`。只提供判分适配和数据访问；不实现 SubmitService 或新 HTTP 接口，不开放 canSubmit，不修改 QuestionStateMapper、DraftService、IdempotentDraftService，也不修改迁移和依赖。

## 作者可调用的签名

```java
// practice.application
GradeOutcome SubmitGrader.grade(String userId, String collectionId, String questionId, JsonNode answer);

public record GradeOutcome(
    String resultJson, String projectedJson, BigDecimal score, BigDecimal maxScore,
    String gradingStatus, String outlineStatesJson) {}

// 复用读题的用户归属、未删除实例、题库审核、精确题目 ID 查询和 ContentPages.rulePackage。
PracticeReadService.RuleQuestion PracticeReadService.ruleQuestion(
    String userId, String collectionId, String questionId);
public record RuleQuestion(JsonNode data, RulePackage rulePackage) {}

// practice.infrastructure.PracticeContextMapper
Optional<PracticeContextRow> lockForUpdate(String contextId, String userId);
int setCurrentRound(String contextId, String userId, String roundId, Instant now);
int bumpProgressVersion(String contextId, String userId, Instant now);
public record PracticeContextRow(String id, String currentRoundId, long progressVersion) {}

// practice.infrastructure.PracticeRoundMapper
int insertRound(PracticeRoundRow row);
int upsertRoundQuestion(PracticeRoundQuestionRow row);
public record PracticeRoundRow(
    String id, String userId, String collectionId, String contextId, String mode, String status,
    String collectionSnapshot, String summary, Instant createdAt, Instant updatedAt,
    Instant completedAt, Instant deletedAt) {}
public record PracticeRoundQuestionRow(
    String roundId, String questionId, String userId, String payload,
    byte[] extensionDigest, Instant updatedAt) {}
```

- `grade` 只执行一次 `execute("submit", rulePackage, {data, answer, withScore:true})`。调用前复核访问权限；不会创建 context、轮次、作答或回执，也不使用未提交投影缓存。必须由未来 SubmitService 在写事务外调用。
- 所有 `RuleResult.ok=false` 按本任务要求转为 422。保留 `RULE_REJECTED`、`RULE_TIMEOUT`、`SCORE_UNAVAILABLE`、`INVALID_OUTLINE_ITEMS`、`INVALID_OUTLINE_STATES`、`UNSUPPORTED_API_VERSION`；未知或 null code 收敛为 RULE_REJECTED。用户消息为固定中文，不返回 runner 内部消息。
- runner 不可用时不包装、不吞掉 `RuleRunnerException`。新增全局异常处理映射为 503 RULE_UNAVAILABLE；这修补了现有通用处理会返回 500 的缺口。异常详情只记服务端日志。
- `gradingStatus=pending` 时 `score=null`；未返回 outlineStates 时 `outlineStatesJson=null`，显式空数组保留为 `"[]"`。不将缺失分数默认为 0；不完整的成功响应抛 RuleRunnerException。
- 判分数值原样转 BigDecimal，不舍入、不截断。此层不写 DECIMAL 列，不能代替未来提交写库时的精度策略。

## SQL 与调用约定

| 方法 | SQL 行为 | 归属 / 并发 |
| --- | --- | --- |
| lockForUpdate | SELECT id,current_round_id,progress_version FROM practice_context WHERE id=? AND user_id=? FOR UPDATE | 调用者必须先开启事务并持有到所有写入结束；无归属 / 不存在返回 Optional.empty |
| setCurrentRound | UPDATE practice_context SET current_round_id=?,updated_at=? | context 的 user_id 必须匹配；非 null 轮次必须属于同一 context/用户；null 可清空；不增加 progress_version |
| bumpProgressVersion | UPDATE practice_context SET progress_version=progress_version+1,updated_at=? | 带 contextId/userId，数据库内原子自增 |
| insertRound | INSERT INTO practice_round (...) SELECT ... FROM practice_context WHERE id=? AND user_id=? AND collection_id=? | 归属 / 实例不匹配影响 0 行；重复主键抛 DuplicateKeyException，不忽略错误 |
| upsertRoundQuestion | INSERT INTO practice_round_question (...) SELECT ... FROM practice_round WHERE id=? AND user_id=? ON DUPLICATE KEY UPDATE payload=?,extension_digest=?,updated_at=? | 同 roundId/questionId 更新，其余不动；拒绝向他人的轮次写入或覆盖快照 |

全部 SQL 手写，只用 `#{}`。Mapper 不开启事务或调用 rule-runner。未来 SubmitService 按设计 5.2 的顺序：事务外判分 → 事务内锁 context → 条件更新作答 → 必要时插入轮次并 setCurrentRound → upsertRoundQuestion → bumpProgressVersion → 回执。判断每个写方法的影响行数；upsert 的更新行数由 JDBC/MySQL 的 affected-rows 语义决定，不能用返回 2 当作版本号。

`payload` 由调用者组装完整冻结快照，题目部分必须使用 GradeOutcome.projectedJson，加当次 answer/result/status 等；不能放 content_question.data 原文。Mapper 不解析 JSON、不选择题序、不生成 ID，不决定轮次何时开始或结束。时间与 ID 也由调用者提供。

## 文件清单

- 新增 application：SubmitGrader、GradeOutcome。
- 调整 PracticeReadService：抽出私有 source 查询供原有读题、草稿准备与新增 ruleQuestion 复用，原有读题/草稿行为保持。
- 新增 infrastructure：PracticeContextMapper、PracticeContextRow、PracticeRoundMapper、PracticeRoundRow、PracticeRoundQuestionRow。
- 调整 ApiExceptionHandler：RuleRunnerException 的 503 映射。
- 新增测试：SubmitGraderIntegrationTest、SubmitInfrastructureMapperTest、RuleRunnerExceptionHandlerTest。
- 新增本报告。

## 测试映射

| 覆盖 | 测试 |
| --- | --- |
| 五题型参考答案 / 正确选项 | referenceAnswerUsesRealSubmitWithScoreAndPreservesEveryOutcomeField（5） |
| 五题型错误答案仍是合法作答 | wrongAnswerUsesRealRulesRatherThanBeingTreatedAsInvalid（5） |
| 五题型无效作答 → 422 | invalidAnswerOfEveryBasicTypeIs422RuleRejected（5） |
| Schema 合法但题目中不存在的选项 | answerWithValidSchemaButUnknownOptionIsRejectedByHook |
| 拒绝码白名单、未知/null 码 | rejectionCodesAreAllowlistedAndNeverExposeInternalCodes（8） |
| 不可用异常原实例传出、真实 runner 非 200 | ruleRunnerExceptionIsPropagatedAsTheSameInstance、realRunnerNon200RemainsRuleRunnerException |
| 成功响应缺字段不能成为 0 分 | malformedSuccessCannotBecomeAZeroScore（3） |
| 小数不静默取整、空 outline 数组保留 | decimalScoreIsPreservedWithoutChoosingARoundingPolicy |
| 用户隔离、删除实例、题目不存在、审核撤销，runner 不调用 | ownershipDeletedCollectionQuestionLookupAndApprovalUseReadPathBeforeRunner |
| 503 映射且消息不泄漏内部信息 | unavailableRunnerMapsTo503WithoutLeakingInternalDetails |
| context 锁的返回列 / 用户隔离 | lockReturnsCurrentRoundAndProgressOnlyToOwner |
| FOR UPDATE 的真实互斥及提交后释放 | forUpdateHoldsTheLockUntilTransactionEnds |
| 轮次全部 V1 列、3 种状态、UTC 毫秒时间 | insertRoundPersistsEveryV1ColumnAndMillisecondInstant（3） |
| nullable 列 / exam mode | insertRoundPreservesNullableColumnsAndExamMode |
| 轮次重复主键、非法 JSON 失败 | duplicateRoundIdFailsAndInvalidJsonIsNotIgnored |
| 轮次插入不能借用别人的 context / 不符的实例 | roundInsertCannotUseAnothersContextOrMismatchedCollection |
| setCurrentRound 归属、跨 context / 不存在轮次拒绝、清空、时间 | setCurrentRoundChecksContextOwnerAndRoundScopeAndCanClearIt |
| progress 原子递增、两个并发事务、用户隔离 | bumpProgressVersionIsAtomicAndOwnerScoped |
| upsert 冻结 payload/digest/时间，更新不影响其他轮次或题目 | upsertFreezesPayloadDigestAndTimestampThenUpdatesOnlyThatQuestion |
| upsert 用户隔离、不存在轮次 | roundQuestionCannotBeInsertedOrOverwrittenByAnotherUser |
| context/round/snapshot 同事务回滚 | roundAndSnapshotWritesRollBackWithCallerTransaction |

SubmitGrader 集成测试用 Testcontainers MySQL 与真实 rule-runner 镜像，导入 samples/basic-types.qbank 的五种题型。真实判分用例核对 submit 参数、withScore:true、全部 outcome 字段，确认 runner 执行时没有事务，且没有创建任何练习状态/轮次/回执。额外故障注入只用于未知码、异常透传和损坏响应等边界，不替代五题型真实判分。

新增判分测试的独立 Spring 上下文在类结束后用 `@DirtiesContext(AFTER_CLASS)` 关闭，释放连接池。首轮全量执行发现上下文缓存累积连接触及共享 MySQL 上限（1040 Too many connections），导致后续读题上下文启动失败；本次通过及时回收新增测试自身的资源解决，不提高 MySQL 限制或修改生产连接池。

Mapper 测试直接 JdbcTemplate 建独立 context，每项清理；并发用 CountDownLatch。锁测试由 MySQL 的 lock-wait timeout 确认第二个事务确实被阻塞，不用固定 sleep 猜测锁状态。

## 差异与未决事项

1. **V1 与文档差异**：practice_round.status 在 V1 允许 in_progress/completed/interrupted，设计 4.3 只写前两项。本任务以 V1 为准，不改迁移，测试三者均可持久化。
2. **422 与协议错误表差异**：本任务及 API 设计错误表要求 RULE_REJECTED 为 422；协议第 8 节写草稿/提交 RULE_REJECTED → 400 VALIDATION_FAILED。本实现遵从本次明确要求，返回 422 RULE_REJECTED；协议文档未擅自改写。
3. **主观题不自动判语义**：样例简答题的 grade 对参考答案文本及错误文本都返回 graded/0，由 review 再评分；单问答题区的非空作答都返回 pending/null。测试分别覆盖两类文本，保留真实规则，不把它们伪装成满分 / 零分自动判对错，也不修改基础拓展。
4. **RR-3 仍未明确提交得分的 DECIMAL 精度策略**：M2 明确的拒绝规则只适用于导入满分；此层保留得分原值。作者编写 SubmitService 写 DECIMAL(12,4) 前仍需明确超精度得分策略，不能依赖 MySQL 静默舍入。

## 验证结果

- JDK 21，`mvn -B -ntp -f backend/pom.xml verify`：BUILD SUCCESS；2909 项，0 failures、0 errors、3 项原有 skipped。
- 本次新增 45 项：SubmitGraderIntegrationTest 31/31、SubmitInfrastructureMapperTest 13/13、RuleRunnerExceptionHandlerTest 1/1 全部通过；原有 PracticeReadIntegrationTest 28/28 通过。
- 指定三个文件、数据库迁移及 pom.xml 与 main 一致。
- 本任务没有启动 Compose 项目；测试使用的 runner、临时目录及连接池由测试清理，MySQL/Ryuk 在 Maven JVM 退出后回收。
- 工作流只由 PR / main push 触发，因此本地 verify 通过后开 PR 触发 CI，全部 job 的最终结果与链接记录在 PR 描述中。
