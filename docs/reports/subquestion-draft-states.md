# L1.6 小题草稿状态

## 行为与实现

草稿与回执仍由原有 IdempotentDraftService / DraftService 原子提交，未修改它们、SubmitService 或 QuestionStateMapper。随后 DraftOutlineService 在事务外对本题调用 validateAnswer；独立 DraftOutlineMapper 用 user_id、题目 ID、revision 与 status=draft 条件回写派生状态，不增加作答 revision、不更新作答时间。

默认规则 deadline 为 250ms，可配置 qf.practice.draft-validation-deadline；HTTP 沿用客户端的额外一秒宽限。规则超时、拒绝、不可用、响应无效均保留已保存的答案，进度为 null；前端显示“小题状态暂未知”，不误显示为已确认未作答。

API 1.3 answer-state 由拓展页面发送本次答案的状态，宿主仅接收本题大纲中的 ID 与 unanswered/draft；旧拓展使用 outlineScore.outlineStates，没有小题进度则按答案是否非空推断。所有结果重新组装成 ID 与状态，不回传规则的投影、分数或其他字段。部分作答是“小题中有 draft 也有 unanswered”的组合，规则 API 没有 partial 枚举。

同一 requestId 的派生状态只扩展回执一次，与条件更新派生状态在同一短事务中提交。重放使用首次记录的进度，不重新调用规则；旧请求的进度不能覆盖新草稿或已提交状态。进度计算前答案已提交，因此另一个请求无需等慢规则即可保存。

## 对应测试

- realValidationStoresPartProgressAndReplayDoesNotCallRulesAgain：真实 rule-runner 返回小题混合状态，HTTP 保存、读题恢复、幂等重放与安全字段。
- extensionWithoutValidateAnswerFallsBackToNonemptyAnswer：真实无校验钩子的拓展，空答案与非空答案回退。
- realRuleDeadlineFailureKeepsDraftAndNextRequestStillWorks：真实死循环钩子被终止，答案保留、状态未知，后续正常请求恢复。
- validationFailureSavesAnswerAndReplaysUnknownProgressWithoutRetryingRules：不可用时答案保存与未知状态回放。
- slowValidationOutsideTransactionCannotOverwriteNewerDraftProgress：CountDownLatch 门控，第二份草稿先完成，第一份校验后返回；验证事务外调用、不阻塞新保存、最终进度不倒退。
- declaredAnswerStateUsesOnlyWhitelistedDraftStatusesAndIds：声明能力的页面状态持久化，拒绝把判分状态当成草稿状态。
- DraftOutlineServiceTest：协议参数与短 deadline、空答案、false 是合法答案、能力声明、状态/ID 白名单、规则异常、响应不泄露答案。
- 原 DraftActionIntegrationTest：原有回执/草稿原子性、并发重复请求、409、IDOR、快照零查询等全部继续运行。
- 前端 outline.test.mjs 与 central-outline.spec.mjs：进度未知与未作答分开、页面实时更新、刷新后小题状态保持。

语义集成测试使用 5 秒规则 deadline，避免把 CI 的新进程冷启动当成规则语义失败；单元测试检查 250ms 参数，真实死循环仍由 runner 的 650ms 钩子上限终止。

## 设计冲突与范围

协议原 §5.2 要求校验失败返回 400/503，本次任务明确要求失败仍保存；按后发产品任务实现，并在该节补充更正。现有 answer-state 能力仍由页面声明、规则 validateAnswer 仅返回 valid，小题进度遵循其既有两条通路；没有让宿主解析题型内部答案。

prepareDraft 原有投影缓存未命中时仍须取得安全投影；该步骤的投影故障错误语义未修改。本次只对进度校验失败执行保留答案策略，不绕过归属、审核或内容版本检查。

旧基础简答题页面仅 reportAnswerState({status})，其 getScore 在未提交时也不提供小题进度；即使作者集中大纲把它列为 parts，宿主也不能据此解析私有答案或猜测各小题是否已答。此时保存整题进度，小题显示未知；需要拓展自身按公开 API 上报 targetId。浏览器回归先验证真实输入保存，再通过公开 SDK 上报小题状态并验证刷新持久化；没有修改已分叉的拓展代码。

## 性能验证

使用 loadtest/probes/draft-outline.py，只允许本机 qf-draft-outline* 独立项目和回环 URL，计时客户端在本机 rule-runner 容器中直接请求 app:8080（runner 的专用网络不能访问 nginx），不计 nginx 或 Windows 发布端口。300 题合成题库，4 个并发调用各保存不同题，预热每题 10 次后测 3 组、每组 200 次 HTTP 草稿保存。固定服务资源与 finish-compose.yml 相同，合计 4 核 / 8 GiB，工作进程上限 8；不访问作者服务器，不使用真实题库或账号。

运行命令：`python loadtest/probes/draft-outline.py --project qf-draft-outline --base-url http://127.0.0.1:8106 --output .artifacts/local/draft-outline/<main-or-candidate>.json`。环境与实际结果在 PR 中列出；探针不输出账号、Cookie 或题目内容。

实际本机结果（Windows Docker Desktop；每组 200 次，两个版本各 600 次，错误均为 0）：

| 版本 | 第 1 组 P95 | 第 2 组 P95 | 第 3 组 P95 | 三组 P95 中位数 |
| --- | --- | --- | --- | --- |
| main@97ff866 | 157.1ms | 101.6ms | 103.5ms | 103.5ms |
| 本次实现 | 243.7ms | 204.3ms | 178.3ms | 204.3ms |

三组 P95 中位数增加约 100.8ms（97.4%），新增本题规则调用与派生状态 / 回执写入存在明确开销，不能据此宣称性能不变。未读取整本题库；数据是一次固定本机环境下的短探针，包含 JIT、CPU 配额与本机其他常驻容器的影响，不是正式容量结论。任务要求的事务外、短 deadline、失败保留答案与并发旧状态不覆盖均另有测试验证。
