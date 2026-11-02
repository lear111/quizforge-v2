# L2 题库内个人考试

## 实现与边界

- 开始考试冻结 `bank_id` 和标题，创建独立的 `kind=exam` 存档；普通练习与考试的答案、白板、版本号互不影响。
- 考试写入先锁已有 `practice_context`，再锁考试行，在获得锁后重查状态与服务器截止时间。交卷使用状态及 version 条件更新，任务、轮次与交卷状态在同一事务内提交；规则调用仍在事务外。
- 用户交卷、读请求发现过期、后台扫描到期共用交卷入口。扫描默认每 5 秒、每批 100 行，使用已有 `idx_exam_deadline`。多实例可能同时发现同一行，但只有第一个锁定并转换状态的事务创建任务。
- 复用 `PracticeLifecycleJobs` 的租约、恢复、后台名额、250ms 工作节奏、空闲退避和键集分页。`exam_finish` 判分后切换同一任务为 `exam_archive`，逐题冻结历史；不创建另一套判分线程池。
- 失败保留已保存答案及已成功判分的状态，考试保持锁定，可重新交卷继续剩余判分或归档。删除未完成考试会取消任务并清除状态、白板与临时轮次；正在运行的结果必须再次通过 context/任务租约检查，不能写回。
- 完成后在原题库历史中显示 `mode=exam`、`snapshotVersion=1`。完成考试不出现在待处理考试列表中；历史提供“核对考试评分”入口。
- **作者确认的规则**：完成考试后仍可人工改分，已冻结的单题结果和历史汇总随改分在同一事务更新。旧 `EXAM_MODE.md` 的“历史全部只读”在这一点上由本次决定覆盖；作答与白板仍锁定。普通简答题与答题区的 graded/pending 差异继续由拓展决定。
- AI 评分暂不开放，仅保留明确的未实现错误；本任务没有组织考试、URL 代理或新的外部服务。

## 文件清单

| 文件 | 用途 |
|---|---|
| `backend/.../exam/ExamRow.java`、`ExamMapper.java` | 考试持久化、归属、冻结 bank、状态/version 条件更新、索引扫描、删除 |
| `backend/.../exam/ExamService.java` | 幂等创建/交卷、阶段检查、截止保护、删除 |
| `backend/.../exam/ExamController.java` | 考试元数据、概览、读题、草稿/白板、评分、资源和听力接口 |
| `backend/.../exam/ExamDeadlineScanner.java` | 有界到期扫描与故障后继续轮询 |
| `backend/.../exam/ExamHistoryUpdates.java` | 完成后人工改分与冻结历史同步 |
| `backend/.../practice/application/PracticeLifecycleJobs.java` | 在既有后台工作机制中处理考试判分和自动归档 |
| `backend/.../practice/infrastructure/LifecycleJobMapper.java` | 同一任务切换考试归档阶段 |
| `backend/.../practice/application/PracticeLifecycleService.java` | 既有人工评分事务中增加考试历史同步 |
| `backend/.../practice/application/PracticeReadService.java`、`infrastructure/PracticeReadMapper.java` | 复用投影通路，按考试冻结 bank 读取；开始前检查审核而不创建普通存档 |
| `backend/.../practice/application/PracticeHistoryService.java`、`infrastructure/PracticeHistoryMapper.java` | 返回真实 mode、考试历史入口，历史删除同步隐藏考试 |
| `backend/.../practice/web/PracticeActionController.java`、`PracticeReadController.java`、`PracticeLifecycleController.java` | 复用动作适配，阻止用 bank 路由绕过考试限制 |
| `backend/.../content/infrastructure/ContentAccessMapper.java` | 开考后题库更新时，仍按冻结 bank 授权拓展与资源 |
| `backend/.../db/migration/V22__personal_exams.sql` | 任务 kind、冻结标题、历史反查索引；不修改既有迁移 |
| `backend/.../test/.../exam/ExamModeIntegrationTest.java` | 真实 MySQL、真实 runner、可控时钟、门控并发及本机性能探针 |
| `frontend/practice/exam/exam-mode.js`、`exam-mode.css`、`practice/practice-context.js` | 接通既有考试界面，完成后人工核对入口与阶段能力 |
| `frontend/practice/app.js`、`shared/api.js` | 考试资源沿用二进制资源解码，不把图片当 JSON |
| `frontend/test/exam.test.mjs`、`test/browser/exam.spec.mjs` | 阶段能力、时钟、桌面与手机浏览器流程 |
| `docs/design/01-database.md`、`02-api.md`、`07-frontend-api-map.md` | 实际字段、路由、生命周期及本次产品决定 |

未修改 `SubmitService`、`DraftService`、`IdempotentDraftService`、`QuestionStateMapper` 的逻辑。

## 测试对应

`ExamModeIntegrationTest` 共 27 个展开用例，其中 1 个性能探针仅显式启用时运行。

| 要求 | 测试 |
|---|---|
| 创建幂等、删除创建重放 | `creationIsIdempotentAndDeletedCreationCannotReplay` |
| 时长边界、整数/null 类型 | `rejectsInvalidDuration`、`durationMustBeAnIntegerOrNull` |
| features.exam=false | `disabledFeatureRejectsStart` |
| 独立草稿/白板、禁止单题操作、不能绕过 bank 路由 | `examDraftAndWhiteboardAreIsolatedAndForbiddenActionsCannotBypassBankRoute` |
| 并发重复开考 | `concurrentCreationWithSameRequestCreatesOneContext` |
| 等待锁时到期、刷新不延长 | `deadlineIsRecheckedAfterWaitingForContextLock`、`deadlineIsServerControlledAndWritesAreRejectedAfterRefresh` |
| 人工/自动同时交卷只创建一个任务和轮次 | `automaticAndUserSubmissionRaceCreateOnlyOneJobAndRound` |
| 五种题型真实规则、结果历史、完成后不可直接删除 | `realGradingAndHistoryRetainExtensionSemantics`（5 例） |
| 未作答计 0、不调用判分 | `unansweredExamCompletesWithZeroAndNeverInvokesGrader` |
| 失败锁定、保留答案并重试 | `gradingFailurePreservesLockedAnswersAndCanRetry` |
| 完成后改分与历史同步 | `completedExamManualReviewUpdatesHistoryInSameTransaction` |
| 开考后版本更新、冻结页面/资源 | `frozenBankSurvivesCollectionVersionChange` |
| 删除时丢弃正在判分结果、删除题库级联 | `deletedInFlightExamDiscardsGradeAndCollectionDeletionCascades` |
| 全部考试路由 IDOR | `everyExamRouteRejectsOtherUsers` |
| 高频写入不遍历题库、不调用规则 | `draftAndWhiteboardNeverEnumerateBankOrCallRules` |
| 到期索引执行计划 | `expiryScanUsesDeadlineIndex` |
| 保存/白板及后台评分期间前台提交 P95 | `localLatencyProbe`（默认跳过） |

既有 `PracticeLifecycleIntegrationTest` 44 例、`LifecycleJobFollowupIntegrationTest` 9 例、`PracticeCollectionSnapshotTest` 9 例已在本机通过，覆盖原有练习并发和快照行为。前端构建成功，单元测试 160 例通过。

## 本机性能测量

Windows + Docker Desktop（Docker 可用内存约 15.7GB），JDK 21、MySQL 8.4、真实 Node runner，HTTP 随机端口、同一 300 题题库，未设置生产环境 CPU 配额。后台默认名额沿用现有实现；runner 使用既有压测基线 8 个工作进程。机器同时运行其他开发项目，因此绝对耗时只能作为本次局部对照，不作为容量结论。

- 草稿、白板按普通/考试交替采样，各预热 20 次、测量 100 次。
- 前台提交另一个用户的题库，空闲和后台考试判分期间各测量 60 次，提交后重做，10 次预热。用门控确认后台已开始，采样结束时后台考试仍在判分。
- 所有采样请求成功；保存路径断言没有枚举题库和调用规则。

| 操作 | 普通练习/空闲 P95 | 考试/后台判分 P95 | 变化 |
|---|---:|---:|---:|
| 保存草稿 | 92.29ms | 88.95ms | -3.6% |
| 保存白板 | 565.56ms | 574.71ms | +1.6% |
| 前台单题提交 | 1538.58ms | 1432.77ms | -6.9% |

runner 采样结束时启动总数 8、LRU 回收 0、超时停止 0、异常退出 0、队列长度 0；空闲回收 4。原始 JSON、runner 指标与日志仅存于 git 忽略的 `.artifacts/local/exam/`。

首轮使用 2 个 runner 进程而混合试卷涉及 3 种拓展时，前台提交 P95 从 395.05ms 增至 788.83ms；因此不能声称任意资源配置下都不受影响。没有修改生产池策略或默认配置，改用现有压测基线 8 进程后完成上述对照。

复现：`mvn -B -ntp -f backend/pom.xml -Dtest=ExamModeIntegrationTest -Dqf.exam.measure=true test`。探针不在 CI 上做性能阈值断言。

## 浏览器与最终验证

本机独立 Compose 项目 `qf-exam-mode`，端口 8113；使用合成样例和假验证码，不连接外部部署。桌面和 375px 手机视口验证：注册 → 导入/本机审核样例 → 开始限时考试 → 图片加载 → 保存独立答案 → 校准服务器截止时间 → 自动交卷 → 原题库历史 → 查看考试结果 → 返回完成考试核对评分。截图存于 git 忽略的 `.artifacts/local/exam/`。

到期扫描本机 EXPLAIN：`type=range`、`key=idx_exam_deadline`、`key_len=9`、`rows=1`、`Extra=Using index condition`，没有 filesort。

最终浏览器和 CI 结果在提交 PR 前补齐。本机考试测试 27 例，0 失败、0 错误、1 个默认跳过的性能探针；Maven package 成功。未实现 AI 评分属于明确的后续范围；没有迁移真实用户数据。
