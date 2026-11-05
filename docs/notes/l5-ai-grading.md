# L5.4 AI 评分实现与本机验收

## 接口与采用规则

沿用旧 `API.md` 的 `/api/collections/{kind}/{id}/questions/{qid}/ai` 路径：`POST grade`、`GET current`、`GET tasks/{taskId}`、`POST tasks/{taskId}/retry`、`POST tasks/{taskId}/confirm`。`kind` 为 `bank` 或 `exam`。写请求包含 `requestId`，请求体上限 8 KiB；用户身份只来自会话。考试交卷前拒绝 AI 评分。

`current` 返回 `{task}`，其余任务接口返回任务视图；确认接口另返回 `payload`。视图只含任务编号、状态、目标、候选、错误与费用，不包含题目原文、评分材料、图片 data URL、模型检查点或 Key 标识。`cost` 沿用余额单位（0.0001 元）。内部 `model_returned` 对旧页面显示为 `running`，保持原有轮询工作。

作者于 2026-11-04 确认：模型输出只检查有限数与非空反馈（最多 18000 字符）；分值范围、精度与步长由拓展 `review` 决定，宿主不裁剪候选。最后保存拓展返回的分数时仍统一 HALF_UP 四位小数。普通简答题与答题区题目的初始规则判分语义保持不变。

## 持久化与并发

- `ai_grading_task` 保存任务、重试预算、输入和模型检查点；`ai_grading_binding` 保存整题 / 小题的当前绑定；`ai_grading_setting` 保存默认关闭的自动评分偏好。
- 手动请求使用原有回执表，先锁已存在的 context 再重查回执；冲突后在新事务重查。相同目标已有活动任务时，新的点击与 `force` 都复用它。
- 自动评分登记使用提交结果写入事务中的同步事件，只查询本题并插入任务，不执行拓展或模型。提交回滚时任务一起回滚。
- 复用现有生命周期任务的数据库轮询器与 `AiGateway` 执行器，不新建线程池。任务用 `SKIP LOCKED` 领取，按手动 / 自动优先级和创建时间排序；本实例最多同时处理 2 个，可用 `QF_AI_GRADING_CONCURRENCY` 调整为 1～8，模型调用仍受现有网关名额限制。规则执行使用现有后台名额。
- 租约默认 150 秒（沿用 `qf.ai.task-lease`，启动时校验模型最长重试时间小于租约）；`attempts` 是所有消费者写入的栅栏。模型返回后立即保存正文和用量，之后才解析、执行 `review`，并在短事务内幂等扣费与采用。进程恢复读取检查点，不再调用模型。
- `model_calls` 单独计算真实上游尝试，换 Key、网络重试与一次格式修复共用最多三次调用。重复结算使用保存的 `grading:{taskId}:{attempts}`，不会重复扣费。
- 采用前检查提交代次、评分代次、作答哈希、内容哈希、轮次及当前目标绑定；同时用 revision 防止兄弟小题旧结果覆盖新状态。仅 revision 竞争时保留检查点、重新 review 最新状态，不重复模型调用。
- 人工改分、重做、再练一次及删除使旧任务失效。排队任务取消；已返回的运行任务仍扣实际费用，但不采用。小题 AI 采用不递增整题评分代次，兄弟目标互不失效。
- 拓展拒绝候选时保留原结果与候选供确认 / 重试；模型输出有效不代表宿主强制采用。完成练习后和考试历史沿用现有汇总、快照更新通路。

## 自动评分与 targets

账号页提供“提交后自动 AI 评分”，通过 `GET/PUT /api/account/ai/settings` 持久化。浏览器判分上报、服务器兜底、完成练习与考试后台判分的成功写入都会登记自动评分。

自动任务在事务外调用 `prepareAiGrading(...,{automatic:true,state})`。支持整题输入、空计划与最多 256 个唯一目标的计划；整份计划先校验再原子登记小题任务。材料字段白名单、块数、字符数、图片数与旧 `ai-protocol.js` 一致。图片先通过现有资源权限检查，再有界读取并生成 data URL，单张 4 MiB、总计 12 MiB。

## 本机假上游验收

`deploy/docker-compose.ai-test.yml` 仅用于本机与 CI：Node 假上游与 app 共用网络命名空间，只监听 `127.0.0.1:9105`，不发布端口、不访问任何模型服务商。CI 的 Compose smoke 使用此叠加文件；生产 Compose 不启用它。

```powershell
docker compose -p qf-ai-grading -f deploy/docker-compose.yml -f deploy/docker-compose.ai-test.yml up -d --build
$env:QF_COMPOSE_PROJECT='qf-ai-grading'
$env:QF_BROWSER_BASE_URL='http://localhost:8088'
$env:QF_AI_GRADING_BROWSER_TESTS='1'
cd frontend
npx playwright test ai-grading.spec.mjs
cd ..
docker compose -p qf-ai-grading -f deploy/docker-compose.yml -f deploy/docker-compose.ai-test.yml down
```

若 8088 已被占用，应在自己的叠加文件中换空闲端口并同步 `QF_PUBLIC_ORIGIN` 与 `QF_BROWSER_BASE_URL`，不能停止他人的项目。截图、登录态和日志只放 git 忽略的 `local/` / `.artifacts/local/`。

## 作者使用真实 Key 的手动冒烟

1. 使用独立本机 Compose 项目，不叠加 `docker-compose.ai-test.yml`。在本机忽略的环境文件中配置 `QF_AI_BASE_URL`、`QF_AI_MODEL`（以及部署要求的 `QF_AI_MASTER_KEY`），不要提交或打印 Key。
2. 登录管理员账号，打开后台“AI 服务”，把作者自己的真实 Key 加入 Key 池并设置输入 / 输出单价；无需修改拓展或浏览器配置。
3. 在“我的题库”上传并审核基础样例，导入练习空间。作答普通简答题或答题区题并提交，点击小题的“AI 评分”；确认排队、评分中、采用后反馈与实际花费。再人工调整分数，确认人工结果保留。
4. 在账号页打开自动评分，再提交另一题；完成练习、考试交卷也应登记任务。检查余额流水只有对应模型调用的一笔扣费。
5. 余额不足时应提示充值且不调用模型。验证完执行该项目的 `down`，保留数据卷。

自动化测试只使用合成题库与 FakeUpstream，没有调用真实模型；真实 Key 冒烟由作者在本机执行。

## 测试对应关系

| 要求 | 自动化测试 |
| --- | --- |
| 两种主观题真实 prepare/review、扣费、回执、任务视图不泄漏材料 | `manualGradeUsesRealPrepareAndReviewAndChargesOnce` |
| 模型检查点恢复、旧 attempts 栅栏、只扣一次 | `checkpointSurvivesCrashAndDoesNotCallOrChargeModelTwice` |
| 只修复一次、换 Key 与修复共用三次预算 | `malformedOutputGetsExactlyOneRepairAndEachResponseIsBilled`、`secondMalformedOutputFailsInsteadOfRepairingAgain`、`networkKeyRetriesAndRepairShareThreeCallBudget` |
| 余额不足、人工中断、重做后失效 | `insufficientBalanceFailsWithoutModelCallOrAutomaticRetry`、`manualReviewDuringModelCallDiscardsAiButChargesReturnedUsage`、`queuedTaskIsCancelledByRetryAndOldResultCannotBeConfirmed` |
| 优先级与 SKIP LOCKED、并发重复点击 | `queuePrefersManualBeforeAutomaticAndSkipsLockedRow`、`concurrentClicksReuseOneTaskAndOneModelCall` |
| 自动评分与 targets、与提交同事务登记、兄弟小题不覆盖 | `automaticPlanRegistersWithSubmissionAndSupportsAllTargets`、`automaticRegistrationRollsBackTogetherWithSubmission`、`siblingTargetsFinishingTogetherDoNotOverwriteEachOther` |
| 浏览器判分、完成练习、考试交卷登记与历史采用 | `browserReportRegistersAutomaticTaskWithoutCallingAiOrPreparingInsideTransaction`、`finishingAndExamHandinScheduleAutomaticAiAndUpdateCompletedHistory` |
| 拓展拒绝保留原结果、重试、宿主不裁剪模型分数 | `retryCreatesNewTaskAndCandidateRejectionPreservesOriginalResult`、`extensionReviewOwnsRangeAndStepAndHostStoresItsResult` |
| 身份隔离、提交前拒绝、请求大小限制 | `requestIdReuseAndIdorDoNotExposePrivateMaterials`、`oversizedHttpRequestIsRejectedBeforeCreatingTask` |
| 材料白名单、候选格式、JavaScript 空白规则、target 规则 | `GradingProtocolTest` |
| 删除题库 / 考试、内容指纹变化使旧任务失效 | `deletingCollectionCancelsQueuedAiWithoutCallingModel`、`deletingGradingExamCancelsItsAiTask`、`changedQuestionContentInvalidatesTaskBeforePreparingMaterials` |
| 图片权限、data URL、单张与合计上限 | `GradingPromptTest` |
| 桌面 / 手机的提交、AI 反馈和余额、人工覆盖、自动设置持久化 | `ai-grading.spec.mjs` |
