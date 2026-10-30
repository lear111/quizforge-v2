# M3 切片 4：草稿 HTTP 与幂等验证

分支 `feat/m3-draft-idempotency`，基于 `main@55132d7`；用户原有幂等服务提交 rebase 后为 `69d1a0b`。`IdempotentDraftService`、`ReceiptMapper`、`ReceiptRow` 原样保留。

## 实现与范围

- `PracticeActionController`：POST `/api/collections/bank/{id}/questions/{qid}/actions`；绑定前端实际的 requestId/revision/action/data.answer/contentVersion，校验必填、长度与整数范围。只从 Principal 取用户；白板及其余未实现动作明确返回 501。
- `DraftActionService`：调用服务端归属/题目解析和原有幂等事务服务。回执只含 revision；HTTP 返回顶层 revision 加完整读题结构，满足原有 onSaved。使用请求中已经过回执哈希验证的答案和保存的 revision 构造响应，旧请求重放不会变成后续版本的答案。未在 HTTP 层改写服务错误码。
- `PracticeReadService` / `PracticeViews`：复用唯一投影通路，事务前准备未提交投影；开放未提交题目的 canSave，所有题目已提交时概览仍只读。提交、白板等能力仍关闭。草稿响应绝不复用提交态投影。
- 前端只调整状态提示；原始 write-queue 的 requestId、网络重试、冲突恢复和输入保留行为不改。API 映射同步更新。
- 前端附带的 answerState 当前未持久化，响应保持 null；白板、提交、answerState/outlineStates 的业务处理和 validateAnswer 并非本次幂等持久化服务所提供的逻辑。本次未在指定三个文件外另建写库通路来绕过该限制。

## 新增集成测试

`DraftActionIntegrationTest` 继承 MySqlTestBase，随机端口真实 HTTP、Testcontainers MySQL 8.4 与真实 Node rule-runner。每项独立用户/实例/存档并清理；不依赖测试线程上的事务回滚。

| 需求 | 测试 |
| --- | --- |
| 同 ID 重放完全相同响应、只保存一次 | sameRequestReplaysIdenticalResponseAndIncrementsRevisionOnlyOnce |
| 保存 N+1 后重放 N，不读最新状态伪装原结果 | replayAfterAnotherSaveReturnsOriginalAnswerAndRevision |
| 同 ID 两线程并发首次 / 后续保存 | simultaneousSameRequestFirstAndLaterSavesBothReturnOneRevision（revision=0 / 1） |
| 不同答案不能覆盖首次保存 | reusedRequestCannotOverwriteTheFirstAnswer |
| 不同答案应返回 400 | reusedRequestMustReturn400AsRequiredByTheHttpContract（缺陷 1 禁用复现） |
| 不同 ID 旧版本冲突 | differentRequestIdsWithStaleRevisionReturn409WithoutReceipt |
| 写回执失败时首次插入 / 后续更新都回滚 | failingReceiptInsertRollsBackTheDraftInTheSameTransaction（revision=0 / 1） |
| 会话用户隔离与伪造字段忽略 | sessionOwnerCannotSaveAnotherUsersCollectionAndForgedContextIsIgnored |
| 读 → 存 → 再读，恢复答案/revision/stamp/能力 | readSaveReadRestoresAnswerRevisionStampAndCapabilities |
| requestId 缺失/null/空白/超长 | invalidRequestIdIs400AndWritesNothing（5） |
| revision null/负数/小数/字符串/溢出 | invalidRevisionIs400（6） |
| 64 长度边界、不同用户可使用同一 ID | maximumRequestIdIsAcceptedAndScopedByUser |
| 缺 answer 拒绝、JSON null 可清空 | nullAnswerCanBeSavedButMissingAnswerCannot |
| 白板 501、提交题仍不可覆盖 | unsupportedActionsAreExplicitAndSubmittedQuestionsStayReadOnly |
| Cookie、Origin、内容版本、题目不存在、非 JSON | authenticationOriginContentVersionAndQuestionLookupAreEnforced |

并发用 CountDownLatch 启动，DraftSqlGate 将两条真实事务同步在实际 INSERT/UPDATE 之前，验证两个不同 MySQL connection、REPEATABLE READ、单行草稿、单份回执及两份完全相同的 HTTP 200 响应，不用 sleep。回执失败使用真实 MySQL CHECK 约束，不替换 Mapper 返回值；约束在 finally 中删除。

## 发现的缺陷

### 1. REQUEST_ID_REUSED 的 HTTP 状态码与契约不符（未修复）

复现：同一用户先以 requestId=R、revision=0、答案 A 保存，再以相同 R/revision、答案 B 保存。

- 预期：400 REQUEST_ID_REUSED。
- 实际：422 REQUEST_ID_REUSED，消息“请求 ID 已被重复使用”。A 保持不变，revision=1，只有一份回执。
- 失败测试：`DraftActionIntegrationTest.reusedRequestMustReturn400AsRequiredByTheHttpContract`，初轮实际执行失败于 expected 400 / actual 422。该轮 26 项中只有此项失败，0 errors。
- 原因：用户已有 `IdempotentDraftService.replay` 使用 UNPROCESSABLE_ENTITY。
- 处理：未修改指定服务，未在 Controller/异常处理层把 422 偷换为 400。以 `@Disabled("见报告第 1 条")` 保留契约复现；“不同答案不覆盖”的检查继续启用。
- 建议：由作者将该分支改为 BAD_REQUEST，随后启用复现测试。

## 验证结果

待最终 mvn verify、前端及 CI 结果补入。

## 网页手动验证步骤

1. 启动独立 Compose 项目，注册/登录，导入 `samples/basic-types.qbank`。在测试库为导入样例摘要设置 approved 后刷新（真实使用须审核，不能跳过），打开单选题。
2. 保持标签页 A，在同一浏览器另开标签页 B，进入同一本题库、同一题。两页均先加载相同 revision，期间不要刷新 B。
3. A 选择选项 A，等待“已保存”；Network 中 POST actions 为 200，revision 从 N 到 N+1。刷新 A，选项应仍选中。
4. B 在旧版本上选择选项 B。Network 应为 409 REVISION_CONFLICT；B 的选项 B 输入保留，出现“保存状态发生冲突…可重试，或读取已保存的内容”。服务器仍保存 A 的选项、revision=N+1。
5. 可选择“重试保存”（新 requestId、重读后的 revision）或“读取已保存的内容”；前者再保存到 N+2，后者明确放弃本地输入后恢复 A 的答案。提交和白板入口仍不可用。

Playwright 在现有真实五题型导入/读题用例后，使用两个真实标签页与 iframe 选项控件自动覆盖第 2～4 步以及刷新恢复，不模拟 HTTP 写入。截图写入 `.artifacts/screenshots/draft-{desktop|mobile}-{conflict|restored}.png`，CI 随浏览器证据上传。自动化浏览器验证与人工操作步骤在此区分记录。
