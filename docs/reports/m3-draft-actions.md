# M3 切片 4：草稿 HTTP 与幂等验证

分支 `feat/m3-draft-idempotency`，基于 `main@55132d7`；用户原有幂等服务提交 rebase 后为 `69d1a0b`。作者随后在 `d4ee0e9` 上提交 `ac90283`，已按要求先 `git pull --ff-only` 纳入。没有自行修改 `IdempotentDraftService`、`ReceiptMapper`、`ReceiptRow` 的逻辑。

## 实现与范围

- `PracticeActionController`：POST `/api/collections/bank/{id}/questions/{qid}/actions`；绑定前端实际的 requestId/revision/action/data.answer/contentVersion，校验必填、长度与整数范围。只从 Principal 取用户；白板及其余未实现动作明确返回 501。
- `DraftActionService`：调用服务端归属/题目解析和原有幂等事务服务。回执只含 revision；HTTP 返回顶层 revision 加完整读题结构，满足原有 onSaved。使用请求中已经过回执哈希验证的答案和保存的 revision 构造响应，旧请求重放不会变成后续版本的答案。未在 HTTP 层改写服务错误码。
- `PracticeReadService` / `PracticeViews`：复用唯一投影通路，事务前准备未提交投影；开放未提交题目的 canSave，所有题目已提交时概览仍只读。提交、白板等能力仍关闭。草稿响应绝不复用提交态投影。
- 前端只调整状态提示；原始 write-queue 的 requestId、网络重试、冲突恢复和输入保留行为不改。API 映射同步更新。
- 前端附带的 answerState 当前未持久化，响应保持 null；白板、提交、answerState/outlineStates 的业务处理和 validateAnswer 并非本次幂等持久化服务所提供的逻辑。本次未在指定三个文件外另建写库通路来绕过该限制。

## 文件清单

| 文件（省略公共 Java 包前缀） | 来源 / 目的 |
| --- | --- |
| practice/application/IdempotentDraftService.java | 作者已有事务服务及 ac90283 的 400 修复 |
| practice/infrastructure/ReceiptMapper.java、ReceiptRow.java | 作者已有回执访问层；未自行修改 |
| practice/application/DraftActionService.java | HTTP 与原有事务服务之间的适配 |
| practice/web/PracticeActionController.java | draft 请求校验、会话身份与响应 |
| practice/application/PracticeReadService.java、PracticeViews.java | 服务端上下文解析、复用安全投影、可保存能力及响应结构 |
| practice/application/DraftActionIntegrationTest.java（测试） | 26 项 HTTP / MySQL / rule-runner 集成验证 |
| practice/web/PracticeReadIntegrationTest.java（测试） | 更新未提交题 canSave 断言 |
| frontend/practice/app.js | 更新已实现草稿保存的界面提示 |
| frontend/test/browser/read-slices.spec.mjs | 双标签页真实保存、409 和刷新恢复；等待启动切换结束后操作侧栏 |
| docs/design/07-frontend-api-map.md | 同步切片 4 接口状态与契约 |
| docs/reports/m3-draft-actions.md | 本报告 |

## 新增集成测试

`DraftActionIntegrationTest` 继承 MySqlTestBase，随机端口真实 HTTP、Testcontainers MySQL 8.4 与真实 Node rule-runner。每项独立用户/实例/存档并清理；不依赖测试线程上的事务回滚。

| 需求 | 测试 |
| --- | --- |
| 同 ID 重放完全相同响应、只保存一次 | sameRequestReplaysIdenticalResponseAndIncrementsRevisionOnlyOnce |
| 保存 N+1 后重放 N，不读最新状态伪装原结果 | replayAfterAnotherSaveReturnsOriginalAnswerAndRevision |
| 同 ID 两线程并发首次 / 后续保存 | simultaneousSameRequestFirstAndLaterSavesBothReturnOneRevision（revision=0 / 1） |
| 不同答案不能覆盖首次保存 | reusedRequestCannotOverwriteTheFirstAnswer |
| 不同答案应返回 400 | reusedRequestMustReturn400AsRequiredByTheHttpContract（作者修复后重新启用） |
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

### 1. REQUEST_ID_REUSED 的 HTTP 状态码与契约不符（已由作者修复）

复现：同一用户先以 requestId=R、revision=0、答案 A 保存，再以相同 R/revision、答案 B 保存。

- 预期：400 REQUEST_ID_REUSED。
- 初始实际：422 REQUEST_ID_REUSED，消息“请求 ID 已被重复使用”。A 保持不变，revision=1，只有一份回执。
- 失败测试：`DraftActionIntegrationTest.reusedRequestMustReturn400AsRequiredByTheHttpContract`，初轮实际执行失败于 expected 400 / actual 422。该轮 26 项中只有此项失败，0 errors。
- 原因：用户已有 `IdempotentDraftService.replay` 使用 UNPROCESSABLE_ENTITY。
- 处理：初轮遵守约束，以 `@Disabled("见报告第 1 条")` 保留契约复现。作者在 `ac90283` 将该分支改为 BAD_REQUEST；快进拉取后移除 Disabled，重新执行完整验证。没有在 Controller/异常处理层改写服务错误码。

指定 Mapper 中关于 FOR SHARE 的原有注释与实际无锁 SELECT 相互矛盾；实际 SQL 保持不变，并发正确性由上述真实事务测试覆盖。该注释问题留给作者整理。

## 验证结果

- JDK 21，`mvn -B -ntp -f backend/pom.xml clean verify`：BUILD SUCCESS，2864 项，0 failures、0 errors、3 skipped（均为仓库原有 ArchiveReader 禁用复现）；新增 DraftActionIntegrationTest 26/26 通过，PracticeReadIntegrationTest 28/28 通过。耗时 5 分 30 秒。
- `cd frontend && npm run build && npm test`：构建通过，26/26 测试通过。
- 真实 Compose 浏览器（1440×960 / 375×812）：6/6 通过；含五题型、真实图片、双标签页保存 / 409 / 输入保留，以及刷新后恢复已保存选项。桌面与手机截图在 `.artifacts/screenshots/`，不放入 frontend。四个 CI job 的最终状态及证据链接记录在 PR 报告中。
- 本机 Maven 在独立工作树执行，避免主工作目录编辑器覆盖 target/test-classes。Compose 使用独立项目 `qf-draft-actions` 和新建测试卷；本地后端以已验证 JAR 挂载到 Java 21 运行镜像，其余为本分支构建镜像。正常 Dockerfile 的完整构建由 CI Compose job 再验证。
- 初轮本机浏览器曾有一次桌面图片显示占位失败（资源 HTTP 200），尚未确定原因；没有删除或放宽图片断言，也没有修改图片生产代码。新建测试环境后，最终桌面和手机五题型图片检查均通过，仍由 CI 保留该检查。

## 网页手动验证步骤

1. 启动独立 Compose 项目，注册/登录，导入 `samples/basic-types.qbank`。在测试库为导入样例摘要设置 approved 后刷新（真实使用须审核，不能跳过），打开单选题。
2. 保持标签页 A，在同一浏览器另开标签页 B，进入同一本题库、同一题。两页均先加载相同 revision，期间不要刷新 B。
3. A 选择选项 A，等待“已保存”；Network 中 POST actions 为 200，revision 从 N 到 N+1。刷新 A，选项应仍选中。
4. B 在旧版本上选择选项 B。Network 应为 409 REVISION_CONFLICT；B 的选项 B 输入保留，出现“保存状态发生冲突…可重试，或读取已保存的内容”。服务器仍保存 A 的选项、revision=N+1。
5. 可选择“重试保存”（新 requestId、重读后的 revision）或“读取已保存的内容”；前者再保存到 N+2，后者明确放弃本地输入后恢复 A 的答案。提交和白板入口仍不可用。

Playwright 在现有真实五题型导入/读题用例后，使用两个真实标签页与 iframe 选项控件自动覆盖第 2～4 步以及刷新恢复，不模拟 HTTP 写入。截图写入 `.artifacts/screenshots/draft-{desktop|mobile}-{conflict|restored}.png`，CI 随浏览器证据上传。自动化浏览器验证与人工操作步骤在此区分记录。
