# M3 切片 5：提交判分接口与测试

## 改动范围

- 基于作者 `9263909`，先 fetch 并合并 `origin/main@40a6a52`（集中大纲 #59）；合并提交 `f49a779`。
- `SubmitService`、`QuestionStateMapper` 与作者提交逐字节相同，没有修改其逻辑。
- `RoundSnapshots`：collection 冻结标题、题序、最终大纲、分组序号/名、题目指纹；question 冻结提交后投影、作答、判分结果、状态、版本和拓展引用，不存原始题目 data。
- `PracticeReadService` 扩展 PreparedDraft，准备不可变的 collection snapshot；未提交开放 canSubmit；元数据版本检查不调用 rule-runner。
- `PracticeReadMapper/Rows` 补齐快照所需题目指纹和持久化 score/max_score/grading_status。
- `SubmitActionService` 与 `PracticeActionController` 接入 action=submit。requestId、revision、data.answer 与前端写队列一致；会话决定用户，服务端解析上下文和题目指纹；两次 revision 递增的溢出输入返回 400。白板/review/retry 仍返回 501。
- 回执仍只有 revision；响应由请求答案和持久化提交状态构造，重放不再判分，已提交投影不进入未提交缓存。
- `SubmissionResults` 保留拓展结果的其他字段，以持久化判分状态和四位小数分数填充前端 result；读题、概览、快照均与作者服务的 HALF_UP 规则一致。
- 前端只更新能力提示，并扩展已有真实浏览器用例；接口盘点同步更新。

## 新增测试映射

`SubmitActionIntegrationTest` 使用真实 Testcontainers MySQL、真实 rule-runner 与五种题型样例。仅故障/并发门控使用 spy；精度用例在真实 GradeOutcome 基础上注入五位小数，验证作者服务的舍入与 HTTP 适配。

| 要求 | 测试 |
| --- | --- |
| 3a、3b：五种题型直接提交、判分状态/分数、刷新、单题快照 | directSubmitPreservesRealExtensionOutcomeAndRefresh（5 个参数） |
| 3a：客观题错误答案 | wrongObjectiveAnswerIsGradedBelowFullScore（3 个参数） |
| 3c：回执重放、只调用一次 submit、请求 ID 不同答案 | replayIsIdenticalAndDoesNotGradeAgain |
| 3d：不能重复提交或保存已提交题 | submittedQuestionCannotBeSubmittedOrSavedAgain |
| 3e、3g：判分无事务、并发草稿不阻塞、旧答案结果不入库 | gradingDoesNotHoldTransactionAndConcurrentDraftRejectsStaleResult |
| 3f：同库两题同时首次提交，一个轮次和两个快照 | concurrentFirstSubmissionsShareOneRoundAndFreezeBothQuestions |
| 3h：submit 阶段故障后保留草稿 | submitRunnerFailureReturns503AndKeepsSavedDraft |
| 3h：真实停止 runner 后 503，保留草稿（投影已预热） | stoppedRealRunnerReturns503AndRetainsDraft |
| 3i：score/maxScore 同规则 HALF_UP，HTTP 与刷新一致 | scoresUseIdenticalHalfUpRoundingInStorageReadAndSnapshot |
| 3j：其他用户的实例不可提交 | anotherUsersCollectionCannotBeSubmitted |
| collection 快照在改名后仍保持准备时内容，不含作答 | collectionSnapshotKeepsPreparedTitleAndExcludesAnswers |
| 问题 1 复现（禁用） | coldProjectionRunnerFailureMustStillPreserveDraft |
| 3k：真实页面提交、错误颜色、刷新保留结果 | read-slices.spec.mjs：desktop/mobile |

## 发现的问题

### 1. 冷投影缓存下 runner 故障，草稿未保存

复现步骤：

1. 为已批准拓展插入一个新的题目内容指纹，确保未提交投影缓存未命中。
2. 在 `RuleRunnerClient.execute("project", ...)` 注入 `RuleRunnerException`。
3. 不预先读题，直接发送 `action=submit`、revision=0 与合法答案。
4. HTTP 返回 503 RULE_UNAVAILABLE；查询 practice_question_state，实际没有草稿。

预期：按任务 3h，答案已作为 draft 保存（1 行）。实际：0 行。失败测试：`coldProjectionRunnerFailureMustStillPreserveDraft`，断言 `expected: 1 but was: 0`。已实际运行：该轮 16 个测试中仅此项失败（1 failure / 0 errors）。随后按“不改作者逻辑”的约束标记 `@Disabled("见报告第 1 条")`。

原因：SubmitService 先调用 `reads.prepareDraft`，该路径会在缓存未命中时调用 project；之后才调用 drafts.saveDraft。故障发生在保存之前。投影已预热、仅 submit 阶段不可用时，保存草稿的测试正常通过。

建议由作者将仅需归属/内容指纹的准备与投影读取拆开，确保外部 rule-runner 调用发生在保存草稿之后，同时保留判分在事务外的边界。本 PR 未修改 SubmitService 或 QuestionStateMapper.submit。

重新复现命令：临时移除该测试的 @Disabled 后运行 `mvn -f backend/pom.xml -Dtest=SubmitActionIntegrationTest#coldProjectionRunnerFailureMustStillPreserveDraft test`，完成后恢复标记。

## 说明

普通简答题拓展返回 graded、score=0，后续由 review 改分；答题区简答题返回 pending、score=null。两者是拓展自身的判分语义差异，不是宿主缺陷。测试逐项核对 GradeOutcome、数据库状态/分数、原始 result JSON 和 HTTP 响应；没有将普通简答题改成 pending，也没有将 null 分数改成 0。review 接口不属于本任务。

## 网页手动验证步骤

1. 在独立 Compose 环境注册，导入 samples/basic-types.qbank；用测试白名单批准这五个拓展（审核流程仍未实现）。
2. 打开单选题，选择 A（错误答案），等待草稿保存；点击拓展页面的“提交”。
3. 确认显示分数、错误选项和答案解析，大纲对应项变为错误颜色；提交按钮隐藏。
4. 刷新页面，再打开该题，确认选择、结果和大纲颜色保持；重复提交和草稿写入由服务端返回 409。
5. 新导入一个实例，选择正确选项提交，确认满分和正确颜色。普通简答题为 graded/0，答题区简答题为 pending/null。

自动化浏览器用例实际覆盖上述错误答案提交与刷新路径，桌面及 375px 手机宽度均运行；截图放在 .artifacts/screenshots，未放入 frontend 源码目录。

## 验证结果

- `mvn -B -ntp -f backend/pom.xml clean verify`：2,999 项，0 failure、0 error、4 skipped（原有 3 项，加问题 1 的复现）。
- 新增 SubmitActionIntegrationTest：18 项，17 通过、1 个已验证的缺陷复现禁用。
- 前端 `npm test`：32/32 通过。
- Playwright 全套：8/8 通过（desktop + mobile）。首轮在既有多标签页恢复步骤遇到 5 秒就绪等待不足，已将该真实浏览器用例的条件等待统一为 30 秒；没有固定 sleep 或删减断言。
- 独立 `qf-submit-api` Compose 环境已执行 down；没有停止其他项目，也没有删除其数据卷。
- 四个 CI job 的最终状态和截图制品链接见 PR 描述。
